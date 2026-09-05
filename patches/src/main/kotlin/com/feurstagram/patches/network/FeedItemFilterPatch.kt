package com.feurstagram.patches.network

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.instructions
import app.morphe.patcher.patch.bytecodePatch
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.feurstagram.patches.shared.Constants.COMPATIBILITY_INSTAGRAM
import com.feurstagram.patches.shared.Constants.EXTENSION

private const val BLOCK_CLASS = "Lcom/feurstagram/extension/Block;"

// Instagram's home-feed response is a list of items, each wrapped as a JSON
// object keyed by its type (e.g. {"clips_netego": {...}}). The deserialiser
// dispatches on that key. Ads and "suggested" units injected *inline* into the
// timeline payload therefore never hit a separate URL, so the network-layer
// Block cannot see them — only this parse-layer hook can.
//
// The fingerprint anchors on the server-protocol type tokens (stable wire
// strings, not obfuscated code names) plus the non-obfuscated method name
// fragment "parseFromJson", so it survives Instagram renaming the class/method
// to LX/ hex between releases.
//
// Keep this list *short*. Every token is a hard requirement, so one unit type
// Instagram retires takes the whole patch down with it: "in_feed_survey" was
// dropped from the parser in Instagram 444 and silently killed all inline
// ad/netego filtering (issue #117). These four are the load-bearing ones — the
// regular post wrapper, the two netego families and the ad unit — and the method
// name keeps it apart from the serialiser, which carries the same tokens.
internal object FeedItemParseFromJsonFingerprint : Fingerprint(
    strings = listOf(
        "media_or_ad",
        "clips_netego",
        "stories_netego",
        "ad4ad",
    ),
    custom = { method, _ -> method.name.lowercase().contains("parsefromjson") },
)

@Suppress("unused")
val feedItemFilterPatch = bytecodePatch(
    name = "Feed item filtering",
    description = "Drops ad/promo and suggested feed units at the JSON-parse layer, catching the " +
        "ones injected inline into the timeline that URL blocking misses. Gated on the Ads and " +
        "Suggested toggles.",
    default = true,
) {
    compatibleWith(COMPATIBILITY_INSTAGRAM)

    extendWith(EXTENSION)

    execute {
        FeedItemParseFromJsonFingerprint.method.apply {
            // The parse loop reads each item's JSON field name into a register,
            // then dispatches on String.hashCode() through a sparse-switch whose
            // first case loads a type token via const-string/jumbo. The field
            // name is the last value materialised (move-result-object) before
            // that first jumbo string — found structurally, not by name.
            val firstJumboIndex = instructions.first {
                it.opcode == Opcode.CONST_STRING_JUMBO
            }.location.index

            val keyLoad = instructions.last {
                it.opcode == Opcode.MOVE_RESULT_OBJECT && it.location.index < firstJumboIndex
            }
            val keyRegister = (keyLoad as OneRegisterInstruction).registerA

            // Rewriting a blocked unit's type token to an invalid one routes it to
            // the parser's own "unknown FeedItem type" branch, which yields an
            // invalid item Instagram filters out downstream — no throw, no crash.
            addInstructions(
                keyLoad.location.index + 1,
                "invoke-static/range { v$keyRegister .. v$keyRegister }, " +
                    "$BLOCK_CLASS->replaceFeedItemType(Ljava/lang/String;)Ljava/lang/String;\n" +
                    "move-result-object v$keyRegister",
            )
        }
    }
}
