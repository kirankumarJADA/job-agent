#!/usr/bin/env bash
# R4, step 1 on a fresh Oracle Cloud VM (Ubuntu 24.04): format and mount the
# block volume that will hold PGDATA, before provision-postgres17.sh.
#
#   sudo bash prepare-vm.sh --device /dev/oracleoci/oraclevdb [--mount /srv/pgdata] [--label pgdata]
#
# /dev/oracleoci/oraclevdb is the consistent device path OCI gives the first
# attached block volume when "use consistent device path" is chosen; it is a
# symlink to the real disk (for example /dev/sdb).
#
# Refuses, and changes nothing, when:
#   - the device is not a block device, holds the root filesystem, or is
#     mounted anywhere;
#   - the device has partitions or any filesystem/RAID/LVM signature
#     (it never formats a disk that holds something);
#   - the mount point is already a mount point of another device, or is a
#     non-empty directory.
# Run it again on a volume it prepared: it reports "already prepared" and
# exits 0 (the label, the fstab line and the mount are checked).
set -Eeuo pipefail
umask 022
DEVICE= MOUNT=/srv/pgdata LABEL=pgdata TEST=0
die() { echo "prepare-vm: $*" >&2; exit 1; }
while (( $# )); do
  case $1 in
    --device) DEVICE=$2; shift 2 ;;
    --mount) MOUNT=$2; shift 2 ;;
    --label) LABEL=$2; shift 2 ;;
    --container-test) TEST=1; shift ;;
    *) die "unknown argument: $1" ;;
  esac
done
(( EUID == 0 )) || die "run as root (sudo)"
[[ -n $DEVICE ]] || die "give --device (normally /dev/oracleoci/oraclevdb)"
[[ $MOUNT =~ ^/[A-Za-z0-9._/-]+$ && $MOUNT != / ]] || die "--mount must be an absolute path other than /"
[[ $LABEL =~ ^[A-Za-z0-9_-]{1,16}$ ]] || die "--label must be 1-16 letters, digits, - or _"
if (( ! TEST )); then
  . /etc/os-release
  [[ ${ID:-} == ubuntu && ${VERSION_CODENAME:-} == noble ]] || die "expected Ubuntu 24.04 (noble), found ${PRETTY_NAME:-unknown}"
fi
for c in blkid lsblk findmnt mkfs.ext4 wipefs; do command -v "$c" > /dev/null || die "missing $c"; done

real=$(readlink -f "$DEVICE") || die "$DEVICE does not resolve"
[[ -b $real ]] || die "$DEVICE ($real) is not a block device"
echo "device: $DEVICE -> $real, $(lsblk -dno SIZE "$real" | tr -d ' ') ($(lsblk -dno MODEL "$real" 2>/dev/null | xargs || true))"
fstab_line="LABEL=$LABEL $MOUNT ext4 defaults,noatime,_netdev,nofail 0 2"

# Already prepared by this script: label, mount and fstab all agree.
cur_label=$(blkid -o value -s LABEL "$real" 2>/dev/null || true)
cur_type=$(blkid -o value -s TYPE "$real" 2>/dev/null || true)
if [[ $cur_label == "$LABEL" && $cur_type == ext4 ]]; then
  src=$(findmnt -no SOURCE --target "$MOUNT" 2>/dev/null || true)
  if [[ $(readlink -f "$src" 2>/dev/null) == "$real" ]] && grep -qxF "$fstab_line" /etc/fstab; then
    echo "already prepared: $real (ext4, label $LABEL) is mounted at $MOUNT and listed in /etc/fstab"
    echo "PREPARE OK"
    exit 0
  fi
  die "$real already has an ext4 filesystem labelled $LABEL, but it is not mounted at $MOUNT with the expected fstab line; check it by hand, nothing was changed"
fi

# Never format something that is in use or holds data.
root_src=$(readlink -f "$(findmnt -no SOURCE /)" 2>/dev/null || true)
[[ $real != "$root_src" && $root_src != "$real"* ]] || die "$real holds the root filesystem"
if findmnt -rno SOURCE | xargs -r -n1 readlink -f 2>/dev/null | grep -qx "$real"; then die "$real is mounted"; fi
parts=$(lsblk -nro NAME "$real" | tail -n +2 | wc -l)
(( parts == 0 )) || die "$real has $parts partition(s); refusing to format it"
sig=$(wipefs -n "$real" 2>/dev/null | tail -n +2 || true)
[[ -z $sig ]] || die "$real holds a signature (filesystem, RAID or LVM):"$'\n'"$sig"$'\n'"refusing to format it"
if findmnt -no SOURCE --mountpoint "$MOUNT" > /dev/null 2>&1; then die "$MOUNT is already a mount point"; fi
if [[ -d $MOUNT && -n $(ls -A "$MOUNT" 2>/dev/null) ]]; then die "$MOUNT is not empty"; fi
grep -qE "[[:space:]]$MOUNT[[:space:]]" /etc/fstab && die "/etc/fstab already has an entry for $MOUNT"

echo "formatting $real as ext4, label $LABEL (1 % reserved blocks)"
mkfs.ext4 -q -L "$LABEL" -m 1 "$real"
mkdir -p "$MOUNT"
cp -a /etc/fstab "/etc/fstab.before-robin.$(date -u +%Y%m%dT%H%M%SZ)"
echo "$fstab_line" >> /etc/fstab
if (( TEST )); then mount -t ext4 -o defaults,noatime "$real" "$MOUNT"; else systemctl daemon-reload; mount "$MOUNT"; fi
src=$(findmnt -no SOURCE --target "$MOUNT")
[[ $(readlink -f "$src") == "$real" ]] || die "after mounting, $MOUNT shows $src, not $real"
echo "mounted: $(findmnt -no SOURCE,FSTYPE,OPTIONS --target "$MOUNT")"
echo "space: $(df -h --output=size,avail "$MOUNT" | tail -n 1 | xargs)"
echo "fstab: $fstab_line"
echo "PREPARE OK"
