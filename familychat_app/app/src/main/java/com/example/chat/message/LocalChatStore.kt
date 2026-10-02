package com.example.chat

import android.content.Context
import android.util.Base64
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.Transaction
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.nio.charset.StandardCharsets
import java.security.SecureRandom
import java.util.concurrent.ConcurrentHashMap
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

internal sealed interface PendingAction {
    data class Text(
        val text: String,
        val reply: ReplyPreview?,
    ) : PendingAction

    data class Attachment(
        val kind: String,
        val fileName: String,
        val mime: String,
        val localFilePath: String,
        val reply: ReplyPreview?,
        val durationMs: Long,
        val richContent: Boolean,
        val encryptedFilePath: String = "",
        val preparedPayload: String = "",
        val checksumSha256: String = "",
    ) : PendingAction
}

internal data class PendingOutgoing(
    val action: PendingAction,
    val message: ChatMessage,
    val clientMessageId: String = message.clientMessageId,
    val attemptCount: Int = 0,
    val nextAttemptAt: Long = 0L,
    val lastError: String = "",
)

internal data class PersistedConversation(
    val messages: List<ChatMessage> = emptyList(),
    val deliveryStates: Map<String, Long> = emptyMap(),
    val readStates: Map<String, Long> = emptyMap(),
    val outbox: List<PendingOutgoing> = emptyList(),
    val hasMoreLocal: Boolean = false,
)

internal object LocalDecryptedContentKind {
    const val TEXT = "text"
}

internal object LocalCachePolicy {
    fun confirmedMessages(
        messages: List<ChatMessage>,
        now: Long,
        limit: Int,
    ): List<ChatMessage> = messages
        .asSequence()
        .filter { it.id > 0L && (it.expiresAt == 0L || it.expiresAt > now) }
        .sortedWith(compareBy<ChatMessage> { it.ts }.thenBy { it.id })
        .toList()
        .takeLast(limit.coerceAtLeast(0))

    fun restoredPending(pending: PendingOutgoing): PendingOutgoing =
        pending.copy(message = pending.message.copy(localSendState = LocalSendState.FAILED))
}

@Entity(
    tableName = "local_conversations",
    primaryKeys = ["ownerId", "conversationId"],
    indices = [Index(value = ["ownerId", "updatedAt"])],
)
internal data class LocalConversationEntity(
    val ownerId: String,
    val conversationId: Long,
    val summaryBlob: String,
    val stateBlob: String,
    val lastConfirmedMessageId: Long,
    val updatedAt: Long,
)

@Entity(
    tableName = "local_messages",
    primaryKeys = ["ownerId", "conversationId", "messageId"],
    foreignKeys = [
        ForeignKey(
            entity = LocalConversationEntity::class,
            parentColumns = ["ownerId", "conversationId"],
            childColumns = ["ownerId", "conversationId"],
            onDelete = ForeignKey.CASCADE,
        )
    ],
    indices = [
        Index(value = ["ownerId", "conversationId"]),
        Index(value = ["ownerId", "conversationId", "timestamp"]),
    ],
)
internal data class LocalMessageEntity(
    val ownerId: String,
    val conversationId: Long,
    val messageId: Long,
    val clientMessageId: String,
    val timestamp: Long,
    val expiresAt: Long,
    val encryptedBlob: String,
)

@Entity(
    tableName = "local_outbox",
    primaryKeys = ["ownerId", "localMessageId"],
    foreignKeys = [
        ForeignKey(
            entity = LocalConversationEntity::class,
            parentColumns = ["ownerId", "conversationId"],
            childColumns = ["ownerId", "conversationId"],
            onDelete = ForeignKey.CASCADE,
        )
    ],
    indices = [Index(value = ["ownerId", "conversationId", "createdAt"])],
)
internal data class LocalOutboxEntity(
    val ownerId: String,
    val localMessageId: Long,
    val conversationId: Long,
    val createdAt: Long,
    val clientMessageId: String,
    val attemptCount: Int,
    val nextAttemptAt: Long,
    val state: String,
    val lastError: String,
    val updatedAt: Long,
    val encryptedBlob: String,
)

@Entity(
    tableName = "local_drafts",
    primaryKeys = ["ownerId", "conversationId"],
    foreignKeys = [
        ForeignKey(
            entity = LocalConversationEntity::class,
            parentColumns = ["ownerId", "conversationId"],
            childColumns = ["ownerId", "conversationId"],
            onDelete = ForeignKey.CASCADE,
        )
    ],
    indices = [Index(value = ["ownerId", "conversationId"])],
)
internal data class LocalDraftEntity(
    val ownerId: String,
    val conversationId: Long,
    val encryptedBlob: String,
    val updatedAt: Long,
)

@Entity(
    tableName = "local_decrypted_content",
    primaryKeys = ["ownerId", "conversationId", "messageId", "contentKind"],
    foreignKeys = [
        ForeignKey(
            entity = LocalConversationEntity::class,
            parentColumns = ["ownerId", "conversationId"],
            childColumns = ["ownerId", "conversationId"],
            onDelete = ForeignKey.CASCADE,
        )
    ],
    indices = [
        Index(value = ["ownerId", "conversationId"]),
        Index(value = ["ownerId", "expiresAt"]),
    ],
)
internal data class LocalDecryptedContentEntity(
    val ownerId: String,
    val conversationId: Long,
    val messageId: Long,
    val contentKind: String,
    val encryptedBlob: String,
    val expiresAt: Long,
    val updatedAt: Long,
)

@Dao
internal abstract class LocalChatDao {
    @Query("SELECT * FROM local_conversations WHERE ownerId=:ownerId AND conversationId=:conversationId")
    abstract suspend fun conversation(ownerId: String, conversationId: Long): LocalConversationEntity?

    @Query("SELECT * FROM local_conversations WHERE ownerId=:ownerId AND conversationId=:conversationId")
    abstract fun observeConversation(ownerId: String, conversationId: Long): Flow<LocalConversationEntity?>

    @Query("SELECT * FROM local_conversations WHERE ownerId=:ownerId ORDER BY updatedAt DESC")
    abstract suspend fun conversations(ownerId: String): List<LocalConversationEntity>

    @Query("SELECT * FROM local_conversations WHERE ownerId=:ownerId ORDER BY updatedAt DESC")
    abstract fun observeConversations(ownerId: String): Flow<List<LocalConversationEntity>>

    @Query("SELECT conversationId FROM local_conversations WHERE ownerId=:ownerId")
    abstract suspend fun conversationIds(ownerId: String): List<Long>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    abstract suspend fun insertConversation(entity: LocalConversationEntity): Long

    @Query(
        """
        UPDATE local_conversations
        SET summaryBlob=:summaryBlob, updatedAt=:updatedAt
        WHERE ownerId=:ownerId AND conversationId=:conversationId
        """
    )
    abstract suspend fun updateSummary(
        ownerId: String,
        conversationId: Long,
        summaryBlob: String,
        updatedAt: Long,
    )

    @Query(
        """
        UPDATE local_conversations
        SET stateBlob=:stateBlob, lastConfirmedMessageId=:lastConfirmedMessageId, updatedAt=:updatedAt
        WHERE ownerId=:ownerId AND conversationId=:conversationId
        """
    )
    abstract suspend fun updateState(
        ownerId: String,
        conversationId: Long,
        stateBlob: String,
        lastConfirmedMessageId: Long,
        updatedAt: Long,
    )

    @Query("SELECT * FROM local_messages WHERE ownerId=:ownerId AND conversationId=:conversationId ORDER BY timestamp, messageId")
    abstract suspend fun messages(ownerId: String, conversationId: Long): List<LocalMessageEntity>

    @Query("SELECT * FROM local_messages WHERE ownerId=:ownerId AND conversationId=:conversationId ORDER BY timestamp, messageId")
    abstract fun observeMessages(ownerId: String, conversationId: Long): Flow<List<LocalMessageEntity>>

    @Query(
        "SELECT * FROM (SELECT * FROM local_messages WHERE ownerId=:ownerId AND conversationId=:conversationId " +
            "ORDER BY timestamp DESC, messageId DESC LIMIT :limit) ORDER BY timestamp, messageId"
    )
    abstract fun observeRecentMessages(
        ownerId: String,
        conversationId: Long,
        limit: Int,
    ): Flow<List<LocalMessageEntity>>

    @Query("SELECT COUNT(*) FROM local_messages WHERE ownerId=:ownerId AND conversationId=:conversationId")
    abstract fun observeMessageCount(ownerId: String, conversationId: Long): Flow<Int>

    @Query(
        "SELECT * FROM local_messages WHERE ownerId=:ownerId AND conversationId=:conversationId " +
            "AND messageId < :beforeId ORDER BY messageId DESC LIMIT :limit"
    )
    abstract suspend fun messagesBefore(
        ownerId: String,
        conversationId: Long,
        beforeId: Long,
        limit: Int,
    ): List<LocalMessageEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    abstract suspend fun insertMessages(messages: List<LocalMessageEntity>)

