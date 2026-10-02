package com.example.chat

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.BufferedSink
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.SecureRandom
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.X509EncodedKeySpec
import java.util.UUID
import java.util.concurrent.TimeUnit
import javax.crypto.KeyAgreement
import javax.crypto.Cipher
import javax.crypto.CipherInputStream
import javax.crypto.CipherOutputStream
import javax.crypto.KeyGenerator
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

internal class ChatApiException(
    val statusCode: Int,
    val errorCode: String,
) : IllegalStateException(errorCode)

private class ProgressRequestBody(
    private val body: RequestBody,
    private val onProgress: ((bytesDone: Long, totalBytes: Long) -> Unit)?,
) : RequestBody() {
    override fun contentType() = body.contentType()

    override fun contentLength() = body.contentLength()

    override fun writeTo(sink: BufferedSink) {
        val buffer = okio.Buffer()
        body.writeTo(buffer)
        val total = buffer.size
        var written = 0L
        while (!buffer.exhausted()) {
            val toWrite = minOf(buffer.size, 8_192L)
            sink.write(buffer, toWrite)
            written += toWrite
            onProgress?.invoke(written, total)
        }
    }
}

private class ProgressFileRequestBody(
    private val file: File,
    private val contentTypeValue: okhttp3.MediaType,
    private val onProgress: ((bytesDone: Long, totalBytes: Long) -> Unit)?,
) : RequestBody() {
    override fun contentType() = contentTypeValue

    override fun contentLength() = file.length()

    override fun writeTo(sink: BufferedSink) {
        val total = file.length()
        val buffer = ByteArray(64 * 1024)
        var written = 0L
        file.inputStream().use { input ->
            while (true) {
                val read = input.read(buffer)
                if (read <= 0) break
                sink.write(buffer, 0, read)
                written += read.toLong()
                onProgress?.invoke(written, total)
            }
        }
    }
}

class ChatApi {
    private val client = OkHttpClient.Builder()
        .pingInterval(20, TimeUnit.SECONDS)
        .build()

    private fun base(url: String): String = url.trim().trimEnd('/')

    private fun requestBuilder(url: String): Request.Builder =
        Request.Builder()
            .url(url)
            .header(APP_CLIENT_HEADER_NAME, APP_CLIENT_HEADER_VALUE)

    private fun authRequest(url: String, token: String) =
        requestBuilder(url).header("Authorization", "Bearer $token")

    private fun execute(request: Request): Response {
        val response = client.newCall(request).execute()
        if (!response.isSuccessful) {
            val statusCode = response.code
            val errorCode = parseError(response)
            response.close()
            throw ChatApiException(statusCode, errorCode)
        }
        return response
    }

    private fun parseError(response: Response): String {
        val text = response.body?.string().orEmpty()
        return runCatching { JSONObject(text).optString("error").ifBlank { response.message } }
            .getOrDefault(response.message.ifBlank { "request_failed" })
    }

    private fun loginResult(json: JSONObject): LoginResult =
        LoginResult(
            token = json.getString("token"),
            refreshToken = json.optString("refresh_token"),
            sessionId = json.optString("session_id"),
            deviceStatus = json.optString("device_status", "trusted"),
            userCode = json.optString("user_code"),
            username = json.getString("username"),
            color = json.getString("color"),
            avatarUrl = json.optString("avatar_url"),
            isAdmin = json.optBoolean("is_admin", false)
        )

    fun login(serverUrl: String, userCode: String, password: String, deviceId: String): LoginResult {
        val body = JSONObject()
            .put("user_code", userCode)
            .put("password", password)
            .put("device_id", deviceId)
            .put("platform", "android")
            .put("device_name", "${Build.MANUFACTURER} ${Build.MODEL}".trim())
            .put("manufacturer", Build.MANUFACTURER.orEmpty())
            .put("model", Build.MODEL.orEmpty())
            .toString()
        val request = requestBuilder("${base(serverUrl)}/api/login")
            .post(body.toRequestBody("application/json".toMediaType()))
            .build()
        execute(request).use { response ->
            return loginResult(JSONObject(response.body!!.string()))
        }
    }

    fun refreshSession(serverUrl: String, refreshToken: String, deviceId: String): LoginResult {
        val body = JSONObject()
            .put("refresh_token", refreshToken)
            .put("device_id", deviceId)
            .toString()
        val request = requestBuilder("${base(serverUrl)}/api/session/refresh")
            .post(body.toRequestBody("application/json".toMediaType()))
            .build()
        execute(request).use { response ->
            return loginResult(JSONObject(response.body!!.string()))
        }
    }

    fun sessionStatus(serverUrl: String, token: String): SessionStatus {
        execute(authRequest("${base(serverUrl)}/api/session/status", token).get().build()).use { response ->
            val json = JSONObject(response.body!!.string())
            return SessionStatus(
                userCode = json.optString("user_code"),
                username = json.optString("username"),
                deviceId = json.optString("device_id"),
                deviceStatus = json.optString("device_status"),
                sessionStatus = json.optString("session_status")
            )
        }
    }

    fun logout(serverUrl: String, token: String) {
        execute(
            authRequest("${base(serverUrl)}/api/logout", token)
                .post("{}".toRequestBody("application/json".toMediaType()))
                .build()
        ).close()
    }

    fun requestRegistration(serverUrl: String, username: String, password: String): RegistrationRequestResult {
        val body = JSONObject()
            .put("username", username)
            .put("password", password)
            .toString()
        val request = requestBuilder("${base(serverUrl)}/register_request")
            .post(body.toRequestBody("application/json".toMediaType()))
            .build()
        execute(request).use { response ->
            val json = JSONObject(response.body!!.string())
            return RegistrationRequestResult(json.optString("request_token"))
        }
    }

