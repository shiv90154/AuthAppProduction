#include "AudioEngine.h"
#include <android/log.h>
#include <cmath>
#include <algorithm>

#define TAG "AudioEngine"
#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

// ─────────────────────────────────────────────────────────────────────────────
// Stream lifecycle
// ─────────────────────────────────────────────────────────────────────────────

bool AudioEngine::start() {
    oboe::AudioStreamBuilder builder;
    builder.setDirection(oboe::Direction::Output)
            ->setPerformanceMode(oboe::PerformanceMode::LowLatency)
            // Ask for Exclusive (MMAP) mode — the lowest-latency path AAudio
            // offers, bypassing the mixer. If the device/driver doesn't
            // support it, Oboe silently falls back to Shared mode itself, so
            // this is safe to request unconditionally and always gets us the
            // best available option on this specific phone.
            ->setSharingMode(oboe::SharingMode::Exclusive)
            ->setFormat(oboe::AudioFormat::Float)
            ->setChannelCount(oboe::ChannelCount::Stereo)
            // Deliberately NOT calling setSampleRate(): requesting a fixed
            // rate (this used to force 48000) can make AAudio insert its own
            // internal resampler in front of the stream on any device whose
            // native output rate isn't exactly that — a pure latency cost
            // with zero audible benefit here, since every voice already
            // computes its own playback rate from whatever the stream
            // actually opens at (outputSampleRate_, read below) rather than
            // assuming 48000. Leaving this unset lets Oboe/AAudio open the
            // stream at the device's native optimal rate instead.
            ->setCallback(this);

    oboe::Result result = builder.openStream(stream_);
    if (result != oboe::Result::OK) {
        LOGE("Failed to open stream: %s", oboe::convertToText(result));
        return false;
    }

    outputSampleRate_ = stream_->getSampleRate();
    streamFrameCounter_ = 0;

    // Shrink the stream buffer down to a single burst — the smallest size
    // the device will accept — for the lowest achievable output latency.
    // (setBufferSizeInFrames adjusts how much of the allocated buffer
    // capacity is actually used; it's safe to call before requestStart.)
    int32_t framesPerBurst = stream_->getFramesPerBurst();
    if (framesPerBurst > 0) {
        stream_->setBufferSizeInFrames(framesPerBurst);
    }

    result = stream_->requestStart();
    if (result != oboe::Result::OK) {
        LOGE("Failed to start stream: %s", oboe::convertToText(result));
        return false;
    }

    // Logged as an int (not oboe::convertToText) since not every Oboe
    // version exposes a SharingMode text overload — this avoids a build
    // break against whichever Oboe version CMake fetches.
    LOGD("AudioEngine started: sampleRate=%d sharingMode=%d(0=Exclusive,1=Shared) framesPerBurst=%d bufferSize=%d",
         outputSampleRate_,
         static_cast<int>(stream_->getSharingMode()),
         framesPerBurst,
         stream_->getBufferSizeInFrames());
    return true;
}

void AudioEngine::stop() {
    if (stream_) {
        stream_->stop();
        stream_->close();
        stream_.reset();
    }
    // Clear pending delay taps
    std::lock_guard<std::mutex> lock(delayMutex_);
    delayTaps_.clear();
}

// ─────────────────────────────────────────────────────────────────────────────
// Buffer loading
// ─────────────────────────────────────────────────────────────────────────────

