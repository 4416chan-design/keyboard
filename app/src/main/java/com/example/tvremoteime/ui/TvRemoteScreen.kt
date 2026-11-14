package com.example.tvremoteime.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Backspace
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Keyboard
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowLeft
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.LinkOff
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Tv
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import com.example.tvremoteime.model.ConnectionState
import com.example.tvremoteime.model.RemoteKey
import com.example.tvremoteime.model.TvDevice
import com.example.tvremoteime.model.TvProtocolType
import com.example.tvremoteime.model.TvTextFieldState

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun TvRemoteScreen(viewModel: TvRemoteViewModel) {
    val devices by viewModel.discoveredDevices.collectAsState()
    val isScanning by viewModel.isScanning.collectAsState()
    val connectionState by viewModel.connectionState.collectAsState()
    val tvTextFieldState by viewModel.tvTextFieldState.collectAsState()
    val mobileTextFieldValue by viewModel.mobileTextFieldValue.collectAsState()
    val focusTriggerEvent by viewModel.focusTriggerEvent.collectAsState()
    val liveSyncEnabled by viewModel.liveSyncEnabled.collectAsState()

    val focusRequester = remember { FocusRequester() }
    val keyboardController = LocalSoftwareKeyboardController.current
    var showManualDialog by rememberSaveable { mutableStateOf(false) }

    // TV에서 리모컨으로 텍스트 인풋 포커스 시 모바일 텍스트 필드로 자동 포커스 & 소프트 키보드 표시
    LaunchedEffect(focusTriggerEvent) {
        if (focusTriggerEvent > 0L) {
            try {
                focusRequester.requestFocus()
                keyboardController?.show()
            } catch (_: Exception) {
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Default.Tv,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "TV 텍스트 싱크 리모컨",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                    }
                },
                actions = {
                    IconButton(onClick = { showManualDialog = true }) {
                        Icon(Icons.Default.Add, contentDescription = "IP 직접 연결")
                    }
                    IconButton(
                        onClick = {
                            viewModel.stopMdnsDiscovery()
                            viewModel.startMdnsDiscovery()
                        }
                    ) {
                        Icon(Icons.Default.Refresh, contentDescription = "mDNS 재탐색")
                    }
                }
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            // 1. mDNS TV 탐색 및 연결 상태 카드
            MdnsConnectionCard(
                devices = devices,
                isScanning = isScanning,
                connectionState = connectionState,
                onConnectDevice = viewModel::connectToDevice,
                onDisconnect = viewModel::disconnect,
                onOpenManualIp = { showManualDialog = true },
                onSimulateFocus = { viewModel.simulateTvTextFieldFocus() }
            )

            // 2. 핵심 영역: TV 텍스트 인풋 포커스 연동 & 모바일 실시간 텍스트 편집 필드
            MobileTextSyncCard(
                tvState = tvTextFieldState,
                mobileValue = mobileTextFieldValue,
                liveSyncEnabled = liveSyncEnabled,
                focusRequester = focusRequester,
                onValueChange = viewModel::onMobileTextChanged,
                onToggleLiveSync = viewModel::toggleLiveSync,
                onClearText = viewModel::clearText,
                onSendEnter = viewModel::sendEnterOrEditorAction,
                onManualPush = { viewModel.pushMobileTextToTv() }
            )

            // 3. 리모컨 D-Pad 카드 (TV 화면에서 입력창으로 이동해 포커스할 때 사용)
            RemoteDpadCard(
                onSendKey = viewModel::sendRemoteKey
            )
        }
    }

    // PIN 페어링 다이얼로그 (_androidtvremote2._tcp 최초 연결 시)
    val pairingState = connectionState
    if (pairingState is ConnectionState.PairingRequired || pairingState is ConnectionState.VerifyingPin) {
        val device = when (pairingState) {
            is ConnectionState.PairingRequired -> pairingState.device
            is ConnectionState.VerifyingPin -> pairingState.device
            else -> null
        }
        val errorMsg = (pairingState as? ConnectionState.PairingRequired)?.error
        val isVerifying = pairingState is ConnectionState.VerifyingPin

        if (device != null) {
            PairingPinDialog(
                deviceName = device.name,
                errorMessage = errorMsg,
                isVerifying = isVerifying,
                onSubmitPin = viewModel::submitPairingPin,
                onCancel = viewModel::disconnect
            )
        }
    }

    if (showManualDialog) {
        ManualIpConnectDialog(
            onDismiss = { showManualDialog = false },
            onConnect = { name, host, port, protocol ->
                showManualDialog = false
                viewModel.connectManualIp(name, host, port, protocol)
            }
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun MdnsConnectionCard(
    devices: List<TvDevice>,
    isScanning: Boolean,
    connectionState: ConnectionState,
    onConnectDevice: (TvDevice) -> Unit,
    onDisconnect: () -> Unit,
    onOpenManualIp: () -> Unit,
    onSimulateFocus: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f)
        )
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = "1. mDNS TV 연결",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold
                    )
                    if (isScanning) {
                        Spacer(modifier = Modifier.width(8.dp))
                        CircularProgressIndicator(
                            modifier = Modifier.size(14.dp),
                            strokeWidth = 2.dp
                        )
                    }
                }

                ConnectionBadge(connectionState = connectionState)
            }

            if (connectionState is ConnectionState.Error) {
                Text(
                    text = connectionState.message,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error
                )
            }

            if (devices.isEmpty()) {
                Text(
                    text = "같은 Wi-Fi의 Android TV (_androidtvremote2._tcp / _tvkbd._tcp)를 자동 검색 중입니다...",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    devices.forEach { device ->
                        val isConnected = (connectionState as? ConnectionState.Connected)?.device?.id == device.id
                        Surface(
                            onClick = { if (!isConnected) onConnectDevice(device) },
                            shape = RoundedCornerShape(10.dp),
                            border = BorderStroke(
                                width = 1.dp,
                                color = if (isConnected) {
                                    MaterialTheme.colorScheme.primary
                                } else {
                                    MaterialTheme.colorScheme.outlineVariant
                                }
                            ),
                            color = if (isConnected) {
                                MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.45f)
                            } else {
                                MaterialTheme.colorScheme.surface
                            }
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 12.dp, vertical = 10.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = device.name,
                                        style = MaterialTheme.typography.bodyMedium,
                                        fontWeight = FontWeight.SemiBold
                                    )
                                    Text(
                                        text = "${device.host}:${device.port} • ${
                                            if (device.protocolType == TvProtocolType.ANDROID_TV_REMOTE_V2)
                                                "기본 Android TV OS"
                                            else
                                                "커스텀 TV IME"
                                        }",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                                if (isConnected) {
                                    OutlinedButton(onClick = onDisconnect) {
                                        Icon(
                                            Icons.Default.LinkOff,
                                            contentDescription = null,
                                            modifier = Modifier.size(16.dp)
                                        )
                                        Spacer(modifier = Modifier.width(4.dp))
                                        Text("연결 해제")
                                    }
                                } else {
                                    FilledTonalButton(onClick = { onConnectDevice(device) }) {
                                        Text("연결")
                                    }
                                }
                            }
                        }
                    }
                }
            }

            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                AssistChip(
                    onClick = onOpenManualIp,
                    label = { Text("IP 수동 입력") },
                    leadingIcon = {
                        Icon(
                            Icons.Default.Add,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp)
                        )
                    }
                )
                AssistChip(
                    onClick = onSimulateFocus,
                    label = { Text("TV 텍스트 포커스 테스트") },
                    leadingIcon = {
                        Icon(
                            Icons.Default.Keyboard,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp)
                        )
                    }
                )
            }
        }
    }
}

