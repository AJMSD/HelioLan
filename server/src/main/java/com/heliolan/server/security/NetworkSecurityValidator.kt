package com.heliolan.server.security

import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class NetworkSecurityValidator
    @Inject
    constructor() {
        fun isAllowedClientAddress(remoteHost: String): Boolean {
            val normalized = remoteHost.substringBefore('%').trim().lowercase()
            if (normalized == "localhost") return true

            val address = runCatching { InetAddress.getByName(normalized) }.getOrNull() ?: return false
            if (address.isLoopbackAddress) return true

            return when (address) {
                is Inet4Address -> isAllowedIpv4(address.address)
                is Inet6Address -> isAllowedIpv6(address)
                else -> false
            }
        }

        fun isAllowedHostHeader(
            hostHeader: String?,
            allowedIpAddresses: Set<String>,
        ): Boolean {
            val normalizedHost = normalizeHostHeader(hostHeader) ?: return false
            if (normalizedHost == "localhost" || normalizedHost == "127.0.0.1" || normalizedHost == "::1") {
                return true
            }

            val normalizedAllowed =
                allowedIpAddresses
                    .mapNotNull { normalizeHostHeader(it) }
                    .toSet()

            return normalizedHost in normalizedAllowed
        }

        private fun normalizeHostHeader(hostHeader: String?): String? {
            val raw = hostHeader?.trim()?.lowercase()?.substringBefore(',') ?: return null
            if (raw.isBlank()) return null

            return when {
                raw.startsWith("[") && raw.contains("]") ->
                    raw.substringAfter('[').substringBefore(']').substringBefore('%')

                raw.count { it == ':' } > 1 -> raw.substringBefore('%')
                raw.contains(':') -> raw.substringBefore(':')
                else -> raw
            }
        }

        private fun isAllowedIpv4(addressBytes: ByteArray): Boolean {
            if (addressBytes.size != 4) return false
            val first = addressBytes[0].toUByte().toInt()
            val second = addressBytes[1].toUByte().toInt()

            return when {
                first == 10 -> true // 10.0.0.0/8
                first == 172 && second in 16..31 -> true // 172.16.0.0/12
                first == 192 && second == 168 -> true // 192.168.0.0/16
                first == 169 && second == 254 -> true // 169.254.0.0/16
                else -> false
            }
        }

        private fun isAllowedIpv6(address: Inet6Address): Boolean {
            if (address.isLoopbackAddress) return true
            val bytes = address.address
            if (bytes.size != 16) return false
            val first = bytes[0].toUByte().toInt()
            // fd00::/8 (ULA)
            return first == 0xfd
        }
    }
