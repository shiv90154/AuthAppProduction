// native-lib.cpp
#include <jni.h>
#include "MidiProcessor.h"
#include "AudioEngine.h"
#include <vector>
#include <string>

// ── Globals ──────────────────────────────────────────────────────────────────
jclass    g_bridgeClass         = nullptr;
jmethodID g_ccCallback          = nullptr;   // onControlChangeFromNative(II)V
jmethodID g_padHitCallback      = nullptr;   // onPadHitFromNative(IF)V
jmethodID g_learnAssignedCallback = nullptr; // onMidiLearnAssigned(II)V
jmethodID g_programChangeCallback = nullptr; // onProgramChangeFromNative(I)V
jmethodID g_rawNoteCallback       = nullptr; // onRawNoteOnFromNative(II)V

static MidiProcessor midiProcessor;
static AudioEngine   audioEngine;

JavaVM* g_vm = nullptr;

// ── MIDI JNI ─────────────────────────────────────────────────────────────────

extern "C"
JNIEXPORT void JNICALL
Java_com_example_myapplication_NativeBridge_sendMidiMessage(
        JNIEnv *env, jobject thiz, jint channel, jint note, jint velocity)
{
    midiProcessor.processMessage(channel, note, velocity);
}

extern "C"
JNIEXPORT void JNICALL
Java_com_example_myapplication_NativeBridge_sendControlChange(
        JNIEnv *env, jobject thiz, jint channel, jint ccNumber, jint ccValue)
{
    midiProcessor.controlChange(channel, ccNumber, ccValue);
}

extern "C"
JNIEXPORT void JNICALL
Java_com_example_myapplication_NativeBridge_sendProgramChange(
        JNIEnv *env, jobject thiz, jint channel, jint program)
{
    midiProcessor.programChange(channel, program);
}

extern "C"
JNIEXPORT void JNICALL
Java_com_example_myapplication_NativeBridge_enableMidiLearn(
        JNIEnv *env, jobject thiz, jint padNumber)
{
    midiProcessor.enableMidiLearn(padNumber);
}

extern "C"
JNIEXPORT void JNICALL
Java_com_example_myapplication_NativeBridge_assignMidiNote(
        JNIEnv *env, jobject thiz, jint padNumber, jint note)
{
    midiProcessor.assignMidiNote(padNumber, note);
}

extern "C"
JNIEXPORT jint JNICALL
Java_com_example_myapplication_NativeBridge_getMappedNoteForPad(
        JNIEnv *env, jobject thiz, jint padNumber)
{
    return (jint)midiProcessor.getMappedNoteForPad(padNumber);
}

extern "C"
JNIEXPORT jintArray JNICALL
Java_com_example_myapplication_NativeBridge_getAllPadsForNote(
        JNIEnv *env, jobject thiz, jint note)
{
    std::vector<int> pads = midiProcessor.getAllPadsForNote(note);
    jintArray result = env->NewIntArray((jsize)pads.size());
    if (!pads.empty())
    {
        env->SetIntArrayRegion(result, 0, (jsize)pads.size(), pads.data());
    }
    return result;
}

// ── Audio Engine JNI ──────────────────────────────────────────────────────────

extern "C"
JNIEXPORT jboolean JNICALL
Java_com_example_myapplication_NativeBridge_engineStart(JNIEnv *env, jobject thiz)
{
    return audioEngine.start() ? JNI_TRUE : JNI_FALSE;
}

extern "C"
JNIEXPORT void JNICALL
Java_com_example_myapplication_NativeBridge_engineStop(JNIEnv *env, jobject thiz)
{
    audioEngine.stop();
}

extern "C"
JNIEXPORT void JNICALL
Java_com_example_myapplication_NativeBridge_loadPadAudio(
        JNIEnv *env, jobject thiz, jint padIndex, jshortArray pcm,
        jint channels, jint sampleRate)
{
    // channels/sampleRate ultimately come from decoding a user-supplied audio
    // file (see DrumEngine.kt / PcmDecoder) — a malformed/corrupt import could
    // in principle report channels <= 0. Guard against that here since
    // `len / channels` below would otherwise be an integer division by zero.
    if (channels <= 0) return;
    jsize len = env->GetArrayLength(pcm);
    std::vector<int16_t> buffer(len);
    env->GetShortArrayRegion(pcm, 0, len, buffer.data());
    int32_t numFrames = len / channels;
    audioEngine.loadPadBuffer(padIndex, buffer.data(), numFrames, channels, sampleRate);
}

