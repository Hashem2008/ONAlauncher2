/*
 * ONA Launcher 2 - physx-jni 2.2.x on Android (GPL-3.0-or-later)
 *
 * Physics Mod 3.0.x for Forge / Fabric 1.20.1 carries physx-jni 2.2.2, which was only ever built for
 * PC (x86). From 2.3.1 its author also publishes Android ARM64 builds (de.fabmax:physx-jni-android,
 * MIT). The Java side of 2.2.2 and the native side of 2.3.1 differ in 7 of their 4554 native methods
 * (checked one by one: same class, name and JNI signature for all the others). This library is what
 * physx-jni 2.2.2 loads ("libPhysXJniBindings_64.so"): it needs the real 2.3.1 library
 * (libPhysXJniReal.so, found next to it through DT_RUNPATH $ORIGIN), so every JNI lookup and
 * JNI_OnLoad are answered by the real library, and it adds the 7 methods 2.3.1 does not have:
 *
 * - JniThreadManager._init: gone in 2.3.1, whose JNI_OnLoad keeps the Java VM for PhysX threads
 *   itself. Nothing left to do: success.
 * - PxSceneFlagEnum eSUPPRESS_READBACK / eFORCE_READBACK: GPU-only flags PhysX 5.3 dropped (its bit
 *   for them now means "direct GPU API"). A phone has no GPU dynamics: no flag (0).
 * - PxVehicleCommandNonLinearResponseParams "NbSpeedRenponses..." (sic): renamed "NbSpeedResponses..."
 *   in 2.3.1, forwarded.
 * - PxVehicleTankDriveDifferentialParams nbWheelsInTracks: replaced in PhysX 5.3, kept as 0.
 *
 * Built with -nostdlib (no libc needed) by tools/physx-compat/build.sh.
 */

typedef int jint;
typedef long long jlong;
typedef short jshort;
typedef unsigned char jboolean;
typedef void* JNIEnv_;
typedef void* jclass;

#define EXPORT __attribute__((visibility("default")))

/* in the real 2.3.1 library */
extern jshort Java_physx_vehicle2_PxVehicleCommandNonLinearResponseParams__1getNbSpeedResponsesPerCommandValue(JNIEnv_* env, jclass cls, jlong address, jint index);
extern void Java_physx_vehicle2_PxVehicleCommandNonLinearResponseParams__1setNbSpeedResponsesPerCommandValue(JNIEnv_* env, jclass cls, jlong address, jint index, jshort value);

EXPORT jboolean Java_physx_JniThreadManager__1init(JNIEnv_* env, jclass cls) {
    (void) env;
    (void) cls;
    return 1;
}

EXPORT jint Java_physx_physics_PxSceneFlagEnum__1geteSUPPRESS_1READBACK(JNIEnv_* env, jclass cls) {
    (void) env;
    (void) cls;
    return 0;
}

EXPORT jint Java_physx_physics_PxSceneFlagEnum__1geteFORCE_1READBACK(JNIEnv_* env, jclass cls) {
    (void) env;
    (void) cls;
    return 0;
}

EXPORT jshort Java_physx_vehicle2_PxVehicleCommandNonLinearResponseParams__1getNbSpeedRenponsesPerCommandValue(JNIEnv_* env, jclass cls, jlong address, jint index) {
    return Java_physx_vehicle2_PxVehicleCommandNonLinearResponseParams__1getNbSpeedResponsesPerCommandValue(env, cls, address, index);
}

EXPORT void Java_physx_vehicle2_PxVehicleCommandNonLinearResponseParams__1setNbSpeedRenponsesPerCommandValue(JNIEnv_* env, jclass cls, jlong address, jint index, jshort value) {
    Java_physx_vehicle2_PxVehicleCommandNonLinearResponseParams__1setNbSpeedResponsesPerCommandValue(env, cls, address, index, value);
}

EXPORT jint Java_physx_vehicle2_PxVehicleTankDriveDifferentialParams__1getNbWheelsInTracks(JNIEnv_* env, jclass cls, jlong address) {
    (void) env;
    (void) cls;
    (void) address;
    return 0;
}

EXPORT void Java_physx_vehicle2_PxVehicleTankDriveDifferentialParams__1setNbWheelsInTracks(JNIEnv_* env, jclass cls, jlong address, jint value) {
    (void) env;
    (void) cls;
    (void) address;
    (void) value;
}
