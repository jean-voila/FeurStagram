package com.feurstagram.patches.display

import app.morphe.patcher.extensions.InstructionExtensions.instructions
import app.morphe.patcher.extensions.InstructionExtensions.replaceInstruction
import app.morphe.patcher.patch.bytecodePatch
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.feurstagram.patches.shared.Constants.COMPATIBILITY_INSTAGRAM
import com.feurstagram.patches.shared.Constants.EXTENSION

private const val WINDOW = "Landroid/view/Window;"
private const val DISPLAY_CLASS = "Lcom/feurstagram/extension/Display;"

/** Matches an `invoke-*` of android.view.Window.setColorMode(int). */
private fun ReferenceInstruction.isWindowSetColorMode(): Boolean {
    val ref = reference as? MethodReference ?: return false
    return ref.definingClass == WINDOW && ref.name == "setColorMode"
}

/**
 * Instagram switches its window into COLOR_MODE_HDR at runtime (server-gated per
 * account), which lifts the black floor on HDR OLED panels and leaves the dark UI
 * looking washed out. There is no single call site — the switch is invoked from
 * several unrelated (obfuscated) classes — so instead of fingerprinting one, this
 * patch rewrites *every* `Window.setColorMode(int)` call in the app to
 * `Display.setColorMode(Window, int)`, which honours the "Force dark" toggle
 * (drops the mode to COLOR_MODE_DEFAULT when on, passes it through when off).
 *
 * `android.view.Window.setColorMode` is framework API, so the match is by the
 * stable framework method reference — no obfuscated Instagram name is involved and
 * the patch needs no per-version maintenance. If a future Instagram build stops
 * calling it, the patch simply finds nothing and no-ops.
 */
@Suppress("unused")
val setColorModePatch = bytecodePatch(
    name = "Force SDR display",
    description = "Reroutes Instagram's window colour-mode changes so the app can be pinned to " +
        "SDR, keeping the dark UI's blacks deep instead of the washed-out look HDR forces.",
    default = true,
) {
    compatibleWith(COMPATIBILITY_INSTAGRAM)

    extendWith(EXTENSION)

    execute {
        classDefForEach classes@{ classDef ->
            // Never rewrite our own helper: it legitimately calls
            // Window.setColorMode, and rewriting that into Display.setColorMode
            // would make it call itself forever.
            if (classDef.type.startsWith("Lcom/feurstagram/extension/")) return@classes

            // Cheap immutable pre-scan: skip classes that never touch setColorMode
            // so we only pay for a mutable proxy where there is work to do.
            val touchesColorMode = classDef.methods.any { method ->
                method.implementation?.instructions?.any {
                    it is ReferenceInstruction && it.isWindowSetColorMode()
                } == true
            }
            if (!touchesColorMode) return@classes

            mutableClassDefBy(classDef).methods.forEach methods@{ method ->
                method.implementation ?: return@methods
                // Collect first, rewrite after: replaceInstruction swaps 1-for-1 so
                // indices stay valid, but snapshotting keeps the traversal clean.
                val targets = method.instructions.withIndex().filter { (_, insn) ->
                    insn is ReferenceInstruction && insn.isWindowSetColorMode()
                }.map { it.index }

                for (index in targets) {
                    val insn = method.instructions[index] as FiveRegisterInstruction
                    val windowReg = insn.registerC
                    val modeReg = insn.registerD
                    method.replaceInstruction(
                        index,
                        "invoke-static { v$windowReg, v$modeReg }, " +
                            "$DISPLAY_CLASS->setColorMode($WINDOW I)V",
                    )
                }
            }
        }
    }
}