@Composable
private fun ConnectionBadge(connectionState: ConnectionState) {
    val (label, color) = when (connectionState) {
        is ConnectionState.Disconnected -> "미연결" to MaterialTheme.colorScheme.outline
        is ConnectionState.Connecting -> "연결 중..." to MaterialTheme.colorScheme.tertiary
        is ConnectionState.PairingRequired -> "PIN 입력 대기" to MaterialTheme.colorScheme.tertiary
        is ConnectionState.VerifyingPin -> "PIN 확인 중..." to MaterialTheme.colorScheme.tertiary
        is ConnectionState.Connected -> "연결됨 (${connectionState.device.host})" to MaterialTheme.colorScheme.primary
        is ConnectionState.Error -> "오류" to MaterialTheme.colorScheme.error
    }

    Surface(
        color = color.copy(alpha = 0.14f),
        shape = RoundedCornerShape(50),
        border = BorderStroke(1.dp, color.copy(alpha = 0.5f))
    ) {
        Text(
            text = label,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
            style = MaterialTheme.typography.labelSmall,
            color = color,
            fontWeight = FontWeight.SemiBold
        )
    }
}

@Composable
private fun MobileTextSyncCard(
    tvState: TvTextFieldState,
    mobileValue: TextFieldValue,
    liveSyncEnabled: Boolean,
    focusRequester: FocusRequester,
    onValueChange: (TextFieldValue) -> Unit,
    onToggleLiveSync: (Boolean) -> Unit,
    onClearText: () -> Unit,
    onSendEnter: () -> Unit,
    onManualPush: () -> Unit
) {
    val borderColor by animateColorAsState(
        targetValue = if (tvState.isFocused) {
            MaterialTheme.colorScheme.primary
        } else {
            MaterialTheme.colorScheme.outlineVariant
        },
        label = "focusBorder"
    )

    Card(
        modifier = Modifier.fillMaxWidth(),
        border = BorderStroke(if (tvState.isFocused) 2.dp else 1.dp, borderColor),
        colors = CardDefaults.cardColors(
            containerColor = if (tvState.isFocused) {
                MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.22f)
            } else {
                MaterialTheme.colorScheme.surface
            }
        )
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = if (tvState.isFocused) {
                            "🟢 TV 텍스트 인풋 포커스 감지됨!"
                        } else {
                            "2. 모바일 실시간 텍스트 입력창"
                        },
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        color = if (tvState.isFocused) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.onSurface
                        }
                    )
                    val subTitle = buildString {
                        if (tvState.appLabel.isNotBlank() || tvState.appPackage.isNotBlank()) {
                            append("TV 앱: ")
                            append(tvState.appLabel.ifBlank { tvState.appPackage })
                        } else if (tvState.isFocused) {
                            append("TV 입력창의 텍스트가 아래에 표시되며 수정 시 즉시 TV에 입력됩니다.")
                        } else {
                            append("리모컨으로 TV의 텍스트 입력창을 선택하면 자동으로 활성화됩니다.")
                        }
                    }
                    Text(
                        text = subTitle,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = "실시간 반영",
                        style = MaterialTheme.typography.labelSmall
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Switch(
                        checked = liveSyncEnabled,
                        onCheckedChange = onToggleLiveSync
                    )
                }
            }

            // 모바일 화면 텍스트 필드 (TV의 현재 텍스트를 그대로 보여주고 편집 시 실시간 전송)
            OutlinedTextField(
                value = mobileValue,
                onValueChange = onValueChange,
                modifier = Modifier
                    .fillMaxWidth()
                    .focusRequester(focusRequester),
                label = {
                    Text(
                        if (tvState.hint.isNotBlank()) {
                            "TV 입력창 (${tvState.hint})"
                        } else {
                            "TV 텍스트 필드 내용 (편집 시 TV에 그대로 입력)"
                        }
                    )
                },
                placeholder = {
                    Text("TV에서 텍스트 인풋 포커스 시 기존 텍스트가 여기에 표시됩니다")
                },
                trailingIcon = {
                    if (mobileValue.text.isNotEmpty()) {
                        IconButton(onClick = onClearText) {
                            Icon(Icons.Default.Clear, contentDescription = "텍스트 전체 지우기")
                        }
                    }
                },
                keyboardOptions = KeyboardOptions(
                    capitalization = KeyboardCapitalization.None,
                    imeAction = ImeAction.Search
                ),
                keyboardActions = KeyboardActions(
                    onSearch = { onSendEnter() },
                    onDone = { onSendEnter() }
                ),
                minLines = 2,
                maxLines = 4
            )

            // TV 측에 반영된 현재 텍스트 상태 미리보기 및 완료/전송 버튼
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "TV 현재 텍스트: \"${tvState.text}\"",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f)
                )

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    AnimatedVisibility(visible = !liveSyncEnabled) {
                        FilledTonalButton(onClick = onManualPush) {
                            Text("TV에 반영")
                        }
                    }
                    Button(onClick = onSendEnter) {
                        Icon(
                            Icons.AutoMirrored.Filled.Send,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("완료 / 검색")
                    }
                }
            }
        }
    }
}

