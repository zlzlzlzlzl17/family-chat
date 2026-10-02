package com.example.chat

import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.IOException

@RunWith(AndroidJUnit4::class)
class LocalDatabaseMigrationTest {
    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        FamilyChatLocalDatabase::class.java,
        emptyList(),
        FrameworkSQLiteOpenHelperFactory(),
    )

    @Test
    @Throws(IOException::class)
    fun migrate2To3_preservesMessagesAndOutbox() {
        helper.createDatabase(TEST_DB, 2).apply {
            execSQL(
                "INSERT INTO local_conversations " +
                    "(ownerId, conversationId, summaryBlob, stateBlob, lastConfirmedMessageId, updatedAt) " +
                    "VALUES ('owner', 7, '', '', 11, 100)"
            )
            execSQL(
                "INSERT INTO local_messages " +
                    "(ownerId, conversationId, messageId, timestamp, expiresAt, encryptedBlob) " +
                    "VALUES ('owner', 7, 11, 100, 0, 'encrypted')"
            )
            execSQL(
                "INSERT INTO local_outbox " +
                    "(ownerId, localMessageId, conversationId, createdAt, encryptedBlob) " +
                    "VALUES ('owner', -1, 7, 101, 'pending')"
            )
            close()
        }

        helper.runMigrationsAndValidate(
            TEST_DB,
            3,
            true,
            FamilyChatLocalDatabase.MIGRATION_2_3,
        ).use { database ->
            database.query("SELECT clientMessageId FROM local_messages WHERE messageId=11").use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals("", cursor.getString(0))
            }
            database.query(
                "SELECT state, attemptCount, nextAttemptAt FROM local_outbox WHERE localMessageId=-1"
            ).use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals(OutboxState.QUEUED.name, cursor.getString(0))
                assertEquals(0, cursor.getInt(1))
                assertEquals(0L, cursor.getLong(2))
            }
            database.query("SELECT COUNT(*) FROM local_sync_cursors").use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals(0, cursor.getInt(0))
            }
        }
    }

    companion object {
        private const val TEST_DB = "familychat-migration-test"
    }
}
