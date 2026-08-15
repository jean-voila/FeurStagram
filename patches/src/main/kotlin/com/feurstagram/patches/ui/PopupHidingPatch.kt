package com.feurstagram.patches.ui

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import com.feurstagram.patches.shared.Constants.COMPATIBILITY_INSTAGRAM
import com.feurstagram.patches.shared.Constants.EXTENSION

private const val TOASTS_CLASS = "Lcom/feurstagram/extension/Toasts;"

// Instagram shows its rounded "IGToast" pill through a single android.widget.Toast
// subclass that overrides show() to log and then call super. That override is the
// choke point every one of those popups goes through, so hooking it catches them
// all with one injection.
//
// The anchor is the shape, not the name: the class is obfuscated to LX/<hex>; and
// renamed on every release, but it is the app's only Toast subclass, and its
// override is necessarily named show() with no parameters.
internal object IgToastShowFingerprint : Fingerprint(
    name = "show",
    parameters = emptyList(),
    returnType = "V",
    custom = { _, classDef -> classDef.superclass == "Landroid/widget/Toast;" },
)

@Suppress("unused")
val popupHidingPatch = bytecodePatch(
    name = "Popup hiding",
    description = "Drops Instagram's popups (\"Couldn't refresh feed\"), which a blocked surface " +
        "raises on every failed request. Gated on the Instagram popups toggle.",
    default = true,
) {
    compatibleWith(COMPATIBILITY_INSTAGRAM)

    extendWith(EXTENSION)

    execute {
        IgToastShowFingerprint.method.apply {
            val registerCount = implementation?.registerCount
                ?: throw PatchException("Instagram's toast show() has no implementation")
            // show() takes no arguments, so `this` is the single parameter register
            // (parameters live in the highest registers) and v0 is free at entry.
            if (registerCount < 2) {
                throw PatchException("Instagram's toast show() has no free register")
            }

            addInstructionsWithLabels(
                0,
                """
                    invoke-static { }, $TOASTS_CLASS->shouldSuppress()Z
                    move-result v0
                    if-eqz v0, :show
                    return-void
                    :show
                    nop
                """,
            )
        }
    }
}
