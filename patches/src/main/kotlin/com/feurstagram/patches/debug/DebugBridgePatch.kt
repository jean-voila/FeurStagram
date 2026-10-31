package com.feurstagram.patches.debug

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.instructions
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.patch.resourcePatch
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.feurstagram.patches.settings.TabBarBinderFingerprint
import com.feurstagram.patches.shared.Constants.COMPATIBILITY_INSTAGRAM
import com.feurstagram.patches.shared.Constants.EXTENSION

private const val DEBUG_CLASS = "Lcom/feurstagram/extension/DebugBridge;"

// A debug build is also debuggable, so a debugger (jdb over `adb forward
// jdwp`) can stop in Instagram's own code: the way to see which of its
// obfuscated methods a gesture actually runs through.
private val debuggablePatch = resourcePatch {
    execute {
        document("AndroidManifest.xml").use { document ->
            val application = document.documentElement.getElementsByTagName("application").item(0)
                as? org.w3c.dom.Element ?: return@use
            application.setAttribute("android:debuggable", "true")
        }
    }
}

/**
 * Development build only, off by default — enable it with `./build.sh … --debug`
 * (which passes `-e "Debug bridge"` to the patcher).
 *
 * It registers an exported broadcast receiver so the whole settings store can be
 * read and written over ADB (`adb shell am broadcast -a com.feurstagram.debug.…`),
 * which makes an on-device test a shell command instead of a screenshot-and-tap
 * loop. See `DebugBridge` for the command list.
 *
 * Never ship a release with this enabled: any app on the device could send those
 * broadcasts.
 */
@Suppress("unused")
val debugBridgePatch = bytecodePatch(
    name = "Debug bridge",
    description = "Development only: exposes the Feurstagram settings over ADB broadcasts so " +
        "they can be driven from a shell instead of the on-screen panel.",
    default = false,
) {
    compatibleWith(COMPATIBILITY_INSTAGRAM)

    extendWith(EXTENSION)

    dependsOn(debuggablePatch)

    execute {
        // Same anchor as the settings entry point: the tab-bar root is the first
        // handle we get on a logged-in window, and both hooks want it.
        TabBarBinderFingerprint.method.apply {
            val tabBarStore = instructions.first {
                it.opcode == Opcode.IPUT_OBJECT &&
                    ((it as? ReferenceInstruction)?.reference as? FieldReference)?.type ==
                    "Landroid/view/ViewGroup;"
            }
            val tabBarRegister = (tabBarStore as TwoRegisterInstruction).registerA

            addInstructions(
                tabBarStore.location.index + 1,
                "invoke-static { v$tabBarRegister }, " +
                    "$DEBUG_CLASS->install(Landroid/view/ViewGroup;)V",
            )
        }
    }
}