extern "C"
JNIEXPORT void JNICALL
Java_com_example_myapplication_NativeBridge_triggerPad(
        JNIEnv *env, jobject thiz, jint padIndex, jfloat volume, jfloat pitch,
        jboolean stopExisting, jfloat lengthFraction, jfloat pan, jfloat gain)
{
    audioEngine.triggerPad(padIndex, volume, pitch, stopExisting == JNI_TRUE, lengthFraction, pan, gain);
}

extern "C"
JNIEXPORT void JNICALL
Java_com_example_myapplication_NativeBridge_setPadVolumeNative(
        JNIEnv *env, jobject thiz, jint padIndex, jfloat volume)
{
    audioEngine.setPadVolume(padIndex, volume);
}

extern "C"
JNIEXPORT void JNICALL
Java_com_example_myapplication_NativeBridge_setPadPitchNative(
        JNIEnv *env, jobject thiz, jint padIndex, jfloat pitch)
{
    audioEngine.setPadPitch(padIndex, pitch);
}

extern "C"
JNIEXPORT void JNICALL
Java_com_example_myapplication_NativeBridge_setPadPanNative(
        JNIEnv *env, jobject thiz, jint padIndex, jfloat pan)
{
    audioEngine.setPadPan(padIndex, pan);
}

extern "C"
JNIEXPORT void JNICALL
Java_com_example_myapplication_NativeBridge_setPadGainNative(
        JNIEnv *env, jobject thiz, jint padIndex, jfloat gain)
{
    audioEngine.setPadGain(padIndex, gain);
}

extern "C"
JNIEXPORT void JNICALL
Java_com_example_myapplication_NativeBridge_stopPadNative(
        JNIEnv *env, jobject thiz, jint padIndex)
{
    audioEngine.stopPad(padIndex);
}

extern "C"
JNIEXPORT jint JNICALL
Java_com_example_myapplication_NativeBridge_getSampleRateNative(
        JNIEnv *env, jobject thiz)
{
    return (jint)audioEngine.getSampleRate();
}

// ── Delay JNI ─────────────────────────────────────────────────────────────────

extern "C"
JNIEXPORT void JNICALL
Java_com_example_myapplication_NativeBridge_setDelayEnabled(
        JNIEnv *env, jobject thiz, jboolean enabled)
{
    audioEngine.setDelayEnabled(enabled == JNI_TRUE);
}

extern "C"
JNIEXPORT void JNICALL
Java_com_example_myapplication_NativeBridge_setDelayParams(
        JNIEnv *env, jobject thiz, jfloat decayFactor, jint maxTaps)
{
    audioEngine.setDelayParams(decayFactor, maxTaps);
}

extern "C"
JNIEXPORT void JNICALL
Java_com_example_myapplication_NativeBridge_setDelayTapIntervalFrames(
        JNIEnv *env, jobject thiz, jlong frames)
{
    audioEngine.setDelayTapIntervalFrames(frames);
}

extern "C"
JNIEXPORT void JNICALL
Java_com_example_myapplication_NativeBridge_setDelayChokePad(
        JNIEnv *env, jobject thiz, jint padIndex)
{
    audioEngine.setDelayChokePad(padIndex);
}

// ── EQ + Level JNI ───────────────────────────────────────────────────────────

extern "C"
JNIEXPORT void JNICALL
Java_com_example_myapplication_NativeBridge_setMasterLevel(
        JNIEnv *env, jobject thiz, jfloat level)
{
    audioEngine.setMasterLevel(level);
}

extern "C"
JNIEXPORT void JNICALL
Java_com_example_myapplication_NativeBridge_setEqBands(
        JNIEnv *env, jobject thiz, jfloat low, jfloat mid, jfloat high)
{
    audioEngine.setEqBands(low, mid, high);
}

// ── C++ → Kotlin callbacks ────────────────────────────────────────────────────

void sendPadHitToKotlin(int padIndex, float velocity);
void sendLearnAssignedToKotlin(int padNumber, int note);
void sendProgramChangeToKotlin(int program);
void sendRawNoteOnToKotlin(int note, int velocity);