void AudioEngine::loadPadBuffer(int padIndex, const int16_t* pcm, int32_t numFrames,
                                int channels, int sampleRate) {
    if (padIndex < 0 || padIndex >= kMaxPads) return;
    // A malformed/corrupt import can report a bogus sample rate. onAudioReady
    // computes playback rate as pitch * (buf.sampleRate / outputSampleRate_);
    // sampleRate <= 0 makes that rate 0, so v.position never advances and the
    // voice's end-of-sample check never trips — it plays frame 0 forever and
    // never releases its slot, permanently leaking a voice out of the shared
    // 64-voice pool. Reject the load outright rather than accept a buffer
    // that can never finish playing; the pad simply keeps whatever (or no)
    // audio it had before.
    if (sampleRate <= 0) return;

    std::vector<float> stereo;
    stereo.reserve(numFrames * 2);

    for (int32_t i = 0; i < numFrames; i++) {
        float l, r;
        if (channels == 1) {
            float s = pcm[i] / 32768.0f;
            l = r = s;
        } else {
            l = pcm[i * 2]     / 32768.0f;
            r = pcm[i * 2 + 1] / 32768.0f;
        }
        stereo.push_back(l);
        stereo.push_back(r);
    }

    std::lock_guard<std::mutex> lock(bufferMutex_);

    // BUG FIX ("khich khich awaaz jab patch badal ke bajate hain"): swapping
    // buffers_[padIndex].samples out from under a voice that is CURRENTLY
    // playing this slot means the next audio callback reads a completely
    // different waveform from wherever v.position happened to be — an
    // instant amplitude discontinuity = a click/pop, heard every time the
    // user changes kit while pads are still sounding and keeps playing. Flag
    // any live voice on this pad as `releasing` so the audio thread ramps it
    // down over ~5ms instead of hard-cutting into the new sample. New hits
    // after the swap claim fresh voices and play the new buffer cleanly.
    for (auto &v : voices_) {
        if (v.ready.load(std::memory_order_acquire) && v.padIndex == padIndex) {
            v.releasing.store(true, std::memory_order_release);
        }
    }

    buffers_[padIndex].samples    = std::move(stereo);
    buffers_[padIndex].channels   = 2;
    buffers_[padIndex].sampleRate = sampleRate;
    buffers_[padIndex].loaded     = true;

    LOGD("Loaded pad %d: %d frames @ %dHz", padIndex, numFrames, sampleRate);
}

// ─────────────────────────────────────────────────────────────────────────────
// Pad playback
// ─────────────────────────────────────────────────────────────────────────────

