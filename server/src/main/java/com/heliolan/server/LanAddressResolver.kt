package com.heliolan.server

import android.content.Context
import android.net.wifi.WifiManager
import dagger.hilt.android.qualifiers.ApplicationContext
import java.net.Inet4Address
import java.net.NetworkInterface
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Collections
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class LanAddressResolver
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
    ) {
        private val preferredInterfacePrefixes = listOf("wlan", "ap", "swlan", "eth", "en", "p2p")
        private val deprioritizedInterfacePrefixes = listOf("rmnet", "ccmni", "pdp")

        fun resolveLocalIpAddress(): String {
            val wifiIp = resolveWifiIpAddress()
            if (wifiIp != null) {
                return wifiIp
            }
            return resolveNetworkInterfaceIpAddress() ?: "127.0.0.1"
        }

        private fun resolveWifiIpAddress(): String? {
            val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
            val ipAddress = wifiManager?.connectionInfo?.ipAddress ?: return null
            if (ipAddress == 0) return null
            val bytes =
                ByteBuffer
                    .allocate(Int.SIZE_BYTES)
                    .order(ByteOrder.LITTLE_ENDIAN)
                    .putInt(ipAddress)
                    .array()
            return runCatching {
                java.net.InetAddress.getByAddress(bytes).hostAddress
            }.getOrNull()
        }

        private fun resolveNetworkInterfaceIpAddress(): String? {
            val networkInterfaces = NetworkInterface.getNetworkInterfaces() ?: return null
            val interfaces =
                Collections
                    .list(networkInterfaces)
                    .asSequence()
                    .filter { it.isUp && !it.isLoopback }
                    .sortedBy { interfacePriority(it.name) }
                    .toList()

            val siteLocalCandidate =
                interfaces
                    .asSequence()
                    .flatMap { Collections.list(it.inetAddresses).asSequence() }
                    .filterIsInstance<Inet4Address>()
                    .firstOrNull { address ->
                        !address.isLoopbackAddress &&
                            !address.isAnyLocalAddress &&
                            !address.isMulticastAddress &&
                            address.isSiteLocalAddress
                    }
            if (siteLocalCandidate != null) {
                return siteLocalCandidate.hostAddress
            }

            return interfaces
                .asSequence()
                .flatMap { Collections.list(it.inetAddresses).asSequence() }
                .filterIsInstance<Inet4Address>()
                .firstOrNull { address ->
                    !address.isLoopbackAddress &&
                        !address.isAnyLocalAddress &&
                        !address.isMulticastAddress
                }?.hostAddress
        }

        private fun interfacePriority(interfaceName: String): Int {
            val normalized = interfaceName.lowercase()
            return when {
                preferredInterfacePrefixes.any { normalized.startsWith(it) } -> 0
                deprioritizedInterfacePrefixes.any { normalized.startsWith(it) } -> 2
                else -> 1
            }
        }
    }
