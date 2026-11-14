package com.example.tvremoteime.protocol.atv2

import java.io.ByteArrayOutputStream
import java.io.EOFException
import java.io.InputStream
import java.io.OutputStream

/**
 * Android TV Remote Protocol v2 (`pairingmessage.proto` & `remotemessage.proto`) 전용
 * 경량 Protobuf Wire 인코더/디코더입니다.
 */
object AtvProtoWire {

    private const val WIRE_VARINT = 0
    private const val WIRE_LENGTH_DELIMITED = 2

    // =========================================================================
    // Frame I/O (Varint-prefixed Protobuf messages over TLS stream)
    // =========================================================================

    fun readFrame(input: InputStream): ByteArray {
        var shift = 0
        var length = 0
        while (shift < 32) {
            val b = input.read()
            if (b == -1) throw EOFException("Stream closed while reading frame length")
            length = length or ((b and 0x7F) shl shift)
            if ((b and 0x80) == 0) break
            shift += 7
        }
        if (length < 0 || length > 1024 * 1024) {
            throw IllegalStateException("Invalid frame length: $length")
        }
        val payload = ByteArray(length)
        var offset = 0
        while (offset < length) {
            val read = input.read(payload, offset, length - offset)
            if (read == -1) throw EOFException("Stream closed while reading frame payload")
            offset += read
        }
        return payload
    }

    @Synchronized
    fun writeFrame(output: OutputStream, payload: ByteArray) {
        val header = ByteArrayOutputStream()
        writeRawVarint(header, payload.size.toLong())
        output.write(header.toByteArray())
        output.write(payload)
        output.flush()
    }

    // =========================================================================
    // Pairing Messages (Port 6467)
    // =========================================================================

    fun buildPairingRequest(clientName: String, serviceName: String = "androidtvremote2"): ByteArray {
        val reqBody = buildMessage {
            writeString(1, serviceName)
            writeString(2, clientName)
        }
        return buildMessage {
            writeVarintField(1, 2)   // protocol_version = 2
            writeVarintField(2, 200) // status = STATUS_OK (200)
            writeBytesField(10, reqBody) // pairing_request = 10
        }
    }

    fun buildPairingOption(): ByteArray {
        val encoding = buildMessage {
            writeVarintField(1, 3) // ENCODING_TYPE_HEXADECIMAL = 3
            writeVarintField(2, 6) // symbol_length = 6
        }
        val optionBody = buildMessage {
            writeBytesField(1, encoding) // input_encodings = 1
            writeVarintField(3, 1)       // preferred_role = ROLE_TYPE_INPUT (1)
        }
        return buildMessage {
            writeVarintField(1, 2)
            writeVarintField(2, 200)
            writeBytesField(20, optionBody) // pairing_option = 20
        }
    }

    fun buildPairingConfiguration(): ByteArray {
        val encoding = buildMessage {
            writeVarintField(1, 3) // ENCODING_TYPE_HEXADECIMAL = 3
            writeVarintField(2, 6) // symbol_length = 6
        }
        val configBody = buildMessage {
            writeBytesField(1, encoding) // encoding = 1
            writeVarintField(2, 1)       // client_role = ROLE_TYPE_INPUT (1)
        }
        return buildMessage {
            writeVarintField(1, 2)
            writeVarintField(2, 200)
            writeBytesField(30, configBody) // pairing_configuration = 30
        }
    }

    fun buildPairingSecret(secretDigest: ByteArray): ByteArray {
        val secretBody = buildMessage {
            writeBytesField(1, secretDigest) // secret = 1
        }
        return buildMessage {
            writeVarintField(1, 2)
            writeVarintField(2, 200)
            writeBytesField(40, secretBody) // pairing_secret = 40
        }
    }

    data class ParsedPairingMessage(
        val status: Int = 0,
        val hasRequestAck: Boolean = false,
        val hasOption: Boolean = false,
        val hasConfigAck: Boolean = false,
        val hasSecretAck: Boolean = false
    )

    fun parsePairingMessage(bytes: ByteArray): ParsedPairingMessage {
        val fields = parseFields(bytes)
        return ParsedPairingMessage(
            status = fields.firstVarint(2)?.toInt() ?: 0,
            hasRequestAck = fields.containsKey(11),
            hasOption = fields.containsKey(20),
            hasConfigAck = fields.containsKey(31),
            hasSecretAck = fields.containsKey(41)
        )
    }

