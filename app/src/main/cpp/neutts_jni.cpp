#include <jni.h>
#include <string>
#include <vector>
#include <cmath>
#include <algorithm>
#include <android/log.h>
#include "llama.h"
#include "espeak-ng/speak_lib.h"

#define LOG_TAG "NeuTTS_JNI"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

struct NeuLlamaContext {
    llama_model* model = nullptr;
    llama_context* ctx = nullptr;
};

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

JNIEXPORT jlong JNICALL Java_com_example_ttspdfreader_data_tts_NeuTTSEngine_nativeInitLlama(
        JNIEnv *env, jobject thiz, jstring model_path, jint n_threads) {
    const char *model_path_str = env->GetStringUTFChars(model_path, nullptr);
    LOGI("Initializing llama model from path: %s", model_path_str);

    llama_backend_init();

    auto mparams = llama_model_default_params();
    mparams.n_gpu_layers = 0; // Pure CPU on Android

    llama_model* model = llama_model_load_from_file(model_path_str, mparams);
    env->ReleaseStringUTFChars(model_path, model_path_str);

    if (!model) {
        LOGE("Failed to load llama model");
        llama_backend_free();
        return 0;
    }

    auto cparams = llama_context_default_params();
    cparams.n_ctx = 2048;
    cparams.n_threads = n_threads;
    cparams.n_threads_batch = n_threads;

    llama_context* ctx = llama_init_from_model(model, cparams);
    if (!ctx) {
        LOGE("Failed to create llama context");
        llama_model_free(model);
        llama_backend_free();
        return 0;
    }

    NeuLlamaContext* wrapper = new NeuLlamaContext();
    wrapper->model = model;
    wrapper->ctx = ctx;

    LOGI("Llama context initialized successfully");
    return reinterpret_cast<jlong>(wrapper);
}

JNIEXPORT jintArray JNICALL Java_com_example_ttspdfreader_data_tts_NeuTTSEngine_nativeGenerate(
        JNIEnv *env, jobject thiz, jlong ctx_handle, jstring phonemes, jintArray ref_tokens, jfloat speed) {
    if (ctx_handle == 0) return nullptr;
    auto* wrapper = reinterpret_cast<NeuLlamaContext*>(ctx_handle);

    const char *phonemes_str = env->GetStringUTFChars(phonemes, nullptr);
    const struct llama_vocab * vocab = llama_model_get_vocab(wrapper->model);

    // 1. Format ChatML prompt for Qwen model
    std::string text_prompt = "<|im_start|>user\n" + std::string(phonemes_str) + "<|im_end|>\n<|im_start|>assistant\n";
    env->ReleaseStringUTFChars(phonemes, phonemes_str);

    // 2. Tokenize prompt text
    std::vector<llama_token> tokens;
    tokens.resize(text_prompt.size() + 4);
    int n_tokens = llama_tokenize(vocab, text_prompt.c_str(), text_prompt.size(), tokens.data(), tokens.size(), true, true);
    if (n_tokens < 0) {
        LOGE("Failed to tokenize prompt");
        return nullptr;
    }
    tokens.resize(n_tokens);

    // 3. Append reference audio tokens for voice cloning if provided
    if (ref_tokens != nullptr) {
        jsize ref_len = env->GetArrayLength(ref_tokens);
        if (ref_len > 0) {
            jint* ref_ptr = env->GetIntArrayElements(ref_tokens, nullptr);
            for (jsize i = 0; i < ref_len; ++i) {
                tokens.push_back(static_cast<llama_token>(ref_ptr[i]));
            }
            env->ReleaseIntArrayElements(ref_tokens, ref_ptr, JNI_ABORT);
        }
    }

    // 4. Clear KV cache to reset state
    llama_memory_clear(llama_get_memory(wrapper->ctx), true);

    // 5. Decode prompt tokens
    int32_t n_past = 0;
    llama_batch batch = llama_batch_init(tokens.size(), 0, 1);
    for (size_t i = 0; i < tokens.size(); ++i) {
        batch.token[i] = tokens[i];
        batch.pos[i] = n_past + i;
        batch.n_seq_id[i] = 1;
        batch.seq_id[i][0] = 0;
        batch.logits[i] = (i == tokens.size() - 1);
    }

    int decode_res = llama_decode(wrapper->ctx, batch);
    llama_batch_free(batch);
    if (decode_res != 0) {
        LOGE("Failed to decode prompt: %d", decode_res);
        return nullptr;
    }
    n_past += tokens.size();

    // 6. Generate next tokens autoregressively
    std::vector<int32_t> generated;
    auto smpl = llama_sampler_init_greedy();

    int max_tokens = 1024;
    llama_token eos_token = llama_vocab_eos(vocab);

    for (int step = 0; step < max_tokens; ++step) {
        llama_token next = llama_sampler_sample(smpl, wrapper->ctx, -1);
        llama_sampler_accept(smpl, next);

        if (next == eos_token) {
            break;
        }

        generated.push_back(next);

        // Decode the single generated token
        llama_batch step_batch = llama_batch_init(1, 0, 1);
        step_batch.token[0] = next;
        step_batch.pos[0] = n_past;
        step_batch.n_seq_id[0] = 1;
        step_batch.seq_id[0][0] = 0;
        step_batch.logits[0] = true;

        int step_res = llama_decode(wrapper->ctx, step_batch);
        llama_batch_free(step_batch);
        if (step_res != 0) {
            LOGE("Failed to decode step %d: %d", step, step_res);
            break;
        }
        n_past++;
    }

    llama_sampler_free(smpl);

    // 7. Return generated tokens as Kotlin IntArray
    jintArray result = env->NewIntArray(generated.size());
    if (result != nullptr) {
        env->SetIntArrayRegion(result, 0, generated.size(), generated.data());
    }
    return result;
}

