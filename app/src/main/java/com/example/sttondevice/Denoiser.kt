package com.example.sttondevice

import android.util.Log

/**
 * RNNoise(Xiph, BSD-3) 기반 노이즈 억제 래퍼.
 *
 * 네이티브 심볼은 whisper 와 동일한 .so 에 함께 링크되어 있으므로,
 * WhisperContext 로드 이후(모델 준비 후)에 사용하면 별도 loadLibrary 가 필요 없다.
 *
 * 입력: 48kHz, 480의 배수 길이, 정규화 [-1,1] float
 * 출력: 16kHz 정규화 float (입력 길이 / 3)
 */
object Denoiser {
    private const val TAG = "Denoiser"

    /** RNNoise 48kHz 프레임 크기(10ms). 입력은 이 값의 배수여야 한다. */
    const val FRAME_48K = 480

    private external fun nativeCreate(): Long
    private external fun nativeDestroy(ptr: Long)
    private external fun nativeProcess(ptr: Long, in48k: FloatArray): FloatArray

    fun create(): Long = runCatching { nativeCreate() }.getOrElse {
        Log.e(TAG, "RNNoise 생성 실패", it)
        0L
    }

    fun destroy(ptr: Long) {
        if (ptr != 0L) nativeDestroy(ptr)
    }

    /** [in48k] 길이는 [FRAME_48K]의 배수여야 한다. */
    fun process(ptr: Long, in48k: FloatArray): FloatArray = nativeProcess(ptr, in48k)
}