    // =========================================================================
    // Remote & IME Messages (Port 6466)
    // =========================================================================

    fun buildRemoteConfigure(
        code: Int = 622,
        model: String = android.os.Build.MODEL ?: "AndroidPhone",
        vendor: String = android.os.Build.MANUFACTURER ?: "Google",
        packageName: String = "com.example.tvremoteime",
        appVersion: String = "1.0.0"
    ): ByteArray {
        val deviceInfo = buildMessage {
            writeString(1, model)
            writeString(2, vendor)
            writeVarintField(3, 1)
            writeString(4, "1")
            writeString(5, packageName)
            writeString(6, appVersion)
        }
        val configure = buildMessage {
            writeVarintField(1, code.toLong())
            writeBytesField(2, deviceInfo)
        }
        return buildMessage {
            writeBytesField(1, configure) // remote_configure = 1
        }
    }

    fun buildRemoteSetActive(activeCode: Int = 622): ByteArray {
        val setActive = buildMessage {
            writeVarintField(1, activeCode.toLong())
        }
        return buildMessage {
            writeBytesField(2, setActive) // remote_set_active = 2
        }
    }

    fun buildRemotePingResponse(val1: Int): ByteArray {
        val pingResp = buildMessage {
            writeVarintField(1, val1.toLong())
        }
        return buildMessage {
            writeBytesField(9, pingResp) // remote_ping_response = 9
        }
    }

    /**
     * direction: 1 = START_LONG, 2 = END_LONG, 3 = SHORT
     */
    fun buildRemoteKeyInject(keyCode: Int, direction: Int = 3): ByteArray {
        val keyInject = buildMessage {
            writeVarintField(1, keyCode.toLong())
            writeVarintField(2, direction.toLong())
        }
        return buildMessage {
            writeBytesField(10, keyInject) // remote_key_inject = 10
        }
    }

    /**
     * 모바일 텍스트 필드에서 편집한 전체 텍스트와 커서 위치를
     * TV의 현재 포커스된 텍스트 입력창에 실시간으로 반영하는 RemoteImeBatchEdit(tag 21) 패킷입니다.
     *
     * structure:
     * message RemoteImeBatchEdit {
     *   int32 ime_counter = 1;
     *   int32 field_counter = 2;
     *   repeated RemoteEditInfo edit_info = 3;
     * }
     * message RemoteEditInfo {
     *   int32 insert = 1;
     *   RemoteImeObject text_field_status = 2;
     * }
     * message RemoteImeObject {
     *   int32 start = 1;
     *   int32 end = 2;
     *   string value = 3;
     * }
     */
    fun buildRemoteImeBatchEdit(
        imeCounter: Int,
        fieldCounter: Int,
        text: String,
        selectionStart: Int = text.length,
        selectionEnd: Int = text.length
    ): ByteArray {
        val endIdx = if (text.isEmpty()) 0 else text.length - 1
        val safeStart = selectionStart.coerceIn(0, text.length)
        val safeEnd = selectionEnd.coerceIn(safeStart, text.length)

        val imeObject = buildMessage {
            // RemoteImeObject: start=0, end=text.length-1 (또는 커서 범위), value=text
            writeVarintField(1, if (text.isEmpty()) 0L else 0L)
            writeVarintField(2, if (text.isEmpty()) 0L else endIdx.toLong())
            writeString(3, text)
        }
        val editInfo = buildMessage {
            writeVarintField(1, 1L) // insert = 1 (batch replace/commit)
            writeBytesField(2, imeObject)
        }
        val batchEdit = buildMessage {
            writeVarintField(1, imeCounter.toLong())
            writeVarintField(2, fieldCounter.toLong())
            writeBytesField(3, editInfo)
        }
        return buildMessage {
            writeBytesField(21, batchEdit) // remote_ime_batch_edit = 21
        }
    }

    data class ParsedTextFieldStatus(
        val fieldCounter: Int,
        val value: String,
        val start: Int,
        val end: Int,
        val label: String
    )

    data class ParsedAppInfo(
        val imeCounter: Int,
        val label: String,
        val appPackage: String
    )

