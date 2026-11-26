#include <jni.h>
#include <android/log.h>
#include <string>
#include <vector>
#include <mutex>
#include "fluidsynth.h"

namespace {
fluid_settings_t* settings = nullptr;
fluid_synth_t* synth = nullptr;
fluid_player_t* player = nullptr;
int coreSfid = -1;
int channelSfid[16] = {-1,-1,-1,-1,-1,-1,-1,-1,-1,-1,-1,-1,-1,-1,-1,-1};
int channelBank[16] = {-1,-1,-1,-1,-1,-1,-1,-1,-1,-1,-1,-1,-1,-1,-1,-1};
int channelProgram[16] = {-1,-1,-1,-1,-1,-1,-1,-1,-1,-1,-1,-1,-1,-1,-1,-1};
std::mutex synthMutex;
constexpr const char* TAG = "opendrawmusic-native";

void clearChannelProgramCacheLocked() {
    for (int ch = 0; ch < 16; ++ch) {
        channelSfid[ch] = -1;
        channelBank[ch] = -1;
        channelProgram[ch] = -1;
    }
}

void resetSynthLocked() {
    if (player) {
        delete_fluid_player(player);
        player = nullptr;
    }
    if (synth) {
        delete_fluid_synth(synth);
        synth = nullptr;
    }
    if (settings) {
        delete_fluid_settings(settings);
        settings = nullptr;
    }
    coreSfid = -1;
    clearChannelProgramCacheLocked();
}
}

extern "C" JNIEXPORT jint JNICALL
Java_com_opendrawmusic_MainActivity_loadCoreSoundFont(JNIEnv* env, jobject, jstring pathValue) {
    std::lock_guard<std::mutex> lock(synthMutex);
    resetSynthLocked();
    const char* path = env->GetStringUTFChars(pathValue, nullptr);
    settings = new_fluid_settings();
    fluid_settings_setnum(settings, "synth.gain", 0.7);
    fluid_settings_setnum(settings, "synth.sample-rate", 44100.0);
    synth = new_fluid_synth(settings);
    if (!synth) {
        __android_log_print(ANDROID_LOG_ERROR, TAG, "new_fluid_synth failed");
        env->ReleaseStringUTFChars(pathValue, path);
        return -1;
    }
    coreSfid = fluid_synth_sfload(synth, path, 1);
    env->ReleaseStringUTFChars(pathValue, path);
    if (coreSfid == FLUID_FAILED) {
        __android_log_print(ANDROID_LOG_ERROR, TAG, "Core SoundFont load failed: %s", path);
        coreSfid = -1;
        return -1;
    }
    fluid_synth_program_select(synth, 0, coreSfid, 0, 4);
    __android_log_print(ANDROID_LOG_INFO, TAG, "Loaded core SoundFont sfid=%d", coreSfid);
    return coreSfid;
}

extern "C" JNIEXPORT jint JNICALL
Java_com_opendrawmusic_MainActivity_loadExtraSoundFont(JNIEnv* env, jobject, jstring pathValue) {
    std::lock_guard<std::mutex> lock(synthMutex);
    if (!synth) return -1;
    const char* path = env->GetStringUTFChars(pathValue, nullptr);
    int loaded = fluid_synth_sfload(synth, path, 0);
    if (loaded == FLUID_FAILED) {
        __android_log_print(ANDROID_LOG_ERROR, TAG, "Extra SoundFont load failed: %s", path);
        env->ReleaseStringUTFChars(pathValue, path);
        return -1;
    }
    __android_log_print(ANDROID_LOG_INFO, TAG, "Loaded extra SoundFont sfid=%d path=%s", loaded, path);
    env->ReleaseStringUTFChars(pathValue, path);
    return loaded;
}

