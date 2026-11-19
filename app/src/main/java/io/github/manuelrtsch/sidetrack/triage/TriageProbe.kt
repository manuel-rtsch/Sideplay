package io.github.manuelrtsch.sidetrack.triage

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.Context
import android.os.Build
import android.os.SystemClock
import android.security.keystore.KeyInfo
import android.system.Os
import android.system.OsConstants
import io.github.manuelrtsch.sidetrack.bridge.NativeBridge
import java.io.File
import java.security.KeyStore
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.SecretKeyFactory
import org.json.JSONObject

/**
 * Triage-only: records what the platform offers before Sidetrack asks it for anything. A GSI or a
 * reflashed ROM is exactly where the Android Keystore stops being able to decrypt its own data, so
 * the state of the key — not just its presence — is the first thing worth knowing.
 *
 * `KeystoreCipher` silently generates a new key when `getEntry()` finds none, so a log that only
 * records "alias present" cannot tell the original key from a replacement. The creation date and
 * the security level below are what separate the two cases.
 */
object TriageProbe {
    /** The alias the Sidekit SDK uses for its encrypted stores: `sidekit_<store name>`. */
    private const val SDK_ALIAS_PREFIX = "sidekit_"

    /** Written next to the sealed files; holds no secrets, so it is deliberately not encrypted. */
    private const val RUN_MARKER = "triage-last-run.json"

    private val SEALED_STORES = listOf("sidetracks_spotify", "sidetracks_settings")

    fun logPlatform() {
        TriageLog.i(
            "platform",
            "release=${Build.VERSION.RELEASE} sdk=${Build.VERSION.SDK_INT} " +
                "codename=${Build.VERSION.CODENAME} incremental=${Build.VERSION.INCREMENTAL}"
        )
        TriageLog.i(
            "platform",
            "manufacturer=${Build.MANUFACTURER} brand=${Build.BRAND} " +
                "model=${Build.MODEL} device=${Build.DEVICE} hardware=${Build.HARDWARE}"
        )
        TriageLog.i("platform", "fingerprint=${Build.FINGERPRINT}")
        TriageLog.i(
            "platform",
            "tags=${Build.TAGS} type=${Build.TYPE} " +
                "securityPatch=${Build.VERSION.SECURITY_PATCH}"
        )
        TriageLog.i(
            "platform",
            "abis=${Build.SUPPORTED_ABIS.joinToString()} " +
                "64bit=${Build.SUPPORTED_64_BIT_ABIS.joinToString()}"
        )
        TriageLog.i("platform", "providers=${runCatching { java.security.Security.getProviders().joinToString { it.name } }.getOrElse { "?" }}")
    }

    /**
     * Install and run identity. A keystore key can only be invalidated by something outside the
     * app: a ROM flash, a GSI install, a data restore or a reinstall. Without this line a failed
     * decrypt cannot be attributed to any of them, because a plain restart and a reflash look
     * identical in the rest of the log.
     */
    fun logInstall(context: Context) {
        val info = runCatching { context.packageManager.getPackageInfo(context.packageName, 0) }.getOrNull()
        val firstInstall = info?.firstInstallTime ?: 0L
        val lastUpdate = info?.lastUpdateTime ?: 0L
        TriageLog.i(
            "install",
            "version=${info?.versionName ?: "?"} code=${info?.longVersionCode ?: -1} " +
                "firstInstall=${stamp(firstInstall)} lastUpdate=${stamp(lastUpdate)} " +
                "processStart=${stamp(TriageLog.processStartedAtMs)} " +
                "bootElapsed=${SystemClock.elapsedRealtime()} ms"
        )
        logRunMarker(context, firstInstall, lastUpdate)
    }

