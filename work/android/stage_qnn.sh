#!/usr/bin/env bash
# Stage the QAIRT/QNN headers and Android/Hexagon runtime libraries required by the APK.
#
# Usage:
#   source /workspace/env/qairt-sdk.sh
#   bash work/android/stage_qnn.sh
#
# Override QNN_SDK_ROOT to stage another SDK release. The generated files live below
# app/src/main/cpp/include and app/src/main/jniLibs, both intentionally gitignored.
set -euo pipefail

HERE=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)
SDK=${QNN_SDK_ROOT:-${QAIRT_SDK_ROOT:-/workspace/toolchains/qairt/current}}
APP="$HERE/app/src/main"
INCLUDE_DEST="$APP/cpp/include"
JNI_DEST="$APP/jniLibs/arm64-v8a"
TIERS=${QNN_HTP_TIERS:-"68 69 73 75 79 81"}

[ -f "$SDK/include/QNN/QnnBackend.h" ] || {
    echo "No QNN headers under $SDK/include/QNN" >&2
    echo "Set QNN_SDK_ROOT to an extracted QAIRT SDK root." >&2
    exit 1
}
[ -d "$SDK/lib/aarch64-android" ] || {
    echo "No Android runtime directory under $SDK/lib/aarch64-android" >&2
    exit 1
}

mkdir -p "$INCLUDE_DEST" "$JNI_DEST"
rm -rf "$INCLUDE_DEST/QNN"
cp -a "$SDK/include/QNN" "$INCLUDE_DEST/QNN"

android_libs=(
    libQnnHtp.so
    libQnnHtpPrepare.so
    libQnnHtpNetRunExtensions.so
    libQnnSystem.so
)
for tier in $TIERS; do
    android_libs+=("libQnnHtpV${tier}Stub.so")
done

for lib in "${android_libs[@]}"; do
    src="$SDK/lib/aarch64-android/$lib"
    [ -f "$src" ] || { echo "Missing required runtime: $src" >&2; exit 1; }
    cp -a "$src" "$JNI_DEST/$lib"
done

for tier in $TIERS; do
    hex="$SDK/lib/hexagon-v${tier}/unsigned"
    for lib in "libQnnHtpV${tier}Skel.so" "libQnnHtpV${tier}.so"; do
        [ -f "$hex/$lib" ] || { echo "Missing required Hexagon runtime: $hex/$lib" >&2; exit 1; }
        cp -a "$hex/$lib" "$JNI_DEST/$lib"
    done
done

version=${QAIRT_VERSION:-$(basename "$(readlink -f "$SDK")")}
{
    echo "QAIRT SDK  $version"
    echo "source     $(readlink -f "$SDK")"
    echo "staged     $(date -u +%Y-%m-%dT%H:%M:%SZ)"
    echo "tiers      $TIERS"
    echo "contents   include/QNN; Android HTP/System runtime; Hexagon skels"
} > "$APP/jniLibs/QNN_STAGED.txt"

echo "QNN staged from $(readlink -f "$SDK")"
echo "  headers: $INCLUDE_DEST/QNN"
echo "  runtime: $JNI_DEST"
du -sh "$INCLUDE_DEST/QNN" "$JNI_DEST"
