#include <jni.h>
#include <string>
#include <vector>
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