void AudioEngine::triggerPad(int padIndex, float volume, float pitch, bool stopExisting,
                              float lengthFraction, float pan, float gain, float startFraction) {
    if (padIndex < 0 || padIndex >= kMaxPads) return;

    // A pitch of exactly (or near) 0 makes onAudioReady's playback rate 0,
    // which — like an invalid sample rate — freezes a voice on frame 0
    // forever and leaks it out of the pool. Clamp to a safe minimum
    // magnitude, preserving direction/sign.
    if (pitch == 0.0f) {
        pitch = 1.0f;
    } else if (pitch > -0.01f && pitch < 0.01f) {
        pitch = pitch < 0.0f ? -0.01f : 0.01f;
    }

    // Everything in this block touches buffers_[padIndex] and voices_, both
    // of which onAudioReady reads/writes under bufferMutex_ every callback
    // buffer. Locking here (a) makes the `.loaded` check below race-free
    // against a concurrent loadPadBuffer(), and (b) serializes the
    // stop+claim sequence below against a concurrent triggerPad() call for
    // the same pad (two overlapping callers used to each pass the
    // stop-check and independently claim a voice, defeating "ONE SHOT
    // retrigger stops the previous hit"). Scoped to a block so the lock is
    // released before the delay-tap scheduling below, which takes
    // delayMutex_ — fireDelayTaps() (called from the audio thread) takes
    // delayMutex_ first and bufferMutex_ second, so never holding both here
    // at once avoids a lock-order inversion between the two threads.
    bool loaded;
    {
        std::lock_guard<std::mutex> lock(bufferMutex_);
        loaded = buffers_[padIndex].loaded;
        if (loaded) {
            // Stop any currently-playing voice for this pad first — ensures
            // retrigger always restarts from frame 0, never plays two
            // instances. Skipped when stopExisting=false (per-pad "MIX" play
            // mode), which lets repeated hits layer/overlap instead of
            // cutting the previous one off.
            //
            // BUG FIX: this used to kill the voice INSTANTLY (ready=false
            // right here), which cuts the waveform off wherever it happened
            // to be mid-sample — almost never at a zero-crossing —
            // producing an audible click/pop on every fast retrigger
            // ("khich khich awaaz" on repeated hits). Instead of an instant
            // kill, flag it `releasing`; the audio thread (onAudioReady)
            // ramps its gain down over a few ms and only then frees the
            // slot, so the cutoff is a fade, not a discontinuity.
            if (stopExisting) {
                for (auto &v : voices_) {
                    if (v.ready.load(std::memory_order_acquire) && v.padIndex == padIndex) {
                        v.releasing.store(true, std::memory_order_release);
                    }
                }
            }

            // Claim a free voice, fully initialize it, THEN publish it as
            // ready. Writing padIndex/position/volume/pitch happens-before
            // the release store to `ready`, so the audio thread (which
            // acquire-loads `ready`) never sees a half-written voice — this
            // is what previously caused stray/overlapping wrong-sample
            // glitches on fast multi-hits.
            Voice *claimed = nullptr;
            for (auto &v : voices_) {
                bool expected = false;
                if (v.active.compare_exchange_strong(expected, true)) {
                    claimed = &v;
                    break;
                }
            }

            // Pool exhausted (64 simultaneous voices already playing, easily
            // reached with MIX/MULTIPLAY layering across 3 banks) —
            // previously the hit was just silently dropped. Steal the
            // oldest currently-playing voice instead of losing the hit
            // entirely. Safe to do under bufferMutex_: setting `ready`
            // false first means onAudioReady (which only ever runs with
            // this same mutex held) can't be mid-read of this voice while
            // we overwrite it.
            if (!claimed) {
                uint64_t oldestSeq = UINT64_MAX;
                for (auto &v : voices_) {
                    if (!v.ready.load(std::memory_order_acquire)) continue;
                    uint64_t seq = v.claimSeq.load(std::memory_order_relaxed);
                    if (seq < oldestSeq) {
                        oldestSeq = seq;
                        claimed = &v;
                    }
                }
                if (claimed) {
                    claimed->ready.store(false, std::memory_order_release);
                }
            }

            if (claimed) {
                // A lone hit starting from silence gets NO fade — the
                // reverted 2026-08-18 attempt applied the fade
                // unconditionally and caused crackle on every single tap,
                // including this exact case where there was nothing to
                // protect against in the first place. Only arm the fade
                // (attackGain = 0.0f) when at least one OTHER voice is
                // already sounding — a genuinely concurrent/overlapping
                // hit, which is what "multi-hit crackle" actually meant.
                // `claimed` itself is still ready == false here (never
                // published yet), so this scan can't see itself.
                bool anotherVoiceActive = false;
                for (auto &other : voices_) {
                    if (other.ready.load(std::memory_order_acquire)) {
                        anotherVoiceActive = true;
                        break;
                    }
                }

                Voice &v = *claimed;
                v.padIndex = padIndex;
                // Non-destructive CROP start handle: begin playback
                // `startFraction` of the way into the sample instead of at
                // frame 0. Clamped to [0, 0.95] and kept strictly below the
                // end trim so there's always at least a sliver to play.
                float clampedStart = startFraction < 0.0f ? 0.0f : (startFraction > 0.95f ? 0.95f : startFraction);
                float clampedLen = lengthFraction < 0.05f ? 0.05f : (lengthFraction > 1.0f ? 1.0f : lengthFraction);
                if (clampedStart >= clampedLen) clampedStart = 0.0f;
                int64_t totalFrames = static_cast<int64_t>(buffers_[padIndex].samples.size() / 2);
                v.position = static_cast<double>(clampedStart) * static_cast<double>(totalFrames);
                v.startFraction.store(clampedStart, std::memory_order_relaxed);
                v.volume.store(volume, std::memory_order_relaxed);
                v.pitch.store(pitch, std::memory_order_relaxed);
                v.lengthFraction.store(clampedLen, std::memory_order_relaxed);
                v.pan.store(pan, std::memory_order_relaxed);
                v.gain.store(gain, std::memory_order_relaxed);
                v.releaseGain = 1.0f;
                v.attackGain = anotherVoiceActive ? 0.0f : 1.0f;
                v.releasing.store(false, std::memory_order_release);
                v.claimSeq.store(voiceClaimCounter_.fetch_add(1, std::memory_order_relaxed),
                                  std::memory_order_relaxed);
                v.active.store(true, std::memory_order_release);
                v.ready.store(true, std::memory_order_release);
            }
        }
    }
    if (!loaded) return;

    // Schedule delay taps if delay is on for this pad
    if (!delayEnabled_.load(std::memory_order_relaxed)) return;
    int chokePad = delayChokePad_.load(std::memory_order_relaxed);
    if (chokePad != -1 && chokePad != padIndex) return;  // delay only on assigned pad

    float decay   = delayDecayFactor_.load(std::memory_order_relaxed);
    int   maxTaps = delayMaxTaps_.load(std::memory_order_relaxed);

    // We don't know tapIntervalFrames here (it comes from Kotlin BPM).
    // Instead we store the tap interval as a fixed field set by setDelayParams.
    // Use delayTapIntervalFrames_ — set from Kotlin each time BPM changes.
    int64_t interval = delayTapIntervalFrames_.load(std::memory_order_relaxed);
    if (interval <= 0) return;

    std::lock_guard<std::mutex> lock(delayMutex_);
    float tapVol = volume * decay;
    for (int tap = 1; tap <= maxTaps && tapVol > 0.01f; tap++) {
        DelayTap dt;
        dt.padIndex      = padIndex;
        dt.volume        = tapVol;
        dt.pitch         = pitch;
        dt.pan           = pan;
        dt.gain          = gain;
        dt.triggerAtFrame = streamFrameCounter_ + interval * tap;
        delayTaps_.push_back(dt);
        tapVol *= decay;
    }
}

