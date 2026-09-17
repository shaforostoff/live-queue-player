// Drives the real bpm_jni.cpp on the host, through a JNIEnv with four slots in it.
//
// The JNI layer is otherwise untestable anywhere: it only exists inside an
// arm64 .so, and the JVM test suite runs on x86_64. What lives in it is the
// part most likely to be wrong and least likely to be caught elsewhere - the
// session table's lifetime rules, the duration cap's arithmetic, the int16 to
// float conversion, and what happens when cancel and close race. So rather than
// trust it, stand up the four JNIEnv entry points it actually uses and call it.
//
// A jbyteArray here is a pointer to `fake_array` reinterpreted, which is what
// lets GetByteArrayRegion do a real bounds-checked copy.

#include <jni.h>

#include <cmath>
#include <cstdint>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <vector>

// The entry points under test, as the JVM would find them.
extern "C" {
jboolean Java_com_shaforostoff_livequeueplayer_BpmNative_nativeAvailable(JNIEnv *, jclass);
jint     Java_com_shaforostoff_livequeueplayer_BpmNative_nativeSimdSize(JNIEnv *, jclass);
jlong    Java_com_shaforostoff_livequeueplayer_BpmNative_nativeOpen(JNIEnv *, jclass, jint, jint, jint);
jboolean Java_com_shaforostoff_livequeueplayer_BpmNative_nativeAddPcm16(JNIEnv *, jclass, jlong, jbyteArray, jint, jint);
jint     Java_com_shaforostoff_livequeueplayer_BpmNative_nativeFinish(JNIEnv *, jclass, jlong, jdoubleArray);
void     Java_com_shaforostoff_livequeueplayer_BpmNative_nativeCancel(JNIEnv *, jclass, jlong);
void     Java_com_shaforostoff_livequeueplayer_BpmNative_nativeClose(JNIEnv *, jclass, jlong);
jfloat   Java_com_shaforostoff_livequeueplayer_BpmNative_nativeProgress(JNIEnv *, jclass, jlong);
jint     Java_com_shaforostoff_livequeueplayer_BpmNative_nativeOpenSessions(JNIEnv *, jclass);
}

