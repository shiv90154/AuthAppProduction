// MidiProcessor.h
#pragma once

#include <functional>
#include <unordered_map>
#include <vector>

// Note-based pad mapping is back (client's real hardware sends Note-On for
// pad hits), coexisting with CC-based mapping (CcMapRepository's PAD_1..8
// targets, handled Kotlin-side in OctapadScreen) — a controller can trigger
// a pad via either Note-On or CC, whichever it actually sends. `padToNote`
// is the single source of truth (pad -> note); note->pad and
// note->all-pads-sharing-it are derived from it on demand by a short linear
// scan (only 8 pads, so this is cheap) rather than kept as a second index —
// that avoids the class of stale-reverse-mapping bugs a two-map design had
// previously when a note was reassigned or shared across pads.
class MidiProcessor
{
public:

    MidiProcessor();

    void processMessage(int channel, int note, int velocity);

    void noteOn(int channel, int note, int velocity);
    void noteOff(int channel, int note);

    // Control Change (knobs/sliders/CC-mapped pads)
    void controlChange(int channel, int ccNumber, int ccValue);

    // Program Change — direct patch/kit select (0-based `program`, 0-63),
    // distinct from the CC-based Next/Prev patch nav which already exists
    // and is unaffected by this.
    void programChange(int channel, int program);

    // MIDI Learn (Note): call enableMidiLearn(pad), the next Note-On
    // received assigns that note to that pad instead of triggering it.
    void enableMidiLearn(int padNumber);
    void assignMidiNote(int padNumber, int note);
    int  getPadFromNote(int note) const;          // -1 if no pad uses this note
    int  getMappedNoteForPad(int padNumber) const; // -1 if pad out of range
    std::vector<int> getAllPadsForNote(int note) const;

    // Callbacks
    std::function<void(int, int)>   onControlChange;   // (ccNumber, ccValue)
    std::function<void(int, float)> onPadHit;           // (padIndex 0-7, velocity 0..1)
    std::function<void(int)>        onProgramChange;    // (kit index, 0-based)
    std::function<void(int, int)>   onLearnAssigned;     // (padNumber, note) — MIDI Learn UI feedback

private:
    std::unordered_map<int, int> padToNote; // pad (0-7) -> note
    bool midiLearnMode = false;
    int  learningPad = -1;
};