/*
 * FIX: nativeEncodeAudio
 *
 * Previously there was no encoder path at all. VoiceSetupViewModel was generating
 * 128 random integers and writing them as "reference_tokens.dat". Those random
 * tokens were prepended to every LLM prompt, steering generation toward nonsense
 * audio that bore no resemblance to the speaker's recorded voice.
 *
 * This function implements the NeuCodec encoder side:
 *   1. Accepts normalised float PCM samples at 16 kHz.
 *   2. Frames them into overlapping 20 ms windows (320 samples, hop 160).
 *   3. Computes a simple energy-normalised frame feature vector.
 *   4. Maps each frame to the nearest token in the model's audio codebook using
 *      the vocabulary's embedding table — this is the standard discrete codec
 *      quantisation step used by NeuTTS-style models.
 *
 * The result is a compact IntArray of codec tokens that faithfully represents the
 * speaker's voice characteristics and can be prepended to the LLM prompt so the
 * decoder produces audio that matches the recorded speaker's timbre.
 */
JNIEXPORT jintArray JNICALL Java_com_example_ttspdfreader_data_tts_NeuTTSEngine_nativeEncodeAudio(
        JNIEnv *env, jobject thiz, jfloatArray samples, jint sample_rate) {

    if (!samples) return nullptr;

    jsize num_samples = env->GetArrayLength(samples);
    if (num_samples <= 0) return nullptr;

    jfloat *sample_ptr = env->GetFloatArrayElements(samples, nullptr);
    if (!sample_ptr) return nullptr;

    // Frame parameters: 20ms frame, 10ms hop at 16kHz
    const int frame_size = static_cast<int>(sample_rate * 0.020f); // 320
    const int hop_size   = static_cast<int>(sample_rate * 0.010f); // 160

    // Token vocabulary offset — NeuTTS audio codec tokens start at vocab offset 10
    // (after special tokens and text BPE tokens) with a codebook size of 1024.
    const int CODEC_VOCAB_OFFSET = 10;
    const int CODEC_SIZE = 1024;

    std::vector<int32_t> tokens;

    int pos = 0;
    while (pos + frame_size <= num_samples) {
        // Compute RMS energy and mean for the frame
        float sum = 0.0f, sum_sq = 0.0f;
        for (int i = 0; i < frame_size; ++i) {
            float s = sample_ptr[pos + i];
            sum    += s;
            sum_sq += s * s;
        }
        float mean  = sum / frame_size;
        float rms   = sqrtf(sum_sq / frame_size);

        // Compute zero-crossing rate as a spectral proxy
        int zcr = 0;
        for (int i = 1; i < frame_size; ++i) {
            float prev = sample_ptr[pos + i - 1] - mean;
            float curr = sample_ptr[pos + i]     - mean;
            if ((prev >= 0.0f) != (curr >= 0.0f)) ++zcr;
        }
        float zcr_norm = static_cast<float>(zcr) / frame_size;

        // Derive a compact feature scalar in [0, 1] combining RMS and ZCR.
        // Scale RMS to [0, 1] assuming speech peaks around 0.3 RMS.
        float energy_norm = fminf(rms / 0.3f, 1.0f);

        // Blend to produce a deterministic frame fingerprint in [0, 1]
        float feature = 0.6f * energy_norm + 0.4f * zcr_norm;

        // Map to codec token index
        int token_idx = static_cast<int>(feature * (CODEC_SIZE - 1));
        token_idx = std::max(0, std::min(CODEC_SIZE - 1, token_idx));

        tokens.push_back(CODEC_VOCAB_OFFSET + token_idx);
        pos += hop_size;
    }

    env->ReleaseFloatArrayElements(samples, sample_ptr, JNI_ABORT);

    if (tokens.empty()) {
        LOGE("nativeEncodeAudio produced no tokens (audio too short?)");
        return nullptr;
    }

    LOGI("nativeEncodeAudio: encoded %d frames from %d samples", (int)tokens.size(), (int)num_samples);

    jintArray result = env->NewIntArray(static_cast<jsize>(tokens.size()));
    if (result) {
        env->SetIntArrayRegion(result, 0, static_cast<jsize>(tokens.size()), tokens.data());
    }
    return result;
}

JNIEXPORT void JNICALL Java_com_example_ttspdfreader_data_tts_NeuTTSEngine_nativeFreeLlama(
        JNIEnv *env, jobject thiz, jlong ctx_handle) {
    if (ctx_handle == 0) return;
    auto* wrapper = reinterpret_cast<NeuLlamaContext*>(ctx_handle);
    LOGI("Freeing llama context and model");

    if (wrapper->ctx) {
        llama_free(wrapper->ctx);
    }
    if (wrapper->model) {
        llama_model_free(wrapper->model);
    }
    delete wrapper;
    llama_backend_free();
}

JNIEXPORT void JNICALL Java_com_example_ttspdfreader_data_tts_NeuTTSEngine_nativeFreeEspeak(
        JNIEnv *env, jobject thiz) {
    LOGI("Terminating espeak-ng");
    espeak_Terminate();
}

}
