#!/usr/bin/env bash
# Installs the nightly encrypted backup on the Oracle VM (plan §5), after
# provision-postgres17.sh:
#
#   sudo bash install-backup.sh --public-key backup-public.asc [--par-url-file par.txt] [--keep 7] [--run-now]
#
#   --public-key    the OpenPGP PUBLIC key that backups are encrypted to
#                   (generated on your laptop; the private key stays there and
#                   in your password manager). A file holding a secret key is
#                   refused.
#   --par-url-file  a file holding the Object Storage pre-authenticated request
#                   URL (object writes only, ending in /o/). It is read from a
#                   file so that it never appears on a command line.
#   --run-now       also take the first backup immediately.
#
# Installs /usr/local/sbin/robin-backup.sh, /etc/robin/backup-public.asc,
# /etc/robin/backup.env (0600), robin-backup.service and robin-backup.timer
# (daily at 02:30 UTC, catching up after downtime), and enables the timer.
set -Eeuo pipefail
umask 022
KEY= PARFILE= KEEP=7 RUNNOW=0 TEST=0
die() { echo "install-backup: $*" >&2; exit 1; }
while (( $# )); do
  case $1 in
    --public-key) KEY=$2; shift 2 ;;
    --par-url-file) PARFILE=$2; shift 2 ;;
    --keep) KEEP=$2; shift 2 ;;
    --run-now) RUNNOW=1; shift ;;
    --container-test) TEST=1; shift ;;
    *) die "unknown argument: $1" ;;
  esac
done
(( EUID == 0 )) || die "run as root (sudo)"
[[ -s $KEY ]] || die "give --public-key with the backup public key"
[[ $KEEP =~ ^[1-9][0-9]*$ ]] || die "--keep must be a positive number"
here=$(cd "$(dirname "$0")" && pwd)
[[ -s $here/robin-backup.sh ]] || die "robin-backup.sh must sit next to this script"
for c in gpg pg_dump curl runuser; do command -v "$c" > /dev/null || die "missing $c"; done
info=$(GNUPGHOME=$(mktemp -d) gpg --batch --with-colons --show-keys "$KEY" 2>/dev/null) || die "$KEY is not an OpenPGP key"
grep -q '^sec' <<< "$info" && die "$KEY holds a SECRET key; give the public key only"
grep -q '^pub' <<< "$info" || die "$KEY holds no public key"
fpr=$(awk -F: '$1 == "fpr" { print $10; exit }' <<< "$info")

install -d -m 755 /etc/robin
install -m 644 "$KEY" /etc/robin/backup-public.asc
install -m 755 "$here/robin-backup.sh" /usr/local/sbin/robin-backup.sh
sed -i -e 's/\r$//' /usr/local/sbin/robin-backup.sh
{
  echo "# robin-backup settings (install-backup.sh). Root-only: the URL allows uploads."
  echo "ROBIN_BACKUP_KEEP=$KEEP"
  if [[ -n $PARFILE ]]; then
    par=$(tr -d '\r\n' < "$PARFILE")
    [[ $par == https://* && $par == */ ]] || [[ $TEST == 1 && $par == http://* && $par == */ ]] \
      || die "the pre-authenticated request URL must start with https:// and end with /"
    echo "ROBIN_BACKUP_PAR_URL=$par"
  fi
} > /etc/robin/backup.env
chmod 600 /etc/robin/backup.env

install -d -m 755 /etc/systemd/system
cat > /etc/systemd/system/robin-backup.service <<'EOF'
[Unit]
Description=Robin: nightly encrypted backup of database robin
Wants=postgresql@17-main.service
After=postgresql@17-main.service network-online.target

[Service]
Type=oneshot
ExecStart=/usr/local/sbin/robin-backup.sh
Nice=10
IOSchedulingClass=best-effort
IOSchedulingPriority=7
EOF
cat > /etc/systemd/system/robin-backup.timer <<'EOF'
[Unit]
Description=Robin: nightly encrypted backup at 02:30 UTC

[Timer]
OnCalendar=*-*-* 02:30:00 UTC
Persistent=true
RandomizedDelaySec=900

[Install]
WantedBy=timers.target
EOF
if (( TEST )); then
  echo "container test: systemd units written, not enabled"
else
  systemctl daemon-reload
  systemctl enable --now robin-backup.timer
  systemctl list-timers robin-backup.timer --no-pager | head -n 3
fi
echo "backup public key fingerprint: $fpr"
if (( RUNNOW )); then
  if (( TEST )); then /usr/local/sbin/robin-backup.sh; else systemctl start robin-backup.service && journalctl -u robin-backup.service -n 5 --no-pager; fi
fi
echo "INSTALL OK"
