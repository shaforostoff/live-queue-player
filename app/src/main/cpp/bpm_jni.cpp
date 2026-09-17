// JNI shell around bpmcore, for BpmNative.java.
//
// One session per track: a bpmcore::collector fed 16-bit PCM as MediaCodec
// produces it, then analysed on the calling thread. The whole track has to be
// resident - bpmcore normalises its onset envelope by the track's overall level,
// which is not knowable until the last sample - so the collector is what bounds
// the memory, and it is the reason this is a streamed API rather than one call
// taking a byte[]: buffering the int16 in Java first would cost ~31MB of Java
// heap for a three minute stereo track, on top of everything the collector holds
// anyway.
//
// Threading: options.threads is pinned to 1 and never exposed to Java. bpmcore
// would happily spread the spectral stage across cores, but this app runs whole
// tracks in parallel instead, and the two kinds of parallelism would just fight
// over the same cores. bpmcore documents the answer as identical either way.
//
// Cancellation and progress cross the boundary as native atomics rather than as
// a callback into Java. That is worth a sentence: it means no cached jmethodID,
// no AttachCurrentThread, no ProGuard keep rule for a listener interface, and -
// the part that actually matters - cancellation that works *during* finish(),
// which is the multi-second stretch the Java side cannot otherwise interrupt.

#include <bpmcore/bpmcore.h>
#include <pffft/pffft.h>

#include <jni.h>

#include <atomic>
#include <cmath>
#include <cstdint>
#include <map>
#include <memory>
#include <mutex>
#include <vector>

namespace
{

//! What bpmcore::collector reserves for, in seconds of audio at its analysis
//! rate. Feeding more than this makes a buffer of tens of megabytes double.
const int reserve_seconds = 240;

struct session : public bpmcore::listener
{
	explicit session(unsigned rate, unsigned ch, std::uint64_t cap)
		: col(rate), channels(ch), frame_cap(cap) {}

	bool cancelled() override { return abort.load(std::memory_order_relaxed); }
	void progress(double f) override { frac.store(f, std::memory_order_relaxed); }

	bpmcore::collector col;
	unsigned channels;
	std::uint64_t frame_cap;
	std::uint64_t frames_fed = 0;
	std::atomic<bool> abort{false};
	std::atomic<double> frac{0.0};

	// Reused across chunks so a feed does not allocate. raw is int16_t rather
	// than a reinterpreted jbyte* because a jbyte* carries no alignment promise.
	std::vector<std::int16_t> raw;
	std::vector<float> flt;
};

std::mutex g_lock;
std::map<jlong, std::shared_ptr<session> > g_sessions;
//! Never reused, so a stale handle is a lookup miss rather than another track.
jlong g_next_handle = 1;

std::shared_ptr<session> find(jlong handle)
{
	std::lock_guard<std::mutex> guard(g_lock);
	std::map<jlong, std::shared_ptr<session> >::iterator it = g_sessions.find(handle);
	return it == g_sessions.end() ? std::shared_ptr<session>() : it->second;
}

}   // namespace

