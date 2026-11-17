#!/usr/bin/env bash
# Stage D: prepare PostgreSQL 17 for Robin on a fresh Oracle Cloud VM
# (Ubuntu 24.04 LTS, aarch64 or x86_64), following plan §5.
#
#   sudo bash provision-postgres17.sh --data-dir /srv/pgdata \
#        --server-ip <VM public IP> [--server-name <dns name>] \
#        --allow <CIDR> [--allow <CIDR> ...]
#
# --allow lists the client ranges that may reach database "robin" as
# robin_app over TLS (Render's published outbound ranges for the backend's
# region). Loopback is always allowed for maintenance on the VM itself.
#
# It asks for robin_app's password with psql's \password, which hashes it
# with SCRAM on the client: the password is never shown, never on a command
# line and never sent to the server in clear. (ROBIN_APP_PASSWORD_FILE exists
# only for the local container test.)
#
# What it does, and refuses to do:
#   1. checks the OS (Ubuntu 24.04) and that no PostgreSQL cluster exists yet;
#   2. adds the PGDG apt repository after checking its signing-key fingerprint,
#      and installs postgresql-17 (the server package includes citext,
#      pgcrypto and pg_stat_statements);
#   3. creates cluster 17/main with its data in --data-dir, UTF-8 and the
#      locale en_US.UTF-8 (the same as the source);
#   4. writes conf.d/robin.conf with the §5 starting configuration;
#   5. creates a private CA and a server certificate for the given names and
#      IPs (the backend connects with sslmode=verify-full against the CA);
#   6. replaces pg_hba.conf: TLS + SCRAM only, robin_app only, the allowed
#      ranges only; no remote superuser login; everything else rejected;
#   7. creates robin_app (LOGIN NOSUPERUSER NOBYPASSRLS NOCREATEDB
#      NOCREATEROLE), database robin owned by it, and citext and pgcrypto;
#   8. opens 5432 in the host firewall for the allowed ranges only (Oracle's
#      Ubuntu images reject everything else in iptables).
#
# It never touches an existing cluster and never stores a password.
set -Eeuo pipefail
umask 022
# Everything this script prints is also kept in its log (no secrets are printed).
LOGFILE=/var/log/robin-provision.log
exec > >(tee -a "$LOGFILE") 2>&1

PGDG_KEY_URL=https://www.postgresql.org/media/keys/ACCC4CF8.asc
PGDG_KEY_FPR=B97B0AFCAA1A47F044F244A07FCC7D46ACCC4CF8
PG_MAJOR=17
DATA_DIR=/srv/pgdata
SHARED_BUFFERS=3GB
EFFECTIVE_CACHE=8GB
CONTAINER_TEST=0
names=() ips=() allow=()

die() { echo "provision: $*" >&2; exit 1; }
step() { printf '\n== %s\n' "$*"; }

while (( $# )); do
  case $1 in
    --data-dir) DATA_DIR=$2; shift 2 ;;
    --server-name) names+=("$2"); shift 2 ;;
    --server-ip) ips+=("$2"); shift 2 ;;
    --allow) allow+=("$2"); shift 2 ;;
    --shared-buffers) SHARED_BUFFERS=$2; shift 2 ;;
    --effective-cache-size) EFFECTIVE_CACHE=$2; shift 2 ;;
    --container-test) CONTAINER_TEST=1; shift ;;
    *) die "unknown argument: $1" ;;
  esac
done

