package com.haraldmue.velin.data

interface CredentialStore {
    fun load(): DeviceCredentials?
    fun save(credentials: DeviceCredentials)
    fun clear()
}

data class DeviceCredentials(
    val serverUrl: String,
    val deviceId: String,
    val token: String,
    val serverName: String,
    val serverVersion: String,
)