extern "C" {

JNIEXPORT jboolean JNICALL
Java_com_shaforostoff_livequeueplayer_BpmNative_nativeAvailable(JNIEnv *, jclass)
{
	return JNI_TRUE;
}

JNIEXPORT jint JNICALL
Java_com_shaforostoff_livequeueplayer_BpmNative_nativeSimdSize(JNIEnv *, jclass)
{
	// 4 where pffft reached its SIMD path, 1 where it silently fell back to
	// scalar - which is a sixfold slowdown that reports itself no other way.
	return static_cast<jint>(pffft_simd_size());
}

JNIEXPORT jlong JNICALL
Java_com_shaforostoff_livequeueplayer_BpmNative_nativeOpen(
		JNIEnv *, jclass, jint sample_rate, jint channels, jint max_seconds)
{
	if (sample_rate <= 0 || channels < 1 || channels > 8 || max_seconds <= 0) return 0;

	const std::uint64_t rate = static_cast<std::uint64_t>(sample_rate);
	std::uint64_t cap = static_cast<std::uint64_t>(max_seconds) * rate;

	// Stop a second short of what the collector reserved for, rather than
	// exactly at it. Where a resampler is in the way the output length is the
	// input length times an approximated ratio, so landing exactly on the
	// reserve can land a sample or two past it - and those samples cost a
	// reallocation of the entire buffer.
	if (max_seconds >= reserve_seconds && cap > rate) cap -= rate;

	std::shared_ptr<session> s;
	try
	{
		s.reset(new session(static_cast<unsigned>(sample_rate),
		                    static_cast<unsigned>(channels), cap));
	}
	catch (...)
	{
		// The collector reserves tens of megabytes in its constructor; that is
		// exactly the allocation that can fail, and losing one track's tempo is
		// the right cost for it.
		return 0;
	}

	std::lock_guard<std::mutex> guard(g_lock);
	const jlong handle = g_next_handle++;
	g_sessions[handle] = s;
	return handle;
}

JNIEXPORT jboolean JNICALL
Java_com_shaforostoff_livequeueplayer_BpmNative_nativeAddPcm16(
		JNIEnv * env, jclass, jlong handle, jbyteArray pcm, jint offset, jint length)
{
	std::shared_ptr<session> s = find(handle);
	if (!s || pcm == NULL || offset < 0 || length <= 0) return JNI_FALSE;
	if (s->abort.load(std::memory_order_relaxed)) return JNI_FALSE;

	const jsize avail = env->GetArrayLength(pcm);
	if (offset > avail || length > avail - offset) return JNI_FALSE;

	const unsigned ch = s->channels;
	const std::size_t bytes_per_frame = static_cast<std::size_t>(ch) * 2;
	std::size_t frames = static_cast<std::size_t>(length) / bytes_per_frame;
	if (frames == 0) return JNI_TRUE;   // a partial frame; wait for the rest

	if (s->frames_fed >= s->frame_cap) return JNI_FALSE;
	const std::uint64_t room = s->frame_cap - s->frames_fed;
	if (static_cast<std::uint64_t>(frames) > room)
		frames = static_cast<std::size_t>(room);

	const std::size_t samples = frames * ch;
	try
	{
		s->raw.resize(samples);
		s->flt.resize(samples);
	}
	catch (...)
	{
		return JNI_FALSE;
	}

	env->GetByteArrayRegion(pcm, offset, static_cast<jsize>(samples * 2),
	                        reinterpret_cast<jbyte *>(&s->raw[0]));
	if (env->ExceptionCheck()) { env->ExceptionClear(); return JNI_FALSE; }

	// MediaCodec emits host-endian PCM16 and every Android ABI is little-endian,
	// so there is no swapping to do. No per-sample isfinite() check either: this
	// quotient is in [-1, 1] by construction and cannot be NaN. Add one here if
	// a float input path is ever taken - bpmcore does not guard non-finite
	// samples itself, and a single NaN poisons the whole-buffer RMS.
	const float scale = 1.0f / 32768.0f;
	for (std::size_t i = 0; i < samples; i++)
		s->flt[i] = static_cast<float>(s->raw[i]) * scale;

	s->col.add_interleaved(&s->flt[0], frames, ch);
	s->frames_fed += frames;

	return (s->frames_fed < s->frame_cap && !s->col.full()) ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jint JNICALL
Java_com_shaforostoff_livequeueplayer_BpmNative_nativeFinish(
		JNIEnv * env, jclass, jlong handle, jdoubleArray detail)
{
	std::shared_ptr<session> s = find(handle);
	if (!s) return 0;

	bpmcore::analysis a;
	try
	{
		bpmcore::options opt;
		opt.threads = 1;   // see the header comment; never exposed to Java
		a = s->col.finish(s.get(), &opt);
	}
	catch (...)
	{
		return 0;
	}

	if (detail != NULL && env->GetArrayLength(detail) >= 5)
	{
		jdouble d[5];
		d[0] = a.bpm;
		d[1] = a.beat_bpm;
		d[2] = a.confidence;
		d[3] = static_cast<jdouble>(a.rhythm);
		d[4] = a.duration;
		env->SetDoubleArrayRegion(detail, 0, 5, d);
	}

	if (s->abort.load(std::memory_order_relaxed)) return -1;
	if (!a.ok) return 0;

	// The same 1..400 window MetadataExtractor.parseBpmValue applies to a tagged
	// value, so a measured BPM can never be a number a tagged one could not be.
	if (!std::isfinite(a.bpm) || a.bpm < 1.0 || a.bpm > 400.0) return 0;
	return static_cast<jint>(std::lround(a.bpm));
}

JNIEXPORT void JNICALL
Java_com_shaforostoff_livequeueplayer_BpmNative_nativeCancel(JNIEnv *, jclass, jlong handle)
{
	// The shared_ptr is copied out under the lock and the flag set after it is
	// released, so a nativeClose racing this cannot free the session underneath
	// us, and a long finish() is not blocked behind the map lock either.
	std::shared_ptr<session> s = find(handle);
	if (s) s->abort.store(true, std::memory_order_relaxed);
}

JNIEXPORT void JNICALL
Java_com_shaforostoff_livequeueplayer_BpmNative_nativeClose(JNIEnv *, jclass, jlong handle)
{
	std::shared_ptr<session> doomed;   // released after the lock, not under it
	{
		std::lock_guard<std::mutex> guard(g_lock);
		std::map<jlong, std::shared_ptr<session> >::iterator it = g_sessions.find(handle);
		if (it == g_sessions.end()) return;   // idempotent
		doomed = it->second;
		g_sessions.erase(it);
	}
}

JNIEXPORT jfloat JNICALL
Java_com_shaforostoff_livequeueplayer_BpmNative_nativeProgress(JNIEnv *, jclass, jlong handle)
{
	std::shared_ptr<session> s = find(handle);
	if (!s) return 0.0f;
	return static_cast<jfloat>(s->frac.load(std::memory_order_relaxed));
}

JNIEXPORT jint JNICALL
Java_com_shaforostoff_livequeueplayer_BpmNative_nativeOpenSessions(JNIEnv *, jclass)
{
	std::lock_guard<std::mutex> guard(g_lock);
	return static_cast<jint>(g_sessions.size());
}

}   // extern "C"