@Composable
private fun RemoteDpadCard(
    onSendKey: (RemoteKey) -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text(
                text = "3. TV 리모컨 (텍스트 입력창으로 포커스 이동)",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.align(Alignment.Start)
            )

            // 상단 보조 버튼: 뒤로, 홈, 검색, 백스페이스
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                FilledTonalButton(onClick = { onSendKey(RemoteKey.BACK) }) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("뒤로")
                }
                FilledTonalButton(onClick = { onSendKey(RemoteKey.HOME) }) {
                    Icon(Icons.Default.Home, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("홈")
                }
                FilledTonalButton(onClick = { onSendKey(RemoteKey.SEARCH) }) {
                    Icon(Icons.Default.Search, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("검색창")
                }
                FilledTonalButton(onClick = { onSendKey(RemoteKey.DEL) }) {
                    Icon(Icons.AutoMirrored.Filled.Backspace, contentDescription = null, modifier = Modifier.size(16.dp))
                }
            }

            HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))

            // 십자 방향키 (D-Pad) + 중앙 확인(포커스) 버튼
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                FilledIconButton(
                    onClick = { onSendKey(RemoteKey.DPAD_UP) },
                    modifier = Modifier.size(52.dp),
                    shape = CircleShape
                ) {
                    Icon(Icons.Default.KeyboardArrowUp, contentDescription = "위로")
                }

                Row(
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    FilledIconButton(
                        onClick = { onSendKey(RemoteKey.DPAD_LEFT) },
                        modifier = Modifier.size(52.dp),
                        shape = CircleShape
                    ) {
                        Icon(Icons.Default.KeyboardArrowLeft, contentDescription = "왼쪽")
                    }

                    Button(
                        onClick = { onSendKey(RemoteKey.DPAD_CENTER) },
                        modifier = Modifier.size(72.dp),
                        shape = CircleShape
                    ) {
                        Text("OK", fontWeight = FontWeight.Bold)
                    }

                    FilledIconButton(
                        onClick = { onSendKey(RemoteKey.DPAD_RIGHT) },
                        modifier = Modifier.size(52.dp),
                        shape = CircleShape
                    ) {
                        Icon(Icons.Default.KeyboardArrowRight, contentDescription = "오른쪽")
                    }
                }

                FilledIconButton(
                    onClick = { onSendKey(RemoteKey.DPAD_DOWN) },
                    modifier = Modifier.size(52.dp),
                    shape = CircleShape
                ) {
                    Icon(Icons.Default.KeyboardArrowDown, contentDescription = "아래로")
                }
            }
        }
    }
}

