package com.feurstagram.patches.network

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.extensions.InstructionExtensions.instructions
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.util.addInstructionsAtControlFlowLabel
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.feurstagram.patches.shared.Constants.COMPATIBILITY_INSTAGRAM
import com.feurstagram.patches.shared.Constants.EXTENSION
import com.feurstagram.patches.shared.ReelParserFingerprint
import com.feurstagram.patches.shared.reelGetterNames
import com.feurstagram.patches.shared.returnConstant

private const val BLOCK_CLASS = "Lcom/feurstagram/extension/Block;"

internal object TrayEntryTypeGetterFingerprint : Fingerprint(
    definingClass = BLOCK_CLASS,
    name = "trayEntryTypeGetter",
)

/**
 * The home story tray mixes in suggested accounts as ordinary entries of its
 * `tray` list, typed `reel_type: "suggested_user"`. They come inside the tray
 * response itself (`/feed/reels_tray/`), so blocking the `/discover/…`
 * endpoints does not catch them, and the tray request cannot be blocked without
 * losing the stories of the people you follow.
 *
 * This hooks the parser of a single tray entry: each entry it builds goes
 * through `Block.filterTrayEntry`, which turns a suggestion into null when
 * suggestions are blocked. The tray loop already skips entries that parse to
 * null, so nothing else changes. The parser is found by stable JSON field names
 * and the `reel_type` getter is read from the model's serializer, so no
 * obfuscated name is hardcoded.
 */
@Suppress("unused")
val suggestedTrayEntriesPatch = bytecodePatch(
    name = "Suggested accounts in the story tray",
    description = "Drops the suggested accounts Instagram mixes into the story tray, gated on " +
        "the Suggested accounts toggle.",
    default = true,
) {
    compatibleWith(COMPATIBILITY_INSTAGRAM)

    extendWith(EXTENSION)

    execute {
        val getter = reelGetterNames(listOf("reel_type")).substringAfter('=')
        TrayEntryTypeGetterFingerprint.method.returnConstant(getter)

        ReelParserFingerprint.method.apply {
            val returns = instructions.withIndex()
                .filter { it.value.opcode == Opcode.RETURN_OBJECT }
                .map { it.index to (it.value as OneRegisterInstruction).registerA }
                .reversed()
            // A return is often a branch target (several paths share it). A plain
            // insert leaves the branch label on the return itself, so jumps to it
            // would skip the filter and let suggested accounts through; inserting
            // at the label runs the filter on every path.
            for ((index, register) in returns) {
                addInstructionsAtControlFlowLabel(
                    index,
                    "invoke-static/range { v$register .. v$register }, " +
                        "$BLOCK_CLASS->filterTrayEntry(Ljava/lang/Object;)Ljava/lang/Object;\n" +
                        "move-result-object v$register",
                )
            }
        }
    }
}
