package com.qixuan.channelvideoflow.telegram.storage

import java.io.File
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class SecureTdLibDatabaseKeyProviderTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun disabledCandidatePreservesLegacyEmptyKeyPathWithoutReadingStorage() {
        val cipher = RecordingCipher()
        val database = temporaryFolder.newFolder("database").apply {
            resolve("existing.bin").writeText("synthetic")
        }
        val provider = provider(enabled = false, cipher = cipher)

        assertEquals(TdLibDatabaseKeyResult.LegacyUnencrypted, provider.loadOrCreate(database))
        assertEquals(0, cipher.decryptCalls)
        assertFalse(keyFile().exists())
    }

    @Test
    fun existingDatabaseWithoutWrappedKeyRequiresExplicitMigration() {
        val database = temporaryFolder.newFolder("database").apply {
            resolve("td.binlog").writeText("synthetic")
        }

        assertEquals(
            TdLibDatabaseKeyResult.MigrationRequired,
            provider(enabled = true).loadOrCreate(database),
        )
        assertFalse(keyFile().exists())
    }

    @Test
    fun newDatabaseCreatesOneRandomKeyAndReusesItAfterRestart() {
        val database = temporaryFolder.newFolder("database")
        val provider = provider(enabled = true)

        val first = provider.loadOrCreate(database) as TdLibDatabaseKeyResult.Available
        val stored = keyFile().readBytes()
        val second = provider(enabled = true).loadOrCreate(database) as TdLibDatabaseKeyResult.Available

        assertEquals(32, first.key.size)
        assertArrayEquals(ByteArray(32) { index -> (index + 1).toByte() }, first.key)
        assertArrayEquals(first.key, second.key)
        assertFalse(stored.contentEquals(first.key))
    }

    @Test
    fun corruptWrappedKeyFailsClosedWithoutReplacementOrEmptyFallback() {
        val database = temporaryFolder.newFolder("database")
        val file = keyFile().apply {
            parentFile?.mkdirs()
            writeBytes(byteArrayOf(99, 98, 97))
        }
        val before = file.readBytes()

        assertEquals(
            TdLibDatabaseKeyResult.Unavailable,
            provider(enabled = true, cipher = RecordingCipher(failDecrypt = true))
                .loadOrCreate(database),
        )
        assertArrayEquals(before, file.readBytes())
    }

    @Test
    fun deletedWrappedKeyBesideExistingDatabaseNeverCreatesAReplacement() {
        val database = temporaryFolder.newFolder("database")
        val provider = provider(enabled = true)
        assertTrue(provider.loadOrCreate(database) is TdLibDatabaseKeyResult.Available)
        keyFile().delete()
        database.resolve("db.sqlite").writeText("synthetic")

        assertEquals(TdLibDatabaseKeyResult.MigrationRequired, provider.loadOrCreate(database))
        assertFalse(keyFile().exists())
    }

    private fun provider(
        enabled: Boolean,
        cipher: RecordingCipher = RecordingCipher(),
    ) = SecureTdLibDatabaseKeyProvider(
        enabled = enabled,
        keyFile = keyFile(),
        cipher = cipher,
        randomBytes = { bytes -> bytes.indices.forEach { bytes[it] = (it + 1).toByte() } },
    )

    private fun keyFile(): File = File(temporaryFolder.root, "security/tdlib-key.v1")
}

private class RecordingCipher(
    private val failDecrypt: Boolean = false,
) : TdLibDatabaseKeyCipher {
    var decryptCalls: Int = 0
        private set

    override fun encrypt(plaintext: ByteArray): ByteArray =
        byteArrayOf(0x56, 0x44) + plaintext.map { (it.toInt() xor XOR_MASK).toByte() }

    override fun decrypt(encrypted: ByteArray): ByteArray {
        decryptCalls += 1
        if (failDecrypt || encrypted.size < 3) error("synthetic decrypt failure")
        return encrypted.drop(2).map { (it.toInt() xor XOR_MASK).toByte() }.toByteArray()
    }

    private companion object {
        const val XOR_MASK = 0x5A
    }
}