    fun registrationStatus(serverUrl: String, requestToken: String): RegistrationRequestStatus {
        val body = JSONObject().put("request_token", requestToken).toString()
        val request = requestBuilder("${base(serverUrl)}/register_request/status")
            .post(body.toRequestBody("application/json".toMediaType()))
            .build()
        execute(request).use { response ->
            val json = JSONObject(response.body!!.string())
            return RegistrationRequestStatus(
                status = json.optString("status"),
                userCode = json.optString("user_code"),
                reviewNote = json.optString("review_note")
            )
        }
    }

    fun me(serverUrl: String, token: String): ChatUser {
        execute(authRequest("${base(serverUrl)}/api/me", token).get().build()).use { response ->
            val json = JSONObject(response.body!!.string())
            return ChatUser(
                userCode = json.optString("user_code"),
                username = json.getString("username"),
                color = json.getString("color"),
                avatarUrl = json.optString("avatar_url"),
                isAdmin = json.optBoolean("is_admin")
            )
        }
    }

    fun registerDeviceIdentity(serverUrl: String, token: String, identity: DeviceIdentityInfo) {
        val body = JSONObject()
            .put("device_id", identity.deviceId)
            .put("platform", "android")
            .put("device_name", identity.deviceName)
            .put("key_alg", identity.keyAlg)
            .put("public_key", identity.publicKey)
            .toString()
        val request = authRequest("${base(serverUrl)}/api/device_identity", token)
            .post(body.toRequestBody("application/json".toMediaType()))
            .build()
        execute(request).close()
    }

    fun registerDirectPreKey(serverUrl: String, token: String, preKey: LocalDirectPreKey) {
        val oneTimePrekeys = JSONArray()
        preKey.oneTimePreKeys.forEach { item ->
            oneTimePrekeys.put(
                JSONObject()
                    .put("id", item.id)
                    .put("public_key", item.publicKey)
                    .put("signature", item.signature)
            )
        }
        val body = JSONObject()
            .put("device_id", preKey.deviceId)
            .put("key_alg", preKey.keyAlg)
            .put("identity_ecdh_public", preKey.identityEcdhPublic)
            .put("identity_ecdh_signature", preKey.identityEcdhSignature)
            .put("signed_prekey_public", preKey.signedPrekeyPublic)
            .put("signed_prekey_signature", preKey.signedPrekeySignature)
            .put("one_time_prekeys", oneTimePrekeys)
            .toString()
        val request = authRequest("${base(serverUrl)}/api/direct_prekey", token)
            .post(body.toRequestBody("application/json".toMediaType()))
            .build()
        execute(request).close()
    }

    fun deviceIdentities(serverUrl: String, token: String): List<DevicePublicIdentity> {
        execute(authRequest("${base(serverUrl)}/api/device_identities", token).get().build()).use { response ->
            val items = JSONObject(response.body!!.string()).optJSONArray("items") ?: JSONArray()
            return List(items.length()) { index ->
                val item = items.getJSONObject(index)
                DevicePublicIdentity(
                    userCode = item.optString("user_code"),
                    username = item.optString("username"),
                    deviceId = item.optString("device_id"),
                    deviceName = item.optString("device_name"),
                    keyAlg = item.optString("key_alg"),
                    publicKey = item.optString("public_key"),
                    updatedAt = item.optLong("updated_at", 0L),
                    lastSeenAt = item.optLong("last_seen_at", 0L),
                    status = item.optString("status", "trusted"),
                    manufacturer = item.optString("manufacturer"),
                    model = item.optString("model")
                )
            }
        }
    }

    fun myDevices(serverUrl: String, token: String): List<DevicePublicIdentity> {
        execute(authRequest("${base(serverUrl)}/api/devices", token).get().build()).use { response ->
            val items = JSONObject(response.body!!.string()).optJSONArray("items") ?: JSONArray()
            return List(items.length()) { index ->
                val item = items.getJSONObject(index)
                DevicePublicIdentity(
                    userCode = item.optString("user_code"),
                    username = item.optString("username"),
                    deviceId = item.optString("device_id"),
                    deviceName = item.optString("device_name"),
                    keyAlg = item.optString("key_alg"),
                    publicKey = item.optString("public_key"),
                    updatedAt = item.optLong("updated_at", 0L),
                    lastSeenAt = item.optLong("last_seen_at", 0L),
                    status = item.optString("status", "trusted"),
                    manufacturer = item.optString("manufacturer"),
                    model = item.optString("model")
                )
            }
        }
    }

    fun deleteDevice(serverUrl: String, token: String, deviceId: String, currentDeviceId: String) {
        val encodedDeviceId = URLEncoder.encode(deviceId, "UTF-8")
        val encodedCurrentDeviceId = URLEncoder.encode(currentDeviceId, "UTF-8")
        val request = authRequest("${base(serverUrl)}/api/devices/$encodedDeviceId?current_device_id=$encodedCurrentDeviceId", token)
            .delete()
            .build()
        execute(request).close()
    }

    fun approveDevice(serverUrl: String, token: String, deviceId: String) {
        val encodedDeviceId = URLEncoder.encode(deviceId, "UTF-8")
        val request = authRequest("${base(serverUrl)}/api/devices/$encodedDeviceId/approve", token)
            .post("{}".toRequestBody("application/json".toMediaType()))
            .build()
        execute(request).close()
    }

