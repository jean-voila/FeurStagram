package com.feurstagram.patches.ui

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.extensions.InstructionExtensions.instructions
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.util.smali.ExternalLabel
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.StringReference
import com.android.tools.smali.dexlib2.iface.reference.TypeReference
import com.android.tools.smali.dexlib2.AccessFlags
import com.feurstagram.patches.quoicouview.addBridge
import com.feurstagram.patches.shared.Constants.COMPATIBILITY_INSTAGRAM
import com.feurstagram.patches.shared.Constants.EXTENSION

private const val COMMENTS = "Lcom/feurstagram/extension/Comments;"
private const val MEDIA = "Lcom/instagram/feed/media/Media;"

// The state behind a post's action bar; its toString names every flag.
internal object FeedUfiStateToStringFingerprint : Fingerprint(
    name = "toString",
    returnType = "Ljava/lang/String;",
    strings = listOf("MediaUfiUiState(mediaForActionHandler="),
)

// The view state of a story's reply bar.
internal object StoryReplyStateToStringFingerprint : Fingerprint(
    name = "toString",
    returnType = "Ljava/lang/String;",
    strings = listOf("ViewState(showCommentButton="),
)

// Builds a reel's comment button state; logs it under this tag.
internal object ReelsCommentButtonFingerprint : Fingerprint(
    strings = listOf("ReelsCommentButton"),
)

// Media's getter for the comments previewed over a story.
internal object StoryCommentsGetterFingerprint : Fingerprint(
    definingClass = MEDIA,
    returnType = "Ljava/util/List;",
    parameters = emptyList(),
    strings = listOf("story_comments"),
)

private fun Instruction.ref() = (this as? ReferenceInstruction)?.reference

/** The boolean field a toString appends right after [label]. */
private fun Method.flagAfterLabel(label: String): FieldReference {
    val code = implementation!!.instructions.toList()
    val start = code.indexOfFirst { (it.ref() as? StringReference)?.string == label }
    if (start < 0) throw PatchException("State toString lost its \"$label\" label")
    val append = (start until code.size).firstOrNull {
        (code[it].ref() as? MethodReference)?.let { m ->
            m.definingClass == "Ljava/lang/StringBuilder;" && m.name == "append" &&
                m.parameterTypes.map { p -> p.toString() } == listOf("Z")
        } == true
    } ?: throw PatchException("State toString does not append \"$label\"")
    var register = (code[append] as FiveRegisterInstruction).registerD
    var index = append
    while (true) {
        index = (index - 1 downTo 0).firstOrNull {
            code[it].opcode.setsRegister() && (code[it] as? OneRegisterInstruction)?.registerA == register
        } ?: throw PatchException("State flag \"$label\" has no source")
        val write = code[index]
        when (write.opcode) {
            Opcode.MOVE, Opcode.MOVE_FROM16, Opcode.MOVE_16 -> register = (write as TwoRegisterInstruction).registerB
            Opcode.IGET_BOOLEAN -> return write.ref() as FieldReference
            else -> throw PatchException("Unexpected source for \"$label\": ${write.opcode}")
        }
    }
}

/** Run every value written to [field] by its class's constructors through [hook] (Z)Z. */
private fun BytecodePatchContext.filterFlag(field: FieldReference, hook: String) {
    val constructors = mutableClassDefBy(field.definingClass).methods.filter { it.name == "<init>" }
    var writes = 0
    constructors.forEach { init ->
        init.instructions.filter {
            it.opcode == Opcode.IPUT_BOOLEAN && (it.ref() as? FieldReference)?.let { f ->
                f.definingClass == field.definingClass && f.name == field.name
            } == true
        }.reversed().forEach { write ->
            val value = (write as TwoRegisterInstruction).registerA
            init.addInstructions(
                write.location.index,
                """
                    invoke-static/range { v$value .. v$value }, $COMMENTS->$hook(Z)Z
                    move-result v$value
                """,
            )
            writes++
        }
    }
    if (writes == 0) throw PatchException("No constructor writes ${field.name}")
}

