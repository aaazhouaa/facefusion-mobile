#!/usr/bin/env bash
# Stage the QAIRT/QNN headers and Android/Hexagon runtime libraries required by the APK.
#
# Usage:
#   bash work/android/stage_qnn.sh
#   (QNN_SDK_ROOT 由 /etc/profile.d/android-sdk.sh 设为 /opt/QNN)
#
# Override QNN_SDK_ROOT to stage another SDK release. The generated files live below
# app/src/main/cpp/include and app/src/main/jniLibs, both intentionally gitignored.
set -euo pipefail

HERE=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)
SDK=${QNN_SDK_ROOT:-/opt/QNN}
APP="$HERE/app/src/main"
INCLUDE_DEST="$APP/cpp/include"
JNI_DEST="$APP/jniLibs/arm64-v8a"
TIERS=${QNN_HTP_TIERS:-"68 69 73 75 79 81"}

[ -f "$SDK/include/QNN/QnnBackend.h" ] || {
    echo "No QNN headers under $SDK/include/QNN" >&2
    echo "Set QNN_SDK_ROOT to an extracted QAIRT SDK root." >&2
    exit 1
}
[ -d "$SDK/lib/aarch64-android" ] || [ -d "$SDK/jniLibs/arm64-v8a" ] || {
    echo "No Android runtime set under $SDK (expected lib/aarch64-android or jniLibs/arm64-v8a)" >&2
    echo "Set QNN_SDK_ROOT to an extracted QAIRT SDK root." >&2
    exit 1
}

# Two SDK shapes are accepted.  A full QAIRT extract keeps the Android runtimes under
# lib/aarch64-android and each Hexagon skel under lib/hexagon-v<tier>/unsigned.  A bench
# that forwards only the already-packaged set (this container's /opt/QNN) carries all of
# them in one flat jniLibs/arm64-v8a -- already the layout the APK wants.
if [ -d "$SDK/lib/aarch64-android" ]; then
    ANDROID_LIB_DIR="$SDK/lib/aarch64-android"
    skel_src() { echo "$SDK/lib/hexagon-v$1/unsigned/libQnnHtpV$1Skel.so"; }
else
    ANDROID_LIB_DIR="$SDK/jniLibs/arm64-v8a"
    skel_src() { echo "$ANDROID_LIB_DIR/libQnnHtpV$1Skel.so"; }
fi

# Preflight every source before touching the staging tree. This prevents a failed build
# from leaving a half-refreshed set of ignored libraries behind.
android_libs=(libQnnHtp.so libQnnSystem.so)
for tier in $TIERS; do
    android_libs+=("libQnnHtpV${tier}Stub.so")
done
for lib in "${android_libs[@]}"; do
    src="$ANDROID_LIB_DIR/$lib"
    [ -f "$src" ] || { echo "Missing required Android runtime: $src" >&2; exit 1; }
done
for tier in $TIERS; do
    skel="$(skel_src "$tier")"
    [ -f "$skel" ] || { echo "Missing required Hexagon skel: $skel" >&2; exit 1; }
done

mkdir -p "$INCLUDE_DEST" "$JNI_DEST"
rm -rf "$INCLUDE_DEST/QNN"
cp -a "$SDK/include/QNN" "$INCLUDE_DEST/QNN"

# Match the upstream APK layout: backend/system, one Android stub and one Hexagon
# skel per supported tier. The HTP variant implementation and optional helper libraries
# are not packaged; the in-process backend loads the skel through ADSP_LIBRARY_PATH.
rm -f "$JNI_DEST"/libQnnHtpV*.so
rm -f "$JNI_DEST"/libQnnHtpPrepare.so "$JNI_DEST"/libQnnHtpNetRunExtensions.so
for lib in "${android_libs[@]}"; do
    cp -a "$ANDROID_LIB_DIR/$lib" "$JNI_DEST/$lib"
done
for tier in $TIERS; do
    cp -a "$(skel_src "$tier")" "$JNI_DEST/libQnnHtpV${tier}Skel.so"
done

# A flat staged tree is named /opt/QNN, which says nothing about the release it came from;
# the SDK's own staging record does.
version=${QAIRT_VERSION:-$(sed -n 's/^QAIRT SDK[[:space:]]*//p' "$SDK/QNN_STAGED.txt" 2>/dev/null | head -1)}
version=${version:-$(basename "$(readlink -f "$SDK")")}
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