namespace {

struct fake_array { void * data; jsize length; };

jsize get_array_length(JNIEnv *, jarray a)
{
	return reinterpret_cast<fake_array *>(a)->length;
}

void get_byte_region(JNIEnv *, jbyteArray a, jsize start, jsize len, jbyte * out)
{
	fake_array * fa = reinterpret_cast<fake_array *>(a);
	// The real JVM throws ArrayIndexOutOfBounds here; if the code under test
	// ever asks for more than it checked for, say so loudly rather than read
	// past the end and call it a pass.
	if (start < 0 || len < 0 || start > fa->length || len > fa->length - start) {
		std::printf("FAIL  GetByteArrayRegion out of bounds: start=%d len=%d of %d\n",
		            (int)start, (int)len, (int)fa->length);
		std::abort();
	}
	std::memcpy(out, static_cast<const jbyte *>(fa->data) + start, (std::size_t)len);
}

void set_double_region(JNIEnv *, jdoubleArray a, jsize start, jsize len, const jdouble * in)
{
	fake_array * fa = reinterpret_cast<fake_array *>(a);
	std::memcpy(static_cast<jdouble *>(fa->data) + start, in, (std::size_t)len * sizeof(jdouble));
}

jboolean exception_check(JNIEnv *) { return JNI_FALSE; }

JNINativeInterface_ g_fns;
JNIEnv g_env_storage;
JNIEnv * env()
{
	std::memset(&g_fns, 0, sizeof(g_fns));
	g_fns.GetArrayLength      = get_array_length;
	g_fns.GetByteArrayRegion  = get_byte_region;
	g_fns.SetDoubleArrayRegion = set_double_region;
	g_fns.ExceptionCheck      = exception_check;
	g_env_storage.functions = &g_fns;
	return &g_env_storage;
}

int failures = 0;

void check(bool ok, const char * what)
{
	std::printf("%-5s %s\n", ok ? "pass" : "FAIL", what);
	if (!ok) failures++;
}

std::uint32_t rng = 12345u;
float noise() { rng = rng * 1664525u + 1013904223u;
	return (float)((int)(rng >> 8) % 20001 - 10000) / 10000.0f; }

//! The same click track tools/bpmcheck's main.cpp makes, as bytes this time.
std::vector<std::int8_t> click_bytes(double bpm, double seconds, unsigned rate, unsigned ch)
{
	const std::size_t frames = (std::size_t)(seconds * rate);
	std::vector<std::int16_t> pcm(frames * ch, 0);
	const double period = 60.0 / bpm;
	for (double t = 0.0; t < seconds; t += period) {
		const std::size_t start = (std::size_t)(t * rate);
		const std::size_t len   = (std::size_t)(0.02 * rate);
		for (std::size_t i = 0; i < len && start + i < frames; i++) {
			const float envl = std::exp(-8.0f * (float)i / (float)len);
			const float v = noise() * envl * 0.7f;
			for (unsigned c = 0; c < ch; c++)
				pcm[(start + i) * ch + c] = (std::int16_t)(v * 32767.0f);
		}
	}
	std::vector<std::int8_t> bytes(pcm.size() * 2);
	std::memcpy(&bytes[0], &pcm[0], bytes.size());
	return bytes;
}

//! Feed `bytes` in chunks, as NativeBpmEngine does, and analyse.
int measure(JNIEnv * e, const std::vector<std::int8_t> & bytes, unsigned rate, unsigned ch,
            int max_seconds, double * detail_out)
{
	const jlong h = Java_com_shaforostoff_livequeueplayer_BpmNative_nativeOpen(
		e, 0, (jint)rate, (jint)ch, max_seconds);
	if (h == 0) return -99;

	const std::size_t chunk = 64 * 1024;
	for (std::size_t off = 0; off < bytes.size(); off += chunk) {
		const std::size_t n = (off + chunk <= bytes.size()) ? chunk : bytes.size() - off;
		fake_array arr = { (void *)&bytes[0], (jsize)bytes.size() };
		if (!Java_com_shaforostoff_livequeueplayer_BpmNative_nativeAddPcm16(
				e, 0, h, reinterpret_cast<jbyteArray>(&arr), (jint)off, (jint)n)) break;
	}

	double detail[5] = {0,0,0,0,0};
	fake_array darr = { detail, 5 };
	const jint bpm = Java_com_shaforostoff_livequeueplayer_BpmNative_nativeFinish(
		e, 0, h, reinterpret_cast<jdoubleArray>(&darr));
	Java_com_shaforostoff_livequeueplayer_BpmNative_nativeClose(e, 0, h);
	if (detail_out) std::memcpy(detail_out, detail, sizeof(detail));
	return bpm;
}

}   // namespace

