package com.haraldmue.velin.data.cache

import com.haraldmue.velin.data.DeviceCredentials
import com.haraldmue.velin.data.ServerAddress
import java.security.MessageDigest

object CacheNamespace {
    fun from(credentials: DeviceCredentials): String =
        from(credentials.serverUrl, credentials.deviceId)

    fun from(serverUrl: String, deviceId: String): String {
        require(deviceId.isNotEmpty()) { "Device ID must not be empty." }
        val identity = "${ServerAddress.normalize(serverUrl)}\u0000$deviceId"
        return MessageDigest.getInstance("SHA-256")
            .digest(identity.toByteArray(Charsets.UTF_8))
            .joinToString(separator = "") { byte -> "%02x".format(byte.toInt() and 0xff) }
    }
}
