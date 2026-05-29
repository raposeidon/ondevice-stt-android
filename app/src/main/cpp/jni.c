#include <jni.h>
#include <android/asset_manager.h>
#include <android/asset_manager_jni.h>
#include <android/log.h>
#include <stdlib.h>
#include <sys/sysinfo.h>
#include <string.h>
#include "whisper.h"
#include "ggml.h"
#include "rnnoise.h"

#define UNUSED(x) (void)(x)
#define TAG "JNI"

#define LOGI(...) __android_log_print(ANDROID_LOG_INFO,     TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN,     TAG, __VA_ARGS__)

JNIEXPORT jlong JNICALL
Java_com_whispercpp_whisper_WhisperLib_00024Companion_initContext(
        JNIEnv *env, jobject thiz, jstring model_path_str) {
    UNUSED(thiz);
    struct whisper_context *context = NULL;
    const char *model_path_chars = (*env)->GetStringUTFChars(env, model_path_str, NULL);
    struct whisper_context_params cparams = whisper_context_default_params();
    cparams.flash_attn = true; // 어텐션 가속
    context = whisper_init_from_file_with_params(model_path_chars, cparams);
    (*env)->ReleaseStringUTFChars(env, model_path_str, model_path_chars);
    return (jlong) context;
}

JNIEXPORT void JNICALL
Java_com_whispercpp_whisper_WhisperLib_00024Companion_freeContext(
        JNIEnv *env, jobject thiz, jlong context_ptr) {
    UNUSED(env);
    UNUSED(thiz);
    struct whisper_context *context = (struct whisper_context *) context_ptr;
    whisper_free(context);
}

JNIEXPORT void JNICALL
Java_com_whispercpp_whisper_WhisperLib_00024Companion_fullTranscribe(
        JNIEnv *env, jobject thiz, jlong context_ptr, jint num_threads,
        jfloatArray audio_data, jstring language_str, jboolean translate) {
    UNUSED(thiz);
    struct whisper_context *context = (struct whisper_context *) context_ptr;
    jfloat *audio_data_arr = (*env)->GetFloatArrayElements(env, audio_data, NULL);
    const jsize audio_data_length = (*env)->GetArrayLength(env, audio_data);

    const char *language = (*env)->GetStringUTFChars(env, language_str, NULL);

    struct whisper_full_params params = whisper_full_default_params(WHISPER_SAMPLING_GREEDY);
    params.print_realtime = false;
    params.print_progress = false;
    params.print_timestamps = true;
    params.print_special = false;
    params.translate = (translate == JNI_TRUE);
    // 빈 문자열이면 자동 언어 감지(NULL), 아니면 지정 언어 사용
    params.language = (language != NULL && language[0] != '\0') ? language : NULL;
    params.detect_language = (params.language == NULL);
    params.n_threads = num_threads;
    params.offset_ms = 0;
    params.no_context = true;
    params.single_segment = false;
    // temperature_inc 는 기본값(0.2) 유지 = 디코딩 실패 시 재시도 폴백 ON (품질 안전장치)

    whisper_reset_timings(context);

    LOGI("About to run whisper_full (lang=%s, translate=%d)",
         params.language ? params.language : "auto", params.translate);
    if (whisper_full(context, params, audio_data_arr, audio_data_length) != 0) {
        LOGW("Failed to run the model");
    } else {
        whisper_print_timings(context);
    }
    (*env)->ReleaseStringUTFChars(env, language_str, language);
    (*env)->ReleaseFloatArrayElements(env, audio_data, audio_data_arr, JNI_ABORT);
}

JNIEXPORT jint JNICALL
Java_com_whispercpp_whisper_WhisperLib_00024Companion_getTextSegmentCount(
        JNIEnv *env, jobject thiz, jlong context_ptr) {
    UNUSED(env);
    UNUSED(thiz);
    struct whisper_context *context = (struct whisper_context *) context_ptr;
    return whisper_full_n_segments(context);
}

JNIEXPORT jstring JNICALL
Java_com_whispercpp_whisper_WhisperLib_00024Companion_getTextSegment(
        JNIEnv *env, jobject thiz, jlong context_ptr, jint index) {
    UNUSED(thiz);
    struct whisper_context *context = (struct whisper_context *) context_ptr;
    const char *text = whisper_full_get_segment_text(context, index);
    jstring string = (*env)->NewStringUTF(env, text);
    return string;
}