    @Query("DELETE FROM local_messages WHERE ownerId=:ownerId AND conversationId=:conversationId")
    abstract suspend fun deleteMessages(ownerId: String, conversationId: Long)

    @Query("DELETE FROM local_messages WHERE ownerId=:ownerId AND expiresAt>0 AND expiresAt<=:now")
    abstract suspend fun deleteExpiredMessages(ownerId: String, now: Long)

    @Query("SELECT * FROM local_outbox WHERE ownerId=:ownerId AND conversationId=:conversationId ORDER BY createdAt, localMessageId")
    abstract suspend fun outbox(ownerId: String, conversationId: Long): List<LocalOutboxEntity>

    @Query("SELECT * FROM local_outbox WHERE ownerId=:ownerId AND conversationId=:conversationId ORDER BY createdAt, localMessageId")
    abstract fun observeOutbox(ownerId: String, conversationId: Long): Flow<List<LocalOutboxEntity>>

    @Query("SELECT * FROM local_outbox WHERE ownerId=:ownerId ORDER BY createdAt, localMessageId")
    abstract suspend fun allOutbox(ownerId: String): List<LocalOutboxEntity>

    @Query(
        "SELECT * FROM local_outbox WHERE ownerId=:ownerId AND nextAttemptAt<=:now " +
            "AND state IN ('QUEUED','RETRY_WAIT') ORDER BY createdAt LIMIT :limit"
    )
    abstract suspend fun dueOutbox(ownerId: String, now: Long, limit: Int): List<LocalOutboxEntity>

    @Query(
        "SELECT MIN(nextAttemptAt) FROM local_outbox WHERE ownerId=:ownerId " +
            "AND state IN ('QUEUED','RETRY_WAIT')"
    )
    abstract suspend fun nextOutboxAttemptAt(ownerId: String): Long?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    abstract suspend fun insertOutbox(entity: LocalOutboxEntity)

    @Query("DELETE FROM local_outbox WHERE ownerId=:ownerId AND localMessageId=:localMessageId")
    abstract suspend fun deleteOutbox(ownerId: String, localMessageId: Long)

    @Query("DELETE FROM local_outbox WHERE ownerId=:ownerId AND clientMessageId IN (:clientMessageIds)")
    abstract suspend fun deleteOutboxByClientMessageIds(ownerId: String, clientMessageIds: List<String>)

    @Query("DELETE FROM local_outbox WHERE ownerId=:ownerId AND conversationId=:conversationId")
    abstract suspend fun deleteConversationOutbox(ownerId: String, conversationId: Long)

    @Query("DELETE FROM local_outbox WHERE ownerId=:ownerId")
    abstract suspend fun deleteAllOutbox(ownerId: String)

    @Query(
        "UPDATE local_outbox SET state=:state, attemptCount=:attemptCount, nextAttemptAt=:nextAttemptAt, " +
            "lastError=:lastError, updatedAt=:updatedAt WHERE ownerId=:ownerId AND localMessageId=:localMessageId"
    )
    abstract suspend fun updateOutboxState(
        ownerId: String,
        localMessageId: Long,
        state: String,
        attemptCount: Int,
        nextAttemptAt: Long,
        lastError: String,
        updatedAt: Long,
    )

    @Query(
        "UPDATE local_outbox SET clientMessageId=:clientMessageId, encryptedBlob=:encryptedBlob, updatedAt=:updatedAt " +
            "WHERE ownerId=:ownerId AND localMessageId=:localMessageId"
    )
    abstract suspend fun updateOutboxPayload(
        ownerId: String,
        localMessageId: Long,
        clientMessageId: String,
        encryptedBlob: String,
        updatedAt: Long,
    )

    @Query("SELECT * FROM local_sync_cursors WHERE ownerId=:ownerId AND conversationId=:conversationId")
    abstract suspend fun syncCursor(ownerId: String, conversationId: Long): LocalSyncCursorEntity?

    @Query("SELECT * FROM local_sync_cursors WHERE ownerId=:ownerId AND conversationId=:conversationId")
    abstract fun observeSyncCursor(ownerId: String, conversationId: Long): Flow<LocalSyncCursorEntity?>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    abstract suspend fun upsertSyncCursor(entity: LocalSyncCursorEntity)

    @Query("SELECT * FROM local_receipts WHERE ownerId=:ownerId AND conversationId=:conversationId")
    abstract fun observeReceipts(ownerId: String, conversationId: Long): Flow<List<LocalReceiptEntity>>

    @Query(
        "SELECT * FROM local_receipts WHERE ownerId=:ownerId AND conversationId=:conversationId " +
            "AND participantIdentity=:participantIdentity"
    )
    abstract suspend fun receipt(
        ownerId: String,
        conversationId: Long,
        participantIdentity: String,
    ): LocalReceiptEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    abstract suspend fun upsertReceipts(entities: List<LocalReceiptEntity>)

    @Query(
        "SELECT * FROM local_receipts WHERE ownerId=:ownerId " +
            "AND (deliveredPending=1 OR readPending=1) ORDER BY updatedAt LIMIT :limit"
    )
    abstract suspend fun pendingReceipts(ownerId: String, limit: Int): List<LocalReceiptEntity>

    @Query(
        "UPDATE local_receipts SET deliveredPending=0, readPending=0, updatedAt=:updatedAt " +
            "WHERE ownerId=:ownerId AND conversationId=:conversationId AND participantIdentity=:participantIdentity"
    )
    abstract suspend fun acknowledgeReceipt(
        ownerId: String,
        conversationId: Long,
        participantIdentity: String,
        updatedAt: Long,
    )

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    abstract suspend fun upsertAttachmentTransfer(entity: LocalAttachmentTransferEntity)

    @Query("SELECT * FROM local_attachment_transfers WHERE ownerId=:ownerId AND transferId=:transferId")
    abstract fun observeAttachmentTransfer(ownerId: String, transferId: String): Flow<LocalAttachmentTransferEntity?>

    @Query(
        "SELECT * FROM local_attachment_transfers WHERE ownerId=:ownerId AND nextAttemptAt<=:now " +
            "AND state IN ('QUEUED','RETRY_WAIT','PAUSED') ORDER BY createdAt LIMIT :limit"
    )
    abstract suspend fun dueAttachmentTransfers(
        ownerId: String,
        now: Long,
        limit: Int,
    ): List<LocalAttachmentTransferEntity>

    @Query("DELETE FROM local_attachment_transfers WHERE ownerId=:ownerId AND transferId=:transferId")
    abstract suspend fun deleteAttachmentTransfer(ownerId: String, transferId: String)

    @Query("SELECT * FROM local_drafts WHERE ownerId=:ownerId AND conversationId=:conversationId")
    abstract suspend fun draft(ownerId: String, conversationId: Long): LocalDraftEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    abstract suspend fun insertDraft(entity: LocalDraftEntity)

    @Query("DELETE FROM local_drafts WHERE ownerId=:ownerId AND conversationId=:conversationId")
    abstract suspend fun deleteDraft(ownerId: String, conversationId: Long)

    @Query(
        """
        SELECT * FROM local_decrypted_content
        WHERE ownerId=:ownerId AND conversationId=:conversationId
          AND messageId=:messageId AND contentKind=:contentKind
        """
    )
    abstract suspend fun decryptedContent(
        ownerId: String,
        conversationId: Long,
        messageId: Long,
        contentKind: String,
    ): LocalDecryptedContentEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    abstract suspend fun insertDecryptedContent(entity: LocalDecryptedContentEntity)

    @Query(
        """
        DELETE FROM local_decrypted_content
        WHERE ownerId=:ownerId AND conversationId=:conversationId
          AND messageId=:messageId AND contentKind=:contentKind
        """
    )
    abstract suspend fun deleteDecryptedContent(
        ownerId: String,
        conversationId: Long,
        messageId: Long,
        contentKind: String,
    )

    @Query("DELETE FROM local_decrypted_content WHERE ownerId=:ownerId AND expiresAt>0 AND expiresAt<=:now")
    abstract suspend fun deleteExpiredDecryptedContent(ownerId: String, now: Long)

    @Query(
        """
        DELETE FROM local_decrypted_content
        WHERE ownerId=:ownerId
          AND NOT EXISTS (
              SELECT 1 FROM local_messages
              WHERE local_messages.ownerId=local_decrypted_content.ownerId
                AND local_messages.conversationId=local_decrypted_content.conversationId
                AND local_messages.messageId=local_decrypted_content.messageId
          )
        """
    )
    abstract suspend fun deleteAllOrphanedDecryptedContent(ownerId: String)

