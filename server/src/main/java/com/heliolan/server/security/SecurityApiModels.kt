package com.heliolan.server.security

import kotlinx.serialization.Serializable

@Serializable
data class LoginRequest(
    val passcode: String,
)

@Serializable
data class SetPasscodeRequest(
    val passcode: String,
    val currentPasscode: String? = null,
)

@Serializable
data class OpenAccessToggleRequest(
    val enabled: Boolean,
    val confirm: Boolean = false,
)