    private fun logRunMarker(context: Context, firstInstall: Long, lastUpdate: Long) {
        val file = File(context.noBackupFilesDir, RUN_MARKER)
        val previous = runCatching { if (file.exists()) JSONObject(file.readText()) else null }.getOrNull()
        val elapsed = SystemClock.elapsedRealtime()
        if (previous == null) {
            TriageLog.i("run", "no previous run marker: first triage launch on this app data")
        } else {
            val changes = buildList {
                if (previous.optString("fingerprint") != Build.FINGERPRINT) add("ROM fingerprint changed")
                if (previous.optString("incremental") != Build.VERSION.INCREMENTAL) add("ROM build changed")
                if (previous.optLong("firstInstall", -1L) != firstInstall) add("app data recreated")
                if (previous.optLong("lastUpdate", -1L) != lastUpdate) add("app reinstalled or updated")
                if (elapsed < previous.optLong("bootElapsed", 0L)) add("device rebooted")
            }
            TriageLog.i("run", if (changes.isEmpty()) "unchanged since the last run" else changes.joinToString("; "))
        }
        runCatching {
            file.writeText(
                JSONObject()
                    .put("fingerprint", Build.FINGERPRINT)
                    .put("incremental", Build.VERSION.INCREMENTAL)
                    .put("firstInstall", firstInstall)
                    .put("lastUpdate", lastUpdate)
                    .put("bootElapsed", elapsed)
                    .toString()
            )
        }.onFailure { TriageLog.w("run", "could not write the run marker", it) }
    }

    fun probeKeystore(label: String = "startup") {
        val store = keyStore() ?: return

        val aliases = runCatching { store.aliases().toList() }
            .onFailure { TriageLog.e("keystore", "$label: could not list aliases", it) }
            .getOrDefault(emptyList())
        TriageLog.i("keystore", "$label aliases=$aliases")

        val sdkAliases = aliases.filter { it.startsWith(SDK_ALIAS_PREFIX) }
        if (sdkAliases.isEmpty()) {
            TriageLog.w("keystore", "$label no '$SDK_ALIAS_PREFIX*' alias: nothing has been stored yet")
        }
        sdkAliases.forEach { alias ->
            runCatching { store.getEntry(alias, null) }
                .onSuccess { TriageLog.i("keystore", "$label alias '$alias' entry=${it?.javaClass?.simpleName}") }
                .onFailure { TriageLog.e("keystore", "$label alias '$alias' getEntry failed", it) }
            runCatching { store.getCertificate(alias) }
                .onSuccess { TriageLog.i("keystore", "$label alias '$alias' certificate=${it?.javaClass?.simpleName}") }
                .onFailure { TriageLog.e("keystore", "$label alias '$alias' getCertificate failed", it) }
            logKey(store, alias, label)
        }

        runCatching { Cipher.getInstance("AES/GCM/NoPadding") }
            .onSuccess { TriageLog.i("keystore", "$label AES/GCM/NoPadding available") }
            .onFailure { TriageLog.e("keystore", "$label AES/GCM/NoPadding unavailable", it) }
    }

    /**
     * The facts that separate an original key from one the SDK just generated: when the key was
     * created, whether that was during this process, and where it actually lives.
     */
    fun logKey(alias: String, label: String) {
        val store = keyStore() ?: return
        logKey(store, alias, label)
    }

    private fun logKey(store: KeyStore, alias: String, label: String) {
        val present = runCatching { store.containsAlias(alias) }.getOrDefault(false)
        val created = runCatching { store.getCreationDate(alias)?.time }.getOrNull()
        val key = runCatching { store.getKey(alias, null) as? SecretKey }.getOrNull()
        val keyInfo = runCatching {
            key?.let {
                SecretKeyFactory.getInstance(it.algorithm, "AndroidKeyStore")
                    .getKeySpec(it, KeyInfo::class.java) as KeyInfo
            }
        }.getOrNull()
        TriageLog.i(
            "keystore",
            "$label alias=$alias present=$present created=${stamp(created)} " +
                "inThisProcess=${created != null && created >= TriageLog.processStartedAtMs} " +
                "algorithm=${key?.algorithm ?: "?"} exportable=${key?.encoded != null} " +
                "securityLevel=${keyInfo?.securityLevel ?: "?"} " +
                "inSecureHardware=${keyInfo?.isInsideSecureHardware ?: "?"}"
        )
    }

