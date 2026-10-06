#pragma once

#include <vector>
#include <cmath>
#include <algorithm>

// Small stereo Schroeder/Freeverb-style reverb (8 parallel damped comb filters
// + 4 series all-pass filters per channel). One instance == one "room" voicing
// (ROOM 1 / ROOM 2 / HALL differ only in delay-line scale and damping, which
// is what changes the perceived room size). The engine keeps one instance per
// room type and feeds each from the voices that selected that type.
//
// Not thread-safe by itself: init()/clear() run on the thread that opens the
// stream (before/while stopped) or on the audio thread; process() runs only on
// the audio thread.
class Reverb {
public:
    // sizeScale stretches every delay line (bigger = bigger room); damp is the
    // high-frequency absorption of the comb feedback path (0..1).
    void init(int sampleRate, float sizeScale, float damp) {
        static const int kCombTuning[kNumCombs]    = {1116, 1188, 1277, 1356, 1422, 1491, 1557, 1617};
        static const int kAllpassTuning[kNumAllpass] = {556, 441, 341, 225};
        static const int kStereoSpread = 23;
        const float rateScale = static_cast<float>(sampleRate) / 44100.0f;
        for (int i = 0; i < kNumCombs; i++) {
            int lenL = std::max(8, static_cast<int>(kCombTuning[i] * rateScale * sizeScale));
            int lenR = std::max(8, static_cast<int>((kCombTuning[i] + kStereoSpread) * rateScale * sizeScale));
            combL_[i].assign(lenL);
            combR_[i].assign(lenR);
        }
        for (int i = 0; i < kNumAllpass; i++) {
            int lenL = std::max(4, static_cast<int>(kAllpassTuning[i] * rateScale));
            int lenR = std::max(4, static_cast<int>((kAllpassTuning[i] + kStereoSpread) * rateScale));
            apL_[i].assign(lenL);
            apR_[i].assign(lenR);
        }
        damp1_ = damp;
        damp2_ = 1.0f - damp;
    }

    void clear() {
        for (int i = 0; i < kNumCombs; i++) { combL_[i].clear(); combR_[i].clear(); }
        for (int i = 0; i < kNumAllpass; i++) { apL_[i].clear(); apR_[i].clear(); }
    }

    // `in` / `out` are interleaved stereo. Adds the wet signal onto `out`.
    // feedback: comb feedback 0..~0.97 — larger = longer tail.
    void process(const float* in, float* out, int numFrames, float feedback, float wet) {
        for (int n = 0; n < numFrames; n++) {
            const float inL = in[n * 2];
            const float inR = in[n * 2 + 1];
            const float input = (inL + inR) * kInputGain;

            float sumL = 0.0f, sumR = 0.0f;
            for (int i = 0; i < kNumCombs; i++) {
                sumL += combL_[i].tick(input, feedback, damp1_, damp2_);
                sumR += combR_[i].tick(input, feedback, damp1_, damp2_);
            }
            for (int i = 0; i < kNumAllpass; i++) {
                sumL = apL_[i].tick(sumL);
                sumR = apR_[i].tick(sumR);
            }
            out[n * 2]     += sumL * wet;
            out[n * 2 + 1] += sumR * wet;
        }
    }

private:
    static constexpr int   kNumCombs   = 8;
    static constexpr int   kNumAllpass = 4;
    static constexpr float kInputGain  = 0.015f;

    struct Comb {
        std::vector<float> buf;
        int   idx   = 0;
        float store = 0.0f;
        void assign(int len) { buf.assign(static_cast<size_t>(len), 0.0f); idx = 0; store = 0.0f; }
        void clear() { std::fill(buf.begin(), buf.end(), 0.0f); idx = 0; store = 0.0f; }
        inline float tick(float input, float feedback, float damp1, float damp2) {
            float output = buf[idx];
            store = output * damp2 + store * damp1;
            // Flush denormals — this runs every sample for the whole tail.
            if (std::fabs(store) < 1e-15f) store = 0.0f;
            buf[idx] = input + store * feedback;
            if (++idx >= static_cast<int>(buf.size())) idx = 0;
            return output;
        }
    };

    struct Allpass {
        std::vector<float> buf;
        int idx = 0;
        void assign(int len) { buf.assign(static_cast<size_t>(len), 0.0f); idx = 0; }
        void clear() { std::fill(buf.begin(), buf.end(), 0.0f); idx = 0; }
        inline float tick(float input) {
            float bufOut = buf[idx];
            float output = -input + bufOut;
            buf[idx] = input + bufOut * 0.5f;
            if (++idx >= static_cast<int>(buf.size())) idx = 0;
            return output;
        }
    };

    Comb    combL_[kNumCombs], combR_[kNumCombs];
    Allpass apL_[kNumAllpass], apR_[kNumAllpass];
    float   damp1_ = 0.4f;
    float   damp2_ = 0.6f;
};