    @Query(
        """
        DELETE FROM local_decrypted_content
        WHERE ownerId=:ownerId AND conversationId=:conversationId
          AND messageId NOT IN (
              SELECT messageId FROM local_messages
              WHERE ownerId=:ownerId AND conversationId=:conversationId
          )
        """
    )
    abstract suspend fun deleteOrphanedDecryptedContent(ownerId: String, conversationId: Long)

    @Query("DELETE FROM local_conversations WHERE ownerId=:ownerId AND conversationId=:conversationId")
    abstract suspend fun deleteConversation(ownerId: String, conversationId: Long)

    @Query("DELETE FROM local_conversations WHERE ownerId=:ownerId")
    abstract suspend fun deleteOwner(ownerId: String)

    @Transaction
    open suspend fun putSummary(entity: LocalConversationEntity) {
        insertConversation(entity)
        updateSummary(entity.ownerId, entity.conversationId, entity.summaryBlob, entity.updatedAt)
    }

    @Transaction
    open suspend fun replaceSnapshot(
        conversation: LocalConversationEntity,
        messages: List<LocalMessageEntity>,
    ) {
        insertConversation(conversation)
        updateState(
            conversation.ownerId,
            conversation.conversationId,
            conversation.stateBlob,
            conversation.lastConfirmedMessageId,
            conversation.updatedAt,
        )
        deleteMessages(conversation.ownerId, conversation.conversationId)
        if (messages.isNotEmpty()) insertMessages(messages)
        deleteOrphanedDecryptedContent(conversation.ownerId, conversation.conversationId)
    }

    @Transaction
    open suspend fun mergeSnapshot(
        conversation: LocalConversationEntity,
        messages: List<LocalMessageEntity>,
        cursor: LocalSyncCursorEntity,
    ) {
        insertConversation(conversation)
        updateState(
            conversation.ownerId,
            conversation.conversationId,
            conversation.stateBlob,
            conversation.lastConfirmedMessageId,
            conversation.updatedAt,
        )
        if (messages.isNotEmpty()) insertMessages(messages)
        val confirmedClientIds = messages.map(LocalMessageEntity::clientMessageId).filter(String::isNotBlank)
        if (confirmedClientIds.isNotEmpty()) deleteOutboxByClientMessageIds(conversation.ownerId, confirmedClientIds)
        upsertSyncCursor(cursor)
        deleteOrphanedDecryptedContent(conversation.ownerId, conversation.conversationId)
    }

    @Transaction
    open suspend fun putOutbox(conversation: LocalConversationEntity, outbox: LocalOutboxEntity) {
        insertConversation(conversation)
        insertOutbox(outbox)
    }

    @Transaction
    open suspend fun putDraft(conversation: LocalConversationEntity, draft: LocalDraftEntity) {
        insertConversation(conversation)
        insertDraft(draft)
    }

    @Transaction
    open suspend fun putDecryptedContent(
        conversation: LocalConversationEntity,
        message: LocalMessageEntity,
        content: LocalDecryptedContentEntity,
    ) {
        insertConversation(conversation)
        insertMessages(listOf(message))
        insertDecryptedContent(content)
    }
}

@Database(
    entities = [
        LocalConversationEntity::class,
        LocalMessageEntity::class,
        LocalOutboxEntity::class,
        LocalDraftEntity::class,
        LocalDecryptedContentEntity::class,
        LocalSyncCursorEntity::class,
        LocalReceiptEntity::class,
        LocalAttachmentTransferEntity::class,
    ],
    version = 3,
    exportSchema = true,
)
internal abstract class FamilyChatLocalDatabase : RoomDatabase() {
    abstract fun localChatDao(): LocalChatDao

