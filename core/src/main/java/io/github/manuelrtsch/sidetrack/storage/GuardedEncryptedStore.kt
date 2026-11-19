package io.github.manuelrtsch.sidetrack.storage

import android.content.Context
import android.util.Log
import hrln.interactive.sidekit.sdk.crypto.EncryptedFileStore
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
 *
 * Two failures that look alike are kept apart, because the consequences differ:
 * - *Authentication* — the key no longer opens the bytes. Dropping the file is correct; it will
 *   never decrypt again.
 * - *Keystore state* — a `KeyStoreException`, `ProviderException` or an I/O error. That is
 *   transient (early boot, a restarting keystore daemon, a disk hiccup) and the same key may work
 *   on the next launch. The read still comes back empty, but the file is left alone; deleting it
 *   would turn a temporary outage into a permanent sign-out.
 *
 * The split lives in [StoreFailure].
 */
class GuardedEncryptedStore(
    context: Context,
    private val name: String
) : JsonStore {
    private val file = EncryptedFileStore(context.applicationContext, name)

    override fun read(): JSONObject = runCatching { file.read() }.getOrElse { error ->
        if (StoreFailure.isAuthenticationFailure(error)) {
            Log.w(TAG, "Dropping unreadable encrypted store '$name'", error)
            runCatching { file.clear() }
        } else {
            // The key blob itself is not the problem, so it may open the file on the next launch.
            Log.w(TAG, "Could not read encrypted store '$name', keeping it for the next launch", error)
        }
        JSONObject()
    }

    override fun write(value: JSONObject) {
        try {
            file.write(value)
        } catch (e: Exception) {
            throw EncryptedStoreException("This device could not save Sidetrack's data securely", e)
        }
    }

    override fun clear() {
        runCatching { file.clear() }
            .onFailure { Log.w(TAG, "Could not clear encrypted store '$name'", it) }
    }

    private companion object {
        const val TAG = "SidetrackStore"
    }
}
