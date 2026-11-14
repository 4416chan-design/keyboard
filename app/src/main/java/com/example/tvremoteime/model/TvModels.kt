package com.example.tvremoteime.model

enum class TvProtocolType(val serviceType: String, val displayName: String) {
    ANDROID_TV_REMOTE_V2(
        serviceType = "_androidtvremote2._tcp.",
        displayName = "Android TV OS 기본 프로토콜 (_androidtvremote2._tcp)"
    ),
    CUSTOM_TV_IME(
        serviceType = "_tvkbd._tcp.",
        displayName = "TV 커스텀 키보드 서비스 (_tvkbd._tcp)"
    )
}

data class TvDevice(
    val id: String = "$host:$port:${protocolType.name}",
    val name: String,
    val host: String,
    val port: Int,
    val protocolType: TvProtocolType
)

sealed interface ConnectionState {
    data object Disconnected : ConnectionState
    data class Connecting(val device: TvDevice) : ConnectionState
    data class PairingRequired(val device: TvDevice, val error: String? = null) : ConnectionState
    data class VerifyingPin(val device: TvDevice) : ConnectionState
    data class Connected(val device: TvDevice) : ConnectionState
    data class Error(val message: String) : ConnectionState
}

/**
 * TV 화면에서 리모컨으로 텍스트 입력창(EditText / SearchBox 등)에 포커스했을 때
 * TV로부터 전달받는 텍스트 필드 상태 모델입니다.
 */
data class TvTextFieldState(
    val isFocused: Boolean = false,
    val text: String = "",
    val selectionStart: Int = 0,
    val selectionEnd: Int = 0,
    val hint: String = "",
    val appPackage: String = "",
    val appLabel: String = "",
    val imeCounter: Int = 0,
    val fieldCounter: Int = 0,
    val lastUpdatedFromTvMillis: Long = 0L
)

/**
 * TV 리모컨 제어용 Android KeyEvent 코드 정의
 */
enum class RemoteKey(val keyCode: Int, val label: String) {
    DPAD_UP(19, "위로"),
    DPAD_DOWN(20, "아래로"),
    DPAD_LEFT(21, "왼쪽"),
    DPAD_RIGHT(22, "오른쪽"),
    DPAD_CENTER(23, "선택/포커스"),
    BACK(4, "뒤로"),
    HOME(3, "홈"),
    ENTER(66, "엔터/완료"),
    DEL(67, "백스페이스"),
    SEARCH(84, "검색")
}