    fun directPreKeys(serverUrl: String, token: String, conversationId: Long): List<DirectPreKeyBundle> {
        execute(
            authRequest(
                "${base(serverUrl)}/api/conversations/$conversationId/direct_prekeys?include_self=1",
                token,
            ).get().build()
        ).use { response ->
            val items = JSONObject(response.body!!.string()).optJSONArray("items") ?: JSONArray()
            return List(items.length()) { index ->
                val item = items.getJSONObject(index)
                DirectPreKeyBundle(
                    userCode = item.optString("user_code"),
                    username = item.optString("username"),
                    deviceId = item.optString("device_id"),
                    deviceName = item.optString("device_name"),
                    identityKeyAlg = item.optString("identity_key_alg"),
                    identityPublicKey = item.optString("identity_public_key"),
                    identityFingerprint = item.optString("identity_fingerprint"),
                    prekeyAlg = item.optString("prekey_alg"),
                    identityEcdhPublic = item.optString("identity_ecdh_public"),
                    identityEcdhSignature = item.optString("identity_ecdh_signature"),
                    signedPrekeyPublic = item.optString("signed_prekey_public"),
                    signedPrekeySignature = item.optString("signed_prekey_signature"),
                    oneTimePrekeyId = item.optString("one_time_prekey_id"),
                    oneTimePrekeyPublic = item.optString("one_time_prekey_public"),
                    oneTimePrekeySignature = item.optString("one_time_prekey_signature"),
                    updatedAt = item.optLong("updated_at", 0L)
                )
            }
        }
    }

    fun groupSenderKeys(serverUrl: String, token: String, conversationId: Long, deviceId: String): GroupSenderKeyState {
        val encodedDeviceId = URLEncoder.encode(deviceId, "UTF-8")
        execute(authRequest("${base(serverUrl)}/api/conversations/$conversationId/group_sender_keys?device_id=$encodedDeviceId", token).get().build()).use { response ->
            val json = JSONObject(response.body!!.string())
            val items = json.optJSONArray("items") ?: JSONArray()
            val devices = json.optJSONArray("devices") ?: JSONArray()
            return GroupSenderKeyState(
                epoch = json.optLong("epoch", 1L),
                items = List(items.length()) { index ->
                    val item = items.getJSONObject(index)
                    GroupSenderKeyBundle(
                        conversationId = item.optLong("conversation_id", conversationId),
                        senderUserCode = item.optString("sender_user_code"),
                        senderUsername = item.optString("sender_username"),
                        deviceId = item.optString("device_id"),
                        senderDeviceName = item.optString("sender_device_name"),
                        senderIdentityPublicKey = item.optString("sender_identity_public_key"),
                        senderIdentityFingerprint = item.optString("sender_identity_fingerprint"),
                        recipientDeviceId = item.optString("recipient_device_id"),
                        epoch = item.optLong("epoch", 1L),
                        keyId = item.optString("key_id"),
                        wrappedKey = item.optString("wrapped_key"),
                        updatedAt = item.optLong("updated_at", 0L)
                    )
                },
                devices = List(devices.length()) { index ->
                    val item = devices.getJSONObject(index)
                    GroupKeyDevice(
                        userCode = item.optString("user_code"),
                        username = item.optString("username"),
                        deviceId = item.optString("device_id"),
                        deviceName = item.optString("device_name"),
                        identityKeyAlg = item.optString("identity_key_alg"),
                        identityPublicKey = item.optString("identity_public_key"),
                        identityFingerprint = item.optString("identity_fingerprint"),
                        prekeyAlg = item.optString("prekey_alg"),
                        identityEcdhPublic = item.optString("identity_ecdh_public"),
                        identityEcdhSignature = item.optString("identity_ecdh_signature"),
                        updatedAt = item.optLong("updated_at", 0L)
                    )
                }
            )
        }
    }

    fun registerGroupSenderKey(serverUrl: String, token: String, key: LocalGroupSenderKey) {
        val envelopes = JSONArray()
        key.envelopes.forEach { envelope ->
            envelopes.put(
                JSONObject()
                    .put("recipient_user_code", envelope.recipientUserCode)
                    .put("recipient_device_id", envelope.recipientDeviceId)
                    .put("wrapped_key", envelope.wrappedKey)
            )
        }
        val body = JSONObject()
            .put("device_id", key.deviceId)
            .put("epoch", key.epoch)
            .put("key_id", key.keyId)
            .put("envelopes", envelopes)
            .toString()
        val request = authRequest("${base(serverUrl)}/api/conversations/${key.conversationId}/group_sender_key", token)
            .post(body.toRequestBody("application/json".toMediaType()))
            .build()
        execute(request).close()
    }

    fun changeUsername(serverUrl: String, token: String, username: String): ChatUser {
        val body = JSONObject().put("username", username).toString()
        val request = authRequest("${base(serverUrl)}/api/username", token)
            .post(body.toRequestBody("application/json".toMediaType()))
            .build()
        execute(request).use { response ->
            val user = JSONObject(response.body!!.string()).getJSONObject("user")
            return ChatUser(
                userCode = user.optString("user_code"),
                username = user.getString("username"),
                color = user.getString("color"),
                avatarUrl = user.optString("avatar_url"),
                isAdmin = user.optBoolean("is_admin", false)
            )
        }
    }

    fun users(serverUrl: String, token: String): List<ChatUser> =
        parseUsers(execute(authRequest("${base(serverUrl)}/api/users", token).get().build()).body!!.string())

    fun conversations(serverUrl: String, token: String): List<ConversationSummary> {
        execute(authRequest("${base(serverUrl)}/api/conversations", token).get().build()).use { response ->
            val items = JSONObject(response.body!!.string()).optJSONArray("items") ?: JSONArray()
            return List(items.length()) { index ->
                val item = items.getJSONObject(index)
                ConversationSummary(
                    id = item.getLong("id"),
                    kind = item.optString("kind"),
                    slug = item.optString("slug"),
                    groupCode = item.optString("group_code"),
                    title = item.optString("title"),
                    avatarUrl = item.optString("avatar_url"),
                    directUserCode = item.optString("direct_user_code"),
                    directUsername = item.optString("direct_username"),
                    lastMessageTs = item.optLong("last_message_ts", 0L),
                    lastMessagePreview = item.optString("last_message_preview"),
                    unreadCount = item.optInt("unread_count", 0),
                    lastReadMessageId = item.optLong("last_read_message_id", 0L)
                )
            }
        }
    }

