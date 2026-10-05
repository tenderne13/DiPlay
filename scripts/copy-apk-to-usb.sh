#!/usr/bin/env bash
set -euo pipefail

usage() {
  echo "用法: scripts/copy-apk-to-usb.sh [apk路径] [U盘挂载点]"
  echo "  不带参数: 复制默认 debug 包 mobile/build/outputs/apk/debug/mobile-debug.apk 到 U 盘"
  echo "  U盘挂载点缺省时优先 /Volumes/HIKSEMI，否则自动探测 /Volumes 下唯一非系统卷"
}

APK="mobile/build/outputs/apk/debug/mobile-debug.apk"
DEST=""

for arg in "$@"; do
  case "$arg" in
    -h|--help) usage; exit 0 ;;
    *) if [[ -z "${APK_SET:-}" && "$arg" != /* ]]; then APK="$arg"; APK_SET=1; else DEST="$arg"; fi ;;
  esac
done

if [[ ! -f "$APK" ]]; then
  echo "APK 不存在: ${APK}（先跑 DIPLAY_AUTH_ASSETS_DIR=\"\$HOME/DiPlay-runtime-assets\" ./gradlew :mobile:assembleStandaloneDebug）" >&2
  exit 1
fi

if [[ -z "$DEST" ]]; then
  if [[ -d /Volumes/HIKSEMI ]]; then
    DEST="/Volumes/HIKSEMI"
  else
    CANDIDATES=()
    for v in /Volumes/*; do
      [[ "$v" == "/Volumes/Macintosh HD" ]] && continue
      [[ -d "$v" && -w "$v" ]] && CANDIDATES+=("$v")
    done
    if [[ "${#CANDIDATES[@]}" -eq 1 ]]; then
      DEST="${CANDIDATES[0]}"
    else
      echo "无法确定 U 盘，候选: ${CANDIDATES[*]:-无}。请显式传入挂载点。" >&2
      exit 1
    fi
  fi
fi

if [[ ! -d "$DEST" || ! -w "$DEST" ]]; then
  echo "目标不可写: ${DEST}（U 盘是否已挂载？ls /Volumes 查看）" >&2
  exit 1
fi

STAMP=$(stat -f %Sm -t %Y%m%d-%H%M "$APK")
VERSION=$(grep -m1 'versionName = ' mobile/build.gradle.kts 2>/dev/null | sed -E 's/.*"([^"]+)".*/\1/')
VERSION=${VERSION:-dev}
TARGET="${DEST}/DiPlay-${VERSION}-hudtest-${STAMP}.apk"
cp "$APK" "$TARGET"
echo "已复制: ${APK} -> ${TARGET} ($(stat -f %z "$TARGET") 字节)"
