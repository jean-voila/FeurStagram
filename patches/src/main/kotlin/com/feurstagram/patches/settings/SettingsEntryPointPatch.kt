package com.feurstagram.patches.settings

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.patch.bytecodePatch
import com.feurstagram.patches.shared.Constants.COMPATIBILITY_INSTAGRAM
import com.feurstagram.patches.shared.Constants.EXTENSION

private const val SETTINGS_CLASS = "Lcom/feurstagram/extension/Settings;"

// The main activity is a real, non-obfuscated class (the MainTabActivity manifest
// alias points at it). onWindowFocusChanged is a framework override, so its name
// is stable across releases too — a far more reliable anchor than matching the
// obfuscated tab-bar binder by shape, which reshuffles between builds.
internal object MainActivityFocusFingerprint : Fingerprint(
    name = "onWindowFocusChanged",
    parameters = listOf("Z"),
    custom = { _, classDef ->
        classDef.type == "Lcom/instagram/mainactivity/InstagramMainActivity;"
    },
)

@Suppress("unused")
val settingsEntryPointPatch = bytecodePatch(
    name = "Settings entry point",
    description = "Opens the Feurstagram settings menu when the Home tab is " +
        "long-pressed, and installs the surface hiders, repost guard and update check.",
    default = true,
) {
    compatibleWith(COMPATIBILITY_INSTAGRAM)

    extendWith(EXTENSION)

    execute {
        MainActivityFocusFingerprint.method.apply {
            // p0 is the InstagramMainActivity instance (an Activity). Once the
            // window has focus the tab bar is laid out, so the extension can wire
            // the long-press handler to the Home tab.
            addInstructions(
                0,
                "invoke-static { p0 }, " +
                    "$SETTINGS_CLASS->installEntryPoints(Landroid/app/Activity;)V",
            )
        }
    }
}
