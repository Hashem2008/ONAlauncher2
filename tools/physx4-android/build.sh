#!/usr/bin/env bash
# ONA: builds physx-jni 1.1.0's native library (PhysX 4.1.2 + its JNI glue) for Android arm64.
#
# Physics Mod for Minecraft 1.18.2 bundles physx-jni 1.1.0, which only ever shipped x86 desktop
# libraries. This compiles the very same sources (fabmax/PhysX4 at the commit physx-jni 1.1.0
# points to, glue code included) for the phone CPU, a port rather than an emulation.
#
# usage: build.sh <work dir> <output .so>
set -euo pipefail

WORK=$(realpath -m "$1")
OUT=$(realpath -m "$2")
PHYSX_COMMIT=5951e9dbe1e4d003bb7524cfa6aeca19356dc838
NDK=${ANDROID_NDK_HOME:?ANDROID_NDK_HOME must point to an Android NDK}

mkdir -p "$WORK"
cd "$WORK"
if [ ! -d physx4/.git ]; then
  git init -q physx4
  git -C physx4 remote add origin https://github.com/fabmax/PhysX4
fi
git -C physx4 fetch -q --depth 1 origin "$PHYSX_COMMIT"
git -C physx4 checkout -q -f FETCH_HEAD

ROOT="$WORK/physx4"
CM="$ROOT/physx/source/compiler/cmake"

# Android variant of the jni_linux target: JNI headers come from the NDK sysroot
sed -e 's/^FIND_PACKAGE(JNI REQUIRED)/# jni.h comes with the NDK sysroot/' \
    -e 's/PHYSX_LINUX_/PHYSX_ANDROID_/g' \
    "$CM/jni_linux/PhysXJniBindings.cmake" > "$CM/android/PhysXJniBindings.cmake"
cat >> "$CM/android/PhysXJniBindings.cmake" <<'CMAKE'
TARGET_LINK_LIBRARIES(PhysXJniBindings PUBLIC log dl m)
SET_TARGET_PROPERTIES(PhysXJniBindings PROPERTIES OUTPUT_NAME PhysXJniBindings_64)
CMAKE
grep -q 'android/PhysXJniBindings.cmake' "$CM/android/CMakeLists.txt" || \
  echo 'INCLUDE(android/PhysXJniBindings.cmake)' >> "$CM/android/CMakeLists.txt"
# Android's jni.h takes JNIEnv** where the desktop one takes void**
sed -i 's/AttachCurrentThreadAsDaemon((void\*\*) &env, NULL)/AttachCurrentThreadAsDaemon(\&env, NULL)/' \
  "$ROOT/physx/source/physxjnibindings/src/glue.h"
grep -q 'AttachCurrentThreadAsDaemon(&env, NULL)' "$ROOT/physx/source/physxjnibindings/src/glue.h"

# Newer clang warns about things PhysX 4.1 predates; warnings must not stop the port
sed -i 's/ -Werror//g; s/ -Wpedantic//g' "$CM/android/CMakeLists.txt"

export PHYSX_ROOT_DIR="$ROOT/physx"
export PM_CMakeModules_PATH="$ROOT/externals/cmakemodules"
export PM_PxShared_PATH="$ROOT/pxshared"
export PM_PATHS=""

cmake -S "$ROOT/physx/compiler/public" -B "$WORK/build" -G "Unix Makefiles" --no-warn-unused-cli \
  -DTARGET_BUILD_PLATFORM=android \
  -DCMAKE_TOOLCHAIN_FILE="$NDK/build/cmake/android.toolchain.cmake" \
  -DANDROID_ABI=arm64-v8a \
  -DANDROID_PLATFORM=android-26 \
  -DANDROID_STL=c++_static \
  -DCM_ANDROID_FP=softfp \
  -DCMAKE_BUILD_TYPE=release \
  -DPHYSX_ROOT_DIR="$ROOT/physx" \
  -DPX_OUTPUT_LIB_DIR="$ROOT/physx" \
  -DPX_OUTPUT_BIN_DIR="$ROOT/physx" \
  -DPX_BUILDSNIPPETS=OFF \
  -DPX_BUILDPUBLICSAMPLES=OFF \
  -DPX_GENERATE_STATIC_LIBRARIES=ON

cmake --build "$WORK/build" --target PhysXJniBindings -j "$(nproc)"

SO=$(find "$WORK/build" "$ROOT/physx/bin" -name 'libPhysXJniBindings*.so' 2>/dev/null | head -1)
[ -n "$SO" ] || { echo "libPhysXJniBindings was not produced"; exit 1; }
mkdir -p "$(dirname "$OUT")"
cp "$SO" "$OUT"
"$NDK/toolchains/llvm/prebuilt/linux-x86_64/bin/llvm-strip" --strip-unneeded "$OUT"

COUNT=$("$NDK/toolchains/llvm/prebuilt/linux-x86_64/bin/llvm-nm" -D --defined-only "$OUT" | grep -c ' Java_physx_' || true)
echo "Built $OUT ($(stat -c %s "$OUT") bytes, $COUNT JNI entry points)"
[ "$COUNT" -gt 1000 ] || { echo "Too few JNI entry points, the glue did not link"; exit 1; }
file "$OUT" || true
