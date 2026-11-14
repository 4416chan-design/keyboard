package com.example.tvremoteime.protocol.customime

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
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.io.PrintWriter
import java.net.InetSocketAddress
import java.net.Socket

/**
 * `:tv-ime` (TV 전체 앱용 커스텀 InputMethodService) 모듈과 TCP JSON-Lines로 통신하는 클라이언트입니다.
 * - TV에서 리모컨으로 어느 앱의 텍스트 인풋이든 포커스하면 `FOCUS_GAINED`와 현재 텍스트(`text`, `selStart`, `selEnd`)를 수신합니다.
 * - 모바일에서 편집한 텍스트를 `REPLACE_TEXT` 명령으로 전송해 TV의 `InputConnection`에 즉시 반영합니다.
 */
class CustomImeClient(
    private val onDisconnected: (reason: String) -> Unit
) {

    companion object {
        private const val TAG = "CustomImeClient"
        const val DEFAULT_CUSTOM_IME_PORT = 9466
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var readerJob: Job? = null
    private var socket: Socket? = null
    private var writer: PrintWriter? = null

    private val _tvTextFieldState = MutableStateFlow(TvTextFieldState())
    val tvTextFieldState: StateFlow<TvTextFieldState> = _tvTextFieldState.asStateFlow()

    suspend fun connect(host: String, port: Int = DEFAULT_CUSTOM_IME_PORT): Result<Unit> =
        withContext(Dispatchers.IO) {
            runCatching {
                disconnect()
                val rawSocket = Socket()
                rawSocket.connect(InetSocketAddress(host, port), 6000)
                rawSocket.keepAlive = true
                socket = rawSocket
                writer = PrintWriter(OutputStreamWriter(rawSocket.getOutputStream(), Charsets.UTF_8), true)

                // 연결 직후 현재 포커스된 텍스트 필드 상태 요청
                sendJson(JSONObject().apply {
                    put("type", "REQUEST_STATE")
                })

                startReaderLoop(rawSocket)
            }.onFailure { e ->
                Log.e(TAG, "CustomImeClient connect failed", e)
                disconnect()
            }
        }

    private fun startReaderLoop(rawSocket: Socket) {
        readerJob?.cancel()
        readerJob = scope.launch {
            try {
                val reader = BufferedReader(InputStreamReader(rawSocket.getInputStream(), Charsets.UTF_8))
                while (isActive && !rawSocket.isClosed) {
                    val line = reader.readLine() ?: break
                    if (line.isBlank()) continue
                    handleIncomingJson(JSONObject(line))
                }
                if (isActive) {
                    onDisconnected("TV 키보드 서비스와 연결이 종료되었습니다.")
                }
            } catch (e: Exception) {
                if (isActive) {
                    Log.w(TAG, "CustomImeClient reader ended: ${e.message}")
                    onDisconnected("TV 키보드 서비스 연결 끊김: ${e.message}")
                }
            }
        }
    }

    private fun handleIncomingJson(json: JSONObject) {
        when (json.optString("type")) {
            "FOCUS_GAINED", "TEXT_UPDATED" -> {
                val text = json.optString("text", "")
                val selStart = json.optInt("selStart", text.length).coerceIn(0, text.length)
                val selEnd = json.optInt("selEnd", text.length).coerceIn(selStart, text.length)
                val hint = json.optString("hint", "")
                val pkg = json.optString("packageName", "")
                val label = json.optString("label", "")
                val fieldCounter = json.optInt("fieldCounter", 0)

                _tvTextFieldState.update {
                    it.copy(
                        isFocused = true,
                        text = text,
                        selectionStart = selStart,
                        selectionEnd = selEnd,
                        hint = hint,
                        appPackage = pkg,
                        appLabel = label,
                        fieldCounter = fieldCounter,
                        lastUpdatedFromTvMillis = System.currentTimeMillis()
                    )
                }
            }
            "FOCUS_LOST" -> {
                _tvTextFieldState.update {
                    it.copy(isFocused = false)
                }
            }
        }
    }

    fun sendReplaceText(
        newText: String,
        selectionStart: Int = newText.length,
        selectionEnd: Int = newText.length
    ) {
        _tvTextFieldState.update {
            it.copy(
                text = newText,
                selectionStart = selectionStart.coerceIn(0, newText.length),
                selectionEnd = selectionEnd.coerceIn(0, newText.length)
            )
        }
        scope.launch {
            sendJson(JSONObject().apply {
                put("type", "REPLACE_TEXT")
                put("text", newText)
                put("selStart", selectionStart)
                put("selEnd", selectionEnd)
            })
        }
    }

    fun sendKeyCode(keyCode: Int) {
        scope.launch {
            sendJson(JSONObject().apply {
                put("type", "KEY_EVENT")
                put("keyCode", keyCode)
            })
        }
    }

    fun sendImeAction() {
        scope.launch {
            sendJson(JSONObject().apply {
                put("type", "PERFORM_EDITOR_ACTION")
            })
        }
    }

    @Synchronized
    private fun sendJson(json: JSONObject) {
        try {
            writer?.println(json.toString())
        } catch (e: Exception) {
            Log.e(TAG, "Failed to send JSON to TV IME", e)
        }
    }

    fun disconnect() {
        readerJob?.cancel()
        readerJob = null
        try {
            writer?.close()
        } catch (_: Exception) {
        }
        try {
            socket?.close()
        } catch (_: Exception) {
        } finally {
            writer = null
            socket = null
        }
    }
}
