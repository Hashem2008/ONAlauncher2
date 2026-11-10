physx-jni 2.2.x on Android (ONA bridge)

Physics Mod 3.0.x for Minecraft 1.20.1 carries physx-jni 2.2.2, built only for PC. physx-jni has Android
ARM64 builds from 2.3.1 on (de.fabmax:physx-jni-android, MIT). libPhysXJniCompat.so lets 2.2.2's Java
side use 2.3.1's Android library:

  <cache>/physx-android/2.2.2-bridge/libPhysXJniBindings_64.so  = libPhysXJniCompat.so (this bridge)
  <cache>/physx-android/2.2.2-bridge/libPhysXJniReal.so          = Android libPhysXJniBindings_64.so 2.3.1
  JVM: -Dphysxjni.nativeLibLocation=<that folder> -Dphysxjni.loadFromResources=false

The bridge has DT_NEEDED libPhysXJniReal.so and DT_RUNPATH $ORIGIN: the JVM's JNI lookups and
JNI_OnLoad (dlsym on the bridge's handle) reach the real library through it. It adds the 7 native
methods 2.3.1 does not have (see physx_jni_compat.c).

Checked on the real files: of 2.2.2's 4554 native methods, 4014 are in the Android 2.3.1 library,
7 in the bridge, 533 are GPU / CUDA / particle / OmniPVD methods the Android build leaves out for
every version (only called when a mod uses GPU physics).

Build (any clang with lld, no NDK needed, arm64 only):
  bash tools/physx-compat/build.sh clang ZalithLauncher/src/main/jniLibs/arm64-v8a/libPhysXJniCompat.so
The result is committed (4 KB). Launcher side: game/version/mod/PhysxSupport.kt (BRIDGED_VERSIONS).