    /**
     * The fork that decides where catalog reads go: the packaged librespot bridge, or the public
     * Web API. `NativeLibraries.load` swallows the linker's message, so the load is retried here
     * directly. A 16 KB page kernel is the usual reason a 4 KB-aligned `.so` refuses to load on a
     * GSI, and the fallback path is quota-limited, which looks like "no data" rather than an error.
     */
    fun logRuntime(context: Context) {
        val pageSize = runCatching { Os.sysconf(OsConstants._SC_PAGESIZE) }.getOrDefault(-1L)
        val memory = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
        TriageLog.i(
            "runtime",
            "abi=${Build.SUPPORTED_ABIS.joinToString()} pageSize=$pageSize 16KB=${pageSize == 16_384L} " +
                "memoryClass=${memory?.memoryClass ?: "?"} largeMemoryClass=${memory?.largeMemoryClass ?: "?"} " +
                "maxHeap=${Runtime.getRuntime().maxMemory()}"
        )
        val loadError = runCatching { System.loadLibrary(NativeBridge.LIBRARY) }.exceptionOrNull()
        TriageLog.i(
            "runtime",
            if (loadError == null) {
                "native '${NativeBridge.LIBRARY}' load=ok"
            } else {
                "native '${NativeBridge.LIBRARY}' load=failed: ${loadError.javaClass.name}: ${loadError.message}"
            }
        )
        TriageLog.i(
            "runtime",
            "NativeBridge.isAvailable=${runCatching { NativeBridge.isAvailable }.getOrElse { false }}"
        )
    }

    /**
     * Why the previous process died. A native crash, an ANR or a low-memory kill leaves no Java
     * stack, so without this a log that simply stops is indistinguishable from a normal exit.
     */
    fun logExitReasons(context: Context) {
        val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager ?: return
        val reasons = runCatching {
            activityManager.getHistoricalProcessExitReasons(context.packageName, 0, 10)
        }
            .onFailure { TriageLog.w("exit", "could not read the process exit reasons", it) }
            .getOrNull()
        if (reasons.isNullOrEmpty()) {
            TriageLog.i("exit", "no previous process exit reasons recorded")
            return
        }
        reasons.forEach { info ->
            TriageLog.i(
                "exit",
                "at=${stamp(info.timestamp)} pid=${info.pid} reason=${reasonName(info.reason)}(${info.reason}) " +
                    "status=${info.status} importance=${info.importance} " +
                    "pss=${info.pss} rss=${info.rss} description=${info.description ?: "-"}"
            )
        }
    }

    private fun reasonName(reason: Int): String = when (reason) {
        ApplicationExitInfo.REASON_EXIT_SELF -> "EXIT_SELF"
        ApplicationExitInfo.REASON_SIGNALED -> "SIGNALED"
        ApplicationExitInfo.REASON_LOW_MEMORY -> "LOW_MEMORY"
        ApplicationExitInfo.REASON_CRASH -> "CRASH"
        ApplicationExitInfo.REASON_CRASH_NATIVE -> "CRASH_NATIVE"
        ApplicationExitInfo.REASON_ANR -> "ANR"
        ApplicationExitInfo.REASON_INITIALIZATION_FAILURE -> "INITIALIZATION_FAILURE"
        ApplicationExitInfo.REASON_PERMISSION_CHANGE -> "PERMISSION_CHANGE"
        ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE -> "EXCESSIVE_RESOURCE_USAGE"
        ApplicationExitInfo.REASON_USER_REQUESTED -> "USER_REQUESTED"
        ApplicationExitInfo.REASON_USER_STOPPED -> "USER_STOPPED"
        ApplicationExitInfo.REASON_DEPENDENCY_DIED -> "DEPENDENCY_DIED"
        ApplicationExitInfo.REASON_OTHER -> "OTHER"
        ApplicationExitInfo.REASON_FREEZER -> "FREEZER"
        ApplicationExitInfo.REASON_PACKAGE_STATE_CHANGE -> "PACKAGE_STATE_CHANGE"
        ApplicationExitInfo.REASON_PACKAGE_UPDATED -> "PACKAGE_UPDATED"
        else -> "UNKNOWN"
    }

    /** The sealed files themselves: present, how big, and what would open them. */
    fun probeSealedFiles(context: Context) {
        val dir = context.noBackupFilesDir
        TriageLog.i(
            "sealed",
            "dir=${dir.absolutePath} exists=${dir.exists()} " +
                "writable=${dir.canWrite()} files=${dir.list()?.joinToString() ?: "?"}"
        )
        SEALED_STORES.forEach { name ->
            val file = File(dir, "$name.sealed")
            TriageLog.i(
                "sealed",
                "$name: exists=${file.exists()} bytes=${if (file.exists()) file.length() else -1} " +
                    "readable=${file.canRead()} alias=sidekit_$name"
            )
        }
    }

    private fun keyStore(): KeyStore? = runCatching {
        KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
    }.onFailure { TriageLog.e("keystore", "AndroidKeyStore unavailable", it) }.getOrNull()

    private fun stamp(ms: Long?): String = if (ms == null || ms <= 0L) {
        "?"
    } else {
        SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date(ms))
    }
}
