package com.example.tvremoteime.tv

import android.content.Context
import android.graphics.Color
import android.inputmethodservice.InputMethodService
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.ExtractedTextRequest
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.io.PrintWriter
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Android TV 전체 앱에서 동작하는 커스텀 키보드 서비스(InputMethodService)입니다.
 * 1) NsdManager를 통해 `_tvkbd._tcp.` mDNS 서비스를 브로드캐스트합니다.
 * 2) TV 리모컨으로 어느 앱의 텍스트 인풋이든 포커스하면 `onStartInputView`에서 현재 텍스트를 추출해
 *    연결된 모바일 앱으로 즉시 전송합니다.
 * 3) 모바일 앱의 텍스트 필드에서 텍스트를 수정하면 `InputConnection`을 통해 TV 입력창에 그대로 반영합니다.
 */
class TvRemoteInputMethodService : InputMethodService() {

    companion object {
        private const val TAG = "TvRemoteIME"
        const val SERVICE_TYPE = "_tvkbd._tcp."
        const val PREFERRED_PORT = 9466
    }

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mainHandler = Handler(Looper.getMainLooper())

    private var serverSocket: ServerSocket? = null
    private val clients = CopyOnWriteArrayList<PrintWriter>()

    private var nsdManager: NsdManager? = null
    private var registrationListener: NsdManager.RegistrationListener? = null

    private var statusTextView: TextView? = null
    private var currentFieldCounter: Int = 0
    private var isInputFocused: Boolean = false
    private var lastRemoteApplyMillis: Long = 0L

    override fun onCreate() {
        super.onCreate()
        startTcpServerAndMdns()
    }

    override fun onCreateInputView(): View {
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(Color.parseColor("#E6181C24"))
            val padH = dpToPx(24)
            val padV = dpToPx(14)
            setPadding(padH, padV, padH, padV)
        }

        val tvStatus = TextView(this).apply {
            text = buildStatusLabel()
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        statusTextView = tvStatus

        val hideButton = Button(this).apply {
            text = "완료 / 닫기"
            setOnClickListener {
                sendDefaultEditorAction(true)
                requestHideSelf(0)
            }
        }

        container.addView(tvStatus)
        container.addView(hideButton)
        return container
    }

    /**
     * 리모컨으로 TV 화면의 텍스트 입력창(EditText / SearchView 등)에 포커스하여
     * 입력 세션이 시작될 때 호출됩니다.
     */
    override fun onStartInput(attribute: EditorInfo?, restarting: Boolean) {
        super.onStartInput(attribute, restarting)
        val hasEditableField = attribute != null &&
            (attribute.inputType and InputType.TYPE_MASK_CLASS) != InputType.TYPE_NULL

        if (hasEditableField) {
            if (!restarting) {
                currentFieldCounter++
            }
            isInputFocused = true
            broadcastCurrentFieldState(eventType = "FOCUS_GAINED", editorInfo = attribute)
        } else {
            if (isInputFocused) {
                isInputFocused = false
                broadcastFocusLost()
            }
        }
    }

    override fun onStartInputView(info: EditorInfo?, restarting: Boolean) {
        super.onStartInputView(info, restarting)
        if (!restarting) {
            currentFieldCounter++
        }
        isInputFocused = true
        updateBannerUi()
        broadcastCurrentFieldState(eventType = "FOCUS_GAINED", editorInfo = info)
    }

    override fun onUpdateSelection(
        oldSelStart: Int,
        oldSelEnd: Int,
        newSelStart: Int,
        newSelEnd: Int,
        candidatesStart: Int,
        candidatesEnd: Int
    ) {
        super.onUpdateSelection(
            oldSelStart,
            oldSelEnd,
            newSelStart,
            newSelEnd,
            candidatesStart,
            candidatesEnd
        )
        // 모바일에서 방금 반영한 변경에 의한 에코는 무시하여 무한 루프 방지
        if (System.currentTimeMillis() - lastRemoteApplyMillis < 300L) return
        if (isInputFocused) {
            broadcastCurrentFieldState(
                eventType = "TEXT_UPDATED",
                editorInfo = currentInputEditorInfo,
                overrideSelStart = newSelStart,
                overrideSelEnd = newSelEnd
            )
        }
    }

