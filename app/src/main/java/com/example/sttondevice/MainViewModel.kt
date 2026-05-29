package com.example.sttondevice

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.whispercpp.whisper.WhisperContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

enum class AppPhase {
    LOADING_MODEL,   // 모델 다운로드/로드 중
    READY,           // 대기
    RECORDING,       // 녹음 중(배치) 또는 실시간 인식 중(스트리밍)
    TRANSCRIBING,    // 변환 중
    ERROR
}

/** 변환 방식 */
enum class TranscriptionMode {
    BATCH,      // 녹음 후 변환
    STREAMING   // 실시간 스트리밍
}

data class UiState(
    val phase: AppPhase = AppPhase.LOADING_MODEL,
    val mode: TranscriptionMode = TranscriptionMode.BATCH,
    val downloadProgress: Int = 0,    // 0..100, -1 = 크기 미상
    val transcript: String = "",
    val streamingBusy: Boolean = false, // 스트리밍 중 구간 변환 진행 여부
    val statusMessage: String = "모델을 준비하는 중...",
    val systemInfo: String = ""
)

class MainViewModel(app: Application) : AndroidViewModel(app) {

    private val _uiState = MutableStateFlow(UiState())
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()

    private val modelManager = ModelManager(app)
    private val recorder = Recorder()
    private var whisper: WhisperContext? = null
    private var streamer: StreamingTranscriber? = null

    companion object {
        private const val TAG = "MainViewModel"
        private const val LANGUAGE = "ko"
    }

    init {
        prepareModel()
    }

    private fun prepareModel() {
        viewModelScope.launch {
            _uiState.update { it.copy(phase = AppPhase.LOADING_MODEL, statusMessage = "모델을 준비하는 중...") }
            try {
                val file = modelManager.ensureModel { pct ->
                    _uiState.update {
                        it.copy(
                            downloadProgress = pct,
                            statusMessage = if (pct in 0..99) "모델 다운로드 중... $pct%" else "모델 로딩 중..."
                        )
                    }
                }
                val ctx = withContext(Dispatchers.IO) {
                    WhisperContext.createContextFromFile(file.absolutePath)
                }
                whisper = ctx
                _uiState.update {
                    it.copy(
                        phase = AppPhase.READY,
                        statusMessage = "준비됨. 녹음 버튼을 누르세요.",
                        systemInfo = WhisperContext.getSystemInfo()
                    )
                }
            } catch (e: Exception) {
                Log.e(TAG, "모델 준비 실패", e)
                _uiState.update { it.copy(phase = AppPhase.ERROR, statusMessage = "모델 준비 실패: ${e.message}") }
            }
        }
    }

    fun retry() {
        if (_uiState.value.phase == AppPhase.ERROR) prepareModel()
    }

    /** 변환 방식 변경(대기 상태에서만 허용). */
    fun setMode(mode: TranscriptionMode) {
        if (_uiState.value.phase != AppPhase.READY) return
        _uiState.update { it.copy(mode = mode) }
    }

    fun startRecording() {
        if (_uiState.value.phase != AppPhase.READY) return
        when (_uiState.value.mode) {
            TranscriptionMode.BATCH -> startBatch()
            TranscriptionMode.STREAMING -> startStreaming()
        }
    }

    /** 녹음/실시간 중지 버튼. */
    fun stop() {
        when (_uiState.value.mode) {
            TranscriptionMode.BATCH -> stopBatchAndTranscribe()
            TranscriptionMode.STREAMING -> stopStreaming()
        }
    }

    // ---------- 배치 ----------

    private fun startBatch() {
        recorder.start()
        if (!recorder.isRecording) {
            _uiState.update { it.copy(phase = AppPhase.ERROR, statusMessage = "마이크를 시작할 수 없습니다.") }
            return
        }
        _uiState.update { it.copy(phase = AppPhase.RECORDING, statusMessage = "녹음 중... 다시 누르면 변환합니다.") }
    }

    private fun stopBatchAndTranscribe() {
        if (_uiState.value.phase != AppPhase.RECORDING) return
        _uiState.update { it.copy(phase = AppPhase.TRANSCRIBING, statusMessage = "변환 중...") }
        viewModelScope.launch {
            try {
                val audio = recorder.stop()
                if (audio.isEmpty()) {
                    _uiState.update { it.copy(phase = AppPhase.READY, statusMessage = "녹음된 오디오가 없습니다.") }
                    return@launch
                }
                val seconds = audio.size / Recorder.SAMPLE_RATE.toFloat()
                val ctx = whisper ?: run {
                    _uiState.update { it.copy(phase = AppPhase.ERROR, statusMessage = "모델이 로드되지 않았습니다.") }
                    return@launch
                }
                val result = ctx.transcribeData(audio, language = LANGUAGE).trim()
                appendText(result)
                _uiState.update {
                    it.copy(phase = AppPhase.READY, statusMessage = "변환 완료 (${"%.1f".format(seconds)}초)")
                }
            } catch (e: Exception) {
                Log.e(TAG, "변환 실패", e)
                _uiState.update { it.copy(phase = AppPhase.ERROR, statusMessage = "변환 실패: ${e.message}") }
            }
        }
    }

    // ---------- 스트리밍 ----------

    private fun startStreaming() {
        val ctx = whisper ?: run {
            _uiState.update { it.copy(phase = AppPhase.ERROR, statusMessage = "모델이 로드되지 않았습니다.") }
            return
        }
        val s = StreamingTranscriber(
            whisper = ctx,
            language = LANGUAGE,
            onCommitted = { text -> appendText(text) },
            onProcessing = { busy -> _uiState.update { it.copy(streamingBusy = busy) } }
        )
        streamer = s
        recorder.start(streamListener = { samples -> s.onAudio(samples) })
        if (!recorder.isRecording) {
            streamer = null
            _uiState.update { it.copy(phase = AppPhase.ERROR, statusMessage = "마이크를 시작할 수 없습니다.") }
            return
        }
        _uiState.update {
            it.copy(phase = AppPhase.RECORDING, statusMessage = "실시간 인식 중... 말을 멈추면 문장이 추가됩니다.")
        }
    }

    private fun stopStreaming() {
        if (_uiState.value.phase != AppPhase.RECORDING) return
        _uiState.update { it.copy(phase = AppPhase.TRANSCRIBING, statusMessage = "마지막 구간 변환 중...") }
        viewModelScope.launch {
            try {
                recorder.stop()           // 녹음 루프 종료
                streamer?.finish()        // 남은 구간 변환 + 대기
            } catch (e: Exception) {
                Log.e(TAG, "스트리밍 종료 실패", e)
            } finally {
                streamer = null
                _uiState.update {
                    it.copy(phase = AppPhase.READY, streamingBusy = false, statusMessage = "실시간 인식 완료")
                }
            }
        }
    }

    // ---------- 공통 ----------

    private fun appendText(text: String) {
        if (text.isBlank()) return
        _uiState.update {
            val prev = it.transcript
            val merged = if (prev.isBlank()) text else prev.trimEnd() + " " + text
            it.copy(transcript = merged)
        }
    }

    fun clearTranscript() {
        _uiState.update { it.copy(transcript = "") }
    }

    override fun onCleared() {
        super.onCleared()
        recorder.cancel()
        viewModelScope.launch { whisper?.release() }
    }
}
