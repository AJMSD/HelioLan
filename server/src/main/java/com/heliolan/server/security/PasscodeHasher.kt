package com.heliolan.server.security

import com.heliolan.server.DashboardSecurityConfig
import org.mindrot.jbcrypt.BCrypt
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class PasscodeHasher
    @Inject
    constructor(
        private val securityConfig: DashboardSecurityConfig,
    ) {
        fun hashPasscode(passcode: String): String {
            require(isValidPasscodeFormat(passcode)) {
                "Passcode must be ${securityConfig.passcodeMinDigits}-${securityConfig.passcodeMaxDigits} digits."
            }
            return BCrypt.hashpw(passcode, BCrypt.gensalt())
        }

        fun verifyPasscode(
            passcode: String,
            hashedPasscode: String,
        ): Boolean {
            if (!isValidPasscodeFormat(passcode)) return false
            return runCatching {
                BCrypt.checkpw(passcode, hashedPasscode)
            }.getOrDefault(false)
        }

        fun isValidPasscodeFormat(passcode: String): Boolean {
            if (passcode.length !in securityConfig.passcodeMinDigits..securityConfig.passcodeMaxDigits) {
                return false
            }
            return passcode.all { it.isDigit() }
        }
    }
