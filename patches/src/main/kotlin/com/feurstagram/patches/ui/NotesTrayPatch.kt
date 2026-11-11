package com.feurstagram.patches.ui

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.instructions
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.StringReference
import com.feurstagram.patches.quoicouview.addBridge
import com.feurstagram.patches.quoicouview.igAdapterApply
import com.feurstagram.patches.quoicouview.igListModelType
import com.feurstagram.patches.quoicouview.isListModel
import com.feurstagram.patches.quoicouview.params
import com.feurstagram.patches.quoicouview.methodRef
import com.feurstagram.patches.shared.Constants.COMPATIBILITY_INSTAGRAM
import com.feurstagram.patches.shared.Constants.EXTENSION

private const val HIDERS = "Lcom/feurstagram/extension/Hiders;"

// The Notes tray's bind logger ("notes_tray_bind_" survives in a QPL annotation)
// takes the tray's list model, whichever way the tray is then drawn.
internal object NotesTrayBindFingerprint : Fingerprint(
    returnType = "V",
    strings = listOf("notes_tray_bind_"),
    custom = { method, _ -> method.parameterTypes.size == 1 },
)

// The list setup chooses either the view inbox or the fully Compose inbox.
// The latter never submits its rows to IgRecyclerViewAdapter at all.
private object InboxListSetupFingerprint : Fingerprint(
    name = "onCreateView",
    returnType = "Landroid/view/View;",
    strings = listOf("list_setup_start"),
)

@Suppress("unused")
val notesTrayPatch = bytecodePatch(
    name = "Notes tray",
    description = "Lets the Notes block take the Notes tray out of the Direct inbox's list, " +
        "for the accounts where Instagram draws it with Compose and no view id to hide.",
    default = true,
) {
    compatibleWith(COMPATIBILITY_INSTAGRAM)

    extendWith(EXTENSION)

    execute {
        val setup = InboxListSetupFingerprint.method
        val setupAt = setup.instructions.indexOfFirst {
            ((it as? ReferenceInstruction)?.reference as? StringReference)?.string == "list_setup_start"
        }
        val composeGate = setup.instructions.drop(setupAt + 1).firstNotNullOfOrNull {
            methodRef((it as? ReferenceInstruction)?.reference)?.takeIf { ref ->
                it.opcode == Opcode.INVOKE_VIRTUAL && ref.returnType == "Z" && ref.parameterTypes.isEmpty()
            }
        } ?: throw PatchException("Inbox setup no longer checks its rendering mode")
        val gate = classDefBy(composeGate.definingClass).methods.single {
            it.name == composeGate.name && it.params.isEmpty() && it.returnType == "Z"
        }
        val modeField = gate.implementation!!.instructions.firstNotNullOfOrNull {
            ((it as? ReferenceInstruction)?.reference as? FieldReference)?.takeIf { ref ->
                it.opcode == Opcode.IGET_OBJECT && ref.definingClass == composeGate.definingClass
            }
        } ?: throw PatchException("Inbox rendering mode has no lazy field")
        var reads = 0
        classDefForEach classes@{ cls ->
            if (cls.type.startsWith("Lcom/feurstagram/")) return@classes
            if (cls.methods.none { method -> method.implementation?.instructions?.any {
                it.opcode == Opcode.IGET_OBJECT && (it as ReferenceInstruction).reference.toString() == modeField.toString()
            } == true }) return@classes
            mutableClassDefBy(cls.type).methods.forEach methods@{ method ->
                if (method.implementation == null) return@methods
                val loads = method.instructions.filter {
                    it.opcode == Opcode.IGET_OBJECT && (it as ReferenceInstruction).reference.toString() == modeField.toString()
                }
                loads.reversed().forEach { load ->
                    val at = load.location.index
                    val unbox = method.instructions.getOrNull(at + 4)
                    val result = method.instructions.getOrNull(at + 5)
                    if (methodRef((unbox as? ReferenceInstruction)?.reference)?.let {
                            it.definingClass == "Ljava/lang/Boolean;" && it.name == "booleanValue"
                        } != true || result?.opcode != Opcode.MOVE_RESULT
                    ) throw PatchException("Unsupported inbox mode read in ${method.name}")
                    val register = (result as OneRegisterInstruction).registerA
                    method.addInstructions(
                        result.location.index + 1,
                        """
                            invoke-static { v$register }, $HIDERS->useComposeInbox(Z)Z
                            move-result v$register
                        """,
                    )
                    reads++
                }
            }
        }
        if (reads == 0) throw PatchException("Inbox rendering mode has no reads")

        val notesModel = NotesTrayBindFingerprint.method.params.single()
        if (!isListModel(notesModel, igListModelType())) {
            throw PatchException("Notes tray bind no longer takes a list model")
        }

        mutableClassDefBy(HIDERS).apply {
            val stub = methods.singleOrNull {
                it.name == "isNotesRow" && it.params == listOf("Ljava/lang/Object;") && it.returnType == "Z"
            } ?: throw PatchException("Extension has no isNotesRow(Object) stub")
            methods.remove(stub)
            addBridge(
                "isNotesRow", listOf("Ljava/lang/Object;"), "Z", registers = 2,
                """
                    instance-of v0, p0, $notesModel
                    return v0
                """,
                stub.accessFlags,
            )
        }

        igAdapterApply().addInstructions(
            0,
            "invoke-static { p1 }, $HIDERS->onListUpdate(Ljava/lang/Object;)V",
        )
    }
}