extern "C" JNIEXPORT jint JNICALL
Java_com_opendrawmusic_MainActivity_renderChord(
        JNIEnv* env,
        jobject,
        jintArray midiValues,
        jfloat seconds,
        jint sfidValue,
        jint bank,
        jint program,
        jfloat gain,
        jshortArray output) {
    std::lock_guard<std::mutex> lock(synthMutex);
    if (!synth || seconds <= 0.0f) return 0;
    const int targetSfid = sfidValue >= 0 ? sfidValue : coreSfid;
    if (targetSfid < 0) return 0;

    const jsize noteCount = env->GetArrayLength(midiValues);
    std::vector<jint> notes(static_cast<size_t>(noteCount));
    if (noteCount > 0) {
        env->GetIntArrayRegion(midiValues, 0, noteCount, notes.data());
    }

    const jsize frames = static_cast<jsize>(44100.0f * seconds);
    if (frames <= 0 || env->GetArrayLength(output) < frames * 2) return 0;
    if (fluid_synth_program_select(synth, 0, targetSfid, bank, program) == FLUID_FAILED) return 0;

    fluid_synth_all_sounds_off(synth, 0);
    for (jint note : notes) {
        if (note >= 0 && note <= 127) fluid_synth_noteon(synth, 0, note, 100);
    }

    std::vector<float> left(static_cast<size_t>(frames), 0.0f);
    std::vector<float> right(static_cast<size_t>(frames), 0.0f);
    // A note sounds for one step: release it just before the step ends so the next step's
    // attack is distinct instead of a sustained drone.
    const jsize noteOffFrame = frames > 1 ? frames - 1 : frames;
    bool released = false;
    for (jsize offset = 0; offset < frames; offset += 256) {
        if (!released && offset >= noteOffFrame) {
            for (jint note : notes) {
                if (note >= 0 && note <= 127) fluid_synth_noteoff(synth, 0, note);
            }
            released = true;
        }
        const jsize count = frames - offset < 256 ? frames - offset : 256;
        fluid_synth_write_float(
                synth,
                count,
                left.data() + offset,
                0,
                1,
                right.data() + offset,
                0,
                1);
    }

    const float safeGain = gain < 0.0f ? 0.0f : (gain > 1.0f ? 1.0f : gain);
    std::vector<jshort> interleaved(static_cast<size_t>(frames * 2));
    for (jsize i = 0; i < frames; ++i) {
        const float l = left[static_cast<size_t>(i)] * safeGain;
        const float r = right[static_cast<size_t>(i)] * safeGain;
        const float clippedLeft = l < -1.0f ? -1.0f : (l > 1.0f ? 1.0f : l);
        const float clippedRight = r < -1.0f ? -1.0f : (r > 1.0f ? 1.0f : r);
        interleaved[static_cast<size_t>(i * 2)] = static_cast<jshort>(clippedLeft * 32767.0f);
        interleaved[static_cast<size_t>(i * 2 + 1)] = static_cast<jshort>(clippedRight * 32767.0f);
    }
    env->SetShortArrayRegion(output, 0, frames * 2, interleaved.data());
    fluid_synth_all_sounds_off(synth, 0);
    return frames * 2;
}

extern "C" JNIEXPORT jint JNICALL
Java_com_opendrawmusic_MainActivity_renderTestNote(JNIEnv* env, jobject, jint midi, jfloat seconds, jshortArray output) {
    jint notes[1] = { midi };
    jintArray arr = env->NewIntArray(1);
    env->SetIntArrayRegion(arr, 0, 1, notes);
    jint result = Java_com_opendrawmusic_MainActivity_renderChord(env, nullptr, arr, seconds, coreSfid, 0, 4, 1.0f, output);
    env->DeleteLocalRef(arr);
    __android_log_print(ANDROID_LOG_INFO, TAG, "Rendered test note MIDI=%d result=%d", midi, result);
    return result;
}

extern "C" JNIEXPORT void JNICALL
Java_com_opendrawmusic_MainActivity_resetPlaybackSynth(JNIEnv*, jobject) {
    std::lock_guard<std::mutex> lock(synthMutex);
    if (synth) {
        for (int ch = 0; ch < 16; ++ch) {
            fluid_synth_all_sounds_off(synth, ch);
        }
    }
    clearChannelProgramCacheLocked();
}

