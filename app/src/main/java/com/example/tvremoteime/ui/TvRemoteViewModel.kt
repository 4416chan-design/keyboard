package com.example.tvremoteime.ui

import android.app.Application
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.tvremoteime.discovery.TvMdnsDiscoveryManager
import com.example.tvremoteime.model.ConnectionState
import com.example.tvremoteime.model.RemoteKey
import com.example.tvremoteime.model.TvDevice
import com.example.tvremoteime.model.TvProtocolType
import com.example.tvremoteime.model.TvTextFieldState
import com.example.tvremoteime.protocol.atv2.AtvPairingClient
import com.example.tvremoteime.protocol.atv2.AtvRemoteClient
import com.example.tvremoteime.protocol.atv2.CertManager
import com.example.tvremoteime.protocol.customime.CustomImeClient
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class TvRemoteViewModel(application: Application) : AndroidViewModel(application) {

    private val discoveryManager = TvMdnsDiscoveryManager(application)
    private val certManager = CertManager(application)
    private val pairingClient = AtvPairingClient(certManager)

    private var atvRemoteClient: AtvRemoteClient? = null
    private var customImeClient: CustomImeClient? = null

    val discoveredDevices: StateFlow<List<TvDevice>> = discoveryManager.devices
    val isScanning: StateFlow<Boolean> = discoveryManager.isScanning

    private val _connectionState = MutableStateFlow<ConnectionState>(ConnectionState.Disconnected)
    val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()

    private val _tvTextFieldState = MutableStateFlow(TvTextFieldState())
    val tvTextFieldState: StateFlow<TvTextFieldState> = _tvTextFieldState.asStateFlow()

    // 모바일 화면의 텍스트 필드에 바인딩되는 상태 (한글 자모 조합 유지를 위해 TextFieldValue 사용)
    private val _mobileTextFieldValue = MutableStateFlow(TextFieldValue(""))
    val mobileTextFieldValue: StateFlow<TextFieldValue> = _mobileTextFieldValue.asStateFlow()

    // TV에서 새로운 텍스트 인풋 포커스가 발생했을 때 모바일 키보드 자동 포커스를 트리거하는 카운터
    private val _focusTriggerEvent = MutableStateFlow(0L)
    val focusTriggerEvent: StateFlow<Long> = _focusTriggerEvent.asStateFlow()

    // 실시간 동기화 여부 (켜져 있으면 타이핑 즉시 TV에 입력됨)
    private val _liveSyncEnabled = MutableStateFlow(true)
    val liveSyncEnabled: StateFlow<Boolean> = _liveSyncEnabled.asStateFlow()

    private var lastMobileEditMillis: Long = 0L
    private var lastSyncedFieldCounter: Int = -1
    private var wasFocusedOnTv: Boolean = false

    init {
        startMdnsDiscovery()
    }

    fun startMdnsDiscovery() {
        discoveryManager.startDiscovery()
    }

    fun stopMdnsDiscovery() {
        discoveryManager.stopDiscovery()
    }

    fun toggleLiveSync(enabled: Boolean) {
        _liveSyncEnabled.value = enabled
        if (enabled && _tvTextFieldState.value.isFocused) {
            pushMobileTextToTv(_mobileTextFieldValue.value)
        }
    }

    fun connectToDevice(device: TvDevice) {
        viewModelScope.launch {
            disconnectCurrentClient()
            _connectionState.value = ConnectionState.Connecting(device)

            when (device.protocolType) {
                TvProtocolType.ANDROID_TV_REMOTE_V2 -> connectAtv2(device)
                TvProtocolType.CUSTOM_TV_IME -> connectCustomIme(device)
            }
        }
    }

    fun connectManualIp(
        name: String,
        host: String,
        port: Int,
        protocolType: TvProtocolType
    ) {
        val device = discoveryManager.addManualDevice(name, host, port, protocolType)
        connectToDevice(device)
    }

    private suspend fun connectAtv2(device: TvDevice) {
        if (!certManager.isHostPaired(device.host)) {
            startPairingFlow(device)
            return
        }

        val client = AtvRemoteClient(
            certManager = certManager,
            onDisconnected = { reason, needsPairing ->
                viewModelScope.launch {
                    if (needsPairing) {
                        startPairingFlow(device, reason)
                    } else {
                        _connectionState.value = ConnectionState.Error(reason)
                        _tvTextFieldState.value = TvTextFieldState()
                    }
                }
            }
        )
        atvRemoteClient = client

        // TV 측 텍스트 필드 포커스 및 텍스트 상태 구독
        viewModelScope.launch {
            client.tvTextFieldState.collect { tvState ->
                onTvStateReceived(tvState)
            }
        }

        val result = client.connect(device.host, device.port)
        if (result.isSuccess) {
            _connectionState.value = ConnectionState.Connected(device)
        } else {
            // 연결 실패 시 페어링이 안 된 상태일 수 있으므로 6467 페어링 시도
            startPairingFlow(device, "기존 인증서로 연결할 수 없어 PIN 페어링을 시작합니다.")
        }
    }

    private suspend fun startPairingFlow(device: TvDevice, message: String? = null) {
        _connectionState.value = ConnectionState.Connecting(device)
        val res = pairingClient.startPairing(device.host, AtvPairingClient.DEFAULT_PAIRING_PORT)
        if (res.isSuccess) {
            _connectionState.value = ConnectionState.PairingRequired(device, message)
        } else {
            _connectionState.value = ConnectionState.Error(
                "TV 페어링 포트(6467)에 연결할 수 없습니다: ${res.exceptionOrNull()?.message ?: "연결 실패"}"
            )
        }
    }

    fun submitPairingPin(pin: String) {
        val currentState = _connectionState.value
        val device = when (currentState) {
            is ConnectionState.PairingRequired -> currentState.device
            is ConnectionState.VerifyingPin -> currentState.device
            else -> return
        }

        viewModelScope.launch {
            _connectionState.value = ConnectionState.VerifyingPin(device)
            val result = pairingClient.verifyPin(pin)
            if (result.isSuccess) {
                // 페어링 완료 후 즉시 6466 원격/IME 채널에 연결
                connectAtv2(device)
            } else {
                // 실패 시 다시 페어링 세션 오픈
                startPairingFlow(
                    device,
                    result.exceptionOrNull()?.message ?: "PIN 검증에 실패했습니다. TV의 새 PIN을 입력해주세요."
                )
            }
        }
    }

    private suspend fun connectCustomIme(device: TvDevice) {
        val client = CustomImeClient(
            onDisconnected = { reason ->
                viewModelScope.launch {
                    _connectionState.value = ConnectionState.Error(reason)
                    _tvTextFieldState.value = TvTextFieldState()
                }
            }
        )
        customImeClient = client

        viewModelScope.launch {
            client.tvTextFieldState.collect { tvState ->
                onTvStateReceived(tvState)
            }
        }

        val result = client.connect(device.host, device.port)
        if (result.isSuccess) {
            _connectionState.value = ConnectionState.Connected(device)
        } else {
            _connectionState.value = ConnectionState.Error(
                "TV 커스텀 키보드 서비스(${device.host}:${device.port})에 연결할 수 없습니다: ${result.exceptionOrNull()?.message}"
            )
        }
    }

    /**
     * TV에서 리모컨으로 텍스트 인풋 포커스 시(또는 TV 내 텍스트 변경 시) 호출됩니다.
     */
    private fun onTvStateReceived(tvState: TvTextFieldState) {
        val newlyFocused = tvState.isFocused && (!wasFocusedOnTv || tvState.fieldCounter != lastSyncedFieldCounter)
        wasFocusedOnTv = tvState.isFocused
        lastSyncedFieldCounter = tvState.fieldCounter
        _tvTextFieldState.value = tvState

        if (!tvState.isFocused) return

        val now = System.currentTimeMillis()
        val isUserActivelyTyping = (now - lastMobileEditMillis) < 600L

        // 1) 새로운 텍스트 필드에 포커스가 갔거나,
        // 2) 사용자가 모바일에서 조합 타이핑 중이 아닐 때 TV의 텍스트를 모바일 입력창에 그대로 표시합니다.
        if (newlyFocused || (!isUserActivelyTyping && tvState.text != _mobileTextFieldValue.value.text)) {
            val safeStart = tvState.selectionStart.coerceIn(0, tvState.text.length)
            val safeEnd = tvState.selectionEnd.coerceIn(safeStart, tvState.text.length)
            _mobileTextFieldValue.value = TextFieldValue(
                text = tvState.text,
                selection = TextRange(safeStart, safeEnd)
            )
        }

        if (newlyFocused && tvState.lastUpdatedFromTvMillis > 0L) {
            _focusTriggerEvent.value = tvState.lastUpdatedFromTvMillis
        }
    }

    /**
     * 모바일 화면 텍스트 필드에서 텍스트를 편집할 때 호출됩니다.
     */
    fun onMobileTextChanged(newValue: TextFieldValue) {
        val previousText = _mobileTextFieldValue.value.text
        _mobileTextFieldValue.value = newValue

        if (newValue.text != previousText) {
            lastMobileEditMillis = System.currentTimeMillis()
            if (_liveSyncEnabled.value) {
                pushMobileTextToTv(newValue)
            }
        }
    }

    /**
     * 현재 모바일 텍스트 필드의 내용을 즉시 TV로 전송합니다.
     */
    fun pushMobileTextToTv(value: TextFieldValue = _mobileTextFieldValue.value) {
        atvRemoteClient?.sendTextBatchEdit(
            newText = value.text,
            selectionStart = value.selection.start,
            selectionEnd = value.selection.end
        )
        customImeClient?.sendReplaceText(
            newText = value.text,
            selectionStart = value.selection.start,
            selectionEnd = value.selection.end
        )
    }

    /**
     * 모바일 텍스트 필드와 TV 텍스트 필드의 내용을 모두 비웁니다.
     */
    fun clearText() {
        val empty = TextFieldValue("", TextRange.Zero)
        _mobileTextFieldValue.value = empty
        lastMobileEditMillis = System.currentTimeMillis()
        pushMobileTextToTv(empty)
    }

    /**
     * TV에서 검색/엔터(완료) 동작을 수행합니다.
     */
    fun sendEnterOrEditorAction() {
        // 먼저 현재 텍스트를 동기화한 뒤 엔터/액션 전송
        pushMobileTextToTv()
        atvRemoteClient?.sendKeyCode(RemoteKey.ENTER.keyCode)
        customImeClient?.sendImeAction()
    }

    /**
     * 앱 내 리모컨 버튼(방향키, 확인, 뒤로, 홈 등)으로 TV 포커스를 이동합니다.
     */
    fun sendRemoteKey(key: RemoteKey) {
        atvRemoteClient?.sendKeyCode(key.keyCode)
        customImeClient?.sendKeyCode(key.keyCode)
    }

    /**
     * 실제 TV가 없을 때에도 "리모컨으로 TV 텍스트 인풋 포커스 -> 모바일 텍스트 필드 표시 & 편집 동기화" 플로우를
     * 즉시 확인해볼 수 있는 시뮬레이션 트리거입니다.
     */
    fun simulateTvTextFieldFocus(sampleText: String = "유튜브 검색어 테스트") {
        val now = System.currentTimeMillis()
        val nextCounter = (_tvTextFieldState.value.fieldCounter + 1)
        onTvStateReceived(
            TvTextFieldState(
                isFocused = true,
                text = sampleText,
                selectionStart = sampleText.length,
                selectionEnd = sampleText.length,
                hint = "검색어를 입력하세요 (TV 텍스트 필드)",
                appPackage = "com.google.android.youtube.tv",
                appLabel = "YouTube TV",
                imeCounter = 1,
                fieldCounter = nextCounter,
                lastUpdatedFromTvMillis = now
            )
        )
    }

    fun disconnect() {
        disconnectCurrentClient()
        _connectionState.value = ConnectionState.Disconnected
        _tvTextFieldState.value = TvTextFieldState()
    }

    private fun disconnectCurrentClient() {
        pairingClient.close()
        atvRemoteClient?.disconnect()
        atvRemoteClient = null
        customImeClient?.disconnect()
        customImeClient = null
        wasFocusedOnTv = false
        lastSyncedFieldCounter = -1
    }

    override fun onCleared() {
        super.onCleared()
        stopMdnsDiscovery()
        disconnectCurrentClient()
    }
}
