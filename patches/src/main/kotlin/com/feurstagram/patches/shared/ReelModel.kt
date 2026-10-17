package com.feurstagram.patches.shared

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.instructions
import app.morphe.patcher.extensions.InstructionExtensions.removeInstructions
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.StringReference

/**
 * Shared anchors into Instagram's story-tray model, for the patches that read it.
 *
 * Every tray entry is a `com.instagram.model.reels.ReelResponseItem` read through
 * the `ReelResponseItemIntf` interface: both names are stable. Its getters are
 * not (they are renamed every release), but the model's serializer writes each
 * field under its JSON name right next to the getter that reads it, so the
 * getter for a field can be looked up by that name at patch time.
 */
const val REEL_INTF = "Lcom/instagram/model/reels/ReelResponseItemIntf;"

/** The JSON parser of a story-tray entry. */
internal object ReelParserFingerprint : Fingerprint(
    name = "unsafeParseFromJson",
    strings = listOf("seen_ranked_position", "latest_reel_media", "media_count"),
)

/** The model's serializer: a static map builder reading each field through the interface. */
internal object ReelSerializerFingerprint : Fingerprint(
    returnType = "Ljava/util/Map;",
    strings = listOf("media_count", "unseen_media_ids", "seen_ranked_position"),
    custom = { method, _ ->
        AccessFlags.STATIC.isSet(method.accessFlags) &&
            method.parameterTypes.any { it.toString() == REEL_INTF }
    },
)

/**
 * "field=getter" pairs for [fields], comma-separated. The serializer writes a
 * field with one static put call fed by the field's name and one interface
 * getter; the compiler may load the name before or after calling the getter,
 * so the getter is the model-interface call between the previous put and this
 * one. Instagram's dex files hold so many strings that most name loads are the
 * jumbo form.
 */
internal fun BytecodePatchContext.reelGetterNames(fields: List<String>): String {
    val insns = ReelSerializerFingerprint.method.instructions.toList()
    fun isStaticCall(i: Int) = insns[i].opcode == Opcode.INVOKE_STATIC ||
        insns[i].opcode == Opcode.INVOKE_STATIC_RANGE
    fun interfaceGetter(i: Int): MethodReference? {
        val op = insns[i].opcode
        if (op != Opcode.INVOKE_INTERFACE && op != Opcode.INVOKE_INTERFACE_RANGE) return null
        val ref = (insns[i] as ReferenceInstruction).reference as MethodReference
        return if (ref.definingClass == REEL_INTF && ref.parameterTypes.isEmpty()) ref else null
    }
    val pairs = fields.mapNotNull { field ->
        val at = insns.indexOfFirst {
            (it.opcode == Opcode.CONST_STRING || it.opcode == Opcode.CONST_STRING_JUMBO) &&
                ((it as ReferenceInstruction).reference as StringReference).string == field
        }
        if (at < 0) return@mapNotNull null
        var start = at - 1
        while (start >= 0 && !isStaticCall(start)) start--
        var end = at + 1
        while (end < insns.size && !isStaticCall(end)) end++
        val getter = ((start + 1) until end).firstNotNullOfOrNull { interfaceGetter(it) }
            ?: return@mapNotNull null
        "$field=${getter.name}"
    }
    if (pairs.isEmpty()) throw PatchException("Story tray model: no getters found")
    return pairs.joinToString(",")
}

/** Rewrite a `static String x()` extension stub to return [value]. */
internal fun MutableMethod.returnConstant(value: String) {
    removeInstructions(instructions.count())
    addInstructions(0, "const-string v0, \"$value\"\nreturn-object v0")
}

/** Hand every entry the tray parser builds to `[target](Object)V`, just before it returns. */
internal fun BytecodePatchContext.hookReelParser(target: String) {
    ReelParserFingerprint.method.apply {
        val returns = instructions.withIndex()
            .filter { it.value.opcode == Opcode.RETURN_OBJECT }
            .map { it.index to (it.value as OneRegisterInstruction).registerA }
            .reversed()
        for ((index, register) in returns) {
            addInstructions(index, "invoke-static/range { v$register .. v$register }, $target")
        }
    }
}
