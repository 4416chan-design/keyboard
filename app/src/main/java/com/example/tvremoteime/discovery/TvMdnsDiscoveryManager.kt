package com.example.tvremoteime.discovery

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import android.util.Log
import com.example.tvremoteime.model.TvDevice
import com.example.tvremoteime.model.TvProtocolType
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.net.Inet4Address
import kotlin.coroutines.resume

/**
 * Android NsdManager(mDNS / Zeroconf)를 이용해 동일 네트워크의 TV를 탐색합니다.
 * - `_androidtvremote2._tcp.` : 기본 Android TV OS 내장 원격 서비스
 * - `_tvkbd._tcp.` : TV 전체 앱용 커스텀 InputMethodService 모듈(:tv-ime)
 */
class TvMdnsDiscoveryManager(context: Context) {

    companion object {
        private const val TAG = "TvMdnsDiscovery"
    }

    private val appContext = context.applicationContext
    private val nsdManager = appContext.getSystemService(Context.NSD_SERVICE) as NsdManager
    private val wifiManager = appContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
    private var multicastLock: WifiManager.MulticastLock? = null

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val resolveMutex = Mutex()

    private val _devices = MutableStateFlow<List<TvDevice>>(emptyList())
    val devices: StateFlow<List<TvDevice>> = _devices.asStateFlow()

    private val _isScanning = MutableStateFlow(false)
    val isScanning: StateFlow<Boolean> = _isScanning.asStateFlow()

    private val activeListeners = mutableListOf<NsdManager.DiscoveryListener>()

    fun startDiscovery() {
        if (_isScanning.value) return
        _isScanning.value = true

        acquireMulticastLock()

        TvProtocolType.entries.forEach { protocolType ->
            val listener = createDiscoveryListener(protocolType)
            try {
                nsdManager.discoverServices(
                    protocolType.serviceType,
                    NsdManager.PROTOCOL_DNS_SD,
                    listener
                )
                activeListeners.add(listener)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to start discovery for ${protocolType.serviceType}", e)
            }
        }
    }

    fun stopDiscovery() {
        activeListeners.forEach { listener ->
            try {
                nsdManager.stopServiceDiscovery(listener)
            } catch (e: Exception) {
                Log.w(TAG, "Stop discovery warning: ${e.message}")
            }
        }
        activeListeners.clear()
        releaseMulticastLock()
        _isScanning.value = false
    }

    fun addManualDevice(name: String, host: String, port: Int, protocolType: TvProtocolType): TvDevice {
        val device = TvDevice(
            name = name.ifBlank { "TV ($host)" },
            host = host.trim(),
            port = port,
            protocolType = protocolType
        )
        _devices.update { current ->
            (current.filterNot { it.id == device.id } + device)
        }
        return device
    }

    private fun createDiscoveryListener(protocolType: TvProtocolType): NsdManager.DiscoveryListener {
        return object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(regType: String) {
                Log.d(TAG, "mDNS discovery started: $regType")
            }

            override fun onServiceFound(service: NsdServiceInfo) {
                Log.d(TAG, "mDNS service found: ${service.serviceName} (${service.serviceType})")
                scope.launch {
                    // NsdManager.resolveService는 동시에 여러 개 호출 시 FAILURE_ALREADY_ACTIVE가 발생하므로
                    // Mutex를 통해 순차적으로 Resolve를 수행합니다.
                    resolveMutex.withLock {
                        val resolved = resolveServiceSuspend(service)
                        if (resolved != null) {
                            val hostAddress = resolved.host?.hostAddress ?: return@withLock
                            val cleanHost = hostAddress.substringBefore("%")
                            val port = if (protocolType == TvProtocolType.ANDROID_TV_REMOTE_V2) {
                                // Android TV Remote v2는 원격 제어에 기본 6466(페어링 6467)을 사용합니다.
                                if (resolved.port > 0) resolved.port else 6466
                            } else {
                                resolved.port
                            }
                            val device = TvDevice(
                                name = resolved.serviceName ?: "Android TV ($cleanHost)",
                                host = cleanHost,
                                port = port,
                                protocolType = protocolType
                            )
                            _devices.update { list ->
                                // IPv4 주소를 우선 유지하며 동일 호스트+프로토콜 중복을 갱신
                                val filtered = list.filterNot {
                                    it.host == device.host && it.protocolType == device.protocolType
                                }
                                filtered + device
                            }
                        }
                    }
                }
            }

            override fun onServiceLost(service: NsdServiceInfo) {
                Log.d(TAG, "mDNS service lost: ${service.serviceName}")
                _devices.update { list ->
                    list.filterNot {
                        it.name == service.serviceName && it.protocolType == protocolType
                    }
                }
            }

            override fun onDiscoveryStopped(serviceType: String) {
                Log.d(TAG, "mDNS discovery stopped: $serviceType")
            }

            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                Log.e(TAG, "Start discovery failed ($serviceType): $errorCode")
            }

            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {
                Log.e(TAG, "Stop discovery failed ($serviceType): $errorCode")
            }
        }
    }

    @Suppress("DEPRECATION")
    private suspend fun resolveServiceSuspend(serviceInfo: NsdServiceInfo): NsdServiceInfo? =
        suspendCancellableCoroutine { cont ->
            val listener = object : NsdManager.ResolveListener {
                override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                    Log.w(TAG, "Resolve failed for ${serviceInfo.serviceName}: code=$errorCode")
                    if (cont.isActive) cont.resume(null)
                }

                override fun onServiceResolved(resolvedInfo: NsdServiceInfo) {
                    Log.d(
                        TAG,
                        "Resolved ${resolvedInfo.serviceName} -> ${resolvedInfo.host?.hostAddress}:${resolvedInfo.port}"
                    )
                    // IPv6 링크 로컬보다 IPv4가 있으면 선호
                    val host = resolvedInfo.host
                    if (host != null && (host is Inet4Address || !host.hostAddress.orEmpty().contains(":"))) {
                        if (cont.isActive) cont.resume(resolvedInfo)
                    } else {
                        if (cont.isActive) cont.resume(resolvedInfo)
                    }
                }
            }
            try {
                nsdManager.resolveService(serviceInfo, listener)
            } catch (e: Exception) {
                if (cont.isActive) cont.resume(null)
            }
        }

    private fun acquireMulticastLock() {
        try {
            if (multicastLock == null) {
                multicastLock = wifiManager.createMulticastLock("TvRemoteMdnsLock").apply {
                    setReferenceCounted(true)
                }
            }
            if (multicastLock?.isHeld == false) {
                multicastLock?.acquire()
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to acquire MulticastLock", e)
        }
    }

    private fun releaseMulticastLock() {
        try {
            if (multicastLock?.isHeld == true) {
                multicastLock?.release()
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to release MulticastLock", e)
        }
    }
}