    fun lookupUser(serverUrl: String, token: String, userCode: String): UserLookupResult {
        val encoded = URLEncoder.encode(userCode, StandardCharsets.UTF_8.name())
        execute(authRequest("${base(serverUrl)}/api/contacts/lookup?user_code=$encoded", token).get().build()).use { response ->
            val json = JSONObject(response.body!!.string())
            val user = json.getJSONObject("user")
            return UserLookupResult(
                user = ChatUser(
                    userCode = user.optString("user_code"),
                    username = user.optString("username"),
                    color = user.optString("color"),
                    avatarUrl = user.optString("avatar_url"),
                    isAdmin = false
                ),
                conversationId = json.optLong("conversation_id", 0L),
                isContact = json.optBoolean("is_contact", false),
                outgoingPending = json.optBoolean("outgoing_pending", false),
                incomingRequestId = json.optLong("incoming_request_id", 0L),
                incomingPending = json.optBoolean("incoming_pending", false)
            )
        }
    }

    fun contactRequests(serverUrl: String, token: String): List<ContactRequestInfo> {
        execute(authRequest("${base(serverUrl)}/api/contact_requests", token).get().build()).use { response ->
            val items = JSONObject(response.body!!.string()).optJSONArray("items") ?: JSONArray()
            return List(items.length()) { index ->
                val item = items.getJSONObject(index)
                ContactRequestInfo(
                    id = item.optLong("id"),
                    direction = item.optString("direction"),
                    status = item.optString("status"),
                    user = ChatUser(
                        userCode = item.optString("user_code"),
                        username = item.optString("username"),
                        color = item.optString("color"),
                        avatarUrl = item.optString("avatar_url"),
                        isAdmin = false
                    ),
                    createdAt = item.optLong("created_at", 0L)
                )
            }
        }
    }

    fun requestContact(serverUrl: String, token: String, userCode: String): Long {
        val body = JSONObject().put("user_code", userCode).toString()
        val request = authRequest("${base(serverUrl)}/api/contact_requests", token)
            .post(body.toRequestBody("application/json".toMediaType()))
            .build()
        execute(request).use { response ->
            val json = JSONObject(response.body!!.string())
            return json.optLong("conversation_id", 0L)
        }
    }

    fun reviewContactRequest(serverUrl: String, token: String, requestId: Long, approve: Boolean): Long {
        val action = if (approve) "approve" else "reject"
        val request = authRequest("${base(serverUrl)}/api/contact_requests/$requestId/$action", token)
            .post("{}".toRequestBody("application/json".toMediaType()))
            .build()
        execute(request).use { response ->
            val json = JSONObject(response.body!!.string())
            return json.optLong("conversation_id", 0L)
        }
    }

    fun createGroup(serverUrl: String, token: String, title: String): Long {
        val body = JSONObject().put("title", title).toString()
        val request = authRequest("${base(serverUrl)}/api/groups", token)
            .post(body.toRequestBody("application/json".toMediaType()))
            .build()
        execute(request).use { response ->
            val json = JSONObject(response.body!!.string())
            return json.optLong("conversation_id", 0L)
        }
    }

    fun lookupGroup(serverUrl: String, token: String, groupCode: String): GroupLookupResult {
        val encoded = URLEncoder.encode(groupCode, StandardCharsets.UTF_8.name())
        execute(authRequest("${base(serverUrl)}/api/groups/lookup?group_code=$encoded", token).get().build()).use { response ->
            val json = JSONObject(response.body!!.string())
            val group = json.getJSONObject("group")
            return GroupLookupResult(
                conversationId = group.optLong("conversation_id", 0L),
                groupCode = group.optString("group_code"),
                title = group.optString("title"),
                avatarUrl = group.optString("avatar_url"),
                isMember = json.optBoolean("is_member", false),
                pending = json.optBoolean("pending", false),
                pendingRequestId = json.optLong("pending_request_id", 0L)
            )
        }
    }

    fun groupJoinRequests(serverUrl: String, token: String): List<GroupJoinRequestInfo> {
        execute(authRequest("${base(serverUrl)}/api/group_join_requests", token).get().build()).use { response ->
            val items = JSONObject(response.body!!.string()).optJSONArray("items") ?: JSONArray()
            return List(items.length()) { index ->
                val item = items.getJSONObject(index)
                GroupJoinRequestInfo(
                    id = item.optLong("id"),
                    direction = item.optString("direction"),
                    status = item.optString("status"),
                    groupCode = item.optString("group_code"),
                    title = item.optString("title"),
                    avatarUrl = item.optString("avatar_url"),
                    requester = ChatUser(
                        userCode = item.optString("requester_user_code"),
                        username = item.optString("requester_username"),
                        color = item.optString("requester_color"),
                        avatarUrl = item.optString("requester_avatar_url"),
                        isAdmin = false
                    ),
                    createdAt = item.optLong("created_at", 0L)
                )
            }
        }
    }

    fun requestJoinGroup(serverUrl: String, token: String, groupCode: String): Long {
        val body = JSONObject().put("group_code", groupCode).toString()
        val request = authRequest("${base(serverUrl)}/api/group_join_requests", token)
            .post(body.toRequestBody("application/json".toMediaType()))
            .build()
        execute(request).use { response ->
            val json = JSONObject(response.body!!.string())
            return json.optLong("conversation_id", 0L)
        }
    }

    fun reviewGroupJoinRequest(serverUrl: String, token: String, requestId: Long, approve: Boolean): Long {
        val action = if (approve) "approve" else "reject"
        val request = authRequest("${base(serverUrl)}/api/group_join_requests/$requestId/$action", token)
            .post("{}".toRequestBody("application/json".toMediaType()))
            .build()
        execute(request).use { response ->
            val json = JSONObject(response.body!!.string())
            return json.optLong("conversation_id", 0L)
        }
    }