JNIEXPORT jlong JNICALL
Java_com_whispercpp_whisper_WhisperLib_00024Companion_getTextSegmentT0(
        JNIEnv *env, jobject thiz, jlong context_ptr, jint index) {
    UNUSED(thiz);
    struct whisper_context *context = (struct whisper_context *) context_ptr;
    return whisper_full_get_segment_t0(context, index);
}

JNIEXPORT jlong JNICALL
Java_com_whispercpp_whisper_WhisperLib_00024Companion_getTextSegmentT1(
        JNIEnv *env, jobject thiz, jlong context_ptr, jint index) {
    UNUSED(thiz);
    struct whisper_context *context = (struct whisper_context *) context_ptr;
    return whisper_full_get_segment_t1(context, index);
}

JNIEXPORT jstring JNICALL
Java_com_whispercpp_whisper_WhisperLib_00024Companion_getSystemInfo(
        JNIEnv *env, jobject thiz) {
    UNUSED(thiz);
    const char *sysinfo = whisper_print_system_info();
    jstring string = (*env)->NewStringUTF(env, sysinfo);
    return string;
}

// ---------- RNNoise 디노이즈 (48kHz 입력 → 16kHz 출력) ----------
// RNNoise 는 48kHz, 480 sample(10ms) 프레임, int16 스케일 float 로 동작.
// 입력/출력 모두 [-1,1] 정규화 float 로 주고받고, 내부에서 스케일 변환한다.
// 출력은 48k→16k 다운샘플(3:1 평균)된 정규화 float.

#define RN_FRAME 480   // 48kHz 10ms
#define RN_OUT   160   // 16kHz 10ms (48k/3)

JNIEXPORT jlong JNICALL
Java_com_example_sttondevice_Denoiser_nativeCreate(JNIEnv *env, jobject thiz) {
    UNUSED(env);
    UNUSED(thiz);
    return (jlong) rnnoise_create(NULL);
}

JNIEXPORT void JNICALL
Java_com_example_sttondevice_Denoiser_nativeDestroy(JNIEnv *env, jobject thiz, jlong ptr) {
    UNUSED(env);
    UNUSED(thiz);
    if (ptr != 0) {
        rnnoise_destroy((DenoiseState *) ptr);
    }
}

// in48k: 길이는 480의 배수(정규화 [-1,1]). 반환: 길이/3 의 16kHz 정규화 float.
JNIEXPORT jfloatArray JNICALL
Java_com_example_sttondevice_Denoiser_nativeProcess(
        JNIEnv *env, jobject thiz, jlong ptr, jfloatArray in48k) {
    UNUSED(thiz);
    DenoiseState *st = (DenoiseState *) ptr;
    const jsize len = (*env)->GetArrayLength(env, in48k);
    const int frames = len / RN_FRAME;
    const int out_len = frames * RN_OUT;

    jfloat *in = (*env)->GetFloatArrayElements(env, in48k, NULL);
    jfloatArray out = (*env)->NewFloatArray(env, out_len);
    if (out == NULL || in == NULL) {
        if (in) (*env)->ReleaseFloatArrayElements(env, in48k, in, JNI_ABORT);
        return out;
    }

    jfloat *obuf = (jfloat *) malloc(sizeof(jfloat) * (size_t) out_len);
    float frame[RN_FRAME];
    for (int f = 0; f < frames; f++) {
        const jfloat *p = in + (size_t) f * RN_FRAME;
        for (int i = 0; i < RN_FRAME; i++) frame[i] = p[i] * 32768.0f;
        rnnoise_process_frame(st, frame, frame);
        // 48k → 16k : 3개 평균 후 정규화
        for (int j = 0; j < RN_OUT; j++) {
            float s = (frame[3 * j] + frame[3 * j + 1] + frame[3 * j + 2]) / 3.0f;
            obuf[(size_t) f * RN_OUT + j] = s / 32768.0f;
        }
    }
    (*env)->SetFloatArrayRegion(env, out, 0, out_len, obuf);
    free(obuf);
    (*env)->ReleaseFloatArrayElements(env, in48k, in, JNI_ABORT);
    return out;
}
