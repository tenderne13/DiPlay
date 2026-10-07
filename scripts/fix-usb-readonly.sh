#!/usr/bin/env bash
# FAT U 盘在车机/Windows 上热插拔后带 dirty 标记，macOS 首次挂载会强制只读。
# 本脚本由 LaunchAgent（com.shilapi.usb-remount）监听 /Volumes 触发：
# 发现只读挂载的 msdos(FAT) 卷就卸载后重新挂载，干净卸载会清掉 dirty 标记。
set -uo pipefail

LOG_TAG="usb-remount"

remount_rw() {
  local mp="$1" dev="$2"
  logger -t "$LOG_TAG" "remounting read-only FAT volume: $dev -> $mp"
  if ! diskutil unmount "$mp" >/dev/null 2>&1; then
    logger -t "$LOG_TAG" "unmount failed (volume busy): $mp"
    return 1
  fi
  sleep 1
  if ! diskutil mount "$dev" >/dev/null 2>&1; then
    logger -t "$LOG_TAG" "mount failed: $dev"
    return 1
  fi
  logger -t "$LOG_TAG" "remounted read-write: $dev -> $mp"
}

# mount 输出形如: /dev/disk4s1 on /Volumes/HIKSEMI (msdos, local, nodev, nosuid, read-only, ...)
mount | while IFS= read -r line; do
  [[ "$line" == *" on /Volumes/"* ]] || continue
  [[ "$line" == *", read-only"* ]] || continue
  [[ "$line" == *"msdos"* ]] || continue
  dev="${line%% on *}"
  mp=$(printf '%s' "$line" | sed -E 's|^.* on (/Volumes/[^ ]+) .*|\1|')
  remount_rw "$mp" "$dev"
done

exit 0
