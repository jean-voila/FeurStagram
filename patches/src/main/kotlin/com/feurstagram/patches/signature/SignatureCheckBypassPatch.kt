package com.feurstagram.patches.signature

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.removeInstructions
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import com.android.tools.smali.dexlib2.AccessFlags
import com.feurstagram.patches.shared.Constants.COMPATIBILITY_INSTAGRAM

/**
 * Instagram wraps a signing certificate's SHA-256 in a "key hash" (Base64, 43 chars) via
 * X.3uq, keeps Meta's trusted key hashes in a static allowlist in X.1eX, and exposes two
 * membership checks:
 *
 *   - X.1eX.A01(keyHash)          -> keyHash in allowlist
 *   - X.1eY.A01(keyHash, ref, z)  -> family-app scope trust (with the same allowlist fallback)
 *
 * Re-signing the APK produces a key hash that is not in the allowlist, so both checks fail,
 * X.0XS.A02 (the "is this APK signed by Meta" gate) turns false, and Instagram's deep-link
 * dispatcher silently drops navigation to the linked content (issue #36).
 *
 * Both methods take the X.3uq key-hash type, so they are matched structurally after locating
 * that type by its stable error string. The obfuscated class/method names therefore never
 * appear in this patch and it survives Instagram renaming them between releases.
 */
internal object KeyHashClassFingerprint : Fingerprint(
    strings = listOf("Invalid SHA256 key hash"),
)

/** Rewrites a method body so it simply returns `true`. */
private fun MutableMethod.replaceBodyToReturnTrue(reason: String) {
    val count = implementation?.instructions?.size
        ?: throw PatchException("$reason has no implementation")
    removeInstructions(count)
    addInstructions(0, "const/4 v0, 0x1\nreturn v0")
}

@Suppress("unused")
val signatureCheckBypassPatch = bytecodePatch(
    name = "Signature check bypass",
    description = "Forces Instagram's signing-certificate trust checks to always pass, so a " +
        "re-signed APK is treated as an official Meta build and deep links route to their " +
        "content instead of falling back to the home feed.",
    default = true,
) {
    compatibleWith(COMPATIBILITY_INSTAGRAM)

    execute {
        val keyHashType = KeyHashClassFingerprint.classDef.type

        // Only one method with signature boolean(X.3uq): X.1eX.A01, the allowlist membership
        // check powering X.0XS.A02 (the self-signature gate) and every other trust decision.
        Fingerprint(
            returnType = "Z",
            parameters = listOf(keyHashType),
            custom = { method, _ -> AccessFlags.STATIC.isSet(method.accessFlags) },
        ).method.replaceBodyToReturnTrue("X.1eX.A01 signature allowlist check")

        // Only one method with signature boolean(X.3uq, X.3uq, Z): X.1eY.A01, the family-app
        // scope trust check used by the deep-link resolver (com.facebook.secure.deeplink).
        Fingerprint(
            returnType = "Z",
            parameters = listOf(keyHashType, keyHashType, "Z"),
            custom = { method, _ -> AccessFlags.STATIC.isSet(method.accessFlags) },
        ).method.replaceBodyToReturnTrue("X.1eY.A01 signature scope check")
    }
}