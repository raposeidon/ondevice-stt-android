package com.example.sttondevice

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * Whisper ggml 모델을 내부 저장소로 내려받아 관리한다.
 * 기본: multilingual base 모델(한국어 지원).
 */
class ModelManager(private val context: Context) {

    companion object {
        private const val TAG = "ModelManager"
        const val MODEL_FILE = "ggml-base.bin"
        private const val MODEL_URL =
            "https://huggingface.co/ggerganov/whisper.cpp/resolve/main/ggml-base.bin"
        // 다운로드 무결성 최소 검증용 예상 크기(약 147MB). 실제와 약간 달라도 통과되도록 하한만 둠.
        private const val MIN_VALID_BYTES = 100_000_000L
    }

    /** 내부 저장소 경로(다운로드 대상). install -r 재설치 시 보존됨. */
    private fun internalFile(): File = File(context.filesDir, MODEL_FILE)

    /**
     * 앱 전용 외부 디렉토리 경로. 권한 없이 접근 가능하며
     * `adb push <model> /sdcard/Android/data/<pkg>/files/` 로 미리 넣어두면
     * 인터넷 다운로드 없이 즉시 사용된다(개발 편의).
     */
    private fun pushedFile(): File? =
        context.getExternalFilesDir(null)?.let { File(it, MODEL_FILE) }

    /** 실제 사용할 모델 파일: push된 외부 파일 우선, 없으면 내부 파일. */
    fun modelFile(): File {
        val pushed = pushedFile()
        if (pushed != null && pushed.exists() && pushed.length() >= MIN_VALID_BYTES) {
            return pushed
        }
        return internalFile()
    }

    fun isModelReady(): Boolean {
        val f = modelFile()
        return f.exists() && f.length() >= MIN_VALID_BYTES
    }

    /**
     * 모델이 없으면 다운로드한다.
     * @param onProgress 0..100 진행률 콜백(전체 크기를 알 수 없으면 -1).
     * @return 모델 파일 경로
     */
    suspend fun ensureModel(onProgress: (Int) -> Unit): File = withContext(Dispatchers.IO) {
        val target = modelFile()
        if (isModelReady()) {
            onProgress(100)
            return@withContext target
        }

        val tmp = File(context.filesDir, "$MODEL_FILE.part")
        if (tmp.exists()) tmp.delete()

        Log.d(TAG, "모델 다운로드 시작: $MODEL_URL")
        val conn = (URL(MODEL_URL).openConnection() as HttpURLConnection).apply {
            connectTimeout = 30_000
            readTimeout = 30_000
            instanceFollowRedirects = true
            requestMethod = "GET"
        }
        try {
            conn.connect()
            if (conn.responseCode !in 200..299) {
                throw RuntimeException("다운로드 실패: HTTP ${conn.responseCode}")
            }
            val total = conn.contentLengthLong
            conn.inputStream.use { input ->
                tmp.outputStream().use { output ->
                    val buf = ByteArray(64 * 1024)
                    var downloaded = 0L
                    var lastPct = -1
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        output.write(buf, 0, n)
                        downloaded += n
                        if (total > 0) {
                            val pct = (downloaded * 100 / total).toInt()
                            if (pct != lastPct) {
                                lastPct = pct
                                onProgress(pct)
                            }
                        } else {
                            onProgress(-1)
                        }
                    }
                }
            }
        } finally {
            conn.disconnect()
        }

        if (tmp.length() < MIN_VALID_BYTES) {
            tmp.delete()
            throw RuntimeException("다운로드한 모델 크기가 비정상입니다: ${tmp.length()} bytes")
        }
        if (target.exists()) target.delete()
        if (!tmp.renameTo(target)) {
            throw RuntimeException("모델 파일 이동 실패")
        }
        Log.d(TAG, "모델 준비 완료: ${target.absolutePath} (${target.length()} bytes)")
        onProgress(100)
        target
    }
}
