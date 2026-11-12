#!/usr/bin/env bash
# ONA: builds zstd-jni (github.com/luben/zstd-jni, BSD licensed) for Android.
#
# Distant Horizons bundles zstd-jni renamed to "dhcomgithubluben.zstd" with desktop natives only
# (linux/aarch64/libzstd-jni_dh-1.5.7-6.so needs glibc's libpthread.so.0). On the phone DH stops
# the game at start: "Distant Horizons critical setup error: One or more libraries are either
# in-accessible ... dlopen failed: library "libpthread.so.0" not found". zstd-jni then looks for
# "zstd-jni_dh-<version>" in java.library.path; the launcher puts the build made here there under
# the name the mod asks for (game/version/mod/ZstdJniSupport.kt).
#
# Two libraries come out of the same sources:
#   libona_zstdjni_dh.so  JNI names of Distant Horizons' copy (Java_dhcomgithubluben_zstd_...)
#   libona_zstdjni.so     JNI names of plain zstd-jni (Java_com_github_luben_zstd_...)
# The 148 JNI functions are the same in zstd-jni 1.5.7-6 and 1.5.7-12 (DH 3.0.3 and 3.3.3).
#
# usage: build.sh <work dir> <output dir> [abi: arm64-v8a (default) | armeabi-v7a | x86_64 | x86]
# Host test: HOST_CC=gcc JNI_INCLUDES="-I$JAVA_HOME/include -I$JAVA_HOME/include/linux" build.sh ...
set -euo pipefail

WORK=$(realpath -m "$1")
OUTDIR=$(realpath -m "$2")
ZSTD_JNI_TAG=v1.5.7-6
ABI=${3:-arm64-v8a}
if [ -n "${HOST_CC:-}" ]; then
  CC="$HOST_CC"
  NM=nm
  STRIP=strip
  LIBS="-lpthread"
else
  NDK=${ANDROID_NDK_HOME:?ANDROID_NDK_HOME must point to an Android NDK}
  BIN="$NDK/toolchains/llvm/prebuilt/linux-x86_64/bin"
  case "$ABI" in
    arm64-v8a)   CC="$BIN/aarch64-linux-android26-clang" ;;
    armeabi-v7a) CC="$BIN/armv7a-linux-androideabi26-clang" ;;
    x86_64)      CC="$BIN/x86_64-linux-android26-clang" ;;
    x86)         CC="$BIN/i686-linux-android26-clang" ;;
    *) echo "unknown ABI $ABI" >&2; exit 1 ;;
  esac
  NM="$BIN/llvm-nm"
  STRIP="$BIN/llvm-strip"
  LIBS="-llog -ldl -lm"
fi
echo "Building zstd-jni ($ZSTD_JNI_TAG) for $ABI"

mkdir -p "$WORK"
cd "$WORK"
SRC="$WORK/zstd-jni-${ZSTD_JNI_TAG#v}"
if [ ! -d "$SRC/src/main/native" ]; then
  rm -rf "$SRC"
  if curl -fsSL "https://github.com/luben/zstd-jni/archive/refs/tags/$ZSTD_JNI_TAG.tar.gz" -o zstd-jni.tar.gz; then
    tar -xzf zstd-jni.tar.gz "zstd-jni-${ZSTD_JNI_TAG#v}/src/main/native"
  else
    git clone -q --depth 1 --branch "$ZSTD_JNI_TAG" https://github.com/luben/zstd-jni.git "$SRC"
  fi
fi
NATIVE="$SRC/src/main/native"

FLAGS="-O2 -fPIC -fvisibility=hidden -std=gnu99 -DZSTD_LEGACY_SUPPORT=4 -DZSTD_MULTITHREAD=1 -DZSTD_DISABLE_ASM"
INCLUDES="-I$NATIVE -I$NATIVE/common -I$NATIVE/legacy ${JNI_INCLUDES:-}"

# zstd itself, once for both libraries
mkdir -p "$WORK/obj-$ABI/zstd"
ZSTD_OBJS=()
for src in $(find "$NATIVE/common" "$NATIVE/compress" "$NATIVE/decompress" "$NATIVE/dictBuilder" "$NATIVE/legacy" -name '*.c' | sort); do
  obj="$WORK/obj-$ABI/zstd/$(echo "${src#$NATIVE/}" | tr '/' '_').o"
  # shellcheck disable=SC2086
  "$CC" $FLAGS $INCLUDES -c "$src" -o "$obj"
  ZSTD_OBJS+=("$obj")
done

# build_lib <output name> <JNI prefix> <class path>
build_lib() {
  local name=$1 prefix=$2 classes=$3
  local dir="$WORK/jni-$ABI-$name"
  rm -rf "$dir"
  mkdir -p "$dir"
  local objs=()
  for src in "$NATIVE"/jni_*.c; do
    local copy="$dir/$(basename "$src")"
    sed -e "s/Java_com_github_luben_zstd_/${prefix}_/g" -e "s#com/github/luben/zstd/#${classes}/#g" "$src" > "$copy"
    # shellcheck disable=SC2086
    "$CC" $FLAGS $INCLUDES -c "$copy" -o "$copy.o"
    objs+=("$copy.o")
  done
  local out="$OUTDIR/$name"
  mkdir -p "$OUTDIR"
  # shellcheck disable=SC2086
  "$CC" -shared -Wl,--no-undefined -Wl,-Bsymbolic -Wl,-soname,"$name" -o "$out" "${objs[@]}" "${ZSTD_OBJS[@]}" $LIBS
  "$STRIP" --strip-unneeded "$out"
  local exports
  exports=$("$NM" -D --defined-only "$out" | grep -c " ${prefix}_" || true)
  if [ "$exports" -lt 140 ]; then
    echo "$name has only $exports JNI entry points" >&2
    exit 1
  fi
  if grep -q "com/github/luben" "$out" && [ "$classes" != "com/github/luben/zstd" ]; then
    echo "$name still names com/github/luben classes" >&2
    exit 1
  fi
  echo "Built $out ($(stat -c %s "$out") bytes, $exports JNI entry points)"
}

build_lib libona_zstdjni_dh.so Java_dhcomgithubluben_zstd dhcomgithubluben/zstd
build_lib libona_zstdjni.so Java_com_github_luben_zstd com/github/luben/zstd