    data class ParsedRemoteMessage(
        val configureCode: Int? = null,
        val setActiveNeeded: Boolean = false,
        val pingRequestVal1: Int? = null,
        val appInfo: ParsedAppInfo? = null,
        val textFieldStatus: ParsedTextFieldStatus? = null,
        val imeShowRequested: Boolean = false,
        val rawError: Int? = null
    )

    fun parseRemoteMessage(bytes: ByteArray): ParsedRemoteMessage {
        val top = parseFields(bytes)

        var configureCode: Int? = null
        var setActiveNeeded = false
        var pingVal1: Int? = null
        var parsedAppInfo: ParsedAppInfo? = null
        var parsedTextStatus: ParsedTextFieldStatus? = null
        var imeShowRequested = false
        var rawError: Int? = null

        // tag 1: remote_configure
        top.firstBytes(1)?.let { confBytes ->
            val confFields = parseFields(confBytes)
            configureCode = confFields.firstVarint(1)?.toInt() ?: 622
        }

        // tag 2: remote_set_active
        if (top.containsKey(2)) {
            setActiveNeeded = true
        }

        // tag 3: remote_error
        top.firstBytes(3)?.let { errBytes ->
            val errFields = parseFields(errBytes)
            rawError = errFields.firstVarint(1)?.toInt()
        }

        // tag 8: remote_ping_request
        top.firstBytes(8)?.let { pingBytes ->
            val pingFields = parseFields(pingBytes)
            pingVal1 = pingFields.firstVarint(1)?.toInt() ?: 0
        }

        // tag 20: remote_ime_key_inject (TV에서 텍스트 필드 포커스 또는 앱 상태 변경 시 수신)
        top.firstBytes(20)?.let { imeBytes ->
            val imeFields = parseFields(imeBytes)
            imeFields.firstBytes(1)?.let { appBytes ->
                val appFields = parseFields(appBytes)
                parsedAppInfo = ParsedAppInfo(
                    imeCounter = appFields.firstVarint(1)?.toInt() ?: 0,
                    label = appFields.firstString(10).orEmpty(),
                    appPackage = appFields.firstString(12).orEmpty()
                )
            }
            imeFields.firstBytes(2)?.let { tfBytes ->
                parsedTextStatus = parseTextFieldStatus(tfBytes)
            }
        }

        // tag 21: remote_ime_batch_edit (TV 측 텍스트 편집 상태 브로드캐스트 시 수신)
        top.firstBytes(21)?.let { batchBytes ->
            val batchFields = parseFields(batchBytes)
            val imeCounter = batchFields.firstVarint(1)?.toInt() ?: 0
            val fieldCounter = batchFields.firstVarint(2)?.toInt() ?: 0
            batchFields.firstBytes(3)?.let { editInfoBytes ->
                val editFields = parseFields(editInfoBytes)
                editFields.firstBytes(2)?.let { objBytes ->
                    val objFields = parseFields(objBytes)
                    val start = objFields.firstVarint(1)?.toInt() ?: 0
                    val end = objFields.firstVarint(2)?.toInt() ?: 0
                    val value = objFields.firstString(3).orEmpty()
                    parsedTextStatus = ParsedTextFieldStatus(
                        fieldCounter = fieldCounter,
                        value = value,
                        start = start,
                        end = end,
                        label = ""
                    )
                    if (parsedAppInfo == null && imeCounter != 0) {
                        parsedAppInfo = ParsedAppInfo(imeCounter = imeCounter, label = "", appPackage = "")
                    }
                }
            }
        }

        // tag 22: remote_ime_show_request (TV에서 키보드/텍스트 입력 표시 요청 시)
        top.firstBytes(22)?.let { showBytes ->
            imeShowRequested = true
            val showFields = parseFields(showBytes)
            showFields.firstBytes(2)?.let { tfBytes ->
                if (parsedTextStatus == null) {
                    parsedTextStatus = parseTextFieldStatus(tfBytes)
                }
            }
        }

        return ParsedRemoteMessage(
            configureCode = configureCode,
            setActiveNeeded = setActiveNeeded,
            pingRequestVal1 = pingVal1,
            appInfo = parsedAppInfo,
            textFieldStatus = parsedTextStatus,
            imeShowRequested = imeShowRequested,
            rawError = rawError
        )
    }

