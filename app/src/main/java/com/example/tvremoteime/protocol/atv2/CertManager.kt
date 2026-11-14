package com.example.tvremoteime.protocol.atv2

import android.content.Context
import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder
import org.bouncycastle.jce.provider.BouncyCastleProvider
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder
import java.io.File
import java.math.BigInteger
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.SecureRandom
import java.security.Security
import java.security.cert.X509Certificate
import java.security.interfaces.RSAPublicKey
import java.util.Date
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

/**
 * Android TV Remote Protocol v2 (_androidtvremote2._tcp) mTLS 인증용
 * 클라이언트 RSA-2048 X.509 인증서 생성/보관 및 PIN 해시(SHA-256) 계산을 담당합니다.
 */
class CertManager(private val context: Context) {

    companion object {
        private const val KEYSTORE_FILE = "atv_remote_client.p12"
        private const val KEYSTORE_ALIAS = "atv_client"
        private val KEYSTORE_PASSWORD = "androidtvremote".toCharArray()
        private const val PREFS_NAME = "atv_paired_hosts"
    }

    @Volatile
    var lastServerCert: X509Certificate? = null
        private set

    private val keyStore: KeyStore by lazy { loadOrCreateKeyStore() }

    fun isHostPaired(host: String): Boolean {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getBoolean(host, false)
    }

    fun markHostPaired(host: String, paired: Boolean) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putBoolean(host, paired).apply()
    }

    fun getClientCertificate(): X509Certificate {
        return keyStore.getCertificate(KEYSTORE_ALIAS) as X509Certificate
    }

    fun createSslSocket(host: String, port: Int, timeoutMs: Int = 8000): SSLSocket {
        val kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm()).apply {
            init(keyStore, KEYSTORE_PASSWORD)
        }

        val trustAllCerts = arrayOf<TrustManager>(object : X509TrustManager {
            override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit
            override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {
                if (!chain.isNullOrEmpty()) {
                    lastServerCert = chain[0]
                }
            }
            override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
        })

        val sslContext = SSLContext.getInstance("TLS").apply {
            init(kmf.keyManagers, trustAllCerts, SecureRandom())
        }

        val socket = sslContext.socketFactory.createSocket() as SSLSocket
        socket.connect(java.net.InetSocketAddress(host, port), timeoutMs)
        socket.useClientMode = true
        socket.needClientAuth = true
        socket.startHandshake()

        if (lastServerCert == null) {
            val peerCerts = socket.session.peerCertificates
            if (peerCerts.isNotEmpty() && peerCerts[0] is X509Certificate) {
                lastServerCert = peerCerts[0] as X509Certificate
            }
        }
        return socket
    }

    /**
     * TV 화면에 표시된 6자리 16진수 PIN 코드로 PairingSecret에 들어갈 32바이트 SHA-256 해시를 생성합니다.
     * SHA256(clientModulus + clientExponent + serverModulus + serverExponent + hexDecode(pin[2..5]))
     */
    fun computePairingSecret(pinHex: String): ByteArray? {
        val cleanPin = pinHex.trim().uppercase()
        if (cleanPin.length != 6 || !cleanPin.all { it in '0'..'9' || it in 'A'..'F' }) {
            return null
        }

        val clientPub = getClientCertificate().publicKey as? RSAPublicKey ?: return null
        val serverPub = lastServerCert?.publicKey as? RSAPublicKey ?: return null

        val clientModulus = clientPub.modulus.toUnsignedBytes()
        val clientExponent = clientPub.publicExponent.toUnsignedBytes()
        val serverModulus = serverPub.modulus.toUnsignedBytes()
        val serverExponent = serverPub.publicExponent.toUnsignedBytes()

        val pinSuffixBytes = hexToBytes(cleanPin.substring(2))

        val digest = java.security.MessageDigest.getInstance("SHA-256").apply {
            update(clientModulus)
            update(clientExponent)
            update(serverModulus)
            update(serverExponent)
            update(pinSuffixBytes)
        }.digest()

        return digest
    }

    private fun BigInteger.toUnsignedBytes(): ByteArray {
        val bytes = this.toByteArray()
        return if (bytes.size > 1 && bytes[0] == 0.toByte()) {
            bytes.copyOfRange(1, bytes.size)
        } else {
            bytes
        }
    }

    private fun hexToBytes(hex: String): ByteArray {
        val result = ByteArray(hex.length / 2)
        for (i in result.indices) {
            val index = i * 2
            result[i] = hex.substring(index, index + 2).toInt(16).toByte()
        }
        return result
    }

    private fun loadOrCreateKeyStore(): KeyStore {
        if (Security.getProvider(BouncyCastleProvider.PROVIDER_NAME) == null) {
            Security.addProvider(BouncyCastleProvider())
        } else {
            Security.removeProvider(BouncyCastleProvider.PROVIDER_NAME)
            Security.addProvider(BouncyCastleProvider())
        }

        val file = File(context.filesDir, KEYSTORE_FILE)
        val ks = KeyStore.getInstance("PKCS12")
        if (file.exists()) {
            try {
                file.inputStream().use { ks.load(it, KEYSTORE_PASSWORD) }
                if (ks.containsAlias(KEYSTORE_ALIAS)) {
                    return ks
                }
            } catch (_: Exception) {
                file.delete()
            }
        }

        ks.load(null, null)
        val keyPairGen = KeyPairGenerator.getInstance("RSA").apply {
            initialize(2048, SecureRandom())
        }
        val keyPair = keyPairGen.generateKeyPair()

        val now = System.currentTimeMillis()
        val notBefore = Date(now - 86_400_000L)
        val notAfter = Date(now + 10L * 365 * 86_400_000L)
        val issuer = X500Name("CN=androidtvremote, O=Android, C=US")
        val serial = BigInteger(64, SecureRandom())

        val certBuilder = JcaX509v3CertificateBuilder(
            issuer,
            serial,
            notBefore,
            notAfter,
            issuer,
            keyPair.public
        )

        val signer = JcaContentSignerBuilder("SHA256WithRSA").build(keyPair.private)
        val certHolder = certBuilder.build(signer)
        val cert = JcaX509CertificateConverter().getCertificate(certHolder)

        ks.setKeyEntry(KEYSTORE_ALIAS, keyPair.private, KEYSTORE_PASSWORD, arrayOf(cert))
        file.outputStream().use { ks.store(it, KEYSTORE_PASSWORD) }
        return ks
    }
}