extern "C" JNIEXPORT jint JNICALL
Java_com_opendrawmusic_MainActivity_renderSequencerStep(
        JNIEnv* env,
        jobject,
        jintArray noteOffChannels,
        jintArray noteOffMidis,
        jintArray noteOnChannels,
        jintArray noteOnMidis,
        jintArray noteOnSfids,
        jintArray noteOnBanks,
        jintArray noteOnPrograms,
        jint frames,
        jfloat gain,
        jshortArray output) {
    std::lock_guard<std::mutex> lock(synthMutex);
    if (!synth || frames <= 0 || env->GetArrayLength(output) < frames * 2) return 0;

    const jsize offCount = env->GetArrayLength(noteOffChannels);
    if (offCount > 0 && env->GetArrayLength(noteOffMidis) >= offCount) {
        std::vector<jint> offCh(static_cast<size_t>(offCount));
        std::vector<jint> offMidi(static_cast<size_t>(offCount));
        env->GetIntArrayRegion(noteOffChannels, 0, offCount, offCh.data());
        env->GetIntArrayRegion(noteOffMidis, 0, offCount, offMidi.data());
        for (jsize i = 0; i < offCount; ++i) {
            const int ch = offCh[static_cast<size_t>(i)];
            const int midi = offMidi[static_cast<size_t>(i)];
            if (ch >= 0 && ch < 16 && midi >= 0 && midi <= 127) {
                fluid_synth_noteoff(synth, ch, midi);
            }
        }
    }

    const jsize onCount = env->GetArrayLength(noteOnChannels);
    if (onCount > 0 &&
            env->GetArrayLength(noteOnMidis) >= onCount &&
            env->GetArrayLength(noteOnSfids) >= onCount &&
            env->GetArrayLength(noteOnBanks) >= onCount &&
            env->GetArrayLength(noteOnPrograms) >= onCount) {
        std::vector<jint> onCh(static_cast<size_t>(onCount));
        std::vector<jint> onMidi(static_cast<size_t>(onCount));
        std::vector<jint> onSfid(static_cast<size_t>(onCount));
        std::vector<jint> onBank(static_cast<size_t>(onCount));
        std::vector<jint> onProg(static_cast<size_t>(onCount));
        env->GetIntArrayRegion(noteOnChannels, 0, onCount, onCh.data());
        env->GetIntArrayRegion(noteOnMidis, 0, onCount, onMidi.data());
        env->GetIntArrayRegion(noteOnSfids, 0, onCount, onSfid.data());
        env->GetIntArrayRegion(noteOnBanks, 0, onCount, onBank.data());
        env->GetIntArrayRegion(noteOnPrograms, 0, onCount, onProg.data());

        for (jsize i = 0; i < onCount; ++i) {
            const int ch = onCh[static_cast<size_t>(i)];
            const int midi = onMidi[static_cast<size_t>(i)];
            const int targetSfid = onSfid[static_cast<size_t>(i)] >= 0 ? onSfid[static_cast<size_t>(i)] : coreSfid;
            const int bank = onBank[static_cast<size_t>(i)];
            const int prog = onProg[static_cast<size_t>(i)];
            if (ch < 0 || ch >= 16 || midi < 0 || midi > 127 || targetSfid < 0) continue;
            if (channelSfid[ch] != targetSfid || channelBank[ch] != bank || channelProgram[ch] != prog) {
                if (fluid_synth_program_select(synth, ch, targetSfid, bank, prog) == FLUID_FAILED) continue;
                channelSfid[ch] = targetSfid;
                channelBank[ch] = bank;
                channelProgram[ch] = prog;
            }
            fluid_synth_noteon(synth, ch, midi, 100);
        }
    }

    std::vector<float> left(static_cast<size_t>(frames), 0.0f);
    std::vector<float> right(static_cast<size_t>(frames), 0.0f);
    for (jsize offset = 0; offset < frames; offset += 256) {
        const jsize count = frames - offset < 256 ? frames - offset : 256;
        fluid_synth_write_float(
                synth,
                count,
                left.data() + offset,
                0,
                1,
                right.data() + offset,
                0,
                1);
    }

    const float safeGain = gain < 0.0f ? 0.0f : (gain > 1.0f ? 1.0f : gain);
    std::vector<jshort> interleaved(static_cast<size_t>(frames * 2));
    for (jsize i = 0; i < frames; ++i) {
        const float l = left[static_cast<size_t>(i)] * safeGain;
        const float r = right[static_cast<size_t>(i)] * safeGain;
        const float clippedLeft = l < -1.0f ? -1.0f : (l > 1.0f ? 1.0f : l);
        const float clippedRight = r < -1.0f ? -1.0f : (r > 1.0f ? 1.0f : r);
        interleaved[static_cast<size_t>(i * 2)] = static_cast<jshort>(clippedLeft * 32767.0f);
        interleaved[static_cast<size_t>(i * 2 + 1)] = static_cast<jshort>(clippedRight * 32767.0f);
    }
    env->SetShortArrayRegion(output, 0, frames * 2, interleaved.data());
    return frames * 2;
}

extern "C" JNIEXPORT void JNICALL
Java_com_opendrawmusic_MainActivity_stopSynth(JNIEnv*, jobject) {
    std::lock_guard<std::mutex> lock(synthMutex);
    resetSynthLocked();
    __android_log_print(ANDROID_LOG_INFO, TAG, "Stopped synth and released FluidSynth resources");
}
