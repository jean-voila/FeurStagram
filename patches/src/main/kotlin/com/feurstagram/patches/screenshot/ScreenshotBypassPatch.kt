package com.feurstagram.patches.screenshot

import app.morphe.patcher.extensions.InstructionExtensions.instructions
import app.morphe.patcher.extensions.InstructionExtensions.replaceInstruction
import app.morphe.patcher.patch.bytecodePatch
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.feurstagram.patches.shared.Constants.COMPATIBILITY_INSTAGRAM
import com.feurstagram.patches.shared.Constants.EXTENSION

private const val WINDOW = "Landroid/view/Window;"
private const val ACTIVITY = "Landroid/app/Activity;"
private const val SCREENSHOTS_CLASS = "Lcom/feurstagram/extension/Screenshots;"

private enum class Reroute {
    ADD_FLAGS,
    SET_FLAGS,
    RECENTS,
}

private fun ReferenceInstruction.reroute(): Reroute? {
    val ref = reference as? MethodReference ?: return null
    return when {
        ref.definingClass == WINDOW && ref.name == "addFlags" -> Reroute.ADD_FLAGS
        ref.definingClass == WINDOW && ref.name == "setFlags" -> Reroute.SET_FLAGS
        ref.definingClass == ACTIVITY && ref.name == "setRecentsScreenshotEnabled" -> Reroute.RECENTS
        else -> null
    }
}

/**
 * Instagram marks sensitive windows (view-once media, disappearing DMs) with
 * FLAG_SECURE so the OS refuses screenshots and screen recordings, disables
 * recents thumbnails on those screens, and reports a taken screenshot to the
 * sender via `direct_v2/.../ephemeral_screenshot/` (dropped in
 * `Block.throwIfBlocked`). The flag sites are scattered across dozens of
 * obfuscated classes (54 `Window.addFlags` and 21 `Window.setFlags` call sites
 * on the analysed build), so instead of fingerprinting any of them this patch
 * rewrites *every* `Window.addFlags(int)`, `Window.setFlags(int, int)` and
 * `Activity.setRecentsScreenshotEnabled(boolean)` call in the app to the
 * `Screenshots` helpers, which strip FLAG_SECURE / force recents on only while
 * the "Allow screenshots" toggle is enabled and pass everything through
 * untouched otherwise.
 *
 * All three targets are framework API, so the match is by stable framework
 * method references: no obfuscated Instagram name is involved and the patch
 * needs no per-version maintenance. Range-form invokes are skipped, not
 * rewritten; Instagram's call sites use the fixed-register form.
 */
@Suppress("unused")
val screenshotBypassPatch = bytecodePatch(
    name = "Screenshot bypass",
    description = "Reroutes Instagram's window-flag and recents-thumbnail calls so the " +
        "app can be allowed to take screenshots of view-once media and DMs " +
        "without notifying the sender, gated on the Allow screenshots toggle.",
    default = true,
) {
    compatibleWith(COMPATIBILITY_INSTAGRAM)

    extendWith(EXTENSION)

    execute {
        classDefForEach classes@{ classDef ->
            // Never rewrite our own helpers: they legitimately call
            // Window.addFlags/setFlags and Activity.setRecentsScreenshotEnabled,
            // and rewriting those into Screenshots.* would recurse forever.
            if (classDef.type.startsWith("Lcom/feurstagram/extension/")) return@classes

            // Cheap immutable pre-scan: skip classes that never touch the
            // rerouted calls so we only pay for a mutable proxy where needed.
            val touchesFlags = classDef.methods.any { method ->
                method.implementation?.instructions?.any {
                    it is ReferenceInstruction && it.reroute() != null
                } == true
            }
            if (!touchesFlags) return@classes

            mutableClassDefBy(classDef).methods.forEach methods@{ method ->
                method.implementation ?: return@methods
                // Collect first, rewrite after: replaceInstruction swaps 1-for-1 so
                // indices stay valid, but snapshotting keeps the traversal clean.
                // Non-five-register (range) invokes are skipped, not rewritten.
                val targets = method.instructions.withIndex().mapNotNull { (index, insn) ->
                    val kind = (insn as? ReferenceInstruction)?.reroute() ?: return@mapNotNull null
                    val five = insn as? FiveRegisterInstruction ?: return@mapNotNull null
                    Triple(index, kind, five)
                }

                for ((index, kind, insn) in targets) {
                    // No whitespace inside the parameter list: the inline
                    // smali parser rejects `(L...; II)V` with a space in it.
                    val replacement = when (kind) {
                        Reroute.ADD_FLAGS ->
                            "invoke-static { v${insn.registerC}, v${insn.registerD} }, " +
                                "$SCREENSHOTS_CLASS->addFlags(${WINDOW}I)V"
                        Reroute.SET_FLAGS ->
                            "invoke-static { v${insn.registerC}, v${insn.registerD}, " +
                                "v${insn.registerE} }, $SCREENSHOTS_CLASS->setFlags(${WINDOW}II)V"
                        Reroute.RECENTS ->
                            "invoke-static { v${insn.registerC}, v${insn.registerD} }, " +
                                "$SCREENSHOTS_CLASS->setRecentsScreenshotEnabled(${ACTIVITY}Z)V"
                    }
                    method.replaceInstruction(index, replacement)
                }
            }
        }
    }
}
