package com.feurstagram.patches.bubbles

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.instructions
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.util.findMutableMethodOf
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.feurstagram.patches.shared.Constants.COMPATIBILITY_INSTAGRAM
import com.feurstagram.patches.shared.Constants.EXTENSION

// Removes Instagram's floating friend "bubbles" — the profile-avatar circles with a
// heart (like) or reshare (repost) badge that overlay reels, feed posts and the
// saved-reels grid ("people you follow engaged with this"). Gated on the Bubbles
// runtime toggle.
//
// The bubbles are Litho-drawn from native Pando-tree data, so there is nothing to
// hide at the view layer and nothing hookable at the data-read layer. Instead we
// neutralise the code that BUILDS the bubble UI models:
//
//   1. Reels: force the FriendlyViewerExperimentUtil boolean gates off. Each gate
//      logs a stable QPL marker string, so it is version-robust to fingerprint on.
//   2. Feed / saved-reels grid: null the factories that return
//      ClipsFriendlyBubbleUiState / RepostThoughtBubbleUiState (the on-media bubble
//      view is created from these). The factory CLASSES are obfuscated and change
//      per Instagram build — refresh `bubbleFactoryClasses` from a fresh `am profile`
//      method trace of a grid scroll on a new version (see BUBBLE_REMOVAL_DEVLOG.md).
//      The UI-state TYPE names below are stable.
//
// Every hook routes the target method's RETURN VALUE through a Config helper (which
// returns the original value unless the toggle is on) rather than a register-clobbering
// prologue. Reusing the value's own register is verify-safe regardless of the method's
// register layout — an entry prologue that clobbered v0 failed ART verification on
// methods where v0 holds a parameter.

private const val CONFIG = "Lcom/feurstagram/extension/Config;"

private fun friendlyViewerGate(name: String) =
    "android_purge_26_q2_FriendlyViewerExperimentUtil_$name"

internal object FriendlyViewerLikesFingerprint : Fingerprint(
    strings = listOf(friendlyViewerGate("isFriendlyViewerLikesEnabled")),
    returnType = "Z",
)
internal object FriendlyViewerFollowsFingerprint : Fingerprint(
    strings = listOf(friendlyViewerGate("isFriendlyViewerFollowsEnabled")),
    returnType = "Z",
)
internal object FriendlyViewerCommentsFingerprint : Fingerprint(
    strings = listOf(friendlyViewerGate("isFriendlyViewerCommentsEnabled")),
    returnType = "Z",
)

/**
 * Inject `reg = <filter>(reg)` immediately before every matching return, reusing the
 * return value's own register. Sites are collected first and rewritten from last to
 * first so earlier indices stay valid as instructions are inserted.
 */
private fun MutableMethod.routeReturns(returnOpcode: Opcode, filterSmali: (reg: Int) -> String) {
    val sites = instructions
        .withIndex()
        .filter { it.value.opcode == returnOpcode }
        .map { it.index to (it.value as OneRegisterInstruction).registerA }
    for ((index, register) in sites.sortedByDescending { it.first }) {
        addInstructions(index, filterSmali(register))
    }
}

@Suppress("unused")
val hideBubblesPatch = bytecodePatch(
    name = "Hide bubbles",
    description = "Removes the floating friend like/repost bubbles on reels, feed posts and " +
        "the saved-reels grid, gated on the Bubbles runtime toggle.",
    default = true,
) {
    compatibleWith(COMPATIBILITY_INSTAGRAM)
    extendWith(EXTENSION)

    execute {
        // 1) Reels friend like/follow/comment bubbles -> gate returns false when on.
        for (fingerprint in listOf(
            FriendlyViewerLikesFingerprint,
            FriendlyViewerFollowsFingerprint,
            FriendlyViewerCommentsFingerprint,
        )) fingerprint.method.routeReturns(Opcode.RETURN) { reg ->
            "invoke-static/range { v$reg .. v$reg }, $CONFIG->applyBubbleGate(Z)Z\n" +
                "move-result v$reg"
        }

        // 2) Feed / saved-reels-grid friend & repost bubbles -> null the UI-state
        //    factories so no bubble is built. Class names are obfuscated per build.
        val bubbleFactoryClasses = listOf("LX/4mC;", "LX/6Tp;", "LX/3Mu;", "LX/815;")
        val bubbleUiStateTypes = setOf(
            "Lcom/instagram/friendlysystem/domain/uicontract/ClipsFriendlyBubbleUiState;",
            "Lcom/instagram/friendlysystem/domain/uicontract/RepostThoughtBubbleUiState;",
        )
        for (className in bubbleFactoryClasses) {
            val classDef = classDefByOrNull(className) ?: continue
            val mutableClass = mutableClassDefBy(classDef)
            for (method in classDef.methods) {
                val bubbleType = method.returnType
                if (bubbleType !in bubbleUiStateTypes) continue
                if (method.implementation == null) continue
                mutableClass.findMutableMethodOf(method).routeReturns(Opcode.RETURN_OBJECT) { reg ->
                    "invoke-static/range { v$reg .. v$reg }, " +
                        "$CONFIG->applyBubbleObject(Ljava/lang/Object;)Ljava/lang/Object;\n" +
                        "move-result-object v$reg\n" +
                        "check-cast v$reg, $bubbleType"
                }
            }
        }
    }
}
