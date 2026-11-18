package com.feurstagram.patches.privacy

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.extensions.InstructionExtensions.instructions
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.util.smali.ExternalLabel
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.instruction.NarrowLiteralInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.feurstagram.patches.shared.Constants.COMPATIBILITY_INSTAGRAM
import com.feurstagram.patches.shared.Constants.EXTENSION

private const val GHOST = "Lcom/feurstagram/extension/Ghost;"

// Queues the "mark thread seen" mutation of a DM thread (after piko's
// ViewDmAnonymouslyPatch): the trace-key prefix is a stable literal.
internal object DmSeenFingerprint : Fingerprint(
    returnType = "V",
    strings = listOf("mark_thread_seen-"),
    custom = { method, _ -> AccessFlags.STATIC.isSet(method.accessFlags) },
)

// The DM composer's text watcher (after piko's DisableTypingStatusPatch): its
// afterTextChanged sets the input type flags 0x800033 / 0x800013.
internal object ComposerAfterTextChangedFingerprint : Fingerprint(
    name = "afterTextChanged",
    custom = { method, _ ->
        val literals = method.implementation?.instructions
            ?.mapNotNull { (it as? NarrowLiteralInstruction)?.narrowLiteral }.orEmpty()
        8388659 in literals && 8388627 in literals
    },
)

@Suppress("unused")
val ghostModePatch = bytecodePatch(
    name = "Ghost mode",
    description = "Optionally hides DM read receipts and the typing indicator (story views are " +
        "handled at the network layer). All off by default.",
    default = true,
) {
    compatibleWith(COMPATIBILITY_INSTAGRAM)

    extendWith(EXTENSION)

    execute {
        DmSeenFingerprint.method.apply {
            if (implementation!!.registerCount <= parameterTypes.size) throw PatchException("DM seen has no free register")
            addInstructionsWithLabels(
                0,
                """
                    invoke-static { }, $GHOST->hideDmSeen()Z
                    move-result v0
                    if-eqz v0, :instagram
                    return-void
                """,
                ExternalLabel("instagram", getInstruction(0)),
            )
        }

        val watcher = mutableClassDefBy(ComposerAfterTextChangedFingerprint.classDef.type)
        watcher.methods.single { it.name == "onTextChanged" }.apply {
            // The text is recorded first; the first flag read followed by its branch
            // is where the typing state starts. Stop there.
            val index = instructions.indices.firstOrNull { i ->
                instructions[i].opcode == Opcode.IGET_BOOLEAN && i + 1 < instructions.size &&
                    instructions[i + 1].opcode == Opcode.IF_NEZ
            } ?: throw PatchException("Composer text watcher changed shape")
            val register = getInstruction<OneRegisterInstruction>(index).registerA
            addInstructionsWithLabels(
                index,
                """
                    invoke-static { }, $GHOST->hideTyping()Z
                    move-result v$register
                    if-eqz v$register, :instagram
                    return-void
                """,
                ExternalLabel("instagram", getInstruction(index)),
            )
        }
    }
}
