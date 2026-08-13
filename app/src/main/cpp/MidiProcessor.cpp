// MidiProcessor.cpp
#include "MidiProcessor.h"
#include <android/log.h>

#define TAG "MIDI_CPP"
#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, TAG, __VA_ARGS__)

extern void sendControlChangeToKotlin(int ccNumber, int ccValue);

// GM-style drum default note->pad map, same defaults this app has always
// shipped with, so existing users' muscle memory for unmapped controllers
// doesn't change.
MidiProcessor::MidiProcessor()
{
    padToNote[0] = 36; // Kick
    padToNote[1] = 48; // Hi-Tom
    padToNote[2] = 51; // Ride
    padToNote[3] = 49; // Crash
    padToNote[4] = 45; // Low Tom
    padToNote[5] = 46; // Open Hi-Hat
    padToNote[6] = 42; // Closed Hi-Hat
    padToNote[7] = 38; // Snare
}

void MidiProcessor::processMessage(
        int channel,
        int note,
        int velocity)
{
    if (velocity > 0)
    {
        noteOn(
                channel,
                note,
                velocity);
    }
    else
    {
        noteOff(
                channel,
                note);
    }
}

void MidiProcessor::noteOn(
        int channel,
        int note,
        int velocity)
{
    LOGD("Note-On CH=%d NOTE=%d VEL=%d", channel, note, velocity);

    if (midiLearnMode && learningPad >= 0)
    {
        assignMidiNote(learningPad, note);
        if (onLearnAssigned)
        {
            onLearnAssigned(learningPad, note);
        }
        midiLearnMode = false;
        learningPad = -1;
        return;
    }

    int pad = getPadFromNote(note);
    if (pad >= 0 && onPadHit)
    {
        float velocityFraction = static_cast<float>(velocity) / 127.0f;
        onPadHit(pad, velocityFraction);
    }
}

void MidiProcessor::noteOff(int channel, int note)
{
    LOGD("Note-Off CH=%d NOTE=%d", channel, note);
    // No note-off pad-stop behavior — pads are one-shot/loop/mix triggered
    // on Note-On only, consistent with how touch/CC triggering already works.
}

// ── Control Change (knobs / sliders / CC-mapped pads) ───────────────────────
void MidiProcessor::controlChange(
        int channel,
        int ccNumber,
        int ccValue)
{
    LOGD(
            "CC CH=%d NUMBER=%d VALUE=%d",
            channel,
            ccNumber,
            ccValue
    );

    if (onControlChange)
    {
        onControlChange(
                ccNumber,
                ccValue);
    }

    sendControlChangeToKotlin(
            ccNumber,
            ccValue);
}

// ── Program Change — direct patch/kit select ────────────────────────────────
void MidiProcessor::programChange(int channel, int program)
{
    LOGD("Program Change CH=%d PROGRAM=%d", channel, program);
    if (onProgramChange)
    {
        onProgramChange(program);
    }
}

// ── MIDI Learn (Note) ────────────────────────────────────────────────────────
void MidiProcessor::enableMidiLearn(int padNumber)
{
    midiLearnMode = true;
    learningPad = padNumber;
}

void MidiProcessor::assignMidiNote(int padNumber, int note)
{
    if (padNumber < 0 || padNumber > 7) return;
    padToNote[padNumber] = note;
}

int MidiProcessor::getPadFromNote(int note) const
{
    for (int pad = 0; pad < 8; pad++)
    {
        auto it = padToNote.find(pad);
        if (it != padToNote.end() && it->second == note)
        {
            return pad;
        }
    }
    return -1;
}

int MidiProcessor::getMappedNoteForPad(int padNumber) const
{
    auto it = padToNote.find(padNumber);
    return it != padToNote.end() ? it->second : -1;
}

std::vector<int> MidiProcessor::getAllPadsForNote(int note) const
{
    std::vector<int> pads;
    for (int pad = 0; pad < 8; pad++)
    {
        auto it = padToNote.find(pad);
        if (it != padToNote.end() && it->second == note)
        {
            pads.push_back(pad);
        }
    }
    return pads;
}
