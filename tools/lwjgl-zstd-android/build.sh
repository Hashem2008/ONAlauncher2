#!/usr/bin/env bash
# ONA: builds LWJGL's zstd binding (liblwjgl_zstd.so) for Android arm64.
#
# Voxy bundles org.lwjgl:lwjgl-zstd 3.3.3 with Linux / Windows x64 natives only. On the phone
# Voxy's storage then fails with "Failed to locate library: liblwjgl_zstd.so", every LOD section
# it saves or loads is lost and the world shows holes and glitches. This compiles the same
# LWJGL 3.3.3 JNI code plus the zstd library for the phone CPU (BSD licensed, both).
#
# usage: build.sh <work dir> <output .so> [abi: arm64-v8a (default) | armeabi-v7a | x86_64 | x86]
set -euo pipefail

WORK=$(realpath -m "$1")
OUT=$(realpath -m "$2")
LWJGL_TAG=3.3.3
ZSTD_TAG=v1.5.5
NDK=${ANDROID_NDK_HOME:?ANDROID_NDK_HOME must point to an Android NDK}
BIN="$NDK/toolchains/llvm/prebuilt/linux-x86_64/bin"
ABI=${3:-arm64-v8a}
case "$ABI" in
  arm64-v8a)   CC="$BIN/aarch64-linux-android26-clang" ;;
  armeabi-v7a) CC="$BIN/armv7a-linux-androideabi26-clang" ;;
  x86_64)      CC="$BIN/x86_64-linux-android26-clang" ;;
  x86)         CC="$BIN/i686-linux-android26-clang" ;;
  *) echo "unknown ABI $ABI" >&2; exit 1 ;;
esac
echo "Building liblwjgl_zstd.so for $ABI"

mkdir -p "$WORK"
cd "$WORK"
if [ ! -d "lwjgl3-$LWJGL_TAG" ]; then
  curl -fsSL "https://github.com/LWJGL/lwjgl3/archive/refs/tags/$LWJGL_TAG.tar.gz" -o lwjgl.tar.gz
  tar -xzf lwjgl.tar.gz "lwjgl3-$LWJGL_TAG/modules/lwjgl/zstd" "lwjgl3-$LWJGL_TAG/modules/lwjgl/core/src/main/c"
fi
MOD="$WORK/lwjgl3-$LWJGL_TAG/modules/lwjgl/zstd"
GEN="$MOD/src/generated/c"
CORE_H=$(dirname "$(find "$WORK/lwjgl3-$LWJGL_TAG/modules/lwjgl/core/src/main/c" -name common_tools.h | head -1)")
LINUX_H=$(dirname "$(find "$WORK/lwjgl3-$LWJGL_TAG/modules/lwjgl/core/src/main/c" -name LinuxConfig.h | head -1)")

# zstd itself: the upstream release LWJGL 3.3.3 was made with. LWJGL's own copy leaves out xxhash
# (it lives in LWJGL's xxhash module), so it does not build on its own.
if [ ! -d "zstd-${ZSTD_TAG#v}" ]; then
  curl -fsSL "https://github.com/facebook/zstd/archive/refs/tags/$ZSTD_TAG.tar.gz" -o zstd.tar.gz
  tar -xzf zstd.tar.gz "zstd-${ZSTD_TAG#v}/lib"
fi
ZSTD="$WORK/zstd-${ZSTD_TAG#v}/lib"
echo "LWJGL zstd binding: $GEN"
echo "zstd sources: $ZSTD"
echo "LWJGL headers: $CORE_H $LINUX_H"

SOURCES=$(find "$ZSTD/common" "$ZSTD/compress" "$ZSTD/decompress" "$ZSTD/dictBuilder" -name '*.c'; find "$GEN" -name '*.c')
FLAGS="-O2 -fPIC -fvisibility=hidden -DLWJGL_LINUX -DZSTD_MULTITHREAD -DZSTD_DISABLE_ASM -DZSTD_LEGACY_SUPPORT=0"
INCLUDES="-I$ZSTD -I$ZSTD/common -I$ZSTD/compress -I$ZSTD/dictBuilder -I$CORE_H -I$LINUX_H"

mkdir -p "$WORK/obj-$ABI"
OBJS=()
for src in $SOURCES; do
  obj="$WORK/obj-$ABI/$(echo "${src#$WORK/}" | tr '/' '_').o"
  # shellcheck disable=SC2086
  "$CC" $FLAGS $INCLUDES -c "$src" -o "$obj"
  OBJS+=("$obj")
done
mkdir -p "$(dirname "$OUT")"
"$CC" -shared -Wl,--no-undefined -Wl,-soname,liblwjgl_zstd.so -o "$OUT" "${OBJS[@]}" -llog -ldl -lm
"$BIN/llvm-strip" --strip-unneeded "$OUT"

exports=$("$BIN/llvm-nm" -D --defined-only "$OUT" | grep -c ' Java_org_lwjgl_util_zstd_' || true)
if [ "$exports" -lt 10 ]; then
  echo "liblwjgl_zstd.so has only $exports JNI entry points" >&2
  exit 1
fi
echo "Built $OUT ($(stat -c %s "$OUT") bytes, $exports JNI entry points)"
