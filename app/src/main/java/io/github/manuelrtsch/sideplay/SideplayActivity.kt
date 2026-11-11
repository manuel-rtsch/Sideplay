package io.github.manuelrtsch.sideplay

import android.content.Intent
import android.os.Bundle
import androidx.compose.runtime.Composable
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.rememberNavController
import hrln.interactive.sidekit.design.input.Keypad
import hrln.interactive.sidekit.design.input.KeypadPreferences
import hrln.interactive.sidekit.design.input.T9Mode
import hrln.interactive.sidekit.sdk.app.SidekitActivity
import hrln.interactive.sidekit.sdk.config.HostStatus
import hrln.interactive.sidekit.sdk.config.SidekitConfigClient
import hrln.interactive.sidekit.sdk.config.SidekitConfigSource
import hrln.interactive.sidekit.sdk.input.HostKeypadPreferences
import hrln.interactive.sidekit.sdk.input.SharedPreferencesKeypadPreferences
import hrln.interactive.sidekit.sdk.input.applyInputMode
import hrln.interactive.sidekit.sdk.theme.HostSidekitThemeStore
import hrln.interactive.sidekit.sdk.theme.SharedPreferencesSidekitThemeStore
import hrln.interactive.sidekit.sdk.theme.SidekitThemeStore

/**
 * The Sideplay shell. It reads Sidekit's configuration when a compatible host is installed and
 * keeps its own writable theme and keypad stores otherwise.
 */
class SideplayActivity : SidekitActivity() {
    private val hostAuthority by lazy { SidekitConfigClient.authorityFrom(intent) }

    private val hostAvailable by lazy {
        SidekitConfigClient.status(this, hostAuthority) == HostStatus.Available
    }

    private val hostConfig: SidekitConfigSource by lazy {
        SidekitConfigClient.source(this, hostAuthority)
    }

    override val themeStore: SidekitThemeStore by lazy {
        if (hostAvailable) HostSidekitThemeStore(hostConfig) else SharedPreferencesSidekitThemeStore(this, LOCAL_SETTINGS)
    }

    override val keypadPreferences: KeypadPreferences by lazy {
        if (hostAvailable) HostKeypadPreferences(hostConfig) else SharedPreferencesKeypadPreferences(this, LOCAL_KEYPAD)
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
        val config = if (hostAvailable) hostConfig.read() else null
        keypad.applyInputMode(config?.inputModeOrDefault() ?: T9Mode.MultiTap)
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
