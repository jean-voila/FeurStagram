package com.feurstagram.patches.settings

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.instructions
import app.morphe.patcher.patch.bytecodePatch
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.feurstagram.patches.shared.Constants.COMPATIBILITY_INSTAGRAM
import com.feurstagram.patches.shared.Constants.EXTENSION

private const val SETTINGS_CLASS = "Lcom/feurstagram/extension/Settings;"

private fun fieldType(instruction: Any?): String? =
    ((instruction as? ReferenceInstruction)?.reference as? FieldReference)?.type

// The main tab-bar binder is an obfuscated class whose constructor takes the
// tab-bar root View, pulls the tab_bar ViewGroup child out of it and stashes it
// in a field, alongside a sibling View field. We match that shape rather than
// the obfuscated names, which Instagram reshuffles between releases. The
// findViewById requirement disambiguates it from other same-shaped holders:
// the real binder must look its tab_bar child up by id (on 446 a bare
// field-shape match lands in a different class whose tree never hosts the tab
// bar, silently disabling the settings entry point and all hiders).
internal object TabBarBinderFingerprint : Fingerprint(
    name = "<init>",
    parameters = listOf("Landroid/view/View;"),
    custom = { method, classDef ->
        classDef.type.startsWith("LX/") &&
            method.implementation?.instructions?.let { instructions ->
                var hasViewGroupField = false
                var hasViewField = false
                var callsFindViewById = false
                for (instruction in instructions) {
                    if (instruction.opcode == Opcode.IPUT_OBJECT) {
                        when (fieldType(instruction)) {
                            "Landroid/view/ViewGroup;" -> hasViewGroupField = true
                            "Landroid/view/View;" -> hasViewField = true
                        }
                    }
                    if (instruction.opcode == Opcode.INVOKE_VIRTUAL) {
                        val reference = (instruction as? ReferenceInstruction)?.reference as? MethodReference
                        if (reference?.definingClass == "Landroid/view/View;" &&
                            reference.name == "findViewById"
                        ) {
                            callsFindViewById = true
                        }
                    }
                }
                hasViewGroupField && hasViewField && callsFindViewById
            } == true
    },
)

@Suppress("unused")
val settingsEntryPointPatch = bytecodePatch(
    name = "Settings entry point",
    description = "Opens the Feurstagram settings on a long-press of the Home tab, " +
        "and installs the surface hiders and update check.",
    default = true,
) {
    compatibleWith(COMPATIBILITY_INSTAGRAM)

    extendWith(EXTENSION)

    execute {
        TabBarBinderFingerprint.method.apply {
            // The value stored into the ViewGroup field is the tab_bar root; it's
            // a stable handle into the window, from which the watcher attaches the
            // long-press settings listener to the Home tab and installs the hiders.
            val tabBarStore = instructions.first {
                it.opcode == Opcode.IPUT_OBJECT && fieldType(it) == "Landroid/view/ViewGroup;"
            }
            val tabBarRegister = (tabBarStore as TwoRegisterInstruction).registerA

            addInstructions(
                tabBarStore.location.index + 1,
                "invoke-static { v$tabBarRegister }, " +
                    "$SETTINGS_CLASS->installHomeTabWatcher(Landroid/view/ViewGroup;)V",
            )
        }
    }
}
