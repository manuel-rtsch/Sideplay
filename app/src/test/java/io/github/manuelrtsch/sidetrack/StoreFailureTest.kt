package io.github.manuelrtsch.sidetrack

import io.github.manuelrtsch.sidetrack.storage.StoreFailure
import java.io.IOException
import java.security.GeneralSecurityException
import java.security.InvalidKeyException
import java.security.KeyStoreException
import java.security.ProviderException
import java.security.UnrecoverableKeyException
import javax.crypto.AEADBadTagException
import javax.crypto.IllegalBlockSizeException
import org.json.JSONException
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The store drops a file only when no key can ever read it again. A keystore that is unavailable
 * for a moment must keep the file, otherwise a temporary outage becomes a permanent sign-out.
 */
class StoreFailureTest {
    @Test fun `a key that cannot authenticate the file is a loss`() {
        assertTrue(StoreFailure.isAuthenticationFailure(AEADBadTagException("bad tag")))
        assertTrue(StoreFailure.isAuthenticationFailure(IllegalBlockSizeException("short")))
        assertTrue(StoreFailure.isAuthenticationFailure(InvalidKeyException("wrong key")))
        // The keystore holds no usable key blob any more, so no key will ever open the file.
        assertTrue(StoreFailure.isAuthenticationFailure(UnrecoverableKeyException("not loadable")))
    }

    @Test fun `a truncated or corrupt file is a loss`() {
        assertTrue(StoreFailure.isAuthenticationFailure(GeneralSecurityException("Truncated")))
        assertTrue(StoreFailure.isAuthenticationFailure(JSONException("not a document")))
    }

    @Test fun `a wrapped authentication failure still counts`() {
        val wrapped = RuntimeException("store", AEADBadTagException("bad tag"))

        assertTrue(StoreFailure.isAuthenticationFailure(wrapped))
    }

    @Test fun `a keystore that is unavailable keeps the file`() {
        assertFalse(StoreFailure.isAuthenticationFailure(KeyStoreException("keystore died")))
        assertFalse(StoreFailure.isAuthenticationFailure(ProviderException("no provider")))
        assertFalse(StoreFailure.isAuthenticationFailure(IOException("disk gone")))
    }
}