jint JNI_OnLoad(JavaVM* vm, void* reserved)
{
    g_vm = vm;
    JNIEnv* env = nullptr;
    vm->GetEnv((void**)&env, JNI_VERSION_1_6);

    jclass localClass = env->FindClass("com/example/myapplication/NativeBridge");
    g_bridgeClass = (jclass)env->NewGlobalRef(localClass);

    g_ccCallback            = env->GetStaticMethodID(g_bridgeClass, "onControlChangeFromNative", "(II)V");
    g_padHitCallback        = env->GetStaticMethodID(g_bridgeClass, "onPadHitFromNative", "(IF)V");
    g_learnAssignedCallback = env->GetStaticMethodID(g_bridgeClass, "onMidiLearnAssigned", "(II)V");
    g_programChangeCallback = env->GetStaticMethodID(g_bridgeClass, "onProgramChangeFromNative", "(I)V");
    g_rawNoteCallback       = env->GetStaticMethodID(g_bridgeClass, "onRawNoteOnFromNative", "(II)V");

    midiProcessor.onPadHit        = sendPadHitToKotlin;
    midiProcessor.onLearnAssigned = sendLearnAssignedToKotlin;
    midiProcessor.onProgramChange = sendProgramChangeToKotlin;
    midiProcessor.onRawNoteOn     = sendRawNoteOnToKotlin;

    return JNI_VERSION_1_6;
}

// BUG FIX: every sendXToKotlin() below used to call AttachCurrentThread()
// unconditionally on every invocation and never DetachCurrentThread() —
// these run on the MIDI-receive thread (an Android binder/thread-pool
// thread, not the audio callback thread), so repeatedly attaching without
// ever detaching is a real JNI leak: if that native thread is ever
// recycled/destroyed by the framework's thread pool while still attached,
// ART logs (and on some configurations can abort on) "native thread exited
// without calling DetachCurrentThread". GetEnvForCurrentThread() only
// attaches if the calling thread isn't already attached (checked via
// GetEnv()), and reports that back so the caller only detaches a thread it
// itself attached — never a thread that was already attached before this
// call (e.g. one the JVM/framework manages the lifecycle of itself).
static JNIEnv* GetEnvForCurrentThread(bool* didAttach)
{
    *didAttach = false;
    if (!g_vm) return nullptr;
    JNIEnv* env = nullptr;
    jint status = g_vm->GetEnv((void**)&env, JNI_VERSION_1_6);
    if (status == JNI_OK) return env;
    if (status == JNI_EDETACHED) {
        if (g_vm->AttachCurrentThread(&env, nullptr) != JNI_OK) return nullptr;
        *didAttach = true;
        return env;
    }
    return nullptr; // JNI_EVERSION or other failure
}

void sendControlChangeToKotlin(int ccNumber, int ccValue)
{
    if (!g_vm || !g_bridgeClass || !g_ccCallback) return;
    bool didAttach = false;
    JNIEnv* env = GetEnvForCurrentThread(&didAttach);
    if (!env) return;
    env->CallStaticVoidMethod(g_bridgeClass, g_ccCallback, (jint)ccNumber, (jint)ccValue);
    if (didAttach) g_vm->DetachCurrentThread();
}

void sendPadHitToKotlin(int padIndex, float velocity)
{
    if (!g_vm || !g_bridgeClass || !g_padHitCallback) return;
    bool didAttach = false;
    JNIEnv* env = GetEnvForCurrentThread(&didAttach);
    if (!env) return;
    env->CallStaticVoidMethod(g_bridgeClass, g_padHitCallback, (jint)padIndex, (jfloat)velocity);
    if (didAttach) g_vm->DetachCurrentThread();
}

void sendLearnAssignedToKotlin(int padNumber, int note)
{
    if (!g_vm || !g_bridgeClass || !g_learnAssignedCallback) return;
    bool didAttach = false;
    JNIEnv* env = GetEnvForCurrentThread(&didAttach);
    if (!env) return;
    env->CallStaticVoidMethod(g_bridgeClass, g_learnAssignedCallback, (jint)padNumber, (jint)note);
    if (didAttach) g_vm->DetachCurrentThread();
}

void sendProgramChangeToKotlin(int program)
{
    if (!g_vm || !g_bridgeClass || !g_programChangeCallback) return;
    bool didAttach = false;
    JNIEnv* env = GetEnvForCurrentThread(&didAttach);
    if (!env) return;
    env->CallStaticVoidMethod(g_bridgeClass, g_programChangeCallback, (jint)program);
    if (didAttach) g_vm->DetachCurrentThread();
}

void sendRawNoteOnToKotlin(int note, int velocity)
{
    if (!g_vm || !g_bridgeClass || !g_rawNoteCallback) return;
    bool didAttach = false;
    JNIEnv* env = GetEnvForCurrentThread(&didAttach);
    if (!env) return;
    env->CallStaticVoidMethod(g_bridgeClass, g_rawNoteCallback, (jint)note, (jint)velocity);
    if (didAttach) g_vm->DetachCurrentThread();
}
