#pragma once

#include <oboe/Oboe.h>
#include <vector>
#include <array>
#include <mutex>
#include <atomic>
#include <cstdint>
#include <deque>

// 24 native pad slots: 0-7 = Bank A's active kit, 8-15 = Bank B's,
// 16-23 = Bank C's — lets all three kit banks stay loaded simultaneously
// for instant A/B/C/AB/AC/BC/ABC switching.
constexpr int kMaxPads   = 24;
constexpr int kMaxVoices = 64;

struct PadBuffer {
    std::vector<float> samples;
    int channels   = 0;
    int sampleRate = 44100;
    bool loaded    = false;
};

struct Voice {
    // active = slot claim/reservation lock (CAS'd by trigger callers to grab
    // a free slot). ready = actually safe to mix (audio thread gates on this,
    // not on active). Keeping them separate and setting ready LAST, after all
    // fields below are written, prevents the audio thread from ever reading a
    // half-initialized voice (stale padIndex/position from a previous note).
    std::atomic<bool>  active{false};
    std::atomic<bool>  ready{false};
    int                padIndex = -1;
    double             position = 0.0;
    std::atomic<float> volume{1.0f};
    std::atomic<float> pitch{1.0f};
    // 0.1..1.0 — fraction of the sample's total length to actually play
    // before the voice is cut, for the per-pad "LENGTH" trim control.
    std::atomic<float> lengthFraction{1.0f};
    // -1.0 (full left) .. 0.0 (center) .. 1.0 (full right).
    std::atomic<float> pan{0.0f};
    // Multiplicative trim on top of `volume`, 0.0..2.0 (1.0 = unity).
    std::atomic<float> gain{1.0f};
    // Retrigger fade-out — see triggerPad()/onAudioReady(). Set true from
    // the trigger caller's thread when a new hit on the same pad needs to
    // cut this voice off; releaseGain is only ever read/written by the
    // audio thread itself as it ramps down, so it's a plain float, not
    // atomic (releasing is the only cross-thread signal needed).
    std::atomic<bool>  releasing{false};
    float              releaseGain = 1.0f;
    // Retrigger/steal fade-IN — mirrors releaseGain above but for the start
    // of a voice instead of the end. Set to 0.0f by the trigger caller's
    // thread when a voice is freshly claimed (both a normal free-slot claim
    // and, more importantly, the pool-exhausted "steal the oldest voice"
    // path in triggerPad()), then ramped up to 1.0 by the audio thread over
    // a few ms in onAudioReady, same discipline as releaseGain (only ever
    // touched by the audio thread once published, so a plain float is fine).
    // Softens the click/pop that a voice starting at full amplitude on a
    // non-zero-crossing sample can produce — most audible exactly when the
    // 64-voice pool is under pressure during fast multi-hit playing, which
    // is also when a stolen voice's outgoing sample got cut with zero fade.
    float              attackGain = 1.0f;
    // Monotonically increasing claim order, used only to pick a voice to
    // steal (the oldest one) when the whole 64-voice pool is exhausted —
    // see AudioEngine::triggerPad(). Not part of the active/ready publish
    // protocol, so relaxed ordering is fine.
    std::atomic<uint64_t> claimSeq{0};
};

struct DelayTap {
    int padIndex;
    float volume;
    float pitch;
    float pan;
    float gain;
    int64_t triggerAtFrame;
};

class AudioEngine : public oboe::AudioStreamCallback {
public:
    bool start();
    void stop();

    void loadPadBuffer(int padIndex, const int16_t* pcm, int32_t numFrames,
                       int channels, int sampleRate);

    void triggerPad(int padIndex, float volume, float pitch, bool stopExisting = true,
                     float lengthFraction = 1.0f, float pan = 0.0f, float gain = 1.0f);
    void setPadVolume(int padIndex, float volume);
    void setPadPitch(int padIndex, float pitch);
    void setPadPan(int padIndex, float pan);
    void setPadGain(int padIndex, float gain);
    void stopPad(int padIndex);

    // Actual device-native rate the stream opened at (never a hardcoded
    // constant — see start()). Kotlin needs this to convert ms-based knobs
    // (e.g. delay time) into frame counts correctly on every device.
    int getSampleRate() const { return outputSampleRate_; }

    // Delay
    void setDelayEnabled(bool enabled);
    void setDelayParams(float decayFactor, int maxTaps);
    void setDelayTapIntervalFrames(int64_t frames);
    void setDelayChokePad(int padIndex);

    // EQ + Master Level  (applied in onAudioReady on the final mix)
    void setMasterLevel(float level);              // 0f..2f  (1f = unity)
    void setEqBands(float low, float mid, float high); // each 0f..2f

    oboe::DataCallbackResult onAudioReady(
            oboe::AudioStream *stream, void *audioData, int32_t numFrames) override;
    void restartStream();
    void onErrorBeforeClose(oboe::AudioStream*, oboe::Result) override;
    void onErrorAfterClose(oboe::AudioStream*, oboe::Result) override;

private:
    std::shared_ptr<oboe::AudioStream> stream_;
    std::array<PadBuffer, kMaxPads>    buffers_;
    std::array<Voice, kMaxVoices>      voices_;
    std::mutex  bufferMutex_;
    int         outputSampleRate_ = 48000;
    // Incremented on every voice claim (fresh or stolen); lets triggerPad
    // find the oldest playing voice to steal when the pool is exhausted.
    std::atomic<uint64_t> voiceClaimCounter_{0};

    // Smoothed polyphony headroom — only ever touched from the audio
    // callback thread (onAudioReady), so no atomic needed. See onAudioReady
    // for why this is smoothed instead of recomputed instantly every buffer.
    float smoothedHeadroom_ = 1.0f;

    // Delay
    std::atomic<bool>    delayEnabled_{false};
    std::atomic<float>   delayDecayFactor_{0.5f};
    std::atomic<int>     delayMaxTaps_{1};
    std::atomic<int64_t> delayTapIntervalFrames_{0};
    std::atomic<int>     delayChokePad_{-1};
    std::deque<DelayTap> delayTaps_;
    std::mutex           delayMutex_;
    int64_t              streamFrameCounter_ = 0;

    // EQ (simple 3-band shelving applied post-mix)
    // Implemented as a single-pole IIR shelving filter per band.
    std::atomic<float> masterLevel_{1.0f};
    std::atomic<float> eqLow_{1.0f};   // low shelf  (< ~300 Hz)
    std::atomic<float> eqMid_{1.0f};   // mid band   (~300–4000 Hz)
    std::atomic<float> eqHigh_{1.0f};  // high shelf (> ~4000 Hz)

    // IIR filter state (stereo: L and R for each band)
    float lpState_[2]  = {0,0};   // low-pass  state (for low band)
    float hp1State_[2] = {0,0};   // first hi-pass state (for high band)
    float bp1State_[2] = {0,0};   // intermediate state for mid

    void fireDelayTaps(int32_t numFrames);
};
