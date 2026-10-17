package com.feurstagram.patches.ui

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.extensions.InstructionExtensions.instructions
import app.morphe.patcher.extensions.InstructionExtensions.replaceInstruction
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.feurstagram.patches.shared.Constants.COMPATIBILITY_INSTAGRAM

private const val USER_SESSION = "Lcom/instagram/common/session/UserSession;"
private const val FRAGMENT = "Landroidx/fragment/app/Fragment;"

/**
 * The handler behind Instagram's `instagram://saved` deep link. Its class name is not
 * obfuscated; the method is the one taking (Bundle, Bundle, UserSession) that pushes
 * the Saved screen.
 */
internal object SavedUrlHandlerFingerprint : Fingerprint(
    definingClass = "Lcom/instagram/urlhandlers/saved/SavedUrlHandlerActivity;",
    returnType = "V",
    parameters = listOf("Landroid/os/Bundle;", "Landroid/os/Bundle;", USER_SESSION),
)

/** The tabbed Saved home (pills, "Reels and posts"), found by its analytics module name. */
internal object SavedTabbedHomeFingerprint : Fingerprint(
    strings = listOf("saved_tabbed_home"),
)

/** Unique key for a method reference, independent of how dexlib2 represents it. */
private fun MethodReference.key(): String =
    "$definingClass->$name(${parameterTypes.joinToString("")})$returnType"

/** Static method references invoked by [this] method. */
private fun Method.staticCalls(): List<MethodReference> =
    implementation?.instructions
        ?.filter { it.opcode == Opcode.INVOKE_STATIC || it.opcode == Opcode.INVOKE_STATIC_RANGE }
        ?.mapNotNull { (it as ReferenceInstruction).reference as? MethodReference }
        ?: emptyList()

/**
 * Opens the same Saved screen from the deep link as from Profile → menu → Saved.
 *
 * Instagram has two Saved screens: the current, tabbed one (Todos / Colecciones /
 * Reels… pills, then "Reels and posts") and an older one that only lists
 * collections. Profile → menu → Saved goes through a small factory that returns the
 * tabbed screen when the account's server-side gate allows it, and the older one
 * otherwise. The `instagram://saved` handler skips that factory and always builds
 * the older screen, so the Saved button in the Reels slot — which opens that link —
 * landed on the older screen.
 *
 * This patch swaps the handler's direct call to the older screen for a call to the
 * factory, so the link honours the same gate as the menu. Nothing is hardcoded: the
 * factory is the static `Fragment (UserSession, …)` method that calls into the
 * tabbed home's class, and the call to replace is the one the handler and the
 * factory share (the older screen's constructor helper). If either is not found, the
 * patch fails without touching anything.
 */
@Suppress("unused")
val savedHomePatch = bytecodePatch(
    name = "Saved home deep link",
    description = "Makes the saved-posts deep link (used by the Saved button) open the same " +
        "tabbed Saved screen as the profile menu, instead of an older collections-only view.",
    default = true,
) {
    compatibleWith(COMPATIBILITY_INSTAGRAM)

    execute {
        val tabbedHome = SavedTabbedHomeFingerprint.classDef.type

        val factory = Fingerprint(
            returnType = FRAGMENT,
            custom = { method, _ ->
                AccessFlags.STATIC.isSet(method.accessFlags) &&
                    method.parameterTypes.size == 2 &&
                    method.parameterTypes[0].toString() == USER_SESSION &&
                    method.staticCalls().any { it.definingClass == tabbedHome }
            },
        ).method
        val factoryCalls = factory.staticCalls().map { it.key() }.toSet()
        val factoryDescriptor = "${factory.definingClass}->${factory.name}(" +
            factory.parameterTypes.joinToString("") + ")${factory.returnType}"

        SavedUrlHandlerFingerprint.method.apply {
            // The older screen's helper is the static call both methods make.
            val targets = instructions.withIndex().filter { (_, insn) ->
                insn.opcode == Opcode.INVOKE_STATIC &&
                    ((insn as ReferenceInstruction).reference as MethodReference).key() in factoryCalls
            }
            val (index, insn) = targets.singleOrNull()
                ?: throw PatchException("Saved handler: expected one shared call, found ${targets.size}")
            val call = insn as FiveRegisterInstruction
            if (call.registerCount < 2) throw PatchException("Saved handler: unexpected call shape")

            // Same session and (null) second argument; the result, now typed as a plain
            // Fragment, feeds the navigator's push(Bundle, Fragment) exactly as before.
            replaceInstruction(
                index,
                "invoke-static { v${call.registerC}, v${call.registerD} }, $factoryDescriptor",
            )
        }
    }
}
