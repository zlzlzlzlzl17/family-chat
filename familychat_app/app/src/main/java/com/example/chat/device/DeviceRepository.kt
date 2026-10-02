package com.example.chat

internal class DeviceRepository(
    private val api: ChatApi,
) {
    fun registerDeviceIdentity(serverUrl: String, token: String, identity: DeviceIdentityInfo) =
        api.registerDeviceIdentity(serverUrl, token, identity)

    fun registerDirectPreKey(serverUrl: String, token: String, preKey: LocalDirectPreKey) =
        api.registerDirectPreKey(serverUrl, token, preKey)

    fun deviceIdentities(serverUrl: String, token: String): List<DevicePublicIdentity> =
        api.deviceIdentities(serverUrl, token)

    fun myDevices(serverUrl: String, token: String): List<DevicePublicIdentity> =
        api.myDevices(serverUrl, token)

    fun deleteDevice(serverUrl: String, token: String, deviceId: String, currentDeviceId: String) =
        api.deleteDevice(serverUrl, token, deviceId, currentDeviceId)

    fun approveDevice(serverUrl: String, token: String, deviceId: String) =
        api.approveDevice(serverUrl, token, deviceId)
}
