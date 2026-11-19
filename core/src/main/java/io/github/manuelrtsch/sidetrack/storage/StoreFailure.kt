package io.github.manuelrtsch.sidetrack.storage

import java.security.GeneralSecurityException
import java.security.InvalidKeyException
import java.security.KeyStoreException
import java.security.NoSuchAlgorithmException
import java.security.NoSuchProviderException
import javax.crypto.BadPaddingException
import javax.crypto.IllegalBlockSizeException
import org.json.JSONException

/**
 * Separates the two ways an encrypted store can fail to read, because the right reaction differs:
 *
 * - *Authentication*: the current key does not open these bytes, or the decrypted bytes are not
 *   the document that was written. Nothing can recover the file, so dropping it is safe.
 * - *Keystore state*: the keystore or the disk is momentarily unavailable (`KeyStoreException`,
 *   `ProviderException`, I/O). The same key may work on the next launch, so the file must stay.
 */
internal object StoreFailure {
    /** True when the file can never be read again with this key. */
    fun isAuthenticationFailure(error: Throwable): Boolean {
        var cause: Throwable? = error
        while (cause != null) {
            when (cause) {
                // `AEADBadTagException` is a `BadPaddingException`; the others mean the key itself
                // is not usable for this ciphertext.
                is BadPaddingException, is InvalidKeyException, is IllegalBlockSizeException -> return true

                // Decrypted bytes that are not JSON cannot be recovered either.
                is JSONException -> return true

                // A truncated sealed file surfaces as a bare GeneralSecurityException, while a
                // keystore that cannot answer right now surfaces as one of the excluded subtypes.
                // `UnrecoverableKeyException` is not a `KeyStoreException`, and it means the key
                // blob is gone, so it deliberately falls through to "loss".
                is GeneralSecurityException ->
                    return cause !is KeyStoreException &&
                        cause !is NoSuchAlgorithmException &&
                        cause !is NoSuchProviderException
            }
            cause = cause.cause
        }
        return false
    }
}
