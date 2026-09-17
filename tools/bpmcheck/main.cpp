// Drives the VENDORED bpmcore the same way bpm_jni.cpp does: interleaved int16
// through a collector, threads=1. Synthesises its own audio, so it proves the
// vendored copy and the API usage without any file on disk.
#include <bpmcore/bpmcore.h>
#include <pffft/pffft.h>
#include <cmath>
#include <cstdint>
#include <cstdio>
#include <vector>

// Deterministic noise, so the answer is reproducible anywhere.
static std::uint32_t rng = 12345u;
static float noise() { rng = rng * 1664525u + 1013904223u;
    return (float)((int)(rng >> 8) % 20001 - 10000) / 10000.0f; }

// A click track: a short decaying broadband burst on every beat. Broadband
// because the ODF is spectral flux over six bands from 60Hz to 8kHz.
static std::vector<std::int16_t> click_track(double bpm, double seconds,
                                             unsigned rate, unsigned channels)
{
    const std::size_t frames = (std::size_t)(seconds * rate);
    std::vector<std::int16_t> pcm(frames * channels, 0);
    const double period = 60.0 / bpm;
    for (double t = 0.0; t < seconds; t += period) {
        const std::size_t start = (std::size_t)(t * rate);
        const std::size_t len   = (std::size_t)(0.02 * rate);
        for (std::size_t i = 0; i < len && start + i < frames; i++) {
            const float env = std::exp(-8.0f * (float)i / (float)len);
            const float v = noise() * env * 0.7f;
            for (unsigned c = 0; c < channels; c++)
                pcm[(start + i) * channels + c] = (std::int16_t)(v * 32767.0f);
        }
    }
    return pcm;
}

static bpmcore::analysis run(const std::vector<std::int16_t> & pcm,
                             unsigned rate, unsigned channels)
{
    bpmcore::collector col(rate);
    const std::size_t total = pcm.size() / channels;
    const std::size_t chunk = 16384;                 // frames, as MediaCodec would
    std::vector<float> flt;
    for (std::size_t f = 0; f < total && !col.full(); f += chunk) {
        const std::size_t n = (f + chunk <= total) ? chunk : total - f;
        flt.resize(n * channels);
        for (std::size_t i = 0; i < n * channels; i++)
            flt[i] = (float)pcm[f * channels + i] * (1.0f / 32768.0f);
        col.add_interleaved(&flt[0], n, channels);
    }
    bpmcore::options opt; opt.threads = 1;
    return col.finish(0, &opt);
}

int main()
{
    std::printf("pffft_simd_size = %d\n\n", pffft_simd_size());
    const struct { double bpm; unsigned rate; unsigned ch; } cases[] = {
        { 120.0, 44100, 2 },   // no resampler: 44100 is 22050 * 2
        { 120.0, 48000, 2 },   // resampled to 22050
        { 120.0, 22050, 1 },   // the model rate itself, mono
        {  92.0, 44100, 2 },
        { 138.0, 44100, 2 },
    };
    int bad = 0;
    for (unsigned i = 0; i < sizeof(cases)/sizeof(cases[0]); i++) {
        rng = 12345u;
        std::vector<std::int16_t> pcm = click_track(cases[i].bpm, 60.0,
                                                    cases[i].rate, cases[i].ch);
        const bpmcore::analysis a = run(pcm, cases[i].rate, cases[i].ch);
        const double err = a.ok ? a.bpm - cases[i].bpm : 999.0;
        const bool pass = a.ok && std::fabs(err) < 1.0;
        if (!pass) bad++;
        std::printf("%-6s %6.1f BPM @ %5u Hz x%u -> ok=%d bpm=%7.3f beat=%7.3f "
                    "%-8s conf=%.2f meter=%d dur=%.1f err=%+.3f\n",
                    pass ? "pass" : "FAIL", cases[i].bpm, cases[i].rate, cases[i].ch,
                    (int)a.ok, a.bpm, a.beat_bpm, bpmcore::rhythm_name(a.rhythm),
                    a.confidence, a.meter, a.duration, err);
    }
    std::printf("\n%d of %u failed\n", bad, (unsigned)(sizeof(cases)/sizeof(cases[0])));
    return bad ? 1 : 0;
}