    fun callConfig(serverUrl: String, token: String): CallConfig {
        execute(authRequest("${base(serverUrl)}/api/call_config", token).get().build()).use { response ->
            val items = JSONObject(response.body!!.string()).optJSONArray("items") ?: JSONArray()
            return CallConfig(
                iceServers = List(items.length()) { index ->
                    val item = items.getJSONObject(index)
                    val urlsJson = item.optJSONArray("urls")
                    val urls = if (urlsJson != null) {
                        List(urlsJson.length()) { urlsJson.optString(it) }.filter { it.isNotBlank() }
                    } else {
                        listOf(item.optString("url")).filter { it.isNotBlank() }
                    }
                    CallIceServerConfig(
                        urls = urls,
                        username = item.optString("username"),
                        credential = item.optString("credential")
                    )
                }.filter { it.urls.isNotEmpty() }
            )
        }
    }

    fun appRelease(serverUrl: String, token: String, channel: AppReleaseChannel = AppReleaseChannel.STABLE): AppReleaseInfo? {
        val suffix = if (channel == AppReleaseChannel.STABLE) "" else "?channel=${channel.wireValue}"
        execute(authRequest("${base(serverUrl)}/api/app_release$suffix", token).get().build()).use { response ->
            val item = JSONObject(response.body!!.string()).optJSONObject("item") ?: return null
            return AppReleaseInfo(
                version = item.optString("version"),
                fileName = item.optString("file_name"),
                originalName = item.optString("original_name"),
                fileSize = item.optLong("file_size", 0L),
                uploadedAt = item.optLong("uploaded_at", 0L),
                downloadUrl = item.optString("download_url"),
                sha256 = item.optString("sha256"),
                channel = AppReleaseChannel.fromWire(item.optString("channel").ifBlank { channel.wireValue }),
                versionLabel = item.optString("version_label").ifBlank { item.optString("version") },
            )
        }
    }

    fun history(
        serverUrl: String,
        token: String,
        conversationId: Long,
        sinceId: Long? = null,
        beforeId: Long? = null,
        limit: Int = 200,
    ): HistoryPage {
        val query = buildList {
            add("conversation_id=$conversationId")
            add("limit=$limit")
            sinceId?.takeIf { it > 0 }?.let { add("since_id=$it") }
            beforeId?.takeIf { it > 0 }?.let { add("before_id=$it") }
        }.joinToString("&")
        val request = authRequest("${base(serverUrl)}/api/history?$query", token).get().build()
        execute(request).use { response ->
            val json = JSONObject(response.body!!.string())
            val items = json.optJSONArray("items") ?: JSONArray()
            return HistoryPage(
                items = List(items.length()) { parseMessage(items.getJSONObject(it)) },
                hasMore = json.optBoolean("has_more", false)
            )
        }
    }

    fun sendTextMessage(
        serverUrl: String,
        token: String,
        conversationId: Long,
        payload: String,
        e2ee: Boolean,
        replyJson: String,
        mentions: List<String>,
        clientMessageId: String,
    ): ChatMessage {
        val body = JSONObject()
            .put("conversation_id", conversationId)
            .put("kind", "text")
            .put("payload", payload)
            .put("e2ee", if (e2ee) 1 else 0)
            .put("reply_to", replyJson)
            .put("mentions", JSONArray(mentions))
            .put("client_message_id", clientMessageId)
            .toString()
        val request = authRequest("${base(serverUrl)}/api/messages", token)
            .post(body.toRequestBody("application/json".toMediaType()))
            .build()
        execute(request).use { response ->
            return parseMessage(JSONObject(response.body!!.string()).getJSONObject("message"))
        }
    }

    fun conversationReadStates(serverUrl: String, token: String, conversationId: Long): List<ConversationReadState> {
        val request = authRequest("${base(serverUrl)}/api/read_states?conversation_id=$conversationId", token).get().build()
        execute(request).use { response ->
            val items = JSONObject(response.body!!.string()).optJSONArray("items") ?: JSONArray()
            return List(items.length()) { index ->
                val item = items.getJSONObject(index)
                ConversationReadState(
                    userCode = item.optString("user_code"),
                    username = item.optString("username"),
                    lastReadMessageId = item.optLong("last_read_message_id", 0L)
                )
            }
        }
    }

    fun conversationDeliveryStates(serverUrl: String, token: String, conversationId: Long): List<ConversationDeliveryState> {
        val request = authRequest("${base(serverUrl)}/api/delivery_states?conversation_id=$conversationId", token).get().build()
        execute(request).use { response ->
            val items = JSONObject(response.body!!.string()).optJSONArray("items") ?: JSONArray()
            return List(items.length()) { index ->
                val item = items.getJSONObject(index)
                ConversationDeliveryState(
                    userCode = item.optString("user_code"),
                    username = item.optString("username"),
                    lastDeliveredMessageId = item.optLong("last_delivered_message_id", 0L)
                )
            }
        }
    }

    fun markRead(serverUrl: String, token: String, conversationId: Long, messageId: Long) {
        val body = JSONObject()
            .put("conversation_id", conversationId)
            .put("last_read_message_id", messageId)
            .toString()
        execute(
            authRequest("${base(serverUrl)}/api/read", token)
                .post(body.toRequestBody("application/json".toMediaType()))
                .build()
        ).close()
    }

    fun markDelivered(serverUrl: String, token: String, conversationId: Long, messageId: Long) {
        val body = JSONObject()
            .put("conversation_id", conversationId)
            .put("last_delivered_message_id", messageId)
            .toString()
        execute(
            authRequest("${base(serverUrl)}/api/delivered", token)
                .post(body.toRequestBody("application/json".toMediaType()))
                .build()
        ).close()
    }