    override fun onFinishInputView(finishingInput: Boolean) {
        super.onFinishInputView(finishingInput)
        isInputFocused = false
        broadcastFocusLost()
    }

    /**
     * 현재 포커스된 TV 텍스트 필드의 전체 문자열과 커서 위치, 힌트를 추출하여 모바일로 전송합니다.
     */
    private fun broadcastCurrentFieldState(
        eventType: String,
        editorInfo: EditorInfo? = currentInputEditorInfo,
        overrideSelStart: Int? = null,
        overrideSelEnd: Int? = null
    ) {
        val ic = currentInputConnection ?: return
        val extracted = ic.getExtractedText(ExtractedTextRequest(), 0)
        val text = extracted?.text?.toString().orEmpty()
        val selStart = (overrideSelStart ?: extracted?.selectionStart ?: text.length)
            .coerceIn(0, text.length)
        val selEnd = (overrideSelEnd ?: extracted?.selectionEnd ?: text.length)
            .coerceIn(selStart, text.length)
        val hint = editorInfo?.hintText?.toString()
            ?: editorInfo?.label?.toString()
            ?: ""
        val pkg = editorInfo?.packageName.orEmpty()

        val json = JSONObject().apply {
            put("type", eventType)
            put("text", text)
            put("selStart", selStart)
            put("selEnd", selEnd)
            put("hint", hint)
            put("packageName", pkg)
            put("fieldCounter", currentFieldCounter)
        }
        broadcastJson(json)
    }

    private fun broadcastFocusLost() {
        val json = JSONObject().apply {
            put("type", "FOCUS_LOST")
        }
        broadcastJson(json)
    }

    /**
     * 모바일 앱에서 수신한 JSON 명령을 처리합니다.
     */
    private fun handleClientCommand(json: JSONObject) {
        when (json.optString("type")) {
            "REQUEST_STATE" -> {
                mainHandler.post {
                    if (isInputFocused && currentInputConnection != null) {
                        broadcastCurrentFieldState(eventType = "FOCUS_GAINED")
                    }
                }
            }
            "REPLACE_TEXT" -> {
                val newText = json.optString("text", "")
                val selStart = json.optInt("selStart", newText.length)
                val selEnd = json.optInt("selEnd", newText.length)
                mainHandler.post {
                    applyTextToTvInputConnection(newText, selStart, selEnd)
                }
            }
            "PERFORM_EDITOR_ACTION" -> {
                mainHandler.post {
                    val handled = sendDefaultEditorAction(true)
                    if (!handled) {
                        val action = currentInputEditorInfo?.imeOptions?.and(EditorInfo.IME_MASK_ACTION)
                            ?: EditorInfo.IME_ACTION_DONE
                        currentInputConnection?.performEditorAction(action)
                    }
                }
            }
            "KEY_EVENT" -> {
                val keyCode = json.optInt("keyCode", 0)
                if (keyCode > 0) {
                    mainHandler.post {
                        sendDownUpKeyEvents(keyCode)
                    }
                }
            }
        }
    }

    /**
     * 모바일 텍스트 필드에서 편집한 문자열을 TV의 포커스된 텍스트 필드에 그대로 덮어써서 입력합니다.
     */
    private fun applyTextToTvInputConnection(newText: String, selStart: Int, selEnd: Int) {
        val ic = currentInputConnection ?: return
        lastRemoteApplyMillis = System.currentTimeMillis()

        ic.beginBatchEdit()
        try {
            ic.finishComposingText()
            val extracted = ic.getExtractedText(ExtractedTextRequest(), 0)
            if (extracted?.text != null) {
                val existingLen = extracted.text.length
                ic.setSelection(0, existingLen)
            } else {
                ic.deleteSurroundingText(10_000, 10_000)
            }
            ic.commitText(newText, 1)

            val safeStart = selStart.coerceIn(0, newText.length)
            val safeEnd = selEnd.coerceIn(safeStart, newText.length)
            ic.setSelection(safeStart, safeEnd)
        } finally {
            ic.endBatchEdit()
        }
    }

