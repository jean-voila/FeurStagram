package com.feurstagram.patches.settings

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.instructions
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.feurstagram.patches.shared.Constants.COMPATIBILITY_INSTAGRAM
import com.feurstagram.patches.shared.Constants.EXTENSION

private const val HIDERS = "Lcom/feurstagram/extension/Hiders;"

// Instagram's Back handling for the main tabs ("NavigationController.onBackPress",
// the name survives in a tracing section). Once the current tab has nothing to
// pop itself, it walks back through the tab history, a java.util.Stack: with
// more than one entry it goes to the previous tab, with one it leaves the app.
internal object TabBackPressFingerprint : Fingerprint(
    returnType = "Z",
    parameters = emptyList(),
    strings = listOf("NavigationController.onBackPress"),
)

@Suppress("unused")
val backHistoryPatch = bytecodePatch(
    name = "Hidden Home in Back history",
    description = "Leaves the app on Back from the first tab shown when the Home icon is hidden, " +
        "instead of walking back onto the hidden feed.",
    default = true,
) {
    compatibleWith(COMPATIBILITY_INSTAGRAM)

    extendWith(EXTENSION)

    execute {
        TabBackPressFingerprint.method.apply {
            // The history is read from its Stack field, then sized (the call is
            // compiled against whichever superclass declares size()).
            val depth = instructions.withIndex().filter { (index, instruction) ->
                val reference = (instruction as? ReferenceInstruction)?.reference as? MethodReference
                val load = instructions.getOrNull(index - 1)
                instruction.opcode == Opcode.INVOKE_VIRTUAL && reference?.name == "size" &&
                    reference.parameterTypes.isEmpty() && load?.opcode == Opcode.IGET_OBJECT &&
                    ((load as ReferenceInstruction).reference as FieldReference).type == "Ljava/util/Stack;"
            }.singleOrNull()?.value ?: throw PatchException("Tab Back handling no longer reads its history depth")
            val history = (depth as FiveRegisterInstruction).registerC
            val result = instructions[depth.location.index + 1]
            if (result.opcode != Opcode.MOVE_RESULT) throw PatchException("Tab history depth not kept")
            val register = (result as OneRegisterInstruction).registerA
            addInstructions(
                result.location.index + 1,
                """
                    invoke-static { v$history, v$register }, $HIDERS->backHistoryDepth(Ljava/util/List;I)I
                    move-result v$register
                """,
            )
        }
    }
}
