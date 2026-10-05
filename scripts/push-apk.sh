#!/usr/bin/env bash
set -euo pipefail

ADB="adb"
if ! command -v adb >/dev/null 2>&1; then
  ADB="$HOME/Library/Android/sdk/platform-tools/adb"
fi

usage() {
  echo "用法: scripts/push-apk.sh [apk路径] [-i]"
  echo "  不带参数: 推送默认 debug 包 mobile/build/outputs/apk/debug/mobile-debug.apk 到手机 Download"
  echo "  -i: 推送后直接安装 (adb install -r)"
}

APK="mobile/build/outputs/apk/debug/mobile-debug.apk"
INSTALL=""

for arg in "$@"; do
  case "$arg" in
    -i|--install) INSTALL="yes" ;;
    -h|--help) usage; exit 0 ;;
    *) APK="$arg" ;;
  esac
done

if [[ ! -f "$APK" ]]; then
  echo "APK 不存在: $APK" >&2
  exit 1
fi

COUNT=$("$ADB" devices | awk 'NR>1 && $2=="device" {n++} END {print n+0}')
if [[ "$COUNT" -eq 0 ]]; then
  echo "没有已授权的设备。请连 USB 并在手机上允许调试，或插拔数据线重试。" >&2
  exit 1
fi

"$ADB" push "$APK" /sdcard/Download/
if [[ -n "$INSTALL" ]]; then
  "$ADB" install -r "$APK"
fi
