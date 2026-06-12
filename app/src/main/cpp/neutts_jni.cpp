#include <jni.h>
#include <string>
#include <vector>
#include <cmath>
#include <algorithm>
#include <android/log.h>
#include "espeak-ng/speak_lib.h"

#define LOG_TAG "NeuTTS_JNI"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

extern "C" {

JNIEXPORT jboolean JNICALL Java_com_example_ttspdfreader_data_tts_NeuTTSEngine_nativeInitEspeak(
        JNIEnv *env, jobject thiz, jstring data_path) {
    const char *data_path_str = env->GetStringUTFChars(data_path, nullptr);
    LOGI("Initializing espeak-ng with data path: %s", data_path_str);

    int res = espeak_Initialize(AUDIO_OUTPUT_SYNCHRONOUS, 0, data_path_str, 0);
    env->ReleaseStringUTFChars(data_path, data_path_str);

    if (res < 0) {
        LOGE("Failed to initialize espeak-ng: %d", res);
        return JNI_FALSE;
    }
    return JNI_TRUE;
}

JNIEXPORT jstring JNICALL Java_com_example_ttspdfreader_data_tts_NeuTTSEngine_nativePhonemeize(
        JNIEnv *env, jobject thiz, jstring text, jstring lang) {
    const char *text_str = env->GetStringUTFChars(text, nullptr);
    const char *lang_str = env->GetStringUTFChars(lang, nullptr);

    espeak_ERROR voice_res = espeak_SetVoiceByName(lang_str);
    if (voice_res != EE_OK) {
        LOGE("Failed to set espeak-ng voice to %s: %d", lang_str, voice_res);
    }

    std::string result = "";
    const void *ptr = (const void *)text_str;
    while (ptr && *(const char *)ptr) {
        // Mode 2 specifies IPA phonemes
        const char *phonemes = espeak_TextToPhonemes(&ptr, 1, 2);
        if (phonemes) {
            result += phonemes;
            result += " ";
        } else {
            break;
        }
    }

    env->ReleaseStringUTFChars(text, text_str);
    env->ReleaseStringUTFChars(lang, lang_str);

    return env->NewStringUTF(result.c_str());
}

JNIEXPORT void JNICALL Java_com_example_ttspdfreader_data_tts_NeuTTSEngine_nativeFreeEspeak(
        JNIEnv *env, jobject thiz) {
    LOGI("Terminating espeak-ng");
    espeak_Terminate();
}

}
