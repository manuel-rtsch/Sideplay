package io.github.manuelrtsch.sidetrack.storage

import android.content.Context
import hrln.interactive.sidekit.sdk.crypto.EncryptedFileStore
import io.github.manuelrtsch.sidetrack.triage.TriageLog
import java.io.File
import org.json.JSONObject

/** The small surface Sidetrack needs from an encrypted, atomic JSON store. */
interface JsonStore {
    /** Never throws: an unreadable store reads as empty. */
    fun read(): JSONObject

    /** Throws [EncryptedStoreException] when the value could not be written. */
    fun write(value: JSONObject)

    /** Never throws. */
    fun clear()
}

/** A write that did not reach the disk. The message is safe to show to the user. */
class EncryptedStoreException(message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * [EncryptedFileStore] reads through the Android Keystore without catching anything, and every
 * Sidetrack read happens while the UI composes. A key that was invalidated — a ROM flash, a GSI
 * install, a device restore, a vendor keystore that lost its TEE — makes `read()` throw
 * `AEADBadTagException`, which used to take the whole process down on launch.
 *
 * Losing the key is recoverable: the data is unreadable either way. So a failed read drops the
 * unreadable file and reads as empty, which puts the app back on its first-run path instead of
 * crashing. Writes still fail loudly, because silently pretending to save is worse than saying so.
 */
class GuardedEncryptedStore(
    context: Context,
    private val name: String
) : JsonStore {
    private val file = EncryptedFileStore(context.applicationContext, name)
    private val sealed = File(context.applicationContext.noBackupFilesDir, "$name.sealed")

    override fun read(): JSONObject {
        TriageLog.i(TAG, "read '$name' present=${sealed.exists()} bytes=${if (sealed.exists()) sealed.length() else -1}")
        return runCatching { file.read() }
            .onSuccess { TriageLog.i(TAG, "read '$name' ok keys=${it.length()}") }
            .getOrElse { error ->
                TriageLog.e(TAG, "read '$name' failed — dropping the unreadable store", error)
                runCatching { file.clear() }
                JSONObject()
            }
    }

    override fun write(value: JSONObject) {
        try {
            file.write(value)
            TriageLog.i(TAG, "write '$name' ok keys=${value.length()}")
        } catch (e: Exception) {
            TriageLog.e(TAG, "write '$name' failed", e)
            throw EncryptedStoreException("This device could not save Sidetrack's data securely", e)
        }
    }

    override fun clear() {
        runCatching { file.clear() }
            .onSuccess { TriageLog.i(TAG, "clear '$name' ok") }
            .onFailure { TriageLog.w(TAG, "clear '$name' failed", it) }
    }

    private companion object {
        const val TAG = "SidetrackStore"
    }
}