void AudioEngine::setPadVolume(int padIndex, float volume) {
    for (auto &v : voices_) {
        if (v.ready.load(std::memory_order_acquire) && v.padIndex == padIndex) {
            v.volume.store(volume, std::memory_order_relaxed);
        }
    }
}

void AudioEngine::setPadPitch(int padIndex, float pitch) {
    // Same zero-pitch guard as triggerPad() — a live pitch-knob drag can
    // reach exactly 0 without ever going through triggerPad, which would
    // otherwise freeze the affected voice's playback position forever (see
    // triggerPad's comment on why rate==0 leaks a voice permanently).
    if (pitch == 0.0f) {
        pitch = 1.0f;
    } else if (pitch > -0.01f && pitch < 0.01f) {
        pitch = pitch < 0.0f ? -0.01f : 0.01f;
    }
    for (auto &v : voices_) {
        if (v.ready.load(std::memory_order_acquire) && v.padIndex == padIndex) {
            v.pitch.store(pitch, std::memory_order_relaxed);
        }
    }
}

void AudioEngine::setPadPan(int padIndex, float pan) {
    for (auto &v : voices_) {
        if (v.ready.load(std::memory_order_acquire) && v.padIndex == padIndex) {
            v.pan.store(pan, std::memory_order_relaxed);
        }
    }
}

void AudioEngine::setPadGain(int padIndex, float gain) {
    for (auto &v : voices_) {
        if (v.ready.load(std::memory_order_acquire) && v.padIndex == padIndex) {
            v.gain.store(gain, std::memory_order_relaxed);
        }
    }
}

