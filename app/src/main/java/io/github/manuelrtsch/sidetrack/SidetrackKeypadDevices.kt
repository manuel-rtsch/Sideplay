package io.github.manuelrtsch.sidetrack

import android.view.InputDevice
import hrln.interactive.sidekit.design.input.Keypad
import hrln.interactive.sidekit.design.input.KeypadModule

/**
 * The swappable keypad modules, by the input device each one appears as.
 *
 * Sidekit infers the attached module from the keys themselves (`KeypadDetector`), which cannot
 * read the keys every module shares: the D-pad, Enter, the call keys and Delete "say nothing", so
 * navigating with a game controller leaves the module at whatever was remembered - a stale T9, for
 * a fresh install - until a button only that module has is pressed.
 *
 * The Sidephone exposes each module as its own input device, so the device name answers the
 * question on the first press of any key, D-pad included. `gxa535_keyboard` is the name older
 * firmware used for every module and stays undecided; `gxa535_phone` is the phone's own key
 * device and is not a module at all.
 */
internal fun keypadModuleForDevice(name: String?): KeypadModule? = when (name) {
    "gxa535_t9" -> KeypadModule.T9
    "gxa535_qwerty" -> KeypadModule.Qwerty
    "gxa535_media" -> KeypadModule.Sundial
    "gxa535_gamepad" -> KeypadModule.GamePad
    else -> null
}

/** The module the attached hardware names, or null while only an undecided device is present. */
internal fun attachedKeypadModule(): KeypadModule? = InputDevice.getDeviceIds()
    .asSequence()
    .mapNotNull { id -> runCatching { InputDevice.getDevice(id)?.name }.getOrNull() }
    .firstNotNullOfOrNull(::keypadModuleForDevice)

/**
 * Teaches the keypad the module its input device names. Anything undecided is left alone, so this
 * can only ever replace a guess the hardware contradicts - never a module Sidekit got right.
 */
internal fun Keypad.followInputDevice(module: KeypadModule?) {
    if (module != null) useModule(module)
}
