package io.github.manuelrtsch.sideplay

import android.content.Intent
import android.os.Bundle
import androidx.compose.runtime.Composable
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.rememberNavController
import hrln.interactive.sidekit.design.input.Keypad
import hrln.interactive.sidekit.sdk.SidekitStores
import hrln.interactive.sidekit.sdk.app.SidekitActivity
import hrln.interactive.sidekit.sdk.config.SidekitConfigClient
import hrln.interactive.sidekit.sdk.input.applyInputMode

/**
 * The Sideplay shell. It reads Sidekit's configuration when a compatible host is installed and
 * keeps its own writable theme and keypad stores otherwise.
 *
 * Host integration is best-effort: an absent, older, or unreadable Sidekit never blocks startup.
 * Sideplay then runs standalone with the settings it already had.
 */
class SideplayActivity : SidekitActivity() {
    override val stores: SidekitStores.Stores by lazy {
        SidekitStores.forHostOrLocal(this, intent, LOCAL_SETTINGS, LOCAL_KEYPAD)
    }

    override fun onSidekitCreate(savedInstanceState: Bundle?) {
        if (savedInstanceState == null) SideplayLinks.handle(intent)
    }

    /**
     * Re-read the host's keypad choice every time Sideplay comes back to the front. The user may
     * have switched between T9 and mini QWERTY while Sideplay was in the background; without this
     * the keypad keeps the module and input mode it started with, so QWERTY's T/D/B/J navigation
     * cross never becomes active.
     */
    override fun onSidekitResume(keypad: Keypad) {
        val config = if (stores.isHostBacked) SidekitConfigClient.read(this, stores.hostAuthority) else null
        // Standalone Sideplay keeps its own input mode; a host build owns it when present.
        val local = SideplayLocalSettings(this)
        keypad.applyInputMode(config?.inputModeOrDefault() ?: local.inputMode)
        val module = config?.keypadModuleOrDefault() ?: keypadPreferences.module()
        if (module != null) keypad.useModule(module)
        keypad.useCustomMapping(config?.customModuleOrDefault() ?: keypadPreferences.customModule())
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        SideplayLinks.handle(intent)
    }

    @Composable
    override fun SidekitContent() {
        val nav = rememberNavController()
        SideplayLinks.Follow(nav)
        NavHost(navController = nav, startDestination = SIDE_PLAY_ROOT) {
            sideplayGraph(nav)
        }
    }

    private companion object {
        const val LOCAL_SETTINGS = "sideplay_settings"
        const val LOCAL_KEYPAD = "sideplay_keypad"
    }
}