    fun conversationManage(serverUrl: String, token: String, conversationId: Long): ConversationManageInfo {
        val request = authRequest("${base(serverUrl)}/api/conversations/$conversationId/manage", token).get().build()
        execute(request).use { response ->
            val json = JSONObject(response.body!!.string())
            val conversation = json.getJSONObject("conversation")
            val membersJson = json.optJSONArray("members") ?: JSONArray()
            val joinRequestsJson = json.optJSONArray("pending_join_requests") ?: JSONArray()
            val adminRequestsJson = json.optJSONArray("pending_admin_requests") ?: JSONArray()
            return ConversationManageInfo(
                id = conversation.optLong("id", conversationId),
                kind = conversation.optString("kind"),
                title = conversation.optString("title"),
                groupCode = conversation.optString("group_code"),
                avatarUrl = conversation.optString("avatar_url"),
                messageTtlMs = conversation.optLong("message_ttl_ms", 0L),
                ownRole = conversation.optString("own_role"),
                canManage = conversation.optBoolean("can_manage", false),
                canManageOwner = conversation.optBoolean("can_manage_owner", false),
                adminCount = conversation.optInt("admin_count", 0),
                adminLimit = conversation.optInt("admin_limit", 3),
                keyEpoch = conversation.optLong("key_epoch", 0L),
                keyDeviceCount = conversation.optInt("key_device_count", 0),
                keyReadyDeviceCount = conversation.optInt("key_ready_device_count", 0),
                members = List(membersJson.length()) { index ->
                    val item = membersJson.getJSONObject(index)
                    GroupMemberInfo(
                        user = ChatUser(
                            userCode = item.optString("user_code"),
                            username = item.optString("username"),
                            color = item.optString("color"),
                            avatarUrl = item.optString("avatar_url"),
                            isAdmin = item.optString("role") == "owner" || item.optString("role") == "admin"
                        ),
                        role = item.optString("role", "member"),
                        joinedAt = item.optLong("joined_at", 0L)
                    )
                },
                pendingJoinRequests = List(joinRequestsJson.length()) { index ->
                    val item = joinRequestsJson.getJSONObject(index)
                    GroupJoinRequestInfo(
                        id = item.optLong("id"),
                        direction = item.optString("direction"),
                        status = item.optString("status"),
                        groupCode = item.optString("group_code"),
                        title = item.optString("title"),
                        avatarUrl = item.optString("avatar_url"),
                        requester = ChatUser(
                            userCode = item.optString("requester_user_code"),
                            username = item.optString("requester_username"),
                            color = item.optString("requester_color"),
                            avatarUrl = item.optString("requester_avatar_url"),
                            isAdmin = false
                        ),
                        createdAt = item.optLong("created_at", 0L)
                    )
                },
                pendingAdminRequests = List(adminRequestsJson.length()) { index ->
                    val item = adminRequestsJson.getJSONObject(index)
                    GroupAdminRequestInfo(
                        id = item.optLong("id"),
                        conversationId = item.optLong("conversation_id", conversationId),
                        status = item.optString("status"),
                        requesterUserCode = item.optString("requester_user_code"),
                        requesterUsername = item.optString("requester_username"),
                        targetUserCode = item.optString("target_user_code"),
                        targetUsername = item.optString("target_username"),
                        createdAt = item.optLong("created_at", 0L)
                    )
                }
            )
        }
    }

    fun clearConversationHistory(serverUrl: String, token: String, conversationId: Long) {
        val request = authRequest("${base(serverUrl)}/api/conversations/$conversationId/clear_history", token)
            .post("{}".toRequestBody("application/json".toMediaType()))
            .build()
        execute(request).close()
    }

    fun changeGroupTitle(serverUrl: String, token: String, conversationId: Long, title: String) {
        val body = JSONObject().put("title", title).toString()
        val request = authRequest("${base(serverUrl)}/api/conversations/$conversationId/title", token)
            .post(body.toRequestBody("application/json".toMediaType()))
            .build()
        execute(request).close()
    }

    fun setGroupExpiration(serverUrl: String, token: String, conversationId: Long, ttlMs: Long) {
        val body = JSONObject().put("message_ttl_ms", ttlMs).toString()
        val request = authRequest("${base(serverUrl)}/api/conversations/$conversationId/expiration", token)
            .post(body.toRequestBody("application/json".toMediaType()))
            .build()
        execute(request).close()
    }

    fun removeGroupMember(serverUrl: String, token: String, conversationId: Long, userCode: String) {
        val request = authRequest("${base(serverUrl)}/api/conversations/$conversationId/members/$userCode/remove", token)
            .post("{}".toRequestBody("application/json".toMediaType()))
            .build()
        execute(request).close()
    }

    fun addGroupMember(serverUrl: String, token: String, conversationId: Long, userCode: String) {
        val request = authRequest("${base(serverUrl)}/api/conversations/$conversationId/members/$userCode/add", token)
            .post("{}".toRequestBody("application/json".toMediaType()))
            .build()
        execute(request).close()
    }

    fun transferGroupOwner(serverUrl: String, token: String, conversationId: Long, targetUserCode: String) {
        val body = JSONObject().put("target_user_code", targetUserCode).toString()
        val request = authRequest("${base(serverUrl)}/api/conversations/$conversationId/transfer_owner", token)
            .post(body.toRequestBody("application/json".toMediaType()))
            .build()
        execute(request).close()
    }

    fun requestGroupAdmin(serverUrl: String, token: String, conversationId: Long, targetUserCode: String): String {
        val body = JSONObject().put("target_user_code", targetUserCode).toString()
        val request = authRequest("${base(serverUrl)}/api/conversations/$conversationId/admin_requests", token)
            .post(body.toRequestBody("application/json".toMediaType()))
            .build()
        execute(request).use { response ->
            return JSONObject(response.body!!.string()).optString("status", "pending")
        }
    }

