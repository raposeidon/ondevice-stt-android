package com.example.sttondevice

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.sttondevice.ui.theme.STTonDeviceTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            STTonDeviceTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    SttScreen()
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SttScreen(viewModel: MainViewModel = viewModel()) {
    val state by viewModel.uiState.collectAsState()
    val context = LocalContext.current

    var hasAudioPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(
                context, Manifest.permission.RECORD_AUDIO
            ) == PackageManager.PERMISSION_GRANTED
        )
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        hasAudioPermission = granted
        if (granted) viewModel.startRecording()
    }

    var showLicenses by remember { mutableStateOf(false) }
    if (showLicenses) {
        LicensesDialog(onDismiss = { showLicenses = false })
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .safeDrawingPadding() // 상태바/내비게이션 바와 겹치지 않도록 인셋 적용
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Spacer(Modifier.height(8.dp))
        Box(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier.align(Alignment.Center),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = "온디바이스 음성 인식 (한국어)",
                    style = MaterialTheme.typography.titleLarge
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = "Whisper · 모든 처리는 기기에서 수행됩니다",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            IconButton(
                onClick = { showLicenses = true },
                modifier = Modifier.align(Alignment.TopEnd)
            ) {
                Icon(Icons.Filled.Info, contentDescription = "오픈소스 라이선스")
            }
        }

        Spacer(Modifier.height(16.dp))

        // 변환 방식 선택 (대기 상태에서만 변경 가능)
        val modeEnabled = state.phase == AppPhase.READY
        SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
            SegmentedButton(
                selected = state.mode == TranscriptionMode.BATCH,
                onClick = { viewModel.setMode(TranscriptionMode.BATCH) },
                enabled = modeEnabled,
                shape = SegmentedButtonDefaults.itemShape(index = 0, count = 2)
            ) { Text("녹음 후 변환") }
            SegmentedButton(
                selected = state.mode == TranscriptionMode.STREAMING,
                onClick = { viewModel.setMode(TranscriptionMode.STREAMING) },
                enabled = modeEnabled,
                shape = SegmentedButtonDefaults.itemShape(index = 1, count = 2)
            ) { Text("실시간") }
        }

        Spacer(Modifier.height(16.dp))

        // 상태 메시지
        Text(
            text = state.statusMessage,
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.Center
        )

        if (state.phase == AppPhase.LOADING_MODEL) {
            Spacer(Modifier.height(12.dp))
            if (state.downloadProgress in 0..100) {
                LinearProgressIndicator(
                    progress = { state.downloadProgress / 100f },
                    modifier = Modifier.fillMaxWidth()
                )
            } else {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }
        }

        if (state.phase == AppPhase.TRANSCRIBING ||
            (state.phase == AppPhase.RECORDING && state.streamingBusy)
        ) {
            Spacer(Modifier.height(12.dp))
            CircularProgressIndicator()
        }

        Spacer(Modifier.height(24.dp))

        // 변환 결과
        OutlinedCard(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
        ) {
            val scroll = rememberScrollState()
            LaunchedEffect(state.transcript) { scroll.animateScrollTo(scroll.maxValue) }
            Text(
                text = state.transcript.ifBlank { "여기에 인식 결과가 표시됩니다." },
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(scroll)
                    .padding(16.dp),
                style = MaterialTheme.typography.bodyLarge,
                color = if (state.transcript.isBlank())
                    MaterialTheme.colorScheme.onSurfaceVariant
                else MaterialTheme.colorScheme.onSurface
            )
        }

        Spacer(Modifier.height(16.dp))

        when (state.phase) {
            AppPhase.ERROR -> {
                Button(onClick = { viewModel.retry() }) {
                    Text("다시 시도")
                }
            }

            else -> {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    val recording = state.phase == AppPhase.RECORDING
                    val canRecord = state.phase == AppPhase.READY || recording
                    val streaming = state.mode == TranscriptionMode.STREAMING
                    val label = when {
                        recording && streaming -> "중지"
                        recording -> "변환하기"
                        streaming -> "실시간 시작"
                        else -> "녹음 시작"
                    }

                    Button(
                        onClick = {
                            if (recording) {
                                viewModel.stop()
                            } else if (hasAudioPermission) {
                                viewModel.startRecording()
                            } else {
                                permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                            }
                        },
                        enabled = canRecord,
                        modifier = Modifier.weight(1f),
                        colors = if (recording)
                            ButtonDefaults.buttonColors(
                                containerColor = MaterialTheme.colorScheme.error
                            )
                        else ButtonDefaults.buttonColors()
                    ) {
                        Icon(
                            imageVector = if (recording) Icons.Filled.Stop else Icons.Filled.Mic,
                            contentDescription = null
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(label)
                    }

                    OutlinedButton(
                        onClick = { viewModel.clearTranscript() },
                        enabled = state.transcript.isNotBlank() && !recording
                    ) {
                        Icon(Icons.Filled.Clear, contentDescription = null)
                    }
                }
            }
        }

        Spacer(Modifier.height(8.dp))
    }
}

