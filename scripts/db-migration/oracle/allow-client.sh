#!/usr/bin/env bash
# Adds or removes one client range that may reach database robin as
# robin_app (TLS + SCRAM only), on a VM prepared by provision-postgres17.sh.
#
#   sudo bash allow-client.sh add <CIDR>      e.g. one of Render's Frankfurt outbound ranges
#   sudo bash allow-client.sh remove <CIDR>
#   sudo bash allow-client.sh list
#
# Provisioning can therefore start before Render's ranges are known (they are
# shown in the Render dashboard only while the service runs): provision with
# your own address, rehearse through the SSH tunnel (loopback), and add the
# Render ranges at cutover. Each change edits pg_hba.conf (only the hostssl
# robin robin_app lines it owns, before the final reject rules), reloads the
# server and, on the VM, adjusts the iptables rule for 5432 and saves it.
# The VCN security list must be changed to match, in the OCI console.
set -Eeuo pipefail
CMD=${1:-} CIDR=${2:-} TEST=0
[[ ${3:-} == --container-test ]] && TEST=1
die() { echo "allow-client: $*" >&2; exit 1; }
(( EUID == 0 )) || die "run as root (sudo)"
HBA=/etc/postgresql/17/main/pg_hba.conf
[[ -f $HBA ]] || die "$HBA not found: run provision-postgres17.sh first"
grep -q '^# Written by provision-postgres17.sh' "$HBA" || die "$HBA was not written by provision-postgres17.sh; edit it by hand"
valid_cidr() {
  [[ $1 =~ ^([0-9]{1,3})\.([0-9]{1,3})\.([0-9]{1,3})\.([0-9]{1,3})/([0-9]|[12][0-9]|3[0-2])$ ]] || return 1
  local o; for o in "${BASH_REMATCH[@]:1:4}"; do (( o <= 255 )) || return 1; done
  [[ $1 != 0.0.0.0/0 ]]
}
line_for() { printf 'hostssl  robin                robin_app  %-16s scram-sha-256' "$1"; }
reload() {
  if (( TEST )); then pg_ctlcluster 17 main reload; else systemctl reload postgresql@17-main; fi
}
case $CMD in
  list)
    grep -E '^hostssl[[:space:]]+robin[[:space:]]+robin_app[[:space:]]' "$HBA" | awk '{ print $4 }'
    exit 0 ;;
  add|remove) valid_cidr "$CIDR" || die "'$CIDR' is not an IPv4 CIDR (and 0.0.0.0/0 is never allowed)" ;;
  *) die "usage: allow-client.sh add|remove <CIDR> | list" ;;
esac
cp -a "$HBA" "$HBA.before-allow-client.$(date -u +%Y%m%dT%H%M%SZ)"
present=0
grep -qE "^hostssl[[:space:]]+robin[[:space:]]+robin_app[[:space:]]+${CIDR//./\\.}[[:space:]]" "$HBA" && present=1
if [[ $CMD == add ]]; then
  if (( present )); then echo "$CIDR is already allowed"; else
    # Insert before the first reject rule, so it is evaluated before them.
    awk -v l="$(line_for "$CIDR")" '!done && /^(local|host)[[:space:]].*reject[[:space:]]*$/ { print l; done = 1 } { print }' "$HBA" > "$HBA.new"
    grep -qF "$(line_for "$CIDR")" "$HBA.new" || die "no reject rule found to insert before; nothing changed"
    chown postgres:postgres "$HBA.new"; chmod 640 "$HBA.new"; mv "$HBA.new" "$HBA"
    echo "added $CIDR to pg_hba.conf"
  fi
else
  if (( ! present )); then echo "$CIDR was not allowed"; else
    grep -vE "^hostssl[[:space:]]+robin[[:space:]]+robin_app[[:space:]]+${CIDR//./\\.}[[:space:]]" "$HBA" > "$HBA.new"
    chown postgres:postgres "$HBA.new"; chmod 640 "$HBA.new"; mv "$HBA.new" "$HBA"
    echo "removed $CIDR from pg_hba.conf"
  fi
fi
reload
if (( TEST )); then
  echo "container test: iptables skipped"
else
  rule=(INPUT -p tcp -s "$CIDR" --dport 5432 -m conntrack --ctstate NEW -j ACCEPT)
  if [[ $CMD == add ]]; then
    if ! iptables -C "${rule[@]}" 2>/dev/null; then
      pos=$(iptables -L INPUT --line-numbers -n | awk '$2 == "REJECT" { print $1; exit }')
      if [[ -n $pos ]]; then iptables -I INPUT "$pos" "${rule[@]:1}"; else iptables -A "${rule[@]}"; fi
    fi
  else
    while iptables -C "${rule[@]}" 2>/dev/null; do iptables -D "${rule[@]}"; done
  fi
  command -v netfilter-persistent > /dev/null && netfilter-persistent save > /dev/null
  echo "iptables updated for $CIDR (remember the VCN security list)"
fi
echo "allowed now: $(grep -E '^hostssl[[:space:]]+robin[[:space:]]+robin_app[[:space:]]' "$HBA" | awk '{ print $4 }' | paste -sd' ' -)"
echo "ALLOW-CLIENT OK"
