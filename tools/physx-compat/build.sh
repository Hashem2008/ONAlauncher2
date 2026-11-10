#!/usr/bin/env bash
# ONA: builds libPhysXJniCompat.so (physx-jni 2.2.x on the Android build of 2.3.1), arm64-v8a only
# (the Android builds of physx-jni exist for arm64 only).
# Usage: build.sh <clang> <output .so>   (clang: the NDK's, or any clang with lld)
set -euo pipefail
CLANG="$1"
OUT="$2"
HERE="$(cd "$(dirname "$0")" && pwd)"
WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT
COMMON=(--target=aarch64-linux-android26 -fPIC -O2 -nostdlib -ffreestanding -nostdinc -fuse-ld=lld -Wl,--hash-style=both -Wl,-z,noexecstack -Wl,-z,max-page-size=16384)
"$CLANG" "${COMMON[@]}" -shared -Wl,-soname,libPhysXJniReal.so "$HERE/real_stub.c" -o "$WORK/libPhysXJniReal.so"
mkdir -p "$(dirname "$OUT")"
"$CLANG" "${COMMON[@]}" -shared -fvisibility=hidden -Wl,-soname,libPhysXJniCompat.so \
  -Wl,--enable-new-dtags -Wl,-rpath,'$ORIGIN' "$HERE/physx_jni_compat.c" "$WORK/libPhysXJniReal.so" -o "$OUT"
echo "Built $OUT"