private const val MIT_BODY =
    "Permission is hereby granted, free of charge, to any person obtaining a copy " +
    "of this software and associated documentation files (the \"Software\"), to deal " +
    "in the Software without restriction, including without limitation the rights " +
    "to use, copy, modify, merge, publish, distribute, sublicense, and/or sell " +
    "copies of the Software, and to permit persons to whom the Software is " +
    "furnished to do so, subject to the following conditions:\n\n" +
    "The above copyright notice and this permission notice shall be included in all " +
    "copies or substantial portions of the Software.\n\n" +
    "THE SOFTWARE IS PROVIDED \"AS IS\", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR " +
    "IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY, " +
    "FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE " +
    "AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER " +
    "LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM, " +
    "OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE " +
    "SOFTWARE."

private const val BSD3_BODY =
    "Redistribution and use in source and binary forms, with or without modification, " +
    "are permitted provided that the following conditions are met:\n\n" +
    "- Redistributions of source code must retain the above copyright notice, this list " +
    "of conditions and the following disclaimer.\n\n" +
    "- Redistributions in binary form must reproduce the above copyright notice, this " +
    "list of conditions and the following disclaimer in the documentation and/or other " +
    "materials provided with the distribution.\n\n" +
    "- Neither the name of the Xiph.Org Foundation nor the names of its contributors may " +
    "be used to endorse or promote products derived from this software without specific " +
    "prior written permission.\n\n" +
    "THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS \"AS IS\" AND ANY " +
    "EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED WARRANTIES " +
    "OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE DISCLAIMED. IN NO EVENT " +
    "SHALL THE FOUNDATION OR CONTRIBUTORS BE LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL, " +
    "SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT LIMITED TO, " +
    "PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES; LOSS OF USE, DATA, OR PROFITS; OR " +
    "BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN " +
    "CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN " +
    "ANY WAY OUT OF THE USE OF THIS SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH " +
    "DAMAGE."

private data class OssLicense(val name: String, val license: String, val text: String)

private val OSS_LICENSES = listOf(
    OssLicense(
        name = "whisper.cpp",
        license = "MIT License — Copyright (c) 2023-2024 Georgi Gerganov",
        text = MIT_BODY
    ),
    OssLicense(
        name = "ggml",
        license = "MIT License — Copyright (c) 2023-2026 The ggml authors",
        text = MIT_BODY
    ),
    OssLicense(
        name = "RNNoise",
        license = "BSD 3-Clause License — Copyright (c) 2007-2017, 2024 Jean-Marc Valin; " +
            "(c) 2023 Amazon; (c) 2017 Mozilla; (c) 2005-2017 Xiph.Org Foundation; " +
            "(c) 2003-2004 Mark Borgerding",
        text = BSD3_BODY
    )
)

@Composable
fun LicensesDialog(onDismiss: () -> Unit) {
    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = MaterialTheme.shapes.large,
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 6.dp,
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.85f)
        ) {
            Column(modifier = Modifier.padding(20.dp)) {
                Text(
                    text = "오픈소스 라이선스",
                    style = MaterialTheme.typography.titleLarge
                )
                Spacer(Modifier.height(12.dp))
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .verticalScroll(rememberScrollState())
                ) {
                    OSS_LICENSES.forEachIndexed { index, item ->
                        Text(
                            text = item.name,
                            style = MaterialTheme.typography.titleMedium
                        )
                        Spacer(Modifier.height(2.dp))
                        Text(
                            text = item.license,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(Modifier.height(6.dp))
                        Text(
                            text = item.text,
                            style = MaterialTheme.typography.bodySmall
                        )
                        if (index != OSS_LICENSES.lastIndex) {
                            Spacer(Modifier.height(16.dp))
                            HorizontalDivider()
                            Spacer(Modifier.height(16.dp))
                        }
                    }
                }
                Spacer(Modifier.height(12.dp))
                TextButton(
                    onClick = onDismiss,
                    modifier = Modifier.align(Alignment.End)
                ) {
                    Text("닫기")
                }
            }
        }
    }
}
