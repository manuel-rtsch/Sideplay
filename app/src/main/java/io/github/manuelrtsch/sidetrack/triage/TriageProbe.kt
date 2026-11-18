package io.github.manuelrtsch.sidetrack.triage

import android.content.Context
import android.os.Build
import java.io.File
import java.security.KeyStore
import javax.crypto.Cipher

/**
 * Triage-only: records what the platform offers before Sidetrack asks it for anything. A GSI or a
 * reflashed ROM is exactly where the Android Keystore stops being able to decrypt its own data, so
 * the state of the key is the first thing worth knowing.
 */
object TriageProbe {
    /** The alias the Sidekit SDK uses for its encrypted stores: `sidekit_<store name>`. */
    private const val SDK_ALIAS_PREFIX = "sidekit_"

    private val SEALED_STORES = listOf("sidetracks_spotify", "sidetracks_settings")

    fun logPlatform() {
        TriageLog.i("platform", "release=${Build.VERSION.RELEASE} sdk=${Build.VERSION.SDK_INT} " +
            "codename=${Build.VERSION.CODENAME} incremental=${Build.VERSION.INCREMENTAL}")
        TriageLog.i("platform", "manufacturer=${Build.MANUFACTURER} brand=${Build.BRAND} " +
            "model=${Build.MODEL} device=${Build.DEVICE} hardware=${Build.HARDWARE}")
        TriageLog.i("platform", "fingerprint=${Build.FINGERPRINT}")
        TriageLog.i("platform", "tags=${Build.TAGS} type=${Build.TYPE} " +
            "securityPatch=${Build.VERSION.SECURITY_PATCH}")
        TriageLog.i("platform", "abis=${Build.SUPPORTED_ABIS.joinToString()} " +
            "64bit=${Build.SUPPORTED_64_BIT_ABIS.joinToString()}")
        TriageLog.i("platform", "providers=${runCatching { java.security.Security.getProviders().joinToString { it.name } }.getOrElse { "?" }}")
    }

    fun probeKeystore() {
        val store = runCatching { KeyStore.getInstance("AndroidKeyStore").apply { load(null) } }
            .onFailure { TriageLog.e("keystore", "AndroidKeyStore unavailable", it) }
            .getOrNull() ?: return

        val aliases = runCatching { store.aliases().toList() }
            .onFailure { TriageLog.e("keystore", "could not list aliases", it) }
            .getOrDefault(emptyList())
        TriageLog.i("keystore", "aliases=$aliases")

        val sdkAliases = aliases.filter { it.startsWith(SDK_ALIAS_PREFIX) }
        if (sdkAliases.isEmpty()) TriageLog.w("keystore", "no '$SDK_ALIAS_PREFIX*' alias: nothing has been stored yet")
        sdkAliases.forEach { alias ->
            runCatching { store.getEntry(alias, null) }
                .onSuccess { TriageLog.i("keystore", "alias '$alias' entry=${it?.javaClass?.simpleName}") }
                .onFailure { TriageLog.e("keystore", "alias '$alias' getEntry failed", it) }
            runCatching { store.getCertificate(alias) }
                .onSuccess { TriageLog.i("keystore", "alias '$alias' certificate=${it?.javaClass?.simpleName}") }
                .onFailure { TriageLog.e("keystore", "alias '$alias' getCertificate failed", it) }
        }

        runCatching { Cipher.getInstance("AES/GCM/NoPadding") }
            .onSuccess { TriageLog.i("keystore", "AES/GCM/NoPadding available") }
            .onFailure { TriageLog.e("keystore", "AES/GCM/NoPadding unavailable", it) }
    }

    /** The sealed files themselves: present, how big, and whether the key can still open them. */
    fun probeSealedFiles(context: Context) {
        val dir = context.noBackupFilesDir
        TriageLog.i("sealed", "dir=${dir.absolutePath} exists=${dir.exists()} " +
            "writable=${dir.canWrite()} files=${dir.list()?.joinToString() ?: "?"}")
        SEALED_STORES.forEach { name ->
            val file = File(dir, "$name.sealed")
            TriageLog.i("sealed", "$name: exists=${file.exists()} bytes=${if (file.exists()) file.length() else -1}")
        }
    }
}
