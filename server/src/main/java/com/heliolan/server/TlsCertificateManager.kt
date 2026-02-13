package com.heliolan.server

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import io.ktor.network.tls.certificates.buildKeyStore
import io.ktor.network.tls.certificates.saveToFile
import io.ktor.network.tls.extensions.HashAlgorithm
import io.ktor.network.tls.extensions.SignatureAlgorithm
import java.io.File
import java.net.InetAddress
import java.security.KeyStore
import javax.inject.Inject
import javax.inject.Singleton
import javax.security.auth.x500.X500Principal

/**
 * Creates and loads the optional self-signed TLS certificate used by the embedded server.
 */
@Singleton
class TlsCertificateManager
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
    ) {
        fun resolveKeyStoreFile(config: DashboardTlsConfig): File {
            return File(context.filesDir, "tls/${config.keyStoreFileName}")
        }

        fun loadOrCreateKeyStore(
            config: DashboardTlsConfig,
            localIpAddress: String,
        ): KeyStore {
            val keyStoreFile = resolveKeyStoreFile(config)
            val existing = loadKeyStoreOrNull(keyStoreFile, config.keyStorePassword)
            if (existing != null) {
                return existing
            }

            val subjectAltNames =
                buildList {
                    add("localhost")
                    add("127.0.0.1")
                    if (localIpAddress.isNotBlank()) {
                        add(localIpAddress)
                    }
                }.distinct()

            val subjectAltIps =
                subjectAltNames.mapNotNull { value ->
                    runCatching { InetAddress.getByName(value) }.getOrNull()
                }

            val keyStore =
                buildKeyStore {
                    certificate(config.keyAlias) {
                        password = config.privateKeyPassword
                        keySizeInBits = 2048
                        daysValid = config.validityDays
                        hash = HashAlgorithm.SHA256
                        sign = SignatureAlgorithm.RSA
                        subject = X500Principal("CN=HelioLAN Local Dashboard, OU=HelioLAN, O=HelioLAN, C=US")
                        domains = subjectAltNames
                        ipAddresses = subjectAltIps
                    }
                }

            keyStore.saveToFile(keyStoreFile, config.keyStorePassword)
            return keyStore
        }

        private fun loadKeyStoreOrNull(
            keyStoreFile: File,
            keyStorePassword: String,
        ): KeyStore? {
            if (!keyStoreFile.exists()) return null
            return runCatching {
                KeyStore.getInstance(KeyStore.getDefaultType()).apply {
                    keyStoreFile.inputStream().use { input ->
                        load(input, keyStorePassword.toCharArray())
                    }
                }
            }.getOrNull()
        }
    }
