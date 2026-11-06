package com.feurstagram.patches.ui

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import com.feurstagram.patches.quoicouview.addBridge
import com.feurstagram.patches.quoicouview.igAdapterApply
import com.feurstagram.patches.quoicouview.igListModelType
import com.feurstagram.patches.quoicouview.isListModel
import com.feurstagram.patches.quoicouview.params
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