    private fun startTcpServerAndMdns() {
        serviceScope.launch {
            try {
                val server = try {
                    ServerSocket(PREFERRED_PORT)
                } catch (_: Exception) {
                    ServerSocket(0)
                }
                serverSocket = server
                val boundPort = server.localPort
                registerMdnsService(boundPort)

                while (isActive && !server.isClosed) {
                    val clientSocket = server.accept()
                    handleClientSocket(clientSocket)
                }
            } catch (e: Exception) {
                Log.e(TAG, "TCP server error", e)
            }
        }
    }

    private fun handleClientSocket(socket: Socket) {
        serviceScope.launch {
            val writer = PrintWriter(OutputStreamWriter(socket.getOutputStream(), Charsets.UTF_8), true)
            clients.add(writer)
            mainHandler.post {
                updateBannerUi()
                if (isInputFocused) {
                    broadcastCurrentFieldState(eventType = "FOCUS_GAINED")
                }
            }

            try {
                val reader = BufferedReader(InputStreamReader(socket.getInputStream(), Charsets.UTF_8))
                while (isActive && !socket.isClosed) {
                    val line = reader.readLine() ?: break
                    if (line.isNotBlank()) {
                        handleClientCommand(JSONObject(line))
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Client disconnected: ${e.message}")
            } finally {
                clients.remove(writer)
                try {
                    socket.close()
                } catch (_: Exception) {
                }
                mainHandler.post { updateBannerUi() }
            }
        }
    }

    private fun broadcastJson(json: JSONObject) {
        val payload = json.toString()
        serviceScope.launch {
            clients.forEach { writer ->
                try {
                    writer.println(payload)
                } catch (_: Exception) {
                }
            }
        }
    }

    private fun registerMdnsService(port: Int) {
        try {
            val manager = getSystemService(Context.NSD_SERVICE) as NsdManager
            nsdManager = manager
            val serviceInfo = NsdServiceInfo().apply {
                serviceName = "AndroidTV-IME-${Build.MODEL ?: "TV"}"
                serviceType = SERVICE_TYPE
                setPort(port)
            }
            val listener = object : NsdManager.RegistrationListener {
                override fun onServiceRegistered(info: NsdServiceInfo) {
                    Log.d(TAG, "mDNS registered: ${info.serviceName} on port $port")
                }

                override fun onRegistrationFailed(info: NsdServiceInfo, errorCode: Int) {
                    Log.e(TAG, "mDNS registration failed: $errorCode")
                }

                override fun onServiceUnregistered(info: NsdServiceInfo) {
                    Log.d(TAG, "mDNS unregistered: ${info.serviceName}")
                }

                override fun onUnregistrationFailed(info: NsdServiceInfo, errorCode: Int) {
                    Log.e(TAG, "mDNS unregistration failed: $errorCode")
                }
            }
            registrationListener = listener
            manager.registerService(serviceInfo, NsdManager.PROTOCOL_DNS_SD, listener)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to register mDNS service", e)
        }
    }

    private fun updateBannerUi() {
        statusTextView?.text = buildStatusLabel()
    }

    private fun buildStatusLabel(): String {
        val count = clients.size
        return if (count > 0) {
            "📱 모바일 앱 연결됨 (${count}대) — 스마트폰 텍스트 필드에서 편집하면 바로 입력됩니다"
        } else {
            "📡 mDNS 대기 중 (_tvkbd._tcp) — 모바일 앱에서 이 TV를 선택해 연결하세요"
        }
    }

    private fun dpToPx(dp: Int): Int {
        return TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP,
            dp.toFloat(),
            resources.displayMetrics
        ).toInt()
    }

    override fun onDestroy() {
        super.onDestroy()
        try {
            registrationListener?.let { nsdManager?.unregisterService(it) }
        } catch (_: Exception) {
        }
        try {
            serverSocket?.close()
        } catch (_: Exception) {
        }
        serviceScope.cancel()
    }
}