    companion object {
        @Volatile
        private var instance: FamilyChatLocalDatabase? = null

        fun get(context: Context): FamilyChatLocalDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    FamilyChatLocalDatabase::class.java,
                    "familychat-local-cache.sqlite",
                )
                    .setJournalMode(JournalMode.WRITE_AHEAD_LOGGING)
                    .addMigrations(MIGRATION_1_2, MIGRATION_2_3)
                    .build()
                    .also { instance = it }
            }

        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `local_decrypted_content` (
                        `ownerId` TEXT NOT NULL,
                        `conversationId` INTEGER NOT NULL,
                        `messageId` INTEGER NOT NULL,
                        `contentKind` TEXT NOT NULL,
                        `encryptedBlob` TEXT NOT NULL,
                        `expiresAt` INTEGER NOT NULL,
                        `updatedAt` INTEGER NOT NULL,
                        PRIMARY KEY(`ownerId`, `conversationId`, `messageId`, `contentKind`),
                        FOREIGN KEY(`ownerId`, `conversationId`)
                            REFERENCES `local_conversations`(`ownerId`, `conversationId`)
                            ON UPDATE NO ACTION ON DELETE CASCADE
                    )
                    """.trimIndent()
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_local_decrypted_content_ownerId_conversationId` " +
                        "ON `local_decrypted_content` (`ownerId`, `conversationId`)"
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_local_decrypted_content_ownerId_expiresAt` " +
                        "ON `local_decrypted_content` (`ownerId`, `expiresAt`)"
                )
            }
        }

        internal val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `local_messages` ADD COLUMN `clientMessageId` TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE `local_outbox` ADD COLUMN `clientMessageId` TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE `local_outbox` ADD COLUMN `attemptCount` INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE `local_outbox` ADD COLUMN `nextAttemptAt` INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE `local_outbox` ADD COLUMN `state` TEXT NOT NULL DEFAULT 'QUEUED'")
                db.execSQL("ALTER TABLE `local_outbox` ADD COLUMN `lastError` TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE `local_outbox` ADD COLUMN `updatedAt` INTEGER NOT NULL DEFAULT 0")
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `local_sync_cursors` (
                        `ownerId` TEXT NOT NULL,
                        `conversationId` INTEGER NOT NULL,
                        `newestMessageId` INTEGER NOT NULL,
                        `oldestMessageId` INTEGER NOT NULL,
                        `hasMoreBefore` INTEGER NOT NULL,
                        `phase` TEXT NOT NULL,
                        `continuationCursor` TEXT NOT NULL,
                        `lastSuccessfulSyncAt` INTEGER NOT NULL,
                        `lastAttemptAt` INTEGER NOT NULL,
                        `lastError` TEXT NOT NULL,
                        `updatedAt` INTEGER NOT NULL,
                        PRIMARY KEY(`ownerId`, `conversationId`),
                        FOREIGN KEY(`ownerId`, `conversationId`)
                            REFERENCES `local_conversations`(`ownerId`, `conversationId`)
                            ON UPDATE NO ACTION ON DELETE CASCADE
                    )
                    """.trimIndent()
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_local_sync_cursors_ownerId_updatedAt` " +
                        "ON `local_sync_cursors` (`ownerId`, `updatedAt`)"
                )
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `local_receipts` (
                        `ownerId` TEXT NOT NULL,
                        `conversationId` INTEGER NOT NULL,
                        `participantIdentity` TEXT NOT NULL,
                        `deliveredMessageId` INTEGER NOT NULL,
                        `readMessageId` INTEGER NOT NULL,
                        `deliveredPending` INTEGER NOT NULL,
                        `readPending` INTEGER NOT NULL,
                        `updatedAt` INTEGER NOT NULL,
                        PRIMARY KEY(`ownerId`, `conversationId`, `participantIdentity`),
                        FOREIGN KEY(`ownerId`, `conversationId`)
                            REFERENCES `local_conversations`(`ownerId`, `conversationId`)
                            ON UPDATE NO ACTION ON DELETE CASCADE
                    )
                    """.trimIndent()
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_local_receipts_ownerId_conversationId` " +
                        "ON `local_receipts` (`ownerId`, `conversationId`)"
                )
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `local_attachment_transfers` (
                        `ownerId` TEXT NOT NULL,
                        `transferId` TEXT NOT NULL,
                        `clientMessageId` TEXT NOT NULL,
                        `localMessageId` INTEGER NOT NULL,
                        `conversationId` INTEGER NOT NULL,
                        `direction` TEXT NOT NULL,
                        `localFilePath` TEXT NOT NULL,
                        `remotePath` TEXT NOT NULL,
                        `checksumSha256` TEXT NOT NULL,
                        `bytesTransferred` INTEGER NOT NULL,
                        `totalBytes` INTEGER NOT NULL,
                        `state` TEXT NOT NULL,
                        `attemptCount` INTEGER NOT NULL,
                        `nextAttemptAt` INTEGER NOT NULL,
                        `lastError` TEXT NOT NULL,
                        `createdAt` INTEGER NOT NULL,
                        `updatedAt` INTEGER NOT NULL,
                        PRIMARY KEY(`ownerId`, `transferId`),
                        FOREIGN KEY(`ownerId`, `conversationId`)
                            REFERENCES `local_conversations`(`ownerId`, `conversationId`)
                            ON UPDATE NO ACTION ON DELETE CASCADE
                    )
                    """.trimIndent()
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_local_attachment_transfers_ownerId_conversationId_updatedAt` ON `local_attachment_transfers` (`ownerId`, `conversationId`, `updatedAt`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_local_attachment_transfers_ownerId_state_nextAttemptAt` ON `local_attachment_transfers` (`ownerId`, `state`, `nextAttemptAt`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_local_attachment_transfers_ownerId_clientMessageId` ON `local_attachment_transfers` (`ownerId`, `clientMessageId`)")
            }
        }
    }
}

private class LocalCacheCipher(context: Context) {
    private val secureStore by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        SecureCryptoStore(context.applicationContext)
    }
    private val random = SecureRandom()

    @Volatile
    private var cachedKey: SecretKeySpec? = null

    fun encrypt(ownerId: String, scope: String, plainText: String): String {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(Cipher.ENCRYPT_MODE, dataKey())
            updateAAD(aad(ownerId, scope))
        }
        val encrypted = cipher.doFinal(plainText.toByteArray(StandardCharsets.UTF_8))
        return "$FORMAT_PREFIX${Base64.encodeToString(cipher.iv + encrypted, Base64.NO_WRAP)}"
    }

    fun decrypt(ownerId: String, scope: String, encoded: String): String {
        require(encoded.startsWith(FORMAT_PREFIX)) { "unsupported_local_cache_format" }
        val bytes = Base64.decode(encoded.removePrefix(FORMAT_PREFIX), Base64.NO_WRAP)
        require(bytes.size > IV_BYTES) { "invalid_local_cache_record" }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(Cipher.DECRYPT_MODE, dataKey(), GCMParameterSpec(128, bytes.copyOfRange(0, IV_BYTES)))
            updateAAD(aad(ownerId, scope))
        }
        return String(cipher.doFinal(bytes.copyOfRange(IV_BYTES, bytes.size)), StandardCharsets.UTF_8)
    }

    fun destroyDataKey() = synchronized(this) {
        cachedKey = null
        secureStore.remove(DATA_KEY_NAME)
    }

    private fun dataKey(): SecretKeySpec {
        cachedKey?.let { return it }
        return synchronized(this) {
            cachedKey ?: run {
                val stored = secureStore.get(DATA_KEY_NAME)
                val bytes = runCatching { Base64.decode(stored, Base64.NO_WRAP) }
                    .getOrNull()
                    ?.takeIf { it.size == DATA_KEY_BYTES }
                    ?: ByteArray(DATA_KEY_BYTES).also {
                        random.nextBytes(it)
                        secureStore.put(DATA_KEY_NAME, Base64.encodeToString(it, Base64.NO_WRAP))
                    }
                SecretKeySpec(bytes, "AES").also { cachedKey = it }
            }
        }
    }

    private fun aad(ownerId: String, scope: String): ByteArray =
        "familychat:local-cache:v1|owner=$ownerId|scope=$scope".toByteArray(StandardCharsets.UTF_8)

    companion object {
        private const val DATA_KEY_NAME = "local_cache_data_key_v1"
        private const val DATA_KEY_BYTES = 32
        private const val IV_BYTES = 12
        private const val FORMAT_PREFIX = "v1:"
    }
}

internal object LocalChatCodec {
    fun encodeMessage(message: ChatMessage): String = JSONObject()
        .put("id", message.id)
        .put("client_message_id", message.clientMessageId)
        .put("conversation_id", message.conversationId)
        .put("ts", message.ts)
        .put("expires_at", message.expiresAt)
        .put("user_code", message.userCode)
        .put("username", message.username)
        .put("color", message.color)
        .put("kind", message.kind)
        .put("payload", message.payload)
        .put("e2ee", message.e2ee)
        .put("reply_to", message.replyTo?.let(::encodeReply) ?: JSONObject.NULL)
        .put("mentions", JSONArray(message.mentions))
        .put("local_send_state", message.localSendState.name)
        .put("local_attachment_path", message.localAttachmentPath)
        .put("local_attachment_name", message.localAttachmentName)
        .put("local_attachment_mime", message.localAttachmentMime)
        .put("local_attachment_size", message.localAttachmentSize)
        .put("local_attachment_duration_ms", message.localAttachmentDurationMs)
        .toString()

    fun decodeMessage(raw: String): ChatMessage {
        val json = JSONObject(raw)
        val mentions = json.optJSONArray("mentions") ?: JSONArray()
        return ChatMessage(
            id = json.getLong("id"),
            conversationId = json.getLong("conversation_id"),
            ts = json.getLong("ts"),
            expiresAt = json.optLong("expires_at", 0L),
            userCode = json.optString("user_code"),
            username = json.optString("username"),
            color = json.optString("color"),
            kind = json.optString("kind"),
            payload = json.optString("payload"),
            e2ee = json.optBoolean("e2ee", true),
            replyTo = json.optJSONObject("reply_to")?.let(::decodeReply),
            mentions = List(mentions.length()) { mentions.optString(it) },
            localSendState = runCatching {
                LocalSendState.valueOf(json.optString("local_send_state", LocalSendState.SENT.name))
            }.getOrDefault(LocalSendState.SENT),
            localAttachmentPath = json.optString("local_attachment_path"),
            localAttachmentName = json.optString("local_attachment_name"),
            localAttachmentMime = json.optString("local_attachment_mime"),
            localAttachmentSize = json.optLong("local_attachment_size", 0L),
            localAttachmentDurationMs = json.optLong("local_attachment_duration_ms", 0L),
            clientMessageId = json.optString("client_message_id"),
        )
    }

    fun encodeSummary(summary: ConversationSummary): String = JSONObject()
        .put("id", summary.id)
        .put("kind", summary.kind)
        .put("slug", summary.slug)
        .put("group_code", summary.groupCode)
        .put("title", summary.title)
        .put("avatar_url", summary.avatarUrl)
        .put("direct_user_code", summary.directUserCode)
        .put("direct_username", summary.directUsername)
        .put("last_message_ts", summary.lastMessageTs)
        .put("last_message_preview", summary.lastMessagePreview)
        .put("unread_count", summary.unreadCount)
        .put("last_read_message_id", summary.lastReadMessageId)
        .toString()

    fun decodeSummary(raw: String): ConversationSummary {
        val json = JSONObject(raw)
        return ConversationSummary(
            id = json.getLong("id"),
            kind = json.optString("kind"),
            slug = json.optString("slug"),
            groupCode = json.optString("group_code"),
            title = json.optString("title"),
            avatarUrl = json.optString("avatar_url"),
            directUserCode = json.optString("direct_user_code"),
            directUsername = json.optString("direct_username"),
            lastMessageTs = json.optLong("last_message_ts", 0L),
            lastMessagePreview = json.optString("last_message_preview"),
            unreadCount = json.optInt("unread_count", 0),
            lastReadMessageId = json.optLong("last_read_message_id", 0L),
        )
    }

    fun encodeReceiptState(deliveryStates: Map<String, Long>, readStates: Map<String, Long>): String =
        JSONObject()
            .put("delivery", encodeLongMap(deliveryStates))
            .put("read", encodeLongMap(readStates))
            .toString()

    fun decodeReceiptState(raw: String): Pair<Map<String, Long>, Map<String, Long>> {
        if (raw.isBlank()) return emptyMap<String, Long>() to emptyMap()
        val json = JSONObject(raw)
        return decodeLongMap(json.optJSONObject("delivery")) to decodeLongMap(json.optJSONObject("read"))
    }

    fun encodePending(pending: PendingOutgoing): String {
        val action = when (val value = pending.action) {
            is PendingAction.Text -> JSONObject()
                .put("type", "text")
                .put("text", value.text)
                .put("reply", value.reply?.let(::encodeReply) ?: JSONObject.NULL)

            is PendingAction.Attachment -> JSONObject()
                .put("type", "attachment")
                .put("kind", value.kind)
                .put("file_name", value.fileName)
                .put("mime", value.mime)
                .put("local_file_path", value.localFilePath)
                .put("reply", value.reply?.let(::encodeReply) ?: JSONObject.NULL)
                .put("duration_ms", value.durationMs)
                .put("rich_content", value.richContent)
                .put("encrypted_file_path", value.encryptedFilePath)
                .put("prepared_payload", value.preparedPayload)
                .put("checksum_sha256", value.checksumSha256)
        }
        return JSONObject()
            .put("message", JSONObject(encodeMessage(pending.message)))
            .put("action", action)
            .put("client_message_id", pending.clientMessageId)
            .put("attempt_count", pending.attemptCount)
            .put("next_attempt_at", pending.nextAttemptAt)
            .put("last_error", pending.lastError)
            .toString()
    }

    fun decodePending(raw: String): PendingOutgoing {
        val json = JSONObject(raw)
        val message = decodeMessage(json.getJSONObject("message").toString())
        val actionJson = json.getJSONObject("action")
        val action = when (actionJson.getString("type")) {
            "text" -> PendingAction.Text(
                text = actionJson.optString("text"),
                reply = actionJson.optJSONObject("reply")?.let(::decodeReply),
            )

            "attachment" -> PendingAction.Attachment(
                kind = actionJson.optString("kind"),
                fileName = actionJson.optString("file_name"),
                mime = actionJson.optString("mime"),
                localFilePath = actionJson.optString("local_file_path"),
                reply = actionJson.optJSONObject("reply")?.let(::decodeReply),
                durationMs = actionJson.optLong("duration_ms", 0L),
                richContent = actionJson.optBoolean("rich_content", false),
                encryptedFilePath = actionJson.optString("encrypted_file_path"),
                preparedPayload = actionJson.optString("prepared_payload"),
                checksumSha256 = actionJson.optString("checksum_sha256"),
            )

            else -> error("unsupported_outbox_action")
        }
        return PendingOutgoing(
            action = action,
            message = message,
            clientMessageId = json.optString("client_message_id").ifBlank { message.clientMessageId },
            attemptCount = json.optInt("attempt_count", 0),
            nextAttemptAt = json.optLong("next_attempt_at", 0L),
            lastError = json.optString("last_error"),
        )
    }

    private fun encodeReply(reply: ReplyPreview): JSONObject = JSONObject()
        .put("id", reply.id)
        .put("username", reply.username)
        .put("color", reply.color)
        .put("preview", reply.preview)

    private fun decodeReply(json: JSONObject): ReplyPreview = ReplyPreview(
        id = json.optLong("id", 0L),
        username = json.optString("username"),
        color = json.optString("color"),
        preview = json.optString("preview"),
    )

    private fun encodeLongMap(values: Map<String, Long>): JSONObject = JSONObject().apply {
        values.forEach { (key, value) -> if (key.isNotBlank()) put(key, value) }
    }

    private fun decodeLongMap(json: JSONObject?): Map<String, Long> {
        if (json == null) return emptyMap()
        return buildMap {
            val keys = json.keys()
            while (keys.hasNext()) {
                val key = keys.next()
                if (key.isNotBlank()) put(key, json.optLong(key, 0L))
            }
        }
    }
}

internal class LocalChatRepository(context: Context) {
    private data class CachedMessage(val encryptedBlob: String, val value: ChatMessage?)

    private data class CachedOutbox(
        val encryptedBlob: String,
        val state: String,
        val attemptCount: Int,
        val nextAttemptAt: Long,
        val lastError: String,
        val value: PendingOutgoing?,
    )

    private val dao = FamilyChatLocalDatabase.get(context).localChatDao()
    private val cipher by lazy(LazyThreadSafetyMode.SYNCHRONIZED) { LocalCacheCipher(context) }
    private val mutex = Mutex()
    private val messageWindows = ConcurrentHashMap<String, MutableStateFlow<Int>>()
    private val decodedMessageCache = object : LinkedHashMap<String, CachedMessage>(384, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, CachedMessage>?): Boolean = size > 768
    }
    private val decodedOutboxCache = object : LinkedHashMap<String, CachedOutbox>(32, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, CachedOutbox>?): Boolean = size > 96
    }

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    fun observeConversation(ownerId: String, conversationId: Long): Flow<PersistedConversation> {
        if (ownerId.isBlank() || conversationId <= 0L) {
            return kotlinx.coroutines.flow.flowOf(PersistedConversation())
        }
        val windowKey = "$ownerId:$conversationId"
        val window = messageWindows.getOrPut(windowKey) { MutableStateFlow(INITIAL_MESSAGE_WINDOW) }
        val visibleMessages = window.flatMapLatest { limit ->
            dao.observeRecentMessages(ownerId, conversationId, limit)
        }
        return combine(
            visibleMessages,
            dao.observeOutbox(ownerId, conversationId),
            dao.observeReceipts(ownerId, conversationId),
            dao.observeMessageCount(ownerId, conversationId),
        ) { messageEntities, outboxEntities, receipts, totalMessageCount ->
            val messages = messageEntities.mapNotNull { decodeMessageEntity(ownerId, it) }
            val outbox = outboxEntities.mapNotNull { decodeOutboxEntity(ownerId, it) }
            PersistedConversation(
                messages = messages,
                deliveryStates = receipts.associate { it.participantIdentity to it.deliveredMessageId },
                readStates = receipts.associate { it.participantIdentity to it.readMessageId },
                outbox = outbox,
                hasMoreLocal = totalMessageCount > window.value,
            )
        }
            .distinctUntilChanged()
            .flowOn(Dispatchers.IO)
    }

    fun observeSummaries(ownerId: String): Flow<List<ConversationSummary>> {
        if (ownerId.isBlank()) return kotlinx.coroutines.flow.flowOf(emptyList())
        return dao.observeConversations(ownerId)
            .map { entities ->
                entities.mapNotNull { entity ->
                    entity.summaryBlob.takeIf(String::isNotBlank)?.let { blob ->
                        runCatching {
                            LocalChatCodec.decodeSummary(
                                cipher.decrypt(ownerId, summaryScope(entity.conversationId), blob)
                            )
                        }.getOrNull()
                    }
                }.sortedByDescending(ConversationSummary::lastMessageTs)
            }
            .distinctUntilChanged()
            .flowOn(Dispatchers.IO)
    }

    fun expandMessageWindow(ownerId: String, conversationId: Long, increment: Int = MESSAGE_WINDOW_INCREMENT) {
        if (ownerId.isBlank() || conversationId <= 0L) return
        val key = "$ownerId:$conversationId"
        val window = messageWindows.getOrPut(key) { MutableStateFlow(INITIAL_MESSAGE_WINDOW) }
        window.value = (window.value + increment.coerceAtLeast(1)).coerceAtMost(MAX_MESSAGE_WINDOW)
    }

    fun observeSyncCursor(ownerId: String, conversationId: Long): Flow<ConversationSyncCursor> =
        dao.observeSyncCursor(ownerId, conversationId)
            .map { it?.toModel() ?: ConversationSyncCursor(conversationId = conversationId) }
            .distinctUntilChanged()
            .flowOn(Dispatchers.IO)

    suspend fun syncCursor(ownerId: String, conversationId: Long): ConversationSyncCursor = mutex.withLock {
        if (ownerId.isBlank() || conversationId <= 0L) return@withLock ConversationSyncCursor(conversationId)
        dao.syncCursor(ownerId, conversationId)?.toModel() ?: ConversationSyncCursor(conversationId)
    }

    suspend fun conversationIds(ownerId: String): List<Long> = mutex.withLock {
        if (ownerId.isBlank()) emptyList() else dao.conversationIds(ownerId)
    }

    suspend fun loadConversation(ownerId: String, conversationId: Long): PersistedConversation = mutex.withLock {
        if (ownerId.isBlank() || conversationId <= 0L) return@withLock PersistedConversation()
        pruneExpiredContent(ownerId, System.currentTimeMillis())

        val conversation = dao.conversation(ownerId, conversationId)
        val receiptState = conversation?.stateBlob
            ?.takeIf { it.isNotBlank() }
            ?.let { blob ->
                runCatching {
                    LocalChatCodec.decodeReceiptState(cipher.decrypt(ownerId, stateScope(conversationId), blob))
                }.onFailure { logDiscard("conversation_state", conversationId, 0L, it) }
                    .getOrNull()
            }
            ?: (emptyMap<String, Long>() to emptyMap())

        val invalidMessageIds = mutableListOf<Long>()
        val messages = dao.messages(ownerId, conversationId).mapNotNull { entity ->
            runCatching {
                LocalChatCodec.decodeMessage(
                    cipher.decrypt(ownerId, messageScope(conversationId, entity.messageId), entity.encryptedBlob)
                )
            }.onFailure {
                invalidMessageIds += entity.messageId
                logDiscard("message", conversationId, entity.messageId, it)
            }.getOrNull()
        }
        if (invalidMessageIds.isNotEmpty()) {
            val valid = messages.map { it.id }.toSet()
            dao.deleteMessages(ownerId, conversationId)
            val validEntities = messages
                .filter { it.id in valid }
                .map { messageEntity(ownerId, it) }
            if (validEntities.isNotEmpty()) dao.insertMessages(validEntities)
        }

        val invalidOutboxIds = mutableListOf<Long>()
        val outbox = dao.outbox(ownerId, conversationId).mapNotNull { entity ->
            runCatching {
                LocalChatCodec.decodePending(
                    cipher.decrypt(ownerId, outboxScope(conversationId, entity.localMessageId), entity.encryptedBlob)
                ).let { pending ->
                    require(pending.message.id == entity.localMessageId)
                    require(pending.message.conversationId == conversationId)
                    LocalCachePolicy.restoredPending(
                        pending.copy(
                            message = pending.message.copy(
                                clientMessageId = pending.message.clientMessageId.ifBlank { entity.clientMessageId }
                            ),
                            clientMessageId = pending.clientMessageId.ifBlank { entity.clientMessageId },
                            attemptCount = entity.attemptCount,
                            nextAttemptAt = entity.nextAttemptAt,
                            lastError = entity.lastError,
                        )
                    )
                }
            }.onFailure {
                invalidOutboxIds += entity.localMessageId
                logDiscard("outbox", conversationId, entity.localMessageId, it)
            }.getOrNull()
        }
        invalidOutboxIds.forEach { dao.deleteOutbox(ownerId, it) }
        dao.deleteAllOrphanedDecryptedContent(ownerId)

        PersistedConversation(
            messages = messages.sortedWith(compareBy<ChatMessage> { it.ts }.thenBy { it.id }),
            deliveryStates = receiptState.first,
            readStates = receiptState.second,
            outbox = outbox,
        )
    }

    suspend fun saveConversation(
        ownerId: String,
        conversationId: Long,
        messages: List<ChatMessage>,
        deliveryStates: Map<String, Long>,
        readStates: Map<String, Long>,
    ) = mutex.withLock {
        if (ownerId.isBlank() || conversationId <= 0L) return@withLock
        val now = System.currentTimeMillis()
        val confirmed = LocalCachePolicy.confirmedMessages(messages, now, MAX_CACHED_MESSAGES)
        val existingConversation = dao.conversation(ownerId, conversationId)
        val stateBlob = if (deliveryStates.isEmpty() && readStates.isEmpty()) {
            existingConversation?.stateBlob.orEmpty()
        } else {
            cipher.encrypt(
                ownerId,
                stateScope(conversationId),
                LocalChatCodec.encodeReceiptState(deliveryStates, readStates),
            )
        }
        val conversation = placeholderConversation(ownerId, conversationId, now).copy(
            stateBlob = stateBlob,
            lastConfirmedMessageId = confirmed.maxOfOrNull { it.id } ?: 0L,
        )
        dao.replaceSnapshot(conversation, confirmed.map { messageEntity(ownerId, it) })
        pruneExpiredContent(ownerId, now)
    }

    suspend fun mergeRemotePage(
        ownerId: String,
        conversationId: Long,
        messages: List<ChatMessage>,
        deliveryStates: Map<String, Long>,
        readStates: Map<String, Long>,
        hasMoreBefore: Boolean,
        phase: SyncPhase = SyncPhase.IDLE,
        continuationCursor: String = "",
        error: String = "",
    ) = mutex.withLock {
        if (ownerId.isBlank() || conversationId <= 0L) return@withLock
        val now = System.currentTimeMillis()
        val existing = dao.syncCursor(ownerId, conversationId)
        val confirmed = messages.filter { it.id > 0L }
        val newest = maxOf(existing?.newestMessageId ?: 0L, confirmed.maxOfOrNull(ChatMessage::id) ?: 0L)
        val pageOldest = confirmed.minOfOrNull(ChatMessage::id) ?: 0L
        val oldest = when {
            existing == null -> pageOldest
            existing.oldestMessageId <= 0L -> pageOldest
            pageOldest <= 0L -> existing.oldestMessageId
            else -> minOf(existing.oldestMessageId, pageOldest)
        }
        val stateBlob = cipher.encrypt(
            ownerId,
            stateScope(conversationId),
            LocalChatCodec.encodeReceiptState(deliveryStates, readStates),
        )
        val conversation = placeholderConversation(ownerId, conversationId, now).copy(
            stateBlob = stateBlob,
            lastConfirmedMessageId = newest,
        )
        val cursor = LocalSyncCursorEntity(
            ownerId = ownerId,
            conversationId = conversationId,
            newestMessageId = newest,
            oldestMessageId = oldest,
            hasMoreBefore = hasMoreBefore,
            phase = phase.name,
            continuationCursor = continuationCursor,
            lastSuccessfulSyncAt = if (phase == SyncPhase.IDLE && error.isBlank()) now else existing?.lastSuccessfulSyncAt ?: 0L,
            lastAttemptAt = now,
            lastError = error,
            updatedAt = now,
        )
        dao.mergeSnapshot(conversation, confirmed.map { messageEntity(ownerId, it) }, cursor)
        if (deliveryStates.isNotEmpty() || readStates.isNotEmpty()) {
            persistReceipts(ownerId, conversationId, deliveryStates, readStates, now)
        }
        pruneExpiredContent(ownerId, now)
    }

    suspend fun updateSyncState(
        ownerId: String,
        conversationId: Long,
        phase: SyncPhase,
        error: String = "",
    ) = mutex.withLock {
        if (ownerId.isBlank() || conversationId <= 0L) return@withLock
        val now = System.currentTimeMillis()
        val current = dao.syncCursor(ownerId, conversationId)
        dao.insertConversation(placeholderConversation(ownerId, conversationId, now))
        dao.upsertSyncCursor(
            (current ?: LocalSyncCursorEntity(
                ownerId = ownerId,
                conversationId = conversationId,
                newestMessageId = 0L,
                oldestMessageId = 0L,
                hasMoreBefore = true,
                phase = SyncPhase.IDLE.name,
                continuationCursor = "",
                lastSuccessfulSyncAt = 0L,
                lastAttemptAt = 0L,
                lastError = "",
                updatedAt = now,
            )).copy(
                phase = phase.name,
                lastAttemptAt = now,
                lastError = error,
                updatedAt = now,
            )
        )
    }

    suspend fun saveSummaries(ownerId: String, summaries: List<ConversationSummary>) = mutex.withLock {
        if (ownerId.isBlank()) return@withLock
        val now = System.currentTimeMillis()
        summaries.forEach { summary ->
            dao.putSummary(
                placeholderConversation(ownerId, summary.id, now).copy(
                    summaryBlob = cipher.encrypt(ownerId, summaryScope(summary.id), LocalChatCodec.encodeSummary(summary))
                )
            )
        }
        val activeIds = summaries.map { it.id }.toSet()
        dao.conversationIds(ownerId)
            .filterNot { it in activeIds }
            .forEach { dao.deleteConversation(ownerId, it) }
    }

    suspend fun loadSummaries(ownerId: String): List<ConversationSummary> = mutex.withLock {
        if (ownerId.isBlank()) return@withLock emptyList()
        pruneExpiredContent(ownerId, System.currentTimeMillis())
        dao.conversations(ownerId).mapNotNull { entity ->
            entity.summaryBlob.takeIf { it.isNotBlank() }?.let { blob ->
                runCatching {
                    LocalChatCodec.decodeSummary(cipher.decrypt(ownerId, summaryScope(entity.conversationId), blob))
                }.onFailure { logDiscard("summary", entity.conversationId, 0L, it) }
                    .getOrNull()
            }
        }.sortedByDescending { it.lastMessageTs }
    }

    suspend fun putOutbox(ownerId: String, pending: PendingOutgoing) = mutex.withLock {
        val message = pending.message
        if (ownerId.isBlank() || message.id >= 0L || message.conversationId <= 0L) return@withLock
        val now = System.currentTimeMillis()
        dao.putOutbox(
            placeholderConversation(ownerId, message.conversationId, now),
            LocalOutboxEntity(
                ownerId = ownerId,
                localMessageId = message.id,
                conversationId = message.conversationId,
                createdAt = message.ts,
                clientMessageId = pending.clientMessageId.ifBlank {
                    message.clientMessageId.ifBlank { "local:${message.conversationId}:${message.id}" }
                },
                attemptCount = pending.attemptCount,
                nextAttemptAt = pending.nextAttemptAt,
                state = OutboxState.QUEUED.name,
                lastError = pending.lastError,
                updatedAt = now,
                encryptedBlob = cipher.encrypt(
                    ownerId,
                    outboxScope(message.conversationId, message.id),
                    LocalChatCodec.encodePending(pending),
                ),
            ),
        )
    }

    suspend fun deleteOutbox(ownerId: String, localMessageId: Long) = mutex.withLock {
        if (ownerId.isNotBlank() && localMessageId < 0L) dao.deleteOutbox(ownerId, localMessageId)
    }

    suspend fun retryOutbox(ownerId: String, localMessageId: Long) = mutex.withLock {
        if (ownerId.isBlank() || localMessageId >= 0L) return@withLock
        dao.updateOutboxState(
            ownerId = ownerId,
            localMessageId = localMessageId,
            state = OutboxState.QUEUED.name,
            attemptCount = 0,
            nextAttemptAt = 0L,
            lastError = "",
            updatedAt = System.currentTimeMillis(),
        )
    }

    suspend fun updateOutboxPayload(ownerId: String, pending: PendingOutgoing) = mutex.withLock {
        val message = pending.message
        if (ownerId.isBlank() || message.id >= 0L || message.conversationId <= 0L) return@withLock
        dao.updateOutboxPayload(
            ownerId = ownerId,
            localMessageId = message.id,
            clientMessageId = pending.clientMessageId,
            encryptedBlob = cipher.encrypt(
                ownerId,
                outboxScope(message.conversationId, message.id),
                LocalChatCodec.encodePending(pending),
            ),
            updatedAt = System.currentTimeMillis(),
        )
    }

    suspend fun dueOutbox(ownerId: String, now: Long, limit: Int = 20): List<PendingOutgoing> = mutex.withLock {
        if (ownerId.isBlank()) return@withLock emptyList()
        dao.dueOutbox(ownerId, now, limit).mapNotNull { decodeOutboxEntity(ownerId, it) }
    }

    suspend fun nextOutboxAttemptAt(ownerId: String): Long? = mutex.withLock {
        if (ownerId.isBlank()) return@withLock null
        dao.nextOutboxAttemptAt(ownerId)
    }

    suspend fun markOutboxAttempt(
        ownerId: String,
        localMessageId: Long,
        attemptCount: Int,
        nextAttemptAt: Long,
        state: OutboxState,
        error: String = "",
    ) = mutex.withLock {
        if (ownerId.isBlank() || localMessageId >= 0L) return@withLock
        dao.updateOutboxState(
            ownerId = ownerId,
            localMessageId = localMessageId,
            state = state.name,
            attemptCount = attemptCount,
            nextAttemptAt = nextAttemptAt,
            lastError = error.take(240),
            updatedAt = System.currentTimeMillis(),
        )
    }

    suspend fun pendingReceipts(ownerId: String, limit: Int = 100): List<PersistedReceiptState> = mutex.withLock {
        if (ownerId.isBlank()) return@withLock emptyList()
        dao.pendingReceipts(ownerId, limit).map(LocalReceiptEntity::toModel)
    }

    suspend fun queueReceipt(
        ownerId: String,
        conversationId: Long,
        participantIdentity: String,
        deliveredMessageId: Long,
        readMessageId: Long,
    ) = mutex.withLock {
        if (ownerId.isBlank() || conversationId <= 0L || participantIdentity.isBlank()) return@withLock
        val now = System.currentTimeMillis()
        dao.insertConversation(placeholderConversation(ownerId, conversationId, now))
        val existing = dao.receipt(ownerId, conversationId, participantIdentity)
        dao.upsertReceipts(
            listOf(
                LocalReceiptEntity(
                    ownerId = ownerId,
                    conversationId = conversationId,
                    participantIdentity = participantIdentity,
                    deliveredMessageId = maxOf(existing?.deliveredMessageId ?: 0L, deliveredMessageId),
                    readMessageId = maxOf(existing?.readMessageId ?: 0L, readMessageId),
                    deliveredPending = existing?.deliveredPending == true || deliveredMessageId > 0L,
                    readPending = existing?.readPending == true || readMessageId > 0L,
                    updatedAt = now,
                )
            )
        )
    }

    suspend fun mergeReceipt(
        ownerId: String,
        conversationId: Long,
        participantIdentity: String,
        deliveredMessageId: Long = 0L,
        readMessageId: Long = 0L,
    ) = mutex.withLock {
        if (ownerId.isBlank() || conversationId <= 0L || participantIdentity.isBlank()) return@withLock
        val now = System.currentTimeMillis()
        dao.insertConversation(placeholderConversation(ownerId, conversationId, now))
        val existing = dao.receipt(ownerId, conversationId, participantIdentity)
        dao.upsertReceipts(
            listOf(
                LocalReceiptEntity(
                    ownerId = ownerId,
                    conversationId = conversationId,
                    participantIdentity = participantIdentity,
                    deliveredMessageId = maxOf(existing?.deliveredMessageId ?: 0L, deliveredMessageId),
                    readMessageId = maxOf(existing?.readMessageId ?: 0L, readMessageId),
                    deliveredPending = existing?.deliveredPending ?: false,
                    readPending = existing?.readPending ?: false,
                    updatedAt = now,
                )
            )
        )
    }

    suspend fun acknowledgeReceipt(
        ownerId: String,
        conversationId: Long,
        participantIdentity: String,
    ) = mutex.withLock {
        if (ownerId.isBlank() || conversationId <= 0L || participantIdentity.isBlank()) return@withLock
        dao.acknowledgeReceipt(ownerId, conversationId, participantIdentity, System.currentTimeMillis())
    }

    suspend fun upsertAttachmentTransfer(ownerId: String, transfer: PersistedAttachmentTransfer) = mutex.withLock {
        if (ownerId.isBlank() || transfer.conversationId <= 0L || transfer.transferId.isBlank()) return@withLock
        dao.insertConversation(placeholderConversation(ownerId, transfer.conversationId, transfer.updatedAt))
        dao.upsertAttachmentTransfer(transfer.toEntity(ownerId))
    }

    fun observeAttachmentTransfer(ownerId: String, transferId: String): Flow<PersistedAttachmentTransfer?> =
        dao.observeAttachmentTransfer(ownerId, transferId)
            .map { it?.toModel() }
            .distinctUntilChanged()
            .flowOn(Dispatchers.IO)

    suspend fun dueAttachmentTransfers(
        ownerId: String,
        now: Long,
        limit: Int = 8,
    ): List<PersistedAttachmentTransfer> = mutex.withLock {
        if (ownerId.isBlank()) return@withLock emptyList()
        dao.dueAttachmentTransfers(ownerId, now, limit).map(LocalAttachmentTransferEntity::toModel)
    }

    suspend fun clearOutbox(ownerId: String): List<String> = mutex.withLock {
        if (ownerId.isBlank()) return@withLock emptyList()
        val attachmentPaths = dao.allOutbox(ownerId).flatMap { entity ->
            runCatching {
                LocalChatCodec.decodePending(
                    cipher.decrypt(ownerId, outboxScope(entity.conversationId, entity.localMessageId), entity.encryptedBlob)
                )
            }.getOrNull()
                ?.action
                ?.let { it as? PendingAction.Attachment }
                ?.let { action -> listOf(action.localFilePath, action.encryptedFilePath).filter(String::isNotBlank) }
                .orEmpty()
        }
        dao.deleteAllOutbox(ownerId)
        attachmentPaths
    }

    suspend fun loadDraft(ownerId: String, conversationId: Long): String {
        if (ownerId.isBlank() || conversationId <= 0L) return ""
        val entity = dao.draft(ownerId, conversationId) ?: return ""
        return runCatching {
            cipher.decrypt(ownerId, draftScope(conversationId), entity.encryptedBlob)
        }.onFailure {
            dao.deleteDraft(ownerId, conversationId)
            logDiscard("draft", conversationId, 0L, it)
        }.getOrDefault("")
    }

    suspend fun saveDraft(ownerId: String, conversationId: Long, value: String) = mutex.withLock {
        if (ownerId.isBlank() || conversationId <= 0L) return@withLock
        if (value.isBlank()) {
            dao.deleteDraft(ownerId, conversationId)
            return@withLock
        }
        val now = System.currentTimeMillis()
        dao.putDraft(
            placeholderConversation(ownerId, conversationId, now),
            LocalDraftEntity(
                ownerId = ownerId,
                conversationId = conversationId,
                encryptedBlob = cipher.encrypt(ownerId, draftScope(conversationId), value),
                updatedAt = now,
            ),
        )
    }

    suspend fun loadDecryptedContent(
        ownerId: String,
        message: ChatMessage,
        contentKind: String,
    ): String = mutex.withLock {
        if (ownerId.isBlank() || message.id <= 0L || message.conversationId <= 0L || contentKind.isBlank()) {
            return@withLock ""
        }
        val now = System.currentTimeMillis()
        dao.deleteExpiredDecryptedContent(ownerId, now)
        val entity = dao.decryptedContent(ownerId, message.conversationId, message.id, contentKind)
            ?: return@withLock ""
        if (entity.expiresAt > 0L && entity.expiresAt <= now) {
            dao.deleteDecryptedContent(ownerId, message.conversationId, message.id, contentKind)
            return@withLock ""
        }
        runCatching {
            cipher.decrypt(
                ownerId,
                decryptedContentScope(message.conversationId, message.id, contentKind),
                entity.encryptedBlob,
            )
        }.getOrElse { error ->
            dao.deleteDecryptedContent(ownerId, message.conversationId, message.id, contentKind)
            logDiscard("decrypted_content", message.conversationId, message.id, error)
            ""
        }
    }

    suspend fun saveDecryptedContent(
        ownerId: String,
        message: ChatMessage,
        contentKind: String,
        plainText: String,
    ): Boolean = mutex.withLock {
        if (
            ownerId.isBlank() || message.id <= 0L || message.conversationId <= 0L ||
            contentKind.isBlank() || plainText.isBlank()
        ) {
            return@withLock false
        }
        val now = System.currentTimeMillis()
        val content = LocalDecryptedContentEntity(
            ownerId = ownerId,
            conversationId = message.conversationId,
            messageId = message.id,
            contentKind = contentKind,
            encryptedBlob = cipher.encrypt(
                ownerId,
                decryptedContentScope(message.conversationId, message.id, contentKind),
                plainText,
            ),
            expiresAt = MessageKeyLifecycle.effectiveExpiry(message.expiresAt, now),
            updatedAt = now,
        )
        dao.putDecryptedContent(
            placeholderConversation(ownerId, message.conversationId, now),
            messageEntity(ownerId, message),
            content,
        )
        dao.deleteExpiredDecryptedContent(ownerId, now)
        true
    }

    private suspend fun pruneExpiredContent(ownerId: String, now: Long) {
        dao.deleteExpiredMessages(ownerId, now)
        dao.deleteExpiredDecryptedContent(ownerId, now)
        dao.deleteAllOrphanedDecryptedContent(ownerId)
    }

    suspend fun clearConversation(ownerId: String, conversationId: Long) = mutex.withLock {
        if (ownerId.isBlank() || conversationId <= 0L) return@withLock
        dao.deleteMessages(ownerId, conversationId)
        dao.deleteOrphanedDecryptedContent(ownerId, conversationId)
        dao.deleteConversationOutbox(ownerId, conversationId)
        val now = System.currentTimeMillis()
        val emptyState = cipher.encrypt(
            ownerId,
            stateScope(conversationId),
            LocalChatCodec.encodeReceiptState(emptyMap(), emptyMap()),
        )
        dao.insertConversation(placeholderConversation(ownerId, conversationId, now))
        dao.updateState(ownerId, conversationId, emptyState, 0L, now)
    }

    suspend fun clearAllConversationContent(ownerId: String) = mutex.withLock {
        if (ownerId.isBlank()) return@withLock
        val now = System.currentTimeMillis()
        dao.conversationIds(ownerId).forEach { conversationId ->
            dao.deleteMessages(ownerId, conversationId)
            dao.deleteOrphanedDecryptedContent(ownerId, conversationId)
            dao.deleteConversationOutbox(ownerId, conversationId)
            val emptyState = cipher.encrypt(
                ownerId,
                stateScope(conversationId),
                LocalChatCodec.encodeReceiptState(emptyMap(), emptyMap()),
            )
            dao.updateState(ownerId, conversationId, emptyState, 0L, now)
        }
    }

    suspend fun deleteConversation(ownerId: String, conversationId: Long) = mutex.withLock {
        if (ownerId.isNotBlank() && conversationId > 0L) dao.deleteConversation(ownerId, conversationId)
    }

    suspend fun clearOwner(ownerId: String) = mutex.withLock {
        if (ownerId.isNotBlank()) dao.deleteOwner(ownerId)
    }

    suspend fun destroyLocalEncryptionKey() = mutex.withLock {
        cipher.destroyDataKey()
    }

    private fun messageEntity(ownerId: String, message: ChatMessage): LocalMessageEntity = LocalMessageEntity(
        ownerId = ownerId,
        conversationId = message.conversationId,
        messageId = message.id,
        clientMessageId = message.clientMessageId,
        timestamp = message.ts,
        expiresAt = message.expiresAt,
        encryptedBlob = cipher.encrypt(
            ownerId,
            messageScope(message.conversationId, message.id),
            LocalChatCodec.encodeMessage(message),
        ),
    )

    private fun decodeMessageEntity(ownerId: String, entity: LocalMessageEntity): ChatMessage? {
        val cacheKey = "$ownerId:${entity.conversationId}:${entity.messageId}"
        synchronized(decodedMessageCache) {
            decodedMessageCache[cacheKey]
                ?.takeIf { it.encryptedBlob == entity.encryptedBlob }
                ?.let { return it.value }
        }
        val decoded = runCatching {
            LocalChatCodec.decodeMessage(
                cipher.decrypt(
                    ownerId,
                    messageScope(entity.conversationId, entity.messageId),
                    entity.encryptedBlob,
                )
            ).let { message ->
                if (message.clientMessageId.isBlank() && entity.clientMessageId.isNotBlank()) {
                    message.copy(clientMessageId = entity.clientMessageId)
                } else {
                    message
                }
            }
        }.getOrNull()
        synchronized(decodedMessageCache) {
            decodedMessageCache[cacheKey] = CachedMessage(entity.encryptedBlob, decoded)
        }
        return decoded
    }

    private fun decodeOutboxEntity(ownerId: String, entity: LocalOutboxEntity): PendingOutgoing? {
        val cacheKey = "$ownerId:${entity.conversationId}:${entity.localMessageId}"
        synchronized(decodedOutboxCache) {
            decodedOutboxCache[cacheKey]
                ?.takeIf {
                    it.encryptedBlob == entity.encryptedBlob &&
                        it.state == entity.state &&
                        it.attemptCount == entity.attemptCount &&
                        it.nextAttemptAt == entity.nextAttemptAt &&
                        it.lastError == entity.lastError
                }
                ?.let { return it.value }
        }
        val decoded = runCatching {
            LocalChatCodec.decodePending(
                cipher.decrypt(
                    ownerId,
                    outboxScope(entity.conversationId, entity.localMessageId),
                    entity.encryptedBlob,
                )
            ).let { pending ->
                pending.copy(
                    message = pending.message.copy(
                        clientMessageId = pending.message.clientMessageId.ifBlank { entity.clientMessageId },
                        localSendState = when (runCatching { OutboxState.valueOf(entity.state) }.getOrDefault(OutboxState.QUEUED)) {
                            OutboxState.FAILED -> LocalSendState.FAILED
                            else -> LocalSendState.SENDING
                        },
                    ),
                    clientMessageId = pending.clientMessageId.ifBlank { entity.clientMessageId },
                    attemptCount = entity.attemptCount,
                    nextAttemptAt = entity.nextAttemptAt,
                    lastError = entity.lastError,
                )
            }
        }.getOrNull()
        synchronized(decodedOutboxCache) {
            decodedOutboxCache[cacheKey] = CachedOutbox(
                encryptedBlob = entity.encryptedBlob,
                state = entity.state,
                attemptCount = entity.attemptCount,
                nextAttemptAt = entity.nextAttemptAt,
                lastError = entity.lastError,
                value = decoded,
            )
        }
        return decoded
    }

    private suspend fun persistReceipts(
        ownerId: String,
        conversationId: Long,
        deliveryStates: Map<String, Long>,
        readStates: Map<String, Long>,
        now: Long,
    ) {
        val identities = deliveryStates.keys + readStates.keys
        if (identities.isEmpty()) return
        dao.upsertReceipts(
            identities.map { identity ->
                LocalReceiptEntity(
                    ownerId = ownerId,
                    conversationId = conversationId,
                    participantIdentity = identity,
                    deliveredMessageId = deliveryStates[identity] ?: 0L,
                    readMessageId = readStates[identity] ?: 0L,
                    deliveredPending = false,
                    readPending = false,
                    updatedAt = now,
                )
            }
        )
    }

    private fun placeholderConversation(ownerId: String, conversationId: Long, now: Long) =
        LocalConversationEntity(
            ownerId = ownerId,
            conversationId = conversationId,
            summaryBlob = "",
            stateBlob = "",
            lastConfirmedMessageId = 0L,
            updatedAt = now,
        )

    private fun logDiscard(type: String, conversationId: Long, recordId: Long, error: Throwable) {
        FamilyChatDiagnostics.event(
            "local_cache_record_discarded",
            "record_type" to type,
            "conversation_id" to conversationId,
            "record_id" to recordId,
            "error" to error.javaClass.simpleName,
        )
    }

    companion object {
        private const val MAX_CACHED_MESSAGES = 300
        private const val INITIAL_MESSAGE_WINDOW = 200
        private const val MESSAGE_WINDOW_INCREMENT = 150
        private const val MAX_MESSAGE_WINDOW = 5_000

        private fun stateScope(conversationId: Long) = "conversation-state:$conversationId"
        private fun summaryScope(conversationId: Long) = "conversation-summary:$conversationId"
        private fun messageScope(conversationId: Long, messageId: Long) = "message:$conversationId:$messageId"
        private fun outboxScope(conversationId: Long, messageId: Long) = "outbox:$conversationId:$messageId"
        private fun draftScope(conversationId: Long) = "draft:$conversationId"
        private fun decryptedContentScope(conversationId: Long, messageId: Long, contentKind: String) =
            "decrypted:$conversationId:$messageId:$contentKind"
    }
}
