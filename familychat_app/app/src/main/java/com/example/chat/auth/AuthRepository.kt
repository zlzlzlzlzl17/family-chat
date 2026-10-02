package com.example.chat

internal class AuthRepository(
    private val api: ChatApi,
) {
    fun login(serverUrl: String, userCode: String, password: String, deviceId: String): LoginResult =
        api.login(serverUrl, userCode, password, deviceId)

    fun sessionStatus(serverUrl: String, token: String): SessionStatus =
        api.sessionStatus(serverUrl, token)

    fun logout(serverUrl: String, token: String) = api.logout(serverUrl, token)

    fun requestRegistration(serverUrl: String, username: String, password: String): RegistrationRequestResult =
        api.requestRegistration(serverUrl, username, password)

    fun registrationStatus(serverUrl: String, requestToken: String): RegistrationRequestStatus =
        api.registrationStatus(serverUrl, requestToken)

    fun me(serverUrl: String, token: String): ChatUser = api.me(serverUrl, token)

    fun changeUsername(serverUrl: String, token: String, username: String): ChatUser =
        api.changeUsername(serverUrl, token, username)

    fun changePassword(serverUrl: String, token: String, newPassword: String) =
        api.changePassword(serverUrl, token, newPassword)

    fun requestAccountDeletion(serverUrl: String, token: String) =
        api.requestAccountDeletion(serverUrl, token)

    fun uploadAvatar(
        serverUrl: String,
        token: String,
        fileName: String,
        mime: String,
        bytes: ByteArray,
        onProgress: ((Long, Long) -> Unit)? = null,
    ): String = api.uploadAvatar(serverUrl, token, fileName, mime, bytes, onProgress)
}