int main()
{
	JNIEnv * e = env();

	check(Java_com_shaforostoff_livequeueplayer_BpmNative_nativeAvailable(e, 0) == JNI_TRUE,
	      "nativeAvailable is true in a real build");
	check(Java_com_shaforostoff_livequeueplayer_BpmNative_nativeSimdSize(e, 0) == 4,
	      "pffft reached its SIMD path (4), not the scalar fallback (1)");
	check(Java_com_shaforostoff_livequeueplayer_BpmNative_nativeOpenSessions(e, 0) == 0,
	      "no sessions open before anything starts");

	// --- bad arguments are refused rather than crashed on ---------------
	check(Java_com_shaforostoff_livequeueplayer_BpmNative_nativeOpen(e, 0, 0, 2, 240) == 0,
	      "nativeOpen refuses a zero sample rate");
	check(Java_com_shaforostoff_livequeueplayer_BpmNative_nativeOpen(e, 0, 44100, 0, 240) == 0,
	      "nativeOpen refuses zero channels");
	check(Java_com_shaforostoff_livequeueplayer_BpmNative_nativeOpen(e, 0, 44100, 99, 240) == 0,
	      "nativeOpen refuses an absurd channel count");

	// --- a stale handle is inert, not a crash ---------------------------
	check(Java_com_shaforostoff_livequeueplayer_BpmNative_nativeFinish(e, 0, 999999, 0) == 0,
	      "nativeFinish on an unknown handle returns 0");
	Java_com_shaforostoff_livequeueplayer_BpmNative_nativeCancel(e, 0, 999999);
	Java_com_shaforostoff_livequeueplayer_BpmNative_nativeClose(e, 0, 999999);
	check(Java_com_shaforostoff_livequeueplayer_BpmNative_nativeProgress(e, 0, 999999) == 0.0f,
	      "nativeProgress on an unknown handle returns 0");
	check(true, "cancel and close on an unknown handle do not crash");

	// --- close is idempotent, and handles are never reused ---------------
	{
		const jlong a = Java_com_shaforostoff_livequeueplayer_BpmNative_nativeOpen(e, 0, 44100, 2, 240);
		Java_com_shaforostoff_livequeueplayer_BpmNative_nativeClose(e, 0, a);
		Java_com_shaforostoff_livequeueplayer_BpmNative_nativeClose(e, 0, a);
		const jlong b = Java_com_shaforostoff_livequeueplayer_BpmNative_nativeOpen(e, 0, 44100, 2, 240);
		check(b != a, "a freed handle is never handed out again");
		Java_com_shaforostoff_livequeueplayer_BpmNative_nativeClose(e, 0, b);
		check(Java_com_shaforostoff_livequeueplayer_BpmNative_nativeOpenSessions(e, 0) == 0,
		      "closing leaves no session behind");
	}

	// --- cancel before finish comes back as cancelled --------------------
	{
		rng = 12345u;
		std::vector<std::int8_t> bytes = click_bytes(120.0, 20.0, 44100, 2);
		const jlong h = Java_com_shaforostoff_livequeueplayer_BpmNative_nativeOpen(e, 0, 44100, 2, 240);
		fake_array arr = { (void *)&bytes[0], (jsize)bytes.size() };
		Java_com_shaforostoff_livequeueplayer_BpmNative_nativeAddPcm16(
			e, 0, h, reinterpret_cast<jbyteArray>(&arr), 0, (jint)bytes.size());
		Java_com_shaforostoff_livequeueplayer_BpmNative_nativeCancel(e, 0, h);
		check(Java_com_shaforostoff_livequeueplayer_BpmNative_nativeAddPcm16(
			e, 0, h, reinterpret_cast<jbyteArray>(&arr), 0, 1024) == JNI_FALSE,
		      "a cancelled session stops accepting audio");
		check(Java_com_shaforostoff_livequeueplayer_BpmNative_nativeFinish(e, 0, h, 0) == -1,
		      "a cancelled session finishes as RESULT_CANCELLED");
		Java_com_shaforostoff_livequeueplayer_BpmNative_nativeClose(e, 0, h);
	}

	// --- too short to analyse is 0, not a wrong answer -------------------
	{
		rng = 12345u;
		std::vector<std::int8_t> bytes = click_bytes(120.0, 0.05, 44100, 2);
		check(measure(e, bytes, 44100, 2, 240, 0) == 0,
		      "a fiftieth of a second yields no tempo rather than a wrong one");
	}

	// --- silence is 0 ----------------------------------------------------
	{
		std::vector<std::int8_t> quiet(44100 * 2 * 2 * 30, 0);
		check(measure(e, quiet, 44100, 2, 240, 0) == 0, "thirty seconds of silence yields no tempo");
	}

	// --- the real thing, at three tempos and three rates -----------------
	struct { double bpm; unsigned rate; unsigned ch; } cases[] = {
		{ 120.0, 44100, 2 }, { 120.0, 48000, 2 }, { 120.0, 22050, 1 },
		{  92.0, 44100, 2 }, { 138.0, 44100, 2 },
	};
	for (unsigned i = 0; i < sizeof(cases)/sizeof(cases[0]); i++) {
		rng = 12345u;
		std::vector<std::int8_t> bytes = click_bytes(cases[i].bpm, 60.0, cases[i].rate, cases[i].ch);
		double detail[5];
		const int bpm = measure(e, bytes, cases[i].rate, cases[i].ch, 240, detail);
		char what[160];
		std::snprintf(what, sizeof(what),
		              "%.0f BPM @ %u Hz x%u -> %d  (%.3f exact, beat %.1f, %s)",
		              cases[i].bpm, cases[i].rate, cases[i].ch, bpm,
		              detail[0], detail[1], detail[2] > 0.9 ? "confident" : "unsure");
		check(std::abs(bpm - (int)cases[i].bpm) <= 1, what);
	}

	// --- the duration cap actually truncates -----------------------------
	{
		rng = 12345u;
		std::vector<std::int8_t> bytes = click_bytes(120.0, 60.0, 44100, 2);
		double detail[5];
		measure(e, bytes, 44100, 2, 10, detail);
		// Asked for ten seconds, and one second of that is the guard band that
		// keeps the collector's buffer inside the reserve it made.
		check(detail[4] > 8.0 && detail[4] < 10.5, "a 10s cap analyses about 9s of a 60s track");
	}

	check(Java_com_shaforostoff_livequeueplayer_BpmNative_nativeOpenSessions(e, 0) == 0,
	      "every session was closed");

	std::printf("\n%d failed\n", failures);
	return failures ? 1 : 0;
}
