package com.example.sttondevice

import android.util.Log
import com.whispercpp.whisper.WhisperContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlin.math.sqrt

/**
 * 실시간(준실시간) 스트리밍 변환기.
 *
 * 동작: 마이크 프레임을 받아 에너지 기반 VAD 로 "발화 구간"을 검출한다.
 *  - 말이 멈추면(무음 [TRAILING_SILENCE_MS]) 그때까지의 구간을 하나의 발화로 확정해 변환한다.
 *  - 한 구간이 [MAX_SEGMENT_MS] 를 넘으면 강제로 잘라 변환한다.
 *
 * Whisper 는 batch 모델이라 진짜 토큰 스트리밍이 아니므로,
 * "문장/호흡 단위로 결과가 점점 추가되는" 방식으로 실시간감을 낸다.
 *
 * 변환은 단일 소비자 코루틴(Channel)에서 순차 처리하여 결과 순서를 보장한다.
 *
 * @param onCommitted 확정된 발화 텍스트를 전달(여러 번 호출됨).
 * @param onProcessing 변환 진행 중 여부 콜백.
 */
class StreamingTranscriber(
    private val whisper: WhisperContext,
    private val language: String,
    private val onCommitted: (String) -> Unit,
    private val onProcessing: (Boolean) -> Unit
) {
    companion object {
        private const val TAG = "StreamingTranscriber"
        private const val SILENCE_RMS = 0.018f       // 무음 판정 RMS 임계값
        private const val TRAILING_SILENCE_MS = 700  // 발화 종료로 보는 무음 길이
        private const val MIN_SPEECH_MS = 500        // 너무 짧은 구간은 무시
        private const val MAX_SEGMENT_MS = 12_000     // 무음이 없어도 강제 분할
        private const val LEAD_SILENCE_KEEP_MS = 300  // 발화 전 무음은 이 정도만 남김
    }

    private val sampleRate = Recorder.SAMPLE_RATE
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val channel = Channel<FloatArray>(Channel.UNLIMITED)

    // 현재 누적 중인 구간(오디오 콜백 스레드에서만 접근)
    private var buf = FloatArray(sampleRate * 14)
    private var len = 0
    private var trailingSilenceMs = 0
    private var hasSpeech = false

    private val consumerJob: Job = scope.launch {
        for (audio in channel) {
            onProcessing(true)
            try {
                val text = whisper.transcribeData(audio, language = language).trim()
                if (text.isNotEmpty()) onCommitted(text)
            } catch (e: Exception) {
                Log.e(TAG, "구간 변환 실패", e)
            } finally {
                onProcessing(false)
            }
        }
    }

    /** Recorder IO 스레드에서 순차 호출됨(약 100ms 프레임). */
    fun onAudio(samples: FloatArray) {
        append(samples)
        val frameMs = samples.size * 1000 / sampleRate
        val rms = rms(samples)

        if (rms < SILENCE_RMS) {
            trailingSilenceMs += frameMs
        } else {
            trailingSilenceMs = 0
            hasSpeech = true
        }

        // 아직 발화 전이면 선행 무음이 쌓이지 않도록 최근 일부만 유지
        if (!hasSpeech) {
            val keep = sampleRate * LEAD_SILENCE_KEEP_MS / 1000
            if (len > keep) dropFront(keep)
            return
        }

        val durMs = len * 1000 / sampleRate
        val byMaxLen = durMs >= MAX_SEGMENT_MS
        val byPause = trailingSilenceMs >= TRAILING_SILENCE_MS && durMs >= MIN_SPEECH_MS
        if (byMaxLen || byPause) {
            flushSegment()
        }
    }

    /** 녹음 종료 시 호출: 남은 구간을 확정 변환하고 모든 작업이 끝날 때까지 대기. */
    suspend fun finish() {
        val durMs = len * 1000 / sampleRate
        if (hasSpeech && durMs >= MIN_SPEECH_MS) {
            flushSegment()
        }
        channel.close()
        consumerJob.join()
    }

    private fun flushSegment() {
        if (len == 0) return
        val audio = buf.copyOf(len)
        len = 0
        trailingSilenceMs = 0
        hasSpeech = false
        channel.trySend(audio)
    }

    private fun append(s: FloatArray) {
        if (len + s.size > buf.size) {
            buf = buf.copyOf(maxOf(buf.size * 2, len + s.size))
        }
        System.arraycopy(s, 0, buf, len, s.size)
        len += s.size
    }

    /** 앞부분을 버리고 마지막 [keep] 샘플만 남긴다. */
    private fun dropFront(keep: Int) {
        if (keep >= len) return
        System.arraycopy(buf, len - keep, buf, 0, keep)
        len = keep
    }

    private fun rms(s: FloatArray): Float {
        if (s.isEmpty()) return 0f
        var sum = 0.0
        for (v in s) sum += v.toDouble() * v
        return sqrt(sum / s.size).toFloat()
    }
}