    fun removeGroupAdmin(serverUrl: String, token: String, conversationId: Long, userCode: String) {
        val request = authRequest("${base(serverUrl)}/api/conversations/$conversationId/admins/$userCode/remove", token)
            .post("{}".toRequestBody("application/json".toMediaType()))
            .build()
        execute(request).close()
    }

    fun reviewGroupAdminRequest(serverUrl: String, token: String, requestId: Long, approve: Boolean) {
        val action = if (approve) "approve" else "reject"
        val request = authRequest("${base(serverUrl)}/api/group_admin_requests/$requestId/$action", token)
            .post("{}".toRequestBody("application/json".toMediaType()))
            .build()
        execute(request).close()
    }

    fun changePassword(serverUrl: String, token: String, newPassword: String) {
        val body = JSONObject().put("new_password", newPassword).toString()
        val request = authRequest("${base(serverUrl)}/api/change_password", token)
            .post(body.toRequestBody("application/json".toMediaType()))
            .build()
        execute(request).close()
    }

    fun deleteHistory(serverUrl: String, token: String) {
        val request = authRequest("${base(serverUrl)}/api/delete_history", token)
            .post("{}".toRequestBody("application/json".toMediaType()))
            .build()
        execute(request).close()
    }

    fun deleteDirectConversation(serverUrl: String, token: String, conversationId: Long) {
        val request = authRequest("${base(serverUrl)}/api/conversations/$conversationId", token)
            .delete()
            .build()
        execute(request).close()
    }

    fun deleteGroupConversation(serverUrl: String, token: String, conversationId: Long) {
        val request = authRequest("${base(serverUrl)}/api/conversations/$conversationId", token)
            .delete()
            .build()
        execute(request).close()
    }

    fun leaveGroupConversation(serverUrl: String, token: String, conversationId: Long) {
        val request = authRequest("${base(serverUrl)}/api/conversations/$conversationId/leave", token)
            .post("{}".toRequestBody("application/json".toMediaType()))
            .build()
        execute(request).close()
    }

    fun requestAccountDeletion(serverUrl: String, token: String) {
        val request = authRequest("${base(serverUrl)}/api/account_deletion_request", token)
            .post("{}".toRequestBody("application/json".toMediaType()))
            .build()
        execute(request).close()
    }

    fun recallMessage(serverUrl: String, token: String, messageId: Long) {
        val request = authRequest("${base(serverUrl)}/api/messages/$messageId/recall", token)
            .post("{}".toRequestBody("application/json".toMediaType()))
            .build()
        execute(request).close()
    }

    fun uploadAvatar(
        serverUrl: String,
        token: String,
        fileName: String,
        mime: String,
        bytes: ByteArray,
        onProgress: ((bytesDone: Long, totalBytes: Long) -> Unit)? = null,
    ): String {
        val tempFile = File.createTempFile("avatar_", fileName.substringAfterLast('.', "jpg"))
        tempFile.writeBytes(bytes)
        return try {
            val avatarBody = ProgressRequestBody(tempFile.asRequestBody(mime.toMediaType()), onProgress)
            val form = MultipartBody.Builder().setType(MultipartBody.FORM)
                .addFormDataPart("avatar", fileName, avatarBody)
                .build()
            val request = authRequest("${base(serverUrl)}/api/avatar", token).post(form).build()
            execute(request).use { response ->
                JSONObject(response.body!!.string()).optString("avatar_url")
            }
        } finally {
            tempFile.delete()
        }
    }

    fun uploadConversationAvatar(
        serverUrl: String,
        token: String,
        conversationId: Long,
        fileName: String,
        mime: String,
        bytes: ByteArray,
        onProgress: ((bytesDone: Long, totalBytes: Long) -> Unit)? = null,
    ): String {
        val tempFile = File.createTempFile("group_avatar_", fileName.substringAfterLast('.', "jpg"))
        tempFile.writeBytes(bytes)
        return try {
            val avatarBody = ProgressRequestBody(tempFile.asRequestBody(mime.toMediaType()), onProgress)
            val form = MultipartBody.Builder().setType(MultipartBody.FORM)
                .addFormDataPart("avatar", fileName, avatarBody)
                .build()
            val request = authRequest("${base(serverUrl)}/api/conversations/$conversationId/avatar", token)
                .post(form)
                .build()
            execute(request).use { response ->
                JSONObject(response.body!!.string()).optString("avatar_url")
            }
        } finally {
            tempFile.delete()
        }
    }

    fun uploadAttachment(
        serverUrl: String,
        token: String,
        conversationId: Long,
        kind: String,
        payloadJson: String,
        replyJson: String,
        clientMessageId: String,
        encryptedFile: File,
        onProgress: ((bytesDone: Long, totalBytes: Long) -> Unit)? = null,
    ): ChatMessage {
        val fileBody = ProgressFileRequestBody(
            encryptedFile,
            "application/octet-stream".toMediaType(),
            onProgress
        )
        val form = MultipartBody.Builder().setType(MultipartBody.FORM)
            .addFormDataPart("conversation_id", conversationId.toString())
            .addFormDataPart("kind", kind)
            .addFormDataPart("payload", payloadJson)
            .addFormDataPart("reply_to", replyJson)
            .addFormDataPart("client_message_id", clientMessageId)
            .addFormDataPart("mentions", "[]")
            .addFormDataPart("attachment", "$kind.enc", fileBody)
            .build()
        val request = authRequest("${base(serverUrl)}/api/upload_attachment", token).post(form).build()
        execute(request).use { response ->
            val json = JSONObject(response.body!!.string())
            return parseMessage(json.getJSONObject("message"))
        }
    }

