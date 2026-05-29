package com.example.sttondevice

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * 마이크를 48kHz 로 녹음하여 RNNoise 로 노이즈를 억제한 뒤,
 * Whisper 입력 규격(16kHz mono float)으로 다운샘플해 제공한다.
 *
 * - 배치 모드: stop() 에서 정제된 16kHz FloatArray 반환
 * - 스트리밍 모드: 약 100ms 단위 16kHz float 프레임을 콜백으로 전달
 */
class Recorder {

    companion object {
        const val SAMPLE_RATE = 16000        // 출력(Whisper)
        const val CAPTURE_RATE = 48000       // 녹음/RNNoise
        private const val TAG = "Recorder"
        private val CHANNEL = AudioFormat.CHANNEL_IN_MONO
        private val ENCODING = AudioFormat.ENCODING_PCM_16BIT
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var recordJob: Job? = null
    private var audioRecord: AudioRecord? = null
    private var nsPtr: Long = 0L

    // 48k 캡처 누적 버퍼(480 배수 단위로 처리, 나머지는 carry)
    private var captureBuf = FloatArray(CAPTURE_RATE)
    private var captureLen = 0
    // 배치 모드용 정제된 16k 결과 누적
    private var batchBuf = FloatArray(SAMPLE_RATE * 4)
    private var batchLen = 0

    @Volatile
    var isRecording: Boolean = false
        private set

    /**
     * 녹음 시작.
     * @param streamListener 지정 시 정제된 16kHz float 프레임을 콜백으로 전달(스트리밍).
     *                       null 이면 stop() 에서 일괄 반환(배치).
     */
    @SuppressLint("MissingPermission")
    fun start(streamListener: ((FloatArray) -> Unit)? = null) {
        if (isRecording) return
        captureLen = 0
        batchLen = 0

        val minBufSize = AudioRecord.getMinBufferSize(CAPTURE_RATE, CHANNEL, ENCODING)
        val bufferSize = maxOf(minBufSize, CAPTURE_RATE * 2) // 최소 1초(48k)

        // RNNoise 가 전처리하므로 가공되지 않은 MIC 소스를 사용
        val record = AudioRecord(
            MediaRecorder.AudioSource.MIC,
            CAPTURE_RATE,
            CHANNEL,
            ENCODING,
            bufferSize
        )
        if (record.state != AudioRecord.STATE_INITIALIZED) {
            Log.e(TAG, "AudioRecord 초기화 실패")
            record.release()
            return
        }

        nsPtr = Denoiser.create()
        Log.d(TAG, "RNNoise state=${if (nsPtr != 0L) "ok" else "unavailable(fallback)"}")

        audioRecord = record
        isRecording = true
        record.startRecording()

        // 스트리밍: 100ms(48k 4800 sample=9600 byte) 단위. 배치: 큰 버퍼.
        val readBytes = if (streamListener != null) CAPTURE_RATE / 10 * 2 else bufferSize
        recordJob = scope.launch {
            val buf = ByteArray(readBytes)
            while (isRecording) {
                val read = record.read(buf, 0, buf.size)
                if (read > 0) {
                    routeCapture(pcmToFloat(buf, read), streamListener)
                }
            }
        }
    }

    /** 녹음 종료 후 정제된 16kHz float 반환. 녹음 중이 아니면 빈 배열. */
    suspend fun stop(): FloatArray {
        if (!isRecording) return FloatArray(0)
        isRecording = false
        recordJob?.join()
        recordJob = null

        audioRecord?.apply {
            try {
                stop()
            } catch (e: IllegalStateException) {
                Log.w(TAG, "stop 중 예외", e)
            }
            release()
        }
        audioRecord = null
        Denoiser.destroy(nsPtr)
        nsPtr = 0L

        return batchBuf.copyOf(batchLen)
    }

    fun cancel() {
        isRecording = false
        audioRecord?.apply {
            try {
                stop()
            } catch (_: IllegalStateException) {
            }
            release()
        }
        audioRecord = null
        Denoiser.destroy(nsPtr)
        nsPtr = 0L
        recordJob = null
        captureLen = 0
        batchLen = 0
    }

    /** 48k float 를 480 배수 단위로 디노이즈+다운샘플하여 라우팅한다. */
    private fun routeCapture(samples48k: FloatArray, streamListener: ((FloatArray) -> Unit)?) {
        appendCapture(samples48k)
        val n = captureLen / Denoiser.FRAME_48K
        if (n == 0) return
        val take = n * Denoiser.FRAME_48K
        val chunk = captureBuf.copyOf(take)
        // 나머지를 앞으로 당김
        val remainder = captureLen - take
        if (remainder > 0) System.arraycopy(captureBuf, take, captureBuf, 0, remainder)
        captureLen = remainder

        val out16k = if (nsPtr != 0L) Denoiser.process(nsPtr, chunk) else downsample3(chunk)
        if (streamListener != null) {
            streamListener(out16k)
        } else {
            appendBatch(out16k)
        }
    }

    private fun appendCapture(s: FloatArray) {
        if (captureLen + s.size > captureBuf.size) {
            captureBuf = captureBuf.copyOf(maxOf(captureBuf.size * 2, captureLen + s.size))
        }
        System.arraycopy(s, 0, captureBuf, captureLen, s.size)
        captureLen += s.size
    }

    private fun appendBatch(s: FloatArray) {
        if (batchLen + s.size > batchBuf.size) {
            batchBuf = batchBuf.copyOf(maxOf(batchBuf.size * 2, batchLen + s.size))
        }
        System.arraycopy(s, 0, batchBuf, batchLen, s.size)
        batchLen += s.size
    }

    /** RNNoise 미사용 시 48k→16k 단순 3:1 평균 다운샘플(폴백). */
    private fun downsample3(in48k: FloatArray): FloatArray {
        val outLen = in48k.size / 3
        val out = FloatArray(outLen)
        for (j in 0 until outLen) {
            out[j] = (in48k[3 * j] + in48k[3 * j + 1] + in48k[3 * j + 2]) / 3.0f
        }
        return out
    }

    /** 16-bit little-endian PCM(앞 [len] 바이트) → [-1,1] float */
    private fun pcmToFloat(pcm: ByteArray, len: Int = pcm.size): FloatArray {
        val shortBuf = ByteBuffer.wrap(pcm, 0, len).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
        val out = FloatArray(shortBuf.remaining())
        var i = 0
        while (shortBuf.hasRemaining()) {
            out[i++] = shortBuf.get() / 32768.0f
        }
        return out
    }
}
