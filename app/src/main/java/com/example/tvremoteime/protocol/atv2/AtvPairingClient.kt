package com.example.tvremoteime.protocol.atv2

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.net.ssl.SSLSocket

/**
 * Android TV Remote Protocol v2 포트 6467 페어링 핸드셰이크 클라이언트입니다.
 * 1) startPairing() 호출 시 TV 화면에 6자리 16진수(HEX) PIN 코드가 표시됩니다.
 * 2) verifyPin(pin) 호출 시 SHA-256 서명을 전송하고 인증을 완료합니다.
 */
class AtvPairingClient(private val certManager: CertManager) {

    companion object {
        private const val TAG = "AtvPairingClient"
        const val DEFAULT_PAIRING_PORT = 6467
    }

    private var socket: SSLSocket? = null
    private var currentHost: String? = null

    suspend fun startPairing(host: String, port: Int = DEFAULT_PAIRING_PORT): Result<Unit> =
        withContext(Dispatchers.IO) {
            runCatching {
                close()
                currentHost = host
                val sslSocket = certManager.createSslSocket(host, port)
                socket = sslSocket
                val input = sslSocket.inputStream
                val output = sslSocket.outputStream

                // Step 1: PairingRequest 전송
                AtvProtoWire.writeFrame(
                    output,
                    AtvProtoWire.buildPairingRequest(clientName = "TV 텍스트 싱크 리모컨")
                )
                val ack1 = AtvProtoWire.parsePairingMessage(AtvProtoWire.readFrame(input))
                if (ack1.status != 200) {
                    throw IllegalStateException("PairingRequest 거부됨 (status=${ack1.status})")
                }

                // 서버가 PairingOption을 먼저 보낼 수도 있고 클라이언트가 보낸 뒤 응답할 수도 있음
                AtvProtoWire.writeFrame(output, AtvProtoWire.buildPairingOption())
                if (!ack1.hasOption) {
                    val optMsg = AtvProtoWire.parsePairingMessage(AtvProtoWire.readFrame(input))
                    if (optMsg.status != 200) {
                        throw IllegalStateException("PairingOption 오류 (status=${optMsg.status})")
                    }
                }

                // Step 3: PairingConfiguration 전송 -> 이 시점에 TV 화면에 6자리 PIN 표시됨
                AtvProtoWire.writeFrame(output, AtvProtoWire.buildPairingConfiguration())
                val configAck = AtvProtoWire.parsePairingMessage(AtvProtoWire.readFrame(input))
                if (configAck.status != 200 || !configAck.hasConfigAck) {
                    throw IllegalStateException("PairingConfiguration 실패 (status=${configAck.status})")
                }
                Log.d(TAG, "TV 화면에 6자리 PIN 코드가 표시되었습니다.")
            }.onFailure {
                Log.e(TAG, "Pairing start failed", it)
                close()
            }
        }

    suspend fun verifyPin(pinCode: String): Result<Unit> =
        withContext(Dispatchers.IO) {
            runCatching {
                val activeSocket = socket ?: throw IllegalStateException("페어링 세션이 열려있지 않습니다.")
                val host = currentHost ?: throw IllegalStateException("대상 호스트 정보가 없습니다.")

                val secret = certManager.computePairingSecret(pinCode)
                    ?: throw IllegalArgumentException("6자리 16진수(0-9, A-F) PIN 코드를 입력해주세요.")

                AtvProtoWire.writeFrame(
                    activeSocket.outputStream,
                    AtvProtoWire.buildPairingSecret(secret)
                )

                val secretAck = AtvProtoWire.parsePairingMessage(
                    AtvProtoWire.readFrame(activeSocket.inputStream)
                )
                if (secretAck.status != 200 || !secretAck.hasSecretAck) {
                    throw IllegalStateException("PIN 번호가 일치하지 않거나 인증에 실패했습니다 (status=${secretAck.status}).")
                }

                certManager.markHostPaired(host, true)
                close()
            }.onFailure {
                Log.e(TAG, "PIN verification failed", it)
                close()
            }
        }

    fun close() {
        try {
            socket?.close()
        } catch (_: Exception) {
        } finally {
            socket = null
        }
    }
}