(( EUID == 0 )) || die "run as root (sudo)"
(( ${#names[@]} + ${#ips[@]} > 0 )) || die "give at least one --server-ip or --server-name (the certificate is issued for them)"
(( ${#allow[@]} > 0 )) || die "give at least one --allow CIDR (who may connect)"
for c in "${allow[@]}"; do
  [[ $c =~ ^([0-9]{1,3}\.){3}[0-9]{1,3}/([0-9]|[12][0-9]|3[0-2])$ ]] || die "--allow $c is not an IPv4 CIDR"
  [[ $c != 0.0.0.0/0 ]] || die "--allow 0.0.0.0/0 would open the database to the whole internet"
done
for i in "${ips[@]}"; do [[ $i =~ ^([0-9]{1,3}\.){3}[0-9]{1,3}$ ]] || die "--server-ip $i is not an IPv4 address"; done
for n in "${names[@]}"; do [[ $n =~ ^[A-Za-z0-9]([A-Za-z0-9.-]{0,251}[A-Za-z0-9])?$ ]] || die "--server-name $n is not a host name"; done
[[ $SHARED_BUFFERS =~ ^[0-9]+(MB|GB)$ && $EFFECTIVE_CACHE =~ ^[0-9]+(MB|GB)$ ]] || die "memory sizes must look like 256MB or 3GB"

step "1. checks"
. /etc/os-release
[[ ${ID:-} == ubuntu && ${VERSION_CODENAME:-} == noble ]] || die "expected Ubuntu 24.04 (noble), found ${PRETTY_NAME:-unknown}"
echo "OS: $PRETTY_NAME, architecture $(dpkg --print-architecture)"
if command -v pg_lsclusters > /dev/null && [[ -n $(pg_lsclusters -h 2>/dev/null) ]]; then
  die "a PostgreSQL cluster already exists on this machine; this script only prepares a fresh one"
fi
mkdir -p "$DATA_DIR"
[[ -z $(ls -A "$DATA_DIR" 2>/dev/null | grep -v '^lost+found$') ]] || die "$DATA_DIR is not empty"
if (( ! CONTAINER_TEST )) && ! mountpoint -q "$DATA_DIR"; then
  echo "WARNING: $DATA_DIR is not a mount point. Plan §5 puts PGDATA on its own block volume." >&2
fi

step "2. PGDG repository and packages"
export DEBIAN_FRONTEND=noninteractive
apt-get update -qq
apt-get install -y -qq ca-certificates curl gnupg locales openssl iptables > /dev/null
install -d /usr/share/postgresql-common/pgdg
key=/usr/share/postgresql-common/pgdg/apt.postgresql.org.asc
curl -fsSL -o "$key.new" "$PGDG_KEY_URL"
fpr=$(gpg --show-keys --with-colons "$key.new" 2>/dev/null | awk -F: '$1 == "fpr" { print $10; exit }')
[[ $fpr == "$PGDG_KEY_FPR" ]] || { rm -f "$key.new"; die "PGDG signing key fingerprint is $fpr, expected $PGDG_KEY_FPR"; }
mv "$key.new" "$key"
echo "deb [signed-by=$key] https://apt.postgresql.org/pub/repos/apt noble-pgdg main" > /etc/apt/sources.list.d/pgdg.list
apt-get update -qq
# The cluster is created below, on the data volume, not by the server package.
# Only create_main_cluster is changed; the file's other defaults (include_dir
# 'conf.d', cluster_name, log_line_prefix) are kept.
apt-get install -y -qq postgresql-common > /dev/null
ccf=/etc/postgresql-common/createcluster.conf
if grep -qE '^[#[:space:]]*create_main_cluster' "$ccf"; then
  sed -i -E 's/^[#[:space:]]*create_main_cluster.*/create_main_cluster = false/' "$ccf"
else
  echo 'create_main_cluster = false' >> "$ccf"
fi
apt-get install -y -qq "postgresql-$PG_MAJOR" > /dev/null
BIN=/usr/lib/postgresql/$PG_MAJOR/bin
echo "installed: $("$BIN/postgres" --version) (package $(dpkg-query -W -f '${Version}' "postgresql-$PG_MAJOR"))"

step "3. cluster $PG_MAJOR/main in $DATA_DIR"
sed -i 's/^# *\(en_US.UTF-8 UTF-8\)/\1/' /etc/locale.gen
grep -q '^en_US.UTF-8 UTF-8' /etc/locale.gen || echo 'en_US.UTF-8 UTF-8' >> /etc/locale.gen
locale-gen > /dev/null
chown postgres:postgres "$DATA_DIR"
chmod 700 "$DATA_DIR"
pg_createcluster "$PG_MAJOR" main -d "$DATA_DIR/$PG_MAJOR/main" --locale en_US.UTF-8 --encoding UTF8 -- --data-checksums > /dev/null
CONF=/etc/postgresql/$PG_MAJOR/main

step "4. configuration (plan §5)"
tls=$CONF/tls
install -d -m 700 -o postgres -g postgres "$tls"
install -d -m 755 -o postgres -g postgres "$CONF/conf.d"
grep -qE "^include_dir[[:space:]]*=[[:space:]]*'conf.d'" "$CONF/postgresql.conf" || echo "include_dir = 'conf.d'" >> "$CONF/postgresql.conf"
cat > "$CONF/conf.d/robin.conf" <<EOF
# Robin, plan §5 starting configuration (written by provision-postgres17.sh)
listen_addresses = '*'
port = 5432
max_connections = 40
shared_buffers = $SHARED_BUFFERS
effective_cache_size = $EFFECTIVE_CACHE
work_mem = 16MB
maintenance_work_mem = 512MB
wal_compression = zstd
password_encryption = scram-sha-256
ssl = on
ssl_cert_file = '$tls/server.crt'
ssl_key_file = '$tls/server.key'
ssl_min_protocol_version = 'TLSv1.2'
idle_in_transaction_session_timeout = '10min'
log_min_duration_statement = 1000
log_connections = on
log_disconnections = on
shared_preload_libraries = 'pg_stat_statements'
EOF

step "5. TLS: private CA and server certificate"
ca=/root/robin-pg-ca
install -d -m 700 "$ca"
san=()
for n in "${names[@]}"; do san+=("DNS:$n"); done
for i in "${ips[@]}"; do san+=("IP:$i"); done
san_list=$(IFS=,; echo "${san[*]}")
cn=${names[0]:-${ips[0]}}
openssl req -x509 -newkey ec -pkeyopt ec_paramgen_curve:P-256 -nodes -days 3650 -sha256 \
  -subj "/CN=Robin PostgreSQL CA" -keyout "$ca/ca.key" -out "$ca/ca.crt" 2> /dev/null
openssl req -newkey ec -pkeyopt ec_paramgen_curve:P-256 -nodes -sha256 \
  -subj "/CN=$cn" -keyout "$tls/server.key" -out "$ca/server.csr" 2> /dev/null
printf 'subjectAltName=%s\nbasicConstraints=critical,CA:FALSE\nkeyUsage=critical,digitalSignature\nextendedKeyUsage=serverAuth\n' "$san_list" > "$ca/server.ext"
openssl x509 -req -in "$ca/server.csr" -CA "$ca/ca.crt" -CAkey "$ca/ca.key" -CAcreateserial \
  -days 825 -sha256 -extfile "$ca/server.ext" -out "$tls/server.crt" 2> /dev/null
chown postgres:postgres "$tls/server.key" "$tls/server.crt"
chmod 600 "$tls/server.key"
install -d -m 755 /etc/robin
cp "$ca/ca.crt" /etc/robin/robin-pg-ca.crt
chmod 644 /etc/robin/robin-pg-ca.crt
echo "server certificate for: $san_list"

step "6. pg_hba.conf: TLS + SCRAM, robin_app, allowed ranges only"
{
  echo "# Written by provision-postgres17.sh (plan §5). Anything not listed is rejected."
  echo "# TYPE   DATABASE             USER       ADDRESS          METHOD"
  echo "local    all                  postgres                    peer"
  echo "hostssl  robin,robin_restore  robin_app  127.0.0.1/32     scram-sha-256"
  for c in "${allow[@]}"; do printf 'hostssl  robin                robin_app  %-16s scram-sha-256\n' "$c"; done
  echo "local    all                  all                         reject"
  echo "host     all                  all        0.0.0.0/0        reject"
  echo "host     all                  all        ::/0             reject"
} > "$CONF/pg_hba.conf"
chown postgres:postgres "$CONF/pg_hba.conf"
chmod 640 "$CONF/pg_hba.conf"

step "7. start, role robin_app, database robin"
if (( CONTAINER_TEST )); then pg_ctlcluster "$PG_MAJOR" main start; else systemctl enable --now "postgresql@$PG_MAJOR-main"; fi
PSQL=(runuser -u postgres -- psql -X -q -v ON_ERROR_STOP=1 -d postgres)
"${PSQL[@]}" -c "CREATE ROLE robin_app LOGIN NOSUPERUSER NOBYPASSRLS NOCREATEDB NOCREATEROLE NOREPLICATION"
if [[ -n ${ROBIN_APP_PASSWORD_FILE:-} ]]; then
  # Local container test only: the test harness supplies a throwaway value.
  "${PSQL[@]}" -v pw="$(< "$ROBIN_APP_PASSWORD_FILE")" <<'SQL'
ALTER ROLE robin_app PASSWORD :'pw';
SQL
else
  echo "Set robin_app's password (generate it in your password manager; input hidden):"
  runuser -u postgres -- psql -X -d postgres -c '\password robin_app'
fi
# robin is the production database. robin_restore is for rehearsal restores
# (reachable from loopback only, e.g. through an SSH tunnel); drop it after
# the rehearsal has passed.
for db in robin robin_restore; do
  "${PSQL[@]}" -c "CREATE DATABASE $db OWNER robin_app TEMPLATE template0 ENCODING 'UTF8' LOCALE 'en_US.UTF-8'"
  "${PSQL[@]}" -c "REVOKE ALL ON DATABASE $db FROM PUBLIC" -c "GRANT CONNECT, TEMPORARY ON DATABASE $db TO robin_app"
  # citext and pgcrypto where restore.sh expects them; pg_stat_statements in
  # its own schema (as on Supabase), so public holds only Robin's objects.
  runuser -u postgres -- psql -X -q -v ON_ERROR_STOP=1 -d "$db" \
    -c 'CREATE EXTENSION IF NOT EXISTS citext WITH SCHEMA public' \
    -c 'CREATE EXTENSION IF NOT EXISTS pgcrypto WITH SCHEMA public' \
    -c 'CREATE SCHEMA IF NOT EXISTS extensions' \
    -c 'CREATE EXTENSION IF NOT EXISTS pg_stat_statements WITH SCHEMA extensions'
done

step "8. host firewall"
if (( CONTAINER_TEST )); then
  echo "skipped (container test)"
else
  # Insert the ACCEPT rules before Oracle's final REJECT rule in INPUT.
  pos=$(iptables -L INPUT --line-numbers -n | awk '$2 == "REJECT" { print $1; exit }')
  for c in "${allow[@]}"; do
    if [[ -n $pos ]]; then iptables -I INPUT "$pos" -p tcp -s "$c" --dport 5432 -m conntrack --ctstate NEW -j ACCEPT
    else iptables -A INPUT -p tcp -s "$c" --dport 5432 -m conntrack --ctstate NEW -j ACCEPT; fi
  done
  if command -v netfilter-persistent > /dev/null; then netfilter-persistent save > /dev/null; else echo "WARNING: netfilter-persistent not found; the rules last until reboot" >&2; fi
  echo "5432 opened for: ${allow[*]} (the VCN security list must allow the same ranges)"
fi

step "summary"
runuser -u postgres -- psql -X -At -d robin -c "select 'server: ' || version()" \
  -c "select 'ssl: ' || current_setting('ssl') || ', password_encryption: ' || current_setting('password_encryption') || ', shared_buffers: ' || current_setting('shared_buffers')" \
  -c "select 'robin_app: super=' || rolsuper || ' bypassrls=' || rolbypassrls || ' createdb=' || rolcreatedb from pg_roles where rolname = 'robin_app'" \
  -c "select 'extensions in robin: ' || string_agg(extname || ' ' || extversion, ', ' order by extname) from pg_extension"
echo "CA certificate for the backend (public, not secret): /etc/robin/robin-pg-ca.crt"
echo "  SHA-256 fingerprint: $(openssl x509 -in /etc/robin/robin-pg-ca.crt -noout -fingerprint -sha256 | cut -d= -f2)"
echo "CA private key (root only, needed only to renew the server certificate): $ca/ca.key"
echo "PROVISION OK"