    private fun parseTextFieldStatus(tfBytes: ByteArray): ParsedTextFieldStatus {
        val tfFields = parseFields(tfBytes)
        val counterField = tfFields.firstVarint(1)?.toInt() ?: 0
        val value = tfFields.firstString(2).orEmpty()
        val start = tfFields.firstVarint(3)?.toInt() ?: value.length
        val end = tfFields.firstVarint(4)?.toInt() ?: value.length
        val label = tfFields.firstString(6).orEmpty()
        return ParsedTextFieldStatus(
            fieldCounter = counterField,
            value = value,
            start = start.coerceAtLeast(0),
            end = end.coerceAtLeast(0),
            label = label
        )
    }

    // =========================================================================
    // Low-Level Protobuf Wire Builder & Reader
    // =========================================================================

    private class ProtoBuilder {
        private val out = ByteArrayOutputStream()

        fun writeVarintField(fieldNumber: Int, value: Long) {
            val tag = (fieldNumber shl 3) or WIRE_VARINT
            writeRawVarint(out, tag.toLong())
            writeRawVarint(out, value)
        }

        fun writeBytesField(fieldNumber: Int, bytes: ByteArray) {
            val tag = (fieldNumber shl 3) or WIRE_LENGTH_DELIMITED
            writeRawVarint(out, tag.toLong())
            writeRawVarint(out, bytes.size.toLong())
            out.write(bytes)
        }

        fun writeString(fieldNumber: Int, text: String) {
            writeBytesField(fieldNumber, text.toByteArray(Charsets.UTF_8))
        }

        fun toByteArray(): ByteArray = out.toByteArray()
    }

    private fun buildMessage(block: ProtoBuilder.() -> Unit): ByteArray {
        return ProtoBuilder().apply(block).toByteArray()
    }

    private fun writeRawVarint(out: ByteArrayOutputStream, value: Long) {
        var v = value
        while (true) {
            if ((v and 0x7FL.inv()) == 0L) {
                out.write(v.toInt())
                return
            } else {
                out.write((v.toInt() and 0x7F) or 0x80)
                v = v ushr 7
            }
        }
    }

    private sealed interface ProtoValue {
        data class Varint(val value: Long) : ProtoValue
        data class Bytes(val value: ByteArray) : ProtoValue
    }

    private fun parseFields(data: ByteArray): Map<Int, List<ProtoValue>> {
        val result = mutableMapOf<Int, MutableList<ProtoValue>>()
        var pos = 0

        fun readVarint(): Long {
            var shift = 0
            var res = 0L
            while (pos < data.size && shift < 64) {
                val b = data[pos++].toInt() and 0xFF
                res = res or ((b.toLong() and 0x7FL) shl shift)
                if ((b and 0x80) == 0) return res
                shift += 7
            }
            return res
        }

        while (pos < data.size) {
            val tag = readVarint().toInt()
            if (tag == 0) break
            val fieldNumber = tag ushr 3
            when (val wireType = tag and 0x07) {
                WIRE_VARINT -> {
                    val v = readVarint()
                    result.getOrPut(fieldNumber) { mutableListOf() }.add(ProtoValue.Varint(v))
                }
                1 -> { // 64-bit fixed
                    pos = (pos + 8).coerceAtMost(data.size)
                }
                WIRE_LENGTH_DELIMITED -> {
                    val len = readVarint().toInt()
                    if (len < 0 || pos + len > data.size) break
                    val slice = data.copyOfRange(pos, pos + len)
                    pos += len
                    result.getOrPut(fieldNumber) { mutableListOf() }.add(ProtoValue.Bytes(slice))
                }
                5 -> { // 32-bit fixed
                    pos = (pos + 4).coerceAtMost(data.size)
                }
                else -> {
                    // 알 수 없는 wireType이면 중단
                    break
                }
            }
        }
        return result
    }

    private fun Map<Int, List<ProtoValue>>.firstVarint(field: Int): Long? =
        (this[field]?.firstOrNull() as? ProtoValue.Varint)?.value

    private fun Map<Int, List<ProtoValue>>.firstBytes(field: Int): ByteArray? =
        (this[field]?.firstOrNull() as? ProtoValue.Bytes)?.value

    private fun Map<Int, List<ProtoValue>>.firstString(field: Int): String? =
        firstBytes(field)?.toString(Charsets.UTF_8)
}
