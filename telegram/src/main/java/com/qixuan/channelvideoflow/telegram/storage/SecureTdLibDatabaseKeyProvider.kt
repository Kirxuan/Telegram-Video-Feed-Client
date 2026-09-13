package com.qixuan.channelvideoflow.telegram.storage

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import com.qixuan.channelvideoflow.telegram.BuildConfig
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.StandardCopyOption
import java.security.KeyStore
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

internal fun interface TdLibDatabaseKeyProvider {
    fun loadOrCreate(databaseDirectory: File): TdLibDatabaseKeyResult
}

internal sealed interface TdLibDatabaseKeyResult {
    /** Existing production behavior while the experimental feature flag is disabled. */
    data object LegacyUnencrypted : TdLibDatabaseKeyResult

    data class Available(val key: ByteArray) : TdLibDatabaseKeyResult

    /** Existing databases require an explicit, separately proven two-phase migration. */
    data object MigrationRequired : TdLibDatabaseKeyResult

    /** The key exists but cannot be trusted. Never replace it or fall back to an empty key. */
    data object Unavailable : TdLibDatabaseKeyResult
}

internal class SecureTdLibDatabaseKeyProvider internal constructor(
    private val enabled: Boolean,
    private val keyFile: File,
    private val cipher: TdLibDatabaseKeyCipher,
    private val randomBytes: (ByteArray) -> Unit = { bytes -> SecureRandom().nextBytes(bytes) },
) : TdLibDatabaseKeyProvider {
    constructor(@ApplicationContext context: Context) : this(
        enabled = BuildConfig.TDLIB_DATABASE_ENCRYPTION_CANDIDATE_ENABLED,
        keyFile = File(context.noBackupFilesDir, KEY_FILE_PATH),
        cipher = AndroidKeystoreTdLibDatabaseKeyCipher(),
        randomBytes = { bytes -> SecureRandom().nextBytes(bytes) },
    )

    @Synchronized
    override fun loadOrCreate(databaseDirectory: File): TdLibDatabaseKeyResult {
        if (!enabled) return TdLibDatabaseKeyResult.LegacyUnencrypted
        return try {
            if (keyFile.exists()) {
                readExistingKey()
            } else if (containsDatabaseFiles(databaseDirectory)) {
                TdLibDatabaseKeyResult.MigrationRequired
            } else {
                createNewKey()
            }
        } catch (_: Exception) {
            TdLibDatabaseKeyResult.Unavailable
        }
    }

    private fun readExistingKey(): TdLibDatabaseKeyResult {
        if (!keyFile.isFile || keyFile.length() !in 1..MAX_ENCRYPTED_BYTES.toLong()) {
            return TdLibDatabaseKeyResult.Unavailable
        }
        val plaintext = cipher.decrypt(keyFile.readBytes())
        return if (plaintext.size == DATABASE_KEY_BYTES) {
            TdLibDatabaseKeyResult.Available(plaintext)
        } else {
            plaintext.fill(0)
            TdLibDatabaseKeyResult.Unavailable
        }
    }

    private fun createNewKey(): TdLibDatabaseKeyResult {
        val key = ByteArray(DATABASE_KEY_BYTES)
        return try {
            randomBytes(key)
            val encrypted = cipher.encrypt(key)
            atomicWrite(encrypted)
            TdLibDatabaseKeyResult.Available(key)
        } catch (failure: Exception) {
            key.fill(0)
            throw failure
        }
    }

    private fun containsDatabaseFiles(databaseDirectory: File): Boolean {
        if (!databaseDirectory.exists()) return false
        Files.walk(databaseDirectory.toPath()).use { paths ->
            return paths.anyMatch { path ->
                Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)
            }
        }
    }

    private fun atomicWrite(bytes: ByteArray) {
        val parent = keyFile.parentFile ?: throw IOException("Database key directory unavailable")
        Files.createDirectories(parent.toPath())
        val temporary = File(parent, "${keyFile.name}.tmp")
        try {
            temporary.outputStream().use { output ->
                output.write(bytes)
                output.flush()
                output.fd.sync()
            }
            try {
                Files.move(
                    temporary.toPath(),
                    keyFile.toPath(),
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING,
                )
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(
                    temporary.toPath(),
                    keyFile.toPath(),
                    StandardCopyOption.REPLACE_EXISTING,
                )
            }
        } finally {
            if (temporary.exists()) temporary.delete()
        }
    }

    private companion object {
        const val KEY_FILE_PATH = "security/tdlib-database-key.v1"
        const val DATABASE_KEY_BYTES = 32
        const val MAX_ENCRYPTED_BYTES = 4_096
    }
}

internal interface TdLibDatabaseKeyCipher {
    fun encrypt(plaintext: ByteArray): ByteArray
    fun decrypt(encrypted: ByteArray): ByteArray
}

private class AndroidKeystoreTdLibDatabaseKeyCipher : TdLibDatabaseKeyCipher {
    override fun encrypt(plaintext: ByteArray): ByteArray {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
        cipher.updateAAD(AAD)
        val ciphertext = cipher.doFinal(plaintext)
        val iv = cipher.iv
        return ByteBuffer.allocate(Int.SIZE_BYTES + 1 + iv.size + ciphertext.size)
            .putInt(CONTAINER_MAGIC)
            .put(iv.size.toByte())
            .put(iv)
            .put(ciphertext)
            .array()
    }

    override fun decrypt(encrypted: ByteArray): ByteArray {
        val buffer = ByteBuffer.wrap(encrypted)
        if (buffer.remaining() < Int.SIZE_BYTES + 1) throw IOException("Database key is truncated")
        if (buffer.int != CONTAINER_MAGIC) throw IOException("Database key format is invalid")
        val ivSize = buffer.get().toInt() and 0xff
        if (ivSize != GCM_IV_BYTES || buffer.remaining() <= ivSize) {
            throw IOException("Database key format is invalid")
        }
        val iv = ByteArray(ivSize)
        buffer.get(iv)
        val ciphertext = ByteArray(buffer.remaining())
        buffer.get(ciphertext)
        return Cipher.getInstance(TRANSFORMATION).run {
            init(Cipher.DECRYPT_MODE, getOrCreateKey(), GCMParameterSpec(GCM_TAG_BITS, iv))
            updateAAD(AAD)
            doFinal(ciphertext)
        }
    }

    private fun getOrCreateKey(): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (keyStore.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE).run {
            init(
                KeyGenParameterSpec.Builder(
                    KEY_ALIAS,
                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
                )
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setRandomizedEncryptionRequired(true)
                    .setKeySize(256)
                    .build(),
            )
            generateKey()
        }
    }

    private companion object {
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val KEY_ALIAS = "com.qixuan.channelvideoflow.telegram.database.v1"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val GCM_IV_BYTES = 12
        const val GCM_TAG_BITS = 128
        const val CONTAINER_MAGIC = 0x56444231
        val AAD = "VELORA_TDLIB_DATABASE_KEY_V1".toByteArray(StandardCharsets.US_ASCII)
    }
}
