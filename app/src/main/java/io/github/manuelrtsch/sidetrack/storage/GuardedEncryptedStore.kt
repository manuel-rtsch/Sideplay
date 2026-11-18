package io.github.manuelrtsch.sidetrack.storage

import android.content.Context
import hrln.interactive.sidekit.sdk.crypto.EncryptedFileStore
import io.github.manuelrtsch.sidetrack.triage.TriageLog
import io.github.manuelrtsch.sidetrack.triage.TriageProbe
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
 * Losing the key is recoverable: the data is unreadable either way. So a read that cannot
 * authenticate the file drops it and reads as empty, which puts the app back on its first-run path
 * instead of crashing. Writes still fail loudly, because silently pretending to save is worse than
 * saying so.
 *
 * Two failures that look alike are kept apart, because the consequences differ:
 * - *Authentication* — the key no longer opens the bytes. Dropping the file is correct; it will
 *   never decrypt again.
 * - *Keystore state* — a `KeyStoreException`, `ProviderException` or an I/O error. That is
 *   transient (early boot, a restarting keystore daemon, a disk hiccup) and the same key may work
 *   on the next launch. The read still comes back empty, but the file is left alone; deleting it
 *   would turn a temporary outage into a permanent sign-out.
 *
 * The split lives in [StoreFailure]. Triage logging records the key's creation date on the failure
 * path, because `KeystoreCipher`
 * silently generates a new key when `getEntry()` finds none: a key created during this process is
 * proof that the original was replaced, not merely unreadable.
 */
class GuardedEncryptedStore(
    context: Context,
    private val name: String
) : JsonStore {
    private val file = EncryptedFileStore(context.applicationContext, name)
    private val sealed = File(context.applicationContext.noBackupFilesDir, "$name.sealed")

    override fun read(): JSONObject {
        TriageLog.i(TAG, "read '$name' present=${sealed.exists()} bytes=${if (sealed.exists()) sealed.length() else -1}")
        return try {
            val value = file.read()
            TriageLog.i(TAG, "read '$name' ok keys=${value.length()}")
            value
        } catch (error: Exception) {
            val authentication = StoreFailure.isAuthenticationFailure(error)
            TriageLog.e(
                TAG,
                "read '$name' failed — " +
                    if (authentication) {
                        "the key cannot open it, dropping the file"
                    } else {
                        "keystore not usable right now, keeping the file"
                    },
                error
            )
            TriageProbe.logKey("sidekit_$name", "after the failed read")
            if (authentication) runCatching { file.clear() }
            JSONObject()
        }
    }

    override fun write(value: JSONObject) {
        val existed = sealed.exists()
        try {
            file.write(value)
            TriageLog.i(TAG, "write '$name' ok keys=${value.length()} bytes=${sealed.length()}")
            // The first write is when the SDK creates the key; its creation date says whether that
            // was a new key or a reused one.
            if (!existed) TriageProbe.logKey("sidekit_$name", "after the first write")
        } catch (e: Exception) {
            TriageLog.e(TAG, "write '$name' failed", e)
            TriageProbe.logKey("sidekit_$name", "after the failed write")
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
