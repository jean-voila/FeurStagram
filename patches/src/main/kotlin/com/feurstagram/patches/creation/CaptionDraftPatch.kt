package com.feurstagram.patches.creation

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.instructions
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.feurstagram.patches.quoicouview.addBridge
import com.feurstagram.patches.quoicouview.methodRef
import com.feurstagram.patches.quoicouview.params
import com.feurstagram.patches.shared.Constants.COMPATIBILITY_INSTAGRAM

private object CaptionInputFingerprint : Fingerprint(
    returnType = "Ljava/lang/String;",
    parameters = emptyList(),
    strings = listOf("captionInputTextView"),
    custom = { _, cls -> cls.fields.any { it.type == "Landroidx/compose/runtime/MutableState;" } },
)

/** Save into Instagram's current draft before a navigation can suspend its caption collector. */
@Suppress("unused")
val captionDraftPatch = bytecodePatch(
    name = "Preserve post caption",
    description = "Saves caption edits directly to the current post draft before opening music or story options.",
    default = true,
) {
    compatibleWith(COMPATIBILITY_INSTAGRAM)
    execute {
        val captionType = CaptionInputFingerprint.classDef.type
        val caption = mutableClassDefBy(captionType)
        val saveSites = mutableListOf<Pair<FieldReference, MethodReference>>()
        // The existing String collector hands the caption to its draft model.
        // Reuse that exact setter, including Instagram's hashtag/mention handling.
        classDefForEach { cls ->
            cls.methods.filter { it.name == "emit" }.forEach { method ->
                val body = method.implementation?.instructions?.toList().orEmpty()
                body.forEachIndexed { index, instruction ->
                    if (instruction.opcode != Opcode.IGET_OBJECT) return@forEachIndexed
                    val field = (instruction as ReferenceInstruction).reference as FieldReference
                    if (field.definingClass != captionType) return@forEachIndexed
                    val call = body.getOrNull(index + 1) as? FiveRegisterInstruction ?: return@forEachIndexed
                    val ref = methodRef((call as? ReferenceInstruction)?.reference) ?: return@forEachIndexed
                    if (ref.definingClass == field.type && ref.returnType == "V" &&
                        ref.parameterTypes.map { it.toString() } == listOf("Ljava/lang/String;") &&
                        call.registerC == (instruction as TwoRegisterInstruction).registerA
                    ) saveSites += field to ref
                }
            }
        }
        val (draftField, save) = saveSites.distinctBy { it.first.toString() + it.second.toString() }.singleOrNull()
            ?: throw PatchException("Caption has no unique native draft setter")

        // Locate the edit event field from the Compose caption change handler.
        val events = caption.methods.flatMap { method ->
            method.instructions.withIndex().mapNotNull { (index, instruction) ->
                if (instruction.opcode != Opcode.IGET_OBJECT) return@mapNotNull null
                val field = (instruction as ReferenceInstruction).reference as FieldReference
                if (field.definingClass != captionType) return@mapNotNull null
                method.instructions.drop(index + 1).take(8).firstNotNullOfOrNull {
                    methodRef((it as? ReferenceInstruction)?.reference)?.takeIf { ref ->
                        it.opcode == Opcode.INVOKE_VIRTUAL && ref.definingClass == field.type &&
                            ref.returnType == "Z" && ref.parameterTypes.map { p -> p.toString() } == listOf("Ljava/lang/Object;")
                    }
                }?.let { field to it }
            }
        }.distinctBy { it.first.toString() + it.second.toString() }
        val (eventField, emit) = events.singleOrNull()
            ?: throw PatchException("Caption has no unique edit event")
        caption.addBridge(
            "feurstagramSaveCaption", listOf("Ljava/lang/Object;", "Ljava/lang/Object;"), "V", registers = 3,
            """
                instance-of v0, p1, Ljava/lang/String;
                if-eqz v0, :done
                check-cast p0, $captionType
                check-cast p1, Ljava/lang/String;
                iget-object v0, p0, $captionType->${draftField.name}:${draftField.type}
                invoke-virtual { v0, p1 }, ${save.definingClass}->${save.name}(Ljava/lang/String;)V
                :done
                return-void
            """,
            AccessFlags.PUBLIC.value or AccessFlags.STATIC.value,
        )
        var edits = 0
        classDefForEach classes@{ cls ->
            if (cls.type.startsWith("Lcom/feurstagram/")) return@classes
            if (cls.methods.none { method -> method.implementation?.instructions?.any {
                it.opcode == Opcode.IGET_OBJECT && (it as ReferenceInstruction).reference.toString() == eventField.toString()
            } == true }) return@classes
            mutableClassDefBy(cls.type).methods.forEach methods@{ method ->
                if (method.implementation == null) return@methods
                val loads = method.instructions.filter {
                    it.opcode == Opcode.IGET_OBJECT && (it as ReferenceInstruction).reference.toString() == eventField.toString()
                }
                loads.reversed().forEach { load ->
                    val call = method.instructions.drop(load.location.index + 1).take(8).firstOrNull {
                        it.opcode == Opcode.INVOKE_VIRTUAL && (it as ReferenceInstruction).reference.toString() == emit.toString()
                    } ?: return@forEach
                    val owner = (load as TwoRegisterInstruction).registerB
                    val text = (call as FiveRegisterInstruction).registerD
                    method.addInstructions(call.location.index,
                        "invoke-static { v$owner, v$text }, $captionType->feurstagramSaveCaption(Ljava/lang/Object;Ljava/lang/Object;)V")
                    edits++
                }
            }
        }
        if (edits < 2) throw PatchException("Expected caption edits for both text renderers, found $edits")
    }
}
