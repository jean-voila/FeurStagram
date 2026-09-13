package com.feurstagram.patches.signature

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.removeInstructions
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.feurstagram.patches.shared.Constants.COMPATIBILITY_INSTAGRAM

/**
 * Instagram wraps a signing certificate's SHA-256 in a "key hash" (Base64, 43 chars) via
 * X.3uq, keeps Meta's trusted key hashes in a static allowlist in X.1eX, and exposes two
 * membership checks:
 *
 *   - X.1eX.A01(keyHash)          -> keyHash in allowlist
 *   - X.1eY.A01(keyHash, ref, z)  -> family-app scope trust (with the same allowlist fallback)
 *
 * Instagram 446 reshaped the second one into an instance method on the trusted-provider class
 * that takes the caller identity and reads the key hashes from it, so the patch matches either
 * form.
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

        // The family-app scope trust check used by the deep-link resolver
        // (com.facebook.secure.deeplink). Up to Instagram 445 it is the only method with
        // signature boolean(X.3uq, X.3uq, Z) (X.1eY.A01).
        val legacyScopeCheck = Fingerprint(
            returnType = "Z",
            parameters = listOf(keyHashType, keyHashType, "Z"),
            custom = { method, _ -> AccessFlags.STATIC.isSet(method.accessFlags) },
        ).methodOrNull

        // From Instagram 446 it became an instance method boolean(CallerIdentity, Z) on the
        // trusted-provider class, which reads both key hashes from the caller identity
        // (a getter on its first parameter) instead of taking them as parameters. That shape
        // is unique in the APK; the class's own "*|all_packages|*" string is not, so it is
        // matched on structure alone.
        val scopeCheck = legacyScopeCheck ?: Fingerprint(
            returnType = "Z",
            custom = { method, _ ->
                !AccessFlags.STATIC.isSet(method.accessFlags) &&
                    method.parameterTypes.size == 2 &&
                    method.parameterTypes[1].toString() == "Z" &&
                    method.readsKeyHashFrom(method.parameterTypes[0].toString(), keyHashType)
            },
        ).method
        scopeCheck.replaceBodyToReturnTrue("Signature scope check")
    }
}

/** True if the method calls a getter declared on [ownerType] that returns the key-hash type. */
private fun Method.readsKeyHashFrom(ownerType: String, keyHashType: String): Boolean =
    implementation?.instructions?.any { instruction ->
        val reference = (instruction as? ReferenceInstruction)?.reference as? MethodReference
        reference != null && reference.returnType == keyHashType && reference.definingClass == ownerType
    } ?: false