void AudioEngine::stopPad(int padIndex) {
    // Same fade-out-not-instant-kill treatment as triggerPad()'s
    // stopExisting path — stopPad is also used to silence a pad via choke
    // groups, which would otherwise click every time a choke fired.
    for (auto &v : voices_) {
        if (v.ready.load(std::memory_order_acquire) && v.padIndex == padIndex) {
            v.releasing.store(true, std::memory_order_release);
        }
    }
    // Also cancel any pending delay taps for this pad
    std::lock_guard<std::mutex> lock(delayMutex_);
    auto it = delayTaps_.begin();
    while (it != delayTaps_.end()) {
        if (it->padIndex == padIndex) it = delayTaps_.erase(it);
        else ++it;
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Delay control (called from Kotlin/JNI — not on audio thread)
// ─────────────────────────────────────────────────────────────────────────────

void AudioEngine::setDelayEnabled(bool enabled) {
    // BUG FIX: this used to clear delayTaps_ whenever `enabled` went false.
    // But Kotlin's syncDelayForHit() calls setDelayEnabled(padDelayEnabled)
    // synchronously before EVERY pad hit — so hitting any delay-off pad (or
    // even just selecting one, via the reactive LaunchedEffect) wiped the
    // still-pending echo of a delay-on pad that was hit a moment earlier.
    // That's exactly "delay lagne ke baad koi bhi pad hit karo to delay hat
    // jata hai". `delayEnabled_` already gates whether NEW taps get
    // scheduled (see triggerPad); already-queued taps must be allowed to
    // drain naturally. onAudioReady::fireDelayTaps() runs unconditionally
    // and is a cheap no-op once the queue empties.
    delayEnabled_.store(enabled, std::memory_order_relaxed);
    LOGD("Delay enabled=%d", (int)enabled);
}

void AudioEngine::setDelayParams(float decayFactor, int maxTaps) {
    delayDecayFactor_.store(decayFactor, std::memory_order_relaxed);
    delayMaxTaps_.store(maxTaps, std::memory_order_relaxed);
    LOGD("Delay params: decay=%.2f maxTaps=%d", decayFactor, maxTaps);
}

void AudioEngine::setDelayTapIntervalFrames(int64_t frames) {
    delayTapIntervalFrames_.store(frames, std::memory_order_relaxed);
    LOGD("Delay tap interval=%lld frames", (long long)frames);
}

void AudioEngine::setDelayChokePad(int padIndex) {
    delayChokePad_.store(padIndex, std::memory_order_relaxed);
    LOGD("Delay choke pad=%d", padIndex);
}

// ─────────────────────────────────────────────────────────────────────────────
// EQ + Level
// ─────────────────────────────────────────────────────────────────────────────

void AudioEngine::setMasterLevel(float level) {
    masterLevel_.store(level, std::memory_order_relaxed);
    LOGD("Master level=%.2f", level);
}

void AudioEngine::setEqBands(float low, float mid, float high) {
    eqLow_.store(low,   std::memory_order_relaxed);
    eqMid_.store(mid,   std::memory_order_relaxed);
    eqHigh_.store(high, std::memory_order_relaxed);
    LOGD("EQ low=%.2f mid=%.2f high=%.2f", low, mid, high);
}

// ─────────────────────────────────────────────────────────────────────────────
// Audio callback — called on dedicated real-time thread by Oboe
// ─────────────────────────────────────────────────────────────────────────────

/** Fire any delay taps whose trigger frame is within this buffer window. */
void AudioEngine::fireDelayTaps(int32_t numFrames) {
    std::lock_guard<std::mutex> lock(delayMutex_);
    int64_t windowEnd = streamFrameCounter_ + numFrames;

    auto it = delayTaps_.begin();
    while (it != delayTaps_.end()) {
        if (it->triggerAtFrame < windowEnd) {
            // Find a free voice and trigger. buffers_[pad].loaded is written
            // (under bufferMutex_) by loadPadBuffer() from a different
            // thread whenever a kit/pad reloads — reading it here without
            // the same lock was a data race. This never nests with
            // triggerPad()'s locking (which never holds bufferMutex_ and
            // delayMutex_ at once), so acquiring bufferMutex_ here too can't
            // deadlock against it.
            int pad = it->padIndex;
            std::lock_guard<std::mutex> bufLock(bufferMutex_);
            if (pad >= 0 && pad < kMaxPads && buffers_[pad].loaded) {
                // Same "only fade if something else is already sounding"
                // rule as triggerPad's claim — see the note on Voice::
                // attackGain for why an unconditional fade regressed into
                // crackle on every hit. An echo playing into pure silence
                // (the original already finished by the time this tap
                // fires) gets no fade either.
                bool anotherVoiceActive = false;
                for (auto &other : voices_) {
                    if (other.ready.load(std::memory_order_acquire)) {
                        anotherVoiceActive = true;
                        break;
                    }
                }

                for (auto &v : voices_) {
                    bool expected = false;
                    if (v.active.compare_exchange_strong(expected, true)) {
                        v.padIndex = pad;
                        v.position = 0.0;
                        v.volume.store(it->volume, std::memory_order_relaxed);
                        v.pitch.store(it->pitch, std::memory_order_relaxed);
                        // BUG FIX: an echo voice used to inherit whatever
                        // lengthFraction/pan/gain the slot last held from a
                        // previous, unrelated pad hit — echoes could be
                        // silently truncated or mis-panned depending on prior
                        // voice usage. A delay tap always plays the full
                        // sample at that pad's current pan/gain, not a
                        // leftover LENGTH trim from someone else's voice.
                        v.lengthFraction.store(1.0f, std::memory_order_relaxed);
                        v.pan.store(it->pan, std::memory_order_relaxed);
                        v.gain.store(it->gain, std::memory_order_relaxed);
                        v.releaseGain = 1.0f;
                        v.attackGain = anotherVoiceActive ? 0.0f : 1.0f;
                        v.releasing.store(false, std::memory_order_release);
                        v.claimSeq.store(voiceClaimCounter_.fetch_add(1, std::memory_order_relaxed),
                                          std::memory_order_relaxed);
                        v.ready.store(true, std::memory_order_release);
                        break;
                    }
                }
            }
            it = delayTaps_.erase(it);
        } else {
            ++it;
        }
    }
}

oboe::DataCallbackResult AudioEngine::onAudioReady(
        oboe::AudioStream *stream, void *audioData, int32_t numFrames) {

    auto *out = static_cast<float*>(audioData);
    std::fill(out, out + numFrames * 2, 0.0f);

    // Fire any delay taps that fall in this buffer window.
    //
    // BUG FIX: this used to be gated on delayEnabled_ — but delayEnabled_
    // is a single GLOBAL flag, re-pushed synchronously by Kotlin's
    // syncDelayForHit() right before EVERY pad's trigger, including a pad
    // that has no delay of its own. So: hit pad A (delay on) — taps get
    // scheduled into delayTaps_ with A's decay/volume already baked in
    // (see triggerPad) — then, before those taps are due, hit pad B (delay
    // off) "at the same time" — syncDelayForHit(B) flips delayEnabled_ to
    // false, and this gate then skipped fireDelayTaps() entirely, so A's
    // already-scheduled echoes silently never fired at all. This is
    // exactly "single hit mein delay chalta hai, multiple/simultaneous hit
    // mein delay hat jata hai". delayEnabled_ only needs to gate whether
    // NEW taps get SCHEDULED (still checked in triggerPad) — firing taps
    // that are already queued must never depend on whatever pad happened
    // to be hit most recently. fireDelayTaps() itself is cheap when the
    // queue is empty (one mutex lock, no-op iteration), so calling it
    // unconditionally every buffer costs nothing extra when delay isn't in
    // use.
    fireDelayTaps(numFrames);

    // Retrigger fade-out (~5ms) / concurrent-claim fade-in (~3ms) step
    // sizes — both depend only on outputSampleRate_, which is fixed for
    // this whole callback, so hoisted out of the per-voice loop below
    // instead of recomputed (a float division) once per active voice per
    // buffer.
    const float releaseStep = 1.0f / (static_cast<float>(outputSampleRate_) * 0.005f);
    const float attackStep  = 1.0f / (static_cast<float>(outputSampleRate_) * 0.003f);

    {
        std::lock_guard<std::mutex> lock(bufferMutex_); // brief; loads are rare

        for (auto &v : voices_) {
            // Gate on `ready`, not `active` — `active` only means the slot is
            // claimed, `ready` means padIndex/position/volume/pitch are fully
            // and safely published for this thread to read.
            if (!v.ready.load(std::memory_order_acquire)) continue;

            const PadBuffer &buf = buffers_[v.padIndex];
            if (!buf.loaded || buf.samples.empty()) {
                v.ready.store(false, std::memory_order_release);
                v.active.store(false, std::memory_order_release);
                continue;
            }

            float vol   = v.volume.load(std::memory_order_relaxed);
            float pitch = v.pitch.load(std::memory_order_relaxed);
            float gain  = v.gain.load(std::memory_order_relaxed);
            float pan   = v.pan.load(std::memory_order_relaxed);
            if (pan < -1.0f) pan = -1.0f; else if (pan > 1.0f) pan = 1.0f;
            // Equal-power pan law: center (pan=0) puts both channels at
            // ~0.707 rather than 1.0, so panning doesn't audibly boost or
            // dip perceived loudness as a hit sweeps across the stereo field.
            const float kQuarterPi = 0.78539816f; // PI/4
            float angle = (pan + 1.0f) * kQuarterPi;
            float panL = cosf(angle);
            float panR = sinf(angle);
            float effVol = vol * gain;

            double rate = pitch * (static_cast<double>(buf.sampleRate) / outputSampleRate_);
            int64_t totalFrames = static_cast<int64_t>(buf.samples.size() / 2);
            float lenFrac = v.lengthFraction.load(std::memory_order_relaxed);
            int64_t playableFrames = static_cast<int64_t>(totalFrames * lenFrac);
            if (playableFrames < 1) playableFrames = 1;

            // Retrigger fade-out: ~5ms linear ramp to silence instead of an
            // instant cut, so a fast retrigger never produces a click/pop.
            // Fade-in (attackGain): only armed (< 1.0) by triggerPad/
            // fireDelayTaps when this voice was claimed while at least one
            // OTHER voice was already sounding — a lone hit into silence
            // sets attackGain straight to 1.0f, so this branch is simply
            // never entered for that case (no cost, no behavior change from
            // before any of this existed).
            bool isReleasing = v.releasing.load(std::memory_order_relaxed);

            for (int32_t i = 0; i < numFrames; i++) {
                int64_t idx = static_cast<int64_t>(v.position);
                if (idx >= playableFrames - 1 || idx >= totalFrames - 1) {
                    v.ready.store(false, std::memory_order_release);
                    v.active.store(false, std::memory_order_release);
                    break;
                }

                float frameGain = 1.0f;
                if (isReleasing) {
                    v.releaseGain -= releaseStep;
                    if (v.releaseGain <= 0.0f) {
                        v.ready.store(false, std::memory_order_release);
                        v.active.store(false, std::memory_order_release);
                        v.releasing.store(false, std::memory_order_release);
                        break;
                    }
                    frameGain = v.releaseGain;
                }
                if (v.attackGain < 1.0f) {
                    v.attackGain += attackStep;
                    if (v.attackGain > 1.0f) v.attackGain = 1.0f;
                    frameGain *= v.attackGain;
                }

                double frac = v.position - idx;
                float l0 = buf.samples[idx * 2],     r0 = buf.samples[idx * 2 + 1];
                float l1 = buf.samples[(idx+1) * 2], r1 = buf.samples[(idx+1) * 2 + 1];
                float l = l0 + (l1 - l0) * frac;
                float r = r0 + (r1 - r0) * frac;

                out[i * 2]     += l * effVol * panL * frameGain;
                out[i * 2 + 1] += r * effVol * panR * frameGain;

                v.position += rate;
            }
        }
    }

    // BUG FIX: voices used to be summed with no gain compensation for how
    // many were playing at once, then hard-clamped to [-1,1] — a real
    // discontinuous clip despite the old "soft clip" comment. Stacking a
    // handful of similarly-loud voices pushed the sum well past unity and
    // the hard clamp produced audible harsh distortion, which is what read
    // as "tone quality kharab ho jata hai multiple hit me". Fix is two
    // parts: (1) scale the mix down as polyphony grows so a single voice is
    // untouched but 8+ simultaneous voices don't stack into clipping range,
    // (2) replace the hard clamp with an actual soft-knee curve (tanh) so
    // any residual overshoot rolls off smoothly instead of brick-walling.
    //
    // BUG FIX 3: the headroom scale used to be derived purely from
    // activeVoiceCount (1/sqrt(N)) — that ducks the ENTIRE mix bus the
    // instant a second pad is hit, regardless of whether the two voices
    // were anywhere near clipping in the first place. Two moderately-loud
    // pads hit back-to-back doesn't need any gain reduction at all, but the
    // old formula ducked every time regardless — this is exactly what read
    // as "pehla hit ka volume dab jata hai jaise hi dusra hit hota hai" /
    // "aawaz thoda kam ho jata hai multiple play mein": the first voice's
    // gain was being pulled down because of the SECOND voice starting, not
    // because of anything about the first voice's own loudness.
    //
    // Fixed by measuring the actual peak of this buffer's mixed samples and
    // only reducing gain when that peak would genuinely exceed unity — a
    // real (if simple, look-behind-only) limiter instead of a preemptive,
    // polyphony-count-based duck. Two voices that don't sum past 1.0 never
    // trigger any gain reduction at all, so ordinary multi-pad playing is
    // untouched; the smoothed asymmetric attack/release (fast down, slow
    // up) is kept so genuinely loud stacking still gets caught immediately
    // and recovers inaudibly rather than snapping, same as before.
    float peak = 0.0f;
    for (int32_t i = 0; i < numFrames * 2; i++) {
        float a = fabsf(out[i]);
        if (a > peak) peak = a;
    }
    float targetHeadroom = (peak > 1.0f) ? (1.0f / peak) : 1.0f;
    const float kAttackTimeSec  = 0.005f;  // 5ms  — protect against clipping fast
    const float kReleaseTimeSec = 0.20f;   // 200ms — recover gain slowly/inaudibly
    float bufferDurationSec = static_cast<float>(numFrames) / static_cast<float>(outputSampleRate_);
    float timeConstant = (targetHeadroom < smoothedHeadroom_) ? kAttackTimeSec : kReleaseTimeSec;
    float smoothingCoeff = 1.0f - expf(-bufferDurationSec / timeConstant);
    smoothedHeadroom_ += (targetHeadroom - smoothedHeadroom_) * smoothingCoeff;

    for (int32_t i = 0; i < numFrames * 2; i++) {
        out[i] = tanhf(out[i] * smoothedHeadroom_);
    }

    // ── 3-band EQ + master level ──────────────────────────────────────────
    // Simple single-pole IIR implementation.
    // Low-pass  coefficient at ~300 Hz  → α_lp
    // High-pass coefficient at ~4000 Hz → α_hp
    // Mid = original - low - high (complementary)
    const float sr   = static_cast<float>(outputSampleRate_);
    const float pi   = 3.14159265f;
    const float fc_lo = 300.0f;
    const float fc_hi = 4000.0f;
    const float alpha_lp = 1.0f - expf(-2.0f * pi * fc_lo / sr);
    const float alpha_hp = 1.0f - expf(-2.0f * pi * fc_hi / sr);

    float lvl  = masterLevel_.load(std::memory_order_relaxed);
    float gLow = eqLow_.load(std::memory_order_relaxed);
    float gMid = eqMid_.load(std::memory_order_relaxed);
    float gHi  = eqHigh_.load(std::memory_order_relaxed);

    for (int32_t i = 0; i < numFrames; i++) {
        for (int ch = 0; ch < 2; ch++) {
            float x = out[i * 2 + ch];

            // Low-pass (low shelf)
            lpState_[ch]  += alpha_lp * (x - lpState_[ch]);
            float low      = lpState_[ch];

            // Low-pass at high cutoff (used to separate mid from high)
            bp1State_[ch] += alpha_hp * (x - bp1State_[ch]);
            float high     = x - bp1State_[ch];
            float mid      = x - low - high;

            float y = low * gLow + mid * gMid + high * gHi;
            // EQ band gains (up to 2.0x each) can push an already
            // near-unity signal back over the top — soft-knee here too
            // rather than a hard clamp, for the same reason as above.
            y = tanhf(y);
            out[i * 2 + ch] = y * lvl;
        }
    }

    streamFrameCounter_ += numFrames;
    return oboe::DataCallbackResult::Continue;
}

// ─────────────────────────────────────────────────────────────────────────────
// Error handling
// ─────────────────────────────────────────────────────────────────────────────

void AudioEngine::restartStream() {
    LOGD("Restarting audio stream...");
    stop();
    if (!start()) {
        LOGE("Failed to restart audio stream");
    }
}

void AudioEngine::onErrorBeforeClose(
        oboe::AudioStream *stream,
        oboe::Result error) {
    LOGE("Audio error before close: %s", oboe::convertToText(error));
}

void AudioEngine::onErrorAfterClose(
        oboe::AudioStream *stream,
        oboe::Result error) {
    LOGE("Audio error after close: %s", oboe::convertToText(error));
    restartStream();
}
