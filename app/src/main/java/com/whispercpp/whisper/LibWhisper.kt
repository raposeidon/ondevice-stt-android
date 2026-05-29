package com.whispercpp.whisper

import android.os.Build
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.Executors

private const val LOG_TAG = "LibWhisper"

/**
 * whisper.cpp 컨텍스트 래퍼.
 * whisper.cpp 제약: 한 번에 하나의 스레드에서만 접근해야 하므로
 * 단일 스레드 디스패처에서 모든 네이티브 호출을 수행한다.
 */
class WhisperContext private constructor(private var ptr: Long) {
    private val scope: CoroutineScope = CoroutineScope(
        Executors.newSingleThreadExecutor().asCoroutineDispatcher()
    )

    /**
     * 16kHz mono float PCM 데이터를 변환한다.
     * @param language "ko", "en" 등 ISO 코드. 빈 문자열이면 자동 감지.
     * @param translate true 면 영어로 번역.
     * @param printTimestamp 결과에 타임스탬프 포함 여부.
     */
    suspend fun transcribeData(
        data: FloatArray,
        language: String = "ko",
        translate: Boolean = false,
        printTimestamp: Boolean = false
    ): String = withContext(scope.coroutineContext) {
        require(ptr != 0L) { "WhisperContext 가 이미 해제되었습니다." }
        val numThreads = WhisperCpuConfig.preferredThreadCount
        Log.d(LOG_TAG, "Selecting $numThreads threads, lang=$language")
        WhisperLib.fullTranscribe(ptr, numThreads, data, language, translate)
        val textCount = WhisperLib.getTextSegmentCount(ptr)
        return@withContext buildString {
            for (i in 0 until textCount) {
                val segment = WhisperLib.getTextSegment(ptr, i)
                if (printTimestamp) {
                    val t0 = toTimestamp(WhisperLib.getTextSegmentT0(ptr, i))
                    val t1 = toTimestamp(WhisperLib.getTextSegmentT1(ptr, i))
                    append("[$t0 --> $t1]: $segment\n")
                } else {
                    append(segment)
                }
            }
        }
    }

    suspend fun release() = withContext(scope.coroutineContext) {
        if (ptr != 0L) {
            WhisperLib.freeContext(ptr)
            ptr = 0
        }
    }

    protected fun finalize() {
        runBlocking { release() }
    }

    companion object {
        fun createContextFromFile(filePath: String): WhisperContext {
            val ptr = WhisperLib.initContext(filePath)
            if (ptr == 0L) {
                throw RuntimeException("모델 컨텍스트 생성 실패: $filePath")
            }
            return WhisperContext(ptr)
        }

        fun getSystemInfo(): String = WhisperLib.getSystemInfo()
    }
}

private class WhisperLib {
    companion object {
        init {
            Log.d(LOG_TAG, "Primary ABI: ${Build.SUPPORTED_ABIS[0]}")
            var loadVfpv4 = false
            var loadV8fp16 = false
            if (isArmEabiV7a()) {
                cpuInfo()?.let {
                    if (it.contains("vfpv4")) loadVfpv4 = true
                }
            } else if (isArmEabiV8a()) {
                cpuInfo()?.let {
                    if (it.contains("fphp")) loadV8fp16 = true
                }
            }

            when {
                loadVfpv4 -> {
                    Log.d(LOG_TAG, "Loading libwhisper_vfpv4.so")
                    System.loadLibrary("whisper_vfpv4")
                }
                loadV8fp16 -> {
                    Log.d(LOG_TAG, "Loading libwhisper_v8fp16_va.so")
                    System.loadLibrary("whisper_v8fp16_va")
                }
                else -> {
                    Log.d(LOG_TAG, "Loading libwhisper.so")
                    System.loadLibrary("whisper")
                }
            }
        }

        external fun initContext(modelPath: String): Long
        external fun freeContext(contextPtr: Long)
        external fun fullTranscribe(
            contextPtr: Long,
            numThreads: Int,
            audioData: FloatArray,
            language: String,
            translate: Boolean
        )
        external fun getTextSegmentCount(contextPtr: Long): Int
        external fun getTextSegment(contextPtr: Long, index: Int): String
        external fun getTextSegmentT0(contextPtr: Long, index: Int): Long
        external fun getTextSegmentT1(contextPtr: Long, index: Int): Long
        external fun getSystemInfo(): String
    }
}

//  500 -> 00:00:05.000
private fun toTimestamp(t: Long): String {
    var msec = t * 10
    val hr = msec / (1000 * 60 * 60)
    msec -= hr * (1000 * 60 * 60)
    val min = msec / (1000 * 60)
    msec -= min * (1000 * 60)
    val sec = msec / 1000
    msec -= sec * 1000
    return String.format("%02d:%02d:%02d.%03d", hr, min, sec, msec)
}

private fun isArmEabiV7a(): Boolean = Build.SUPPORTED_ABIS[0] == "armeabi-v7a"
private fun isArmEabiV8a(): Boolean = Build.SUPPORTED_ABIS[0] == "arm64-v8a"

private fun cpuInfo(): String? = try {
    File("/proc/cpuinfo").inputStream().bufferedReader().use { it.readText() }
} catch (e: Exception) {
    Log.w(LOG_TAG, "Couldn't read /proc/cpuinfo", e)
    null
}
