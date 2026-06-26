package com.feurstagram.patches.network

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.instructions
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.util.getReference
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.feurstagram.patches.shared.Constants.COMPATIBILITY_INSTAGRAM
import com.feurstagram.patches.shared.Constants.EXTENSION

private const val LIMIT_FEED_CLASS = "Lcom/feurstagram/extension/LimitFeed;"

// The main-feed request object. Its debug toString() embeds these literals, so
// the class is matched by stable wire/debug strings rather than its obfuscated
// LX/ name.
internal object MainFeedRequestClassFingerprint : Fingerprint(
    strings = listOf("Request{mReason=", ", mInstanceNumber="),
)

// The constructor of that request class, located via the class fingerprint
// above — never by an obfuscated name.
internal object InitMainFeedRequestFingerprint : Fingerprint(
    name = "<init>",
    classFingerprint = MainFeedRequestClassFingerprint,
)

// A method that assembles the feed-request headers. Used only to discover which
// of the request's (obfuscated-named) Map fields holds the headers, by finding
// the Map field that belongs to the request class.
internal object MainFeedHeaderMapFinderFingerprint : Fingerprint(
    strings = listOf("pagination_source", "FEED_REQUEST_SENT"),
)

@Suppress("unused")
val limitFeedToFollowingPatch = bytecodePatch(
    name = "Limit feed to following profiles",
    description = "Optionally restricts the home feed to accounts you follow, by rewriting the " +
        "feed request's pagination header. Gated on the runtime toggle.",
    default = true,
) {
    compatibleWith(COMPATIBILITY_INSTAGRAM)

    extendWith(EXTENSION)

    execute {
        // The header field's name is obfuscated and the request class has several
        // Map fields, so resolve the right one dynamically: inside the header
        // builder, take the first reference to a Map field declared by the
        // request class. Nothing is hardcoded — it is rediscovered each build.
        val requestClass = MainFeedRequestClassFingerprint.classDef.type
        val headerFieldName = MainFeedHeaderMapFinderFingerprint.method.run {
            instructions
                .mapNotNull { it.getReference<FieldReference>() }
                .firstOrNull { it.type == "Ljava/util/Map;" && it.definingClass == requestClass }
                ?.name
        } ?: return@execute  // Map field not found in this build; skip silently

        // In the constructor, wrap the value stored into that header field with
        // our rewriter, just before the store.
        InitMainFeedRequestFingerprint.method.apply {
            val store = instructions.firstOrNull {
                it.opcode == Opcode.IPUT_OBJECT &&
                    it.getReference<FieldReference>()?.name == headerFieldName
            } ?: return@apply  // field not stored in constructor in this build; skip silently

            val headerRegister = (store as TwoRegisterInstruction).registerA

            addInstructions(
                store.location.index,
                "invoke-static/range { v$headerRegister .. v$headerRegister }, " +
                    "$LIMIT_FEED_CLASS->setFollowingHeader(Ljava/util/Map;)Ljava/util/Map;\n" +
                    "move-result-object v$headerRegister",
            )
        }
    }
}
