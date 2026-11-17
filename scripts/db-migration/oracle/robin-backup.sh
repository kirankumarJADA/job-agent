#!/usr/bin/env bash
# Nightly encrypted backup of database robin on the Oracle VM (plan §5),
# run as root by robin-backup.timer (install-backup.sh installs both).
#
#   /etc/robin/backup-public.asc  the OpenPGP PUBLIC key backups are encrypted
#                                 to. Its private key never comes to the VM, so
#                                 the VM cannot read its own backups.
#   /etc/robin/backup.env         optional, root-only (0600):
#     ROBIN_BACKUP_PAR_URL=https://objectstorage.<region>.oraclecloud.com/p/<token>/n/<namespace>/b/<bucket>/o/
#                                 an Object Storage pre-authenticated request
#                                 that allows object WRITES only; each archive
#                                 and its manifest are uploaded under it
#     ROBIN_BACKUP_KEEP=7         local archives kept, newest first
#     ROBIN_BACKUP_DIR=/var/backups/robin   (the boot volume, not PGDATA's)
#     ROBIN_BACKUP_DB=robin
#
# The plaintext dump exists only inside the pipe pg_dump | gpg. A failed run
# leaves no partial file and exits non-zero (systemd records the failure).
# Local retention runs only after a successful upload (or when no upload is
# configured): while uploads fail, no local archive is deleted, because it
# may be the only copy.
set -Eeuo pipefail
umask 077
CONF=${ROBIN_BACKUP_CONF:-/etc/robin/backup.env}
# shellcheck disable=SC1090
[[ -r $CONF ]] && . "$CONF"
KEY=${ROBIN_BACKUP_KEY:-/etc/robin/backup-public.asc}
DIR=${ROBIN_BACKUP_DIR:-/var/backups/robin}
KEEP=${ROBIN_BACKUP_KEEP:-7}
DB=${ROBIN_BACKUP_DB:-robin}
PAR=${ROBIN_BACKUP_PAR_URL:-}
log() { printf '%s robin-backup: %s\n' "$(date -u +%FT%TZ)" "$*"; }
die() { log "FAILED: $*"; exit 1; }

(( EUID == 0 )) || die "run as root"
[[ -s $KEY ]] || die "no public key at $KEY"
[[ $KEEP =~ ^[1-9][0-9]*$ ]] || die "ROBIN_BACKUP_KEEP must be a positive number"
[[ $DB =~ ^[a-z_][a-z0-9_]*$ ]] || die "ROBIN_BACKUP_DB is not a plain database name"
if [[ -n $PAR ]]; then [[ $PAR == https://* || $PAR == http://* ]] && [[ $PAR == */ ]] || die "ROBIN_BACKUP_PAR_URL must be an http(s) URL ending with /"; fi
mkdir -p "$DIR"; chmod 700 "$DIR"
GNUPGHOME=$(mktemp -d); export GNUPGHOME
trap 'rm -rf "$GNUPGHOME"; rm -f "$DIR"/.robin-*.partial' EXIT

keyinfo=$(gpg --batch --with-colons --show-keys "$KEY" 2>/dev/null) || die "$KEY is not an OpenPGP key"
grep -q '^sec' <<< "$keyinfo" && die "$KEY holds a SECRET key; only the public key belongs on the VM"
fpr=$(awk -F: '$1 == "fpr" { print $10; exit }' <<< "$keyinfo")
[[ -n $fpr ]] || die "$KEY holds no usable public key"

stamp=$(date -u +%Y%m%dT%H%M%SZ)
name=robin-$stamp.dump.gpg
tmp=$DIR/.$name.partial
started=$(date +%s)
set +e
runuser -u postgres -- pg_dump -Fc -Z 6 -d "$DB" \
  | gpg --batch --no-tty --yes --trust-model always --recipient-file "$KEY" --compress-algo none --encrypt --output "$tmp"
rc=("${PIPESTATUS[@]}")
set -e
(( rc[0] == 0 )) || die "pg_dump exited with ${rc[0]}"
(( rc[1] == 0 )) || die "gpg exited with ${rc[1]}"
[[ -s $tmp ]] || die "the encrypted archive is empty"
mv "$tmp" "$DIR/$name"
sha=$(sha256sum "$DIR/$name" | cut -d' ' -f1)
bytes=$(stat -c %s "$DIR/$name")
q() { runuser -u postgres -- psql -X -At -d "$DB" -c "$1"; }
{
  echo "format|robin-vm-backup-v1"
  echo "file|$name"
  echo "sha256|$sha"
  echo "bytes|$bytes"
  echo "database|$DB"
  echo "database_size_bytes|$(q 'select pg_database_size(current_database())')"
  echo "public_tables|$(q "select count(*) from pg_class c join pg_namespace n on n.oid = c.relnamespace where n.nspname = 'public' and c.relkind = 'r'")"
  echo "pg_dump|$(runuser -u postgres -- pg_dump --version)"
  echo "recipient_fingerprint|$fpr"
  echo "seconds|$(( $(date +%s) - started ))"
  echo "written_utc|$(date -u +%FT%TZ)"
} > "$DIR/$name.manifest"
log "wrote $name: $bytes bytes, sha256 $sha, $(( $(date +%s) - started ))s"

if [[ -n $PAR ]]; then
  for f in "$name" "$name.manifest"; do
    curl --fail --silent --show-error --retry 3 --retry-delay 10 --upload-file "$DIR/$f" "$PAR$f" > /dev/null \
      || die "upload of $f failed (the local copy is kept)"
  done
  log "uploaded $name and its manifest"
else
  log "no ROBIN_BACKUP_PAR_URL: kept on this VM only"
fi

mapfile -t old < <(ls -1t "$DIR"/robin-*.dump.gpg 2>/dev/null | tail -n +$(( KEEP + 1 )))
for f in "${old[@]}"; do rm -f "$f" "$f.manifest"; done
(( ${#old[@]} == 0 )) || log "removed ${#old[@]} older local archive(s); keeping $KEEP"
log "OK"
