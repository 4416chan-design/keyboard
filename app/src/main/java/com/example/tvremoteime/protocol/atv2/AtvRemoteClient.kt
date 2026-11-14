package com.example.tvremoteime.protocol.atv2

import android.util.Log
import com.example.tvremoteime.model.TvTextFieldState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.net.ssl.SSLHandshakeException
import javax.net.ssl.SSLSocket

/**
 * Android TV Remote Protocol v2 포트 6466 실시간 원격 제어 & IME(텍스트 필드) 동기화 클라이언트입니다.
 * - TV에서 리모컨으로 텍스트 입력창에 포커스하면 `RemoteImeKeyInject` / `RemoteImeShowRequest` 패킷을 수신하여
 *   `tvTextFieldState`를 즉시 업데이트합니다.
 * - 모바일 텍스트 필드에서 텍스트를 수정하면 `RemoteImeBatchEdit` 패킷을 TV로 전송해 TV 입력창에 즉시 반영합니다.
 */
class AtvRemoteClient(
    private val certManager: CertManager,
    private val onDisconnected: (reason: String, needsPairing: Boolean) -> Unit
) {

    companion object {
        private const val TAG = "AtvRemoteClient"
        const val DEFAULT_REMOTE_PORT = 6466
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var readerJob: Job? = null
    private var socket: SSLSocket? = null

    private val _tvTextFieldState = MutableStateFlow(TvTextFieldState())
    val tvTextFieldState: StateFlow<TvTextFieldState> = _tvTextFieldState.asStateFlow()

    suspend fun connect(host: String, port: Int = DEFAULT_REMOTE_PORT): Result<Unit> =
        withContext(Dispatchers.IO) {
            runCatching {
                disconnect()
                val sslSocket = certManager.createSslSocket(host, port)
                sslSocket.keepAlive = true
                socket = sslSocket

                // 초기 Configure 패킷 송신
                AtvProtoWire.writeFrame(
                    sslSocket.outputStream,
                    AtvProtoWire.buildRemoteConfigure()
                )

                startReaderLoop(sslSocket, host)
            }.onFailure { e ->
                Log.e(TAG, "Connect failed to $host:$port", e)
                disconnect()
            }
        }

    private fun startReaderLoop(sslSocket: SSLSocket, host: String) {
        readerJob?.cancel()
        readerJob = scope.launch {
            val input = sslSocket.inputStream
            val output = sslSocket.outputStream
            try {
                while (isActive && !sslSocket.isClosed) {
                    val frame = AtvProtoWire.readFrame(input)
                    val msg = AtvProtoWire.parseRemoteMessage(frame)

                    // 1. Handshake: RemoteConfigure 수신 시 응답
                    if (msg.configureCode != null) {
                        AtvProtoWire.writeFrame(output, AtvProtoWire.buildRemoteConfigure())
                    }

                    // 2. Handshake: RemoteSetActive 수신 시 활성화 응답
                    if (msg.setActiveNeeded) {
                        AtvProtoWire.writeFrame(output, AtvProtoWire.buildRemoteSetActive())
                    }

                    // 3. Keep-Alive: RemotePingRequest 수신 시 즉시 PingResponse 반환
                    if (msg.pingRequestVal1 != null) {
                        AtvProtoWire.writeFrame(
                            output,
                            AtvProtoWire.buildRemotePingResponse(msg.pingRequestVal1)
                        )
                    }

                    // 4. 페어링 인증 오류 또는 세션 거부 감지
                    if (msg.rawError != null) {
                        Log.w(TAG, "Received remote_error code=${msg.rawError}")
                        certManager.markHostPaired(host, false)
                        onDisconnected("TV에서 재페어링을 요청했습니다 (code=${msg.rawError})", true)
                        break
                    }

                    // 5. TV에서 리모컨으로 텍스트 인풋 포커스 시 수신되는 IME 상태 처리
                    val appInfo = msg.appInfo
                    val tfStatus = msg.textFieldStatus
                    if (tfStatus != null || msg.imeShowRequested) {
                        _tvTextFieldState.update { prev ->
                            val newText = tfStatus?.value ?: prev.text
                            val selStart = (tfStatus?.start ?: newText.length).coerceIn(0, newText.length)
                            val selEnd = (tfStatus?.end ?: newText.length).coerceIn(selStart, newText.length)
                            prev.copy(
                                isFocused = true,
                                text = newText,
                                selectionStart = selStart,
                                selectionEnd = selEnd,
                                hint = tfStatus?.label?.ifBlank { prev.hint } ?: prev.hint,
                                appPackage = appInfo?.appPackage?.ifBlank { prev.appPackage } ?: prev.appPackage,
                                appLabel = appInfo?.label?.ifBlank { prev.appLabel } ?: prev.appLabel,
                                imeCounter = appInfo?.imeCounter ?: prev.imeCounter,
                                fieldCounter = tfStatus?.fieldCounter ?: prev.fieldCounter,
                                lastUpdatedFromTvMillis = System.currentTimeMillis()
                            )
                        }
                        Log.d(
                            TAG,
                            "TV TextField Focused/Updated -> text='${tfStatus?.value}', counter=${tfStatus?.fieldCounter}"
                        )
                    } else if (appInfo != null) {
                        _tvTextFieldState.update { prev ->
                            prev.copy(
                                appPackage = appInfo.appPackage.ifBlank { prev.appPackage },
                                appLabel = appInfo.label.ifBlank { prev.appLabel },
                                imeCounter = appInfo.imeCounter
                            )
                        }
                    }
                }
            } catch (e: SSLHandshakeException) {
                Log.w(TAG, "SSLHandshakeException - needs pairing", e)
                certManager.markHostPaired(host, false)
                onDisconnected("TV 페어링 인증이 필요합니다.", true)
            } catch (e: Exception) {
                if (isActive) {
                    Log.w(TAG, "Reader loop ended: ${e.message}")
                    onDisconnected("TV와의 연결이 종료되었습니다 (${e.message ?: "EOF"})", false)
                }
            }
        }
    }

    /**
     * 모바일 텍스트 필드에서 편집한 텍스트를 TV의 현재 포커스된 텍스트 인풋에 그대로 반영합니다.
     */
    fun sendTextBatchEdit(
        newText: String,
        selectionStart: Int = newText.length,
        selectionEnd: Int = newText.length
    ) {
        val currentState = _tvTextFieldState.value
        // 로컬 상태를 먼저 갱신해 UI 점프를 방지
        _tvTextFieldState.update {
            it.copy(
                text = newText,
                selectionStart = selectionStart.coerceIn(0, newText.length),
                selectionEnd = selectionEnd.coerceIn(0, newText.length)
            )
        }

        scope.launch {
            val activeSocket = socket ?: return@launch
            try {
                val packet = AtvProtoWire.buildRemoteImeBatchEdit(
                    imeCounter = currentState.imeCounter,
                    fieldCounter = currentState.fieldCounter,
                    text = newText,
                    selectionStart = selectionStart,
                    selectionEnd = selectionEnd
                )
                AtvProtoWire.writeFrame(activeSocket.outputStream, packet)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to send RemoteImeBatchEdit", e)
            }
        }
    }

    /**
     * D-Pad 이동, 선택(포커스), 엔터, 백스페이스 등의 리모컨 키 이벤트를 전송합니다.
     */
    fun sendKeyCode(keyCode: Int) {
        scope.launch {
            val activeSocket = socket ?: return@launch
            try {
                val packet = AtvProtoWire.buildRemoteKeyInject(keyCode = keyCode, direction = 3)
                AtvProtoWire.writeFrame(activeSocket.outputStream, packet)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to send RemoteKeyInject($keyCode)", e)
            }
        }
    }

    fun clearFocusState() {
        _tvTextFieldState.update { it.copy(isFocused = false) }
    }

    fun disconnect() {
        readerJob?.cancel()
        readerJob = null
        try {
            socket?.close()
        } catch (_: Exception) {
        } finally {
            socket = null
        }
    }
}