@Suppress("unused")
val commentsPatch = bytecodePatch(
    name = "Comments",
    description = "Lets the Comments toggle hide comment buttons, counts and lists on posts, " +
        "Reels and stories (after piko's Disable comments).",
    default = true,
) {
    compatibleWith(COMPATIBILITY_INSTAGRAM)

    extendWith(EXTENSION)

    execute {
        // Posts: comments disabled and no count in the action bar's state.
        val feedState = FeedUfiStateToStringFingerprint.method
        filterFlag(feedState.flagAfterLabel(", isCommentsEnabled="), "shown")
        filterFlag(feedState.flagAfterLabel(", shouldShowCommentCountInUfi="), "shown")

        // Stories: the reply bar's comment button and the self-story hint.
        val storyState = StoryReplyStateToStringFingerprint.method
        filterFlag(storyState.flagAfterLabel("ViewState(showCommentButton="), "shown")
        filterFlag(storyState.flagAfterLabel(", showSelfStoryHint="), "shown")

        // Stories: the comments previewed over a story all come from Media's
        // story_comments getter.
        // Its return is a branch target, so the early exit goes first.
        StoryCommentsGetterFingerprint.method.apply {
            if (implementation!!.registerCount < 2) throw PatchException("Story comments getter has no free register")
            addInstructionsWithLabels(
                0,
                """
                    invoke-static { }, $COMMENTS->hidden()Z
                    move-result v0
                    if-eqz v0, :instagram
                    invoke-static { }, Ljava/util/Collections;->emptyList()Ljava/util/List;
                    move-result-object v0
                    return-object v0
                """,
                ExternalLabel("instagram", getInstruction(0)),
            )
        }

        // Reels: the comment button state is one of three objects of a shared
        // interface: Instagram's static "hidden" instance, a plain button, a button
        // with a count. Logged under "ReelsCommentButton", it starts as the hidden
        // instance in one register; every other value stored in that register is
        // replaced by the hidden instance while comments are blocked.
        ReelsCommentButtonFingerprint.method.apply {
            val code = instructions.toList()
            val tag = code.indexOfFirst { (it.ref() as? StringReference)?.string == "ReelsCommentButton" }
            val hiddenLoad = (tag until code.size).firstOrNull { i ->
                code[i].opcode == Opcode.SGET_OBJECT && (code[i].ref() as? FieldReference)?.let {
                    it.type == it.definingClass
                } == true
            } ?: throw PatchException("Reels comment state lost its hidden instance")
            val hidden = code[hiddenLoad].ref() as FieldReference
            val state = (code[hiddenLoad] as OneRegisterInstruction).registerA
            val stateInterface = classDefBy(hidden.type).interfaces.singleOrNull()
                ?: throw PatchException("Reels hidden comment state has no single interface")
            fun isState(type: String) = type != hidden.type &&
                classDefByOrNull(type)?.interfaces?.contains(stateInterface) == true
            // Where a button state lands in the state register: a singleton load, or
            // the constructor call of a freshly created one.
            val writes = code.indices.filter { i ->
                i > hiddenLoad && when (code[i].opcode) {
                    Opcode.SGET_OBJECT -> (code[i] as OneRegisterInstruction).registerA == state &&
                        isState((code[i].ref() as FieldReference).type)
                    Opcode.INVOKE_DIRECT -> (code[i].ref() as? MethodReference)?.let {
                        it.name == "<init>" && isState(it.definingClass)
                    } == true && (code[i] as FiveRegisterInstruction).registerC == state
                    else -> false
                }
            }
            if (writes.isEmpty()) throw PatchException("Reels comment state has no button value")
            val owner = mutableClassDefBy(definingClass)
            owner.addBridge(
                "feurstagramCommentState", listOf("Ljava/lang/Object;"), "Ljava/lang/Object;", registers = 2,
                """
                    invoke-static { }, $COMMENTS->hidden()Z
                    move-result v0
                    if-eqz v0, :keep
                    sget-object p0, ${hidden.definingClass}->${hidden.name}:${hidden.type}
                    :keep
                    return-object p0
                """,
                AccessFlags.PUBLIC.value or AccessFlags.STATIC.value,
            )
            writes.reversed().forEach { i ->
                addInstructions(
                    i + 1,
                    """
                        invoke-static/range { v$state .. v$state }, $definingClass->feurstagramCommentState(Ljava/lang/Object;)Ljava/lang/Object;
                        move-result-object v$state
                        check-cast v$state, $stateInterface
                    """,
                )
            }
        }
    }
}
