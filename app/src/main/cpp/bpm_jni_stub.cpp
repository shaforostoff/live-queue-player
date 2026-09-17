// The do-nothing half of BpmNative, built for every ABI but arm64-v8a.
//
// It exists so the APK carries a lib/<abi>/ entry for those ABIs. An APK with no
// .so for an ABI is refused outright there - INSTALL_FAILED_NO_MATCHING_ABIS for
// a universal APK, and for an AAB the app is marked 64-bit-only and hidden from
// every 32-bit device on Play. So the choice is not "BPM or no BPM" on those
// devices, it is "the player or nothing", and a few hundred bytes of stub is
// what buys the player.
//
// Every entry point BpmNative declares is defined here, because the JVM resolves
// a native method on first call and a missing one is an UnsatisfiedLinkError at
// that call rather than at load. nativeAvailable() returning false is what stops
// the Java side ever reaching the rest: BpmScanner never starts a decode, so the
// whole feature costs these devices one dlopen.

#include <jni.h>

extern "C" {

JNIEXPORT jboolean JNICALL
Java_com_shaforostoff_livequeueplayer_BpmNative_nativeAvailable(JNIEnv *, jclass)
{
	return JNI_FALSE;
}

JNIEXPORT jint JNICALL
Java_com_shaforostoff_livequeueplayer_BpmNative_nativeSimdSize(JNIEnv *, jclass)
{
	return 0;   // 4 or 1 from a real build; 0 says "there is no transform here"
}

JNIEXPORT jlong JNICALL
Java_com_shaforostoff_livequeueplayer_BpmNative_nativeOpen(JNIEnv *, jclass, jint, jint, jint)
{
	return 0;
}

JNIEXPORT jboolean JNICALL
Java_com_shaforostoff_livequeueplayer_BpmNative_nativeAddPcm16(
		JNIEnv *, jclass, jlong, jbyteArray, jint, jint)
{
	return JNI_FALSE;
}

JNIEXPORT jint JNICALL
Java_com_shaforostoff_livequeueplayer_BpmNative_nativeFinish(
		JNIEnv *, jclass, jlong, jdoubleArray)
{
	return 0;
}

JNIEXPORT void JNICALL
Java_com_shaforostoff_livequeueplayer_BpmNative_nativeCancel(JNIEnv *, jclass, jlong) {}

JNIEXPORT void JNICALL
Java_com_shaforostoff_livequeueplayer_BpmNative_nativeClose(JNIEnv *, jclass, jlong) {}

JNIEXPORT jfloat JNICALL
Java_com_shaforostoff_livequeueplayer_BpmNative_nativeProgress(JNIEnv *, jclass, jlong)
{
	return 0.0f;
}

JNIEXPORT jint JNICALL
Java_com_shaforostoff_livequeueplayer_BpmNative_nativeOpenSessions(JNIEnv *, jclass)
{
	return 0;
}

}   // extern "C"