@Composable
private fun PairingPinDialog(
    deviceName: String,
    errorMessage: String?,
    isVerifying: Boolean,
    onSubmitPin: (String) -> Unit,
    onCancel: () -> Unit
) {
    var pin by rememberSaveable { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = { if (!isVerifying) onCancel() },
        title = { Text("TV 블루투스/원격 PIN 페어링") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = "[$deviceName] TV 화면에 표시된 6자리 16진수(0-9, A-F) 코드를 입력해주세요.",
                    style = MaterialTheme.typography.bodyMedium
                )
                if (!errorMessage.isNullOrBlank()) {
                    Text(
                        text = errorMessage,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                }
                OutlinedTextField(
                    value = pin,
                    onValueChange = { input ->
                        val filtered = input.uppercase().filter { it in '0'..'9' || it in 'A'..'F' }.take(6)
                        pin = filtered
                    },
                    label = { Text("6자리 PIN 코드 (예: A1B2C3)") },
                    singleLine = true,
                    enabled = !isVerifying,
                    textStyle = MaterialTheme.typography.titleLarge.copy(
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold
                    )
                )
                if (isVerifying) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("PIN 코드 검증 중...", style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = { onSubmitPin(pin) },
                enabled = pin.length == 6 && !isVerifying
            ) {
                Text("페어링 확인")
            }
        },
        dismissButton = {
            TextButton(onClick = onCancel, enabled = !isVerifying) {
                Text("취소")
            }
        }
    )
}

@Composable
private fun ManualIpConnectDialog(
    onDismiss: () -> Unit,
    onConnect: (name: String, host: String, port: Int, protocol: TvProtocolType) -> Unit
) {
    var name by rememberSaveable { mutableStateOf("거실 Android TV") }
    var host by rememberSaveable { mutableStateOf("192.168.0.") }
    var selectedProtocol by rememberSaveable { mutableStateOf(TvProtocolType.ANDROID_TV_REMOTE_V2) }
    var portText by rememberSaveable { mutableStateOf("6466") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("TV IP 주소 직접 연결") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("TV 별칭") },
                    singleLine = true
                )
                OutlinedTextField(
                    value = host,
                    onValueChange = { host = it },
                    label = { Text("TV IP 주소 (예: 192.168.0.50)") },
                    singleLine = true
                )
                Text(
                    text = "연결 프로토콜 선택",
                    style = MaterialTheme.typography.labelMedium
                )
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    FilterChip(
                        selected = selectedProtocol == TvProtocolType.ANDROID_TV_REMOTE_V2,
                        onClick = {
                            selectedProtocol = TvProtocolType.ANDROID_TV_REMOTE_V2
                            portText = "6466"
                        },
                        label = { Text("기본 Android TV OS (포트 6466)") }
                    )
                    FilterChip(
                        selected = selectedProtocol == TvProtocolType.CUSTOM_TV_IME,
                        onClick = {
                            selectedProtocol = TvProtocolType.CUSTOM_TV_IME
                            portText = "9466"
                        },
                        label = { Text("TV 커스텀 키보드 서비스 (:tv-ime, 포트 9466)") }
                    )
                }
                OutlinedTextField(
                    value = portText,
                    onValueChange = { portText = it.filter(Char::isDigit) },
                    label = { Text("포트 번호") },
                    singleLine = true
                )
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val port = portText.toIntOrNull()
                        ?: if (selectedProtocol == TvProtocolType.ANDROID_TV_REMOTE_V2) 6466 else 9466
                    onConnect(name, host, port, selectedProtocol)
                },
                enabled = host.isNotBlank()
            ) {
                Text("연결")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("닫기")
            }
        }
    )
}