    internal fun beginAttachmentUpload(
        serverUrl: String,
        token: String,
        conversationId: Long,
        kind: String,
        payloadJson: String,
        replyJson: String,
        clientMessageId: String,
        totalSize: Long,
        checksumSha256: String,
    ): RemoteAttachmentUpload {
        val body = JSONObject()
            .put("conversation_id", conversationId)
            .put("kind", kind)
            .put("payload", payloadJson)
            .put("reply_to", replyJson)
            .put("mentions", JSONArray())
            .put("client_message_id", clientMessageId)
            .put("total_size", totalSize)
            .put("checksum_sha256", checksumSha256)
            .toString()
        val request = authRequest("${base(serverUrl)}/api/attachment_uploads", token)
            .post(body.toRequestBody("application/json".toMediaType()))
            .build()
        execute(request).use { response ->
            val json = JSONObject(response.body!!.string())
            return RemoteAttachmentUpload(
                uploadId = json.optString("upload_id"),
                offset = json.optLong("offset", 0L),
                completedMessage = json.optJSONObject("message")?.let(::parseMessage),
            )
        }
    }

    fun uploadAttachmentChunk(
        serverUrl: String,
        token: String,
        uploadId: String,
        offset: Long,
        bytes: ByteArray,
    ): Long {
        val request = authRequest("${base(serverUrl)}/api/attachment_uploads/$uploadId/chunk", token)
            .header("X-Upload-Offset", offset.toString())
            .put(bytes.toRequestBody("application/octet-stream".toMediaType()))
            .build()
        execute(request).use { response ->
            return JSONObject(response.body!!.string()).getLong("offset")
        }
    }

    fun completeAttachmentUpload(
        serverUrl: String,
        token: String,
        uploadId: String,
        clientMessageId: String,
    ): ChatMessage {
        val body = JSONObject().put("client_message_id", clientMessageId).toString()
        val request = authRequest("${base(serverUrl)}/api/attachment_uploads/$uploadId/complete", token)
            .post(body.toRequestBody("application/json".toMediaType()))
            .build()
        execute(request).use { response ->
            return parseMessage(JSONObject(response.body!!.string()).getJSONObject("message"))
        }
    }

    fun connect(serverUrl: String, token: String, listener: WebSocketListener): WebSocket {
        val http = base(serverUrl).replaceFirst("https://", "wss://").replaceFirst("http://", "ws://")
        val encoded = URLEncoder.encode(token, "UTF-8")
        return client.newWebSocket(
            requestBuilder("$http/ws?token=$encoded").build(),
            listener
        )
    }

    fun download(
        serverUrl: String,
        path: String,
        onProgress: ((bytesDone: Long, totalBytes: Long) -> Unit)? = null,
    ): ByteArray {
        val absolute = if (path.startsWith("http")) path else "${base(serverUrl)}$path"
        execute(requestBuilder(absolute).get().build()).use { response ->
            val body = response.body ?: return ByteArray(0)
            val total = body.contentLength().coerceAtLeast(0L)
            val source = body.source()
            val output = ByteArrayOutputStream()
            val buffer = ByteArray(8_192)
            var done = 0L
            while (true) {
                val read = source.read(buffer, 0, buffer.size)
                if (read <= 0) break
                output.write(buffer, 0, read)
                done += read.toLong()
                onProgress?.invoke(done, total)
            }
            return output.toByteArray()
        }
    }

    fun downloadToFile(
        serverUrl: String,
        path: String,
        targetFile: File,
        onProgress: ((bytesDone: Long, totalBytes: Long) -> Unit)? = null,
    ) {
        val absolute = if (path.startsWith("http")) path else "${base(serverUrl)}$path"
        targetFile.parentFile?.mkdirs()
        execute(requestBuilder(absolute).get().build()).use { response ->
            val body = response.body ?: return
            val total = body.contentLength().coerceAtLeast(0L)
            val buffer = ByteArray(64 * 1024)
            var done = 0L
            try {
                body.byteStream().use { input ->
                    targetFile.outputStream().use { output ->
                        while (true) {
                            val read = input.read(buffer)
                            if (read <= 0) break
                            output.write(buffer, 0, read)
                            done += read.toLong()
                            onProgress?.invoke(done, total)
                        }
                    }
                }
            } catch (error: Throwable) {
                targetFile.delete()
                throw error
            }
        }
    }

    companion object {
        fun parseUsers(text: String): List<ChatUser> {
            val items = JSONObject(text).optJSONArray("items") ?: JSONArray()
            return List(items.length()) { index ->
                val item = items.getJSONObject(index)
                ChatUser(
                    userCode = item.optString("user_code"),
                    username = item.getString("username"),
                    color = item.getString("color"),
                    avatarUrl = item.optString("avatar_url"),
                    isAdmin = item.optBoolean("is_admin")
                )
            }
        }

        fun parseMessage(json: JSONObject): ChatMessage {
            val reply = json.optString("reply_to").takeIf { it.isNotBlank() }?.let {
                val obj = JSONObject(it)
                ReplyPreview(
                    id = obj.optLong("id"),
                    username = obj.optString("username"),
                    color = obj.optString("color"),
                    preview = obj.optString("preview")
                )
            }
            val mentionsArray = json.optJSONArray("mentions") ?: JSONArray()
            val mentions = List(mentionsArray.length()) { mentionsArray.optString(it) }
            return ChatMessage(
                id = json.getLong("id"),
                conversationId = json.optLong("conversation_id", 0L),
                ts = json.getLong("ts"),
                expiresAt = json.optLong("expires_at", 0L),
                userCode = json.optString("user_code"),
                username = json.getString("username"),
                color = json.getString("color"),
                kind = json.getString("kind"),
                payload = json.optString("payload"),
                e2ee = json.optInt("e2ee", 0) == 1,
                replyTo = reply,
                mentions = mentions,
                clientMessageId = json.optString("client_message_id"),
            )
        }
    }
}
