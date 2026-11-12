package com.feurstagram.patches.network

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.instructions
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.patch.PatchException
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.feurstagram.patches.quoicouview.addBridge
import com.feurstagram.patches.quoicouview.params
import com.feurstagram.patches.shared.Constants.COMPATIBILITY_INSTAGRAM
import com.feurstagram.patches.shared.Constants.EXTENSION

private const val BLOCK_CLASS = "Lcom/feurstagram/extension/Block;"
private const val RESPONSE_CLASS = "Lcom/feurstagram/extension/ResponseFilter;"

// This dispatcher receives body bytes after Tigon has downloaded them. Match
// its diagnostic marker and signature, then resolve the other lifecycle calls
// structurally in the same class. No obfuscated identifiers are hardcoded.
internal object ResponseBodyDispatcherFingerprint : Fingerprint(
    strings = listOf("RequestCallbackDispatcher.interceptors", "RequestCallbackDispatcher.callback"),
    returnType = "V",
    custom = { method, _ -> method.parameterTypes.size == 2 &&
        method.parameterTypes.last().toString() == "Ljava/nio/ByteBuffer;" },
)

// TigonServiceLayer is Instagram's network layer; the class name and the
// startRequest method are not obfuscated and stable across releases, so they
// anchor the patch without version-specific names.
//
// Instagram 451 turned startRequest into a Systrace wrapper and moved its body
// into startRequestInternal, so the name is matched as a prefix and the method
// that actually reads the request URI is the one picked.
internal object TigonStartRequestFingerprint : Fingerprint(
    definingClass = "Lcom/instagram/api/tigon/TigonServiceLayer;",
    custom = { method, _ ->
        method.name.startsWith("startRequest") &&
            method.implementation?.instructions?.any { it.isUriLoad() } == true
    },
)

private fun Instruction.isUriLoad() =
    opcode == Opcode.IGET_OBJECT &&
        ((this as? ReferenceInstruction)?.reference as? FieldReference)?.type == "Ljava/net/URI;"

@Suppress("unused")
val networkBlockPatch = bytecodePatch(
    name = "Content response filtering",
    description = "Keeps Instagram's native requests and filters blocked content only after " +
        "responses arrive. Logging, seen, uploads and security responses are preserved.",
    default = true,
) {
    compatibleWith(COMPATIBILITY_INSTAGRAM)

    extendWith(EXTENSION)

    execute {
        TigonStartRequestFingerprint.method.apply {
            // The request URI is loaded into a register via an `iget-object` of
            // type java.net.URI inside the request-sending try block. Inject our
            // observer right after it. It never throws or changes the request.
            val uriLoad = instructions.first { it.isUriLoad() }
            val uriRegister = (uriLoad as OneRegisterInstruction).registerA

            addInstructions(
                uriLoad.location.index + 1,
                "invoke-static/range { v$uriRegister .. v$uriRegister }, " +
                    "$BLOCK_CLASS->onRequest(Ljava/net/URI;)V",
            )
        }

        val body = ResponseBodyDispatcherFingerprint.method
        val dispatcher = mutableClassDefBy(body.definingClass)
        val requestType = body.params.first()
        val requestUri = TigonStartRequestFingerprint.method.instructions.mapNotNull {
            (it as? ReferenceInstruction)?.reference as? FieldReference
        }.single { it.definingClass == requestType && it.type == "Ljava/net/URI;" }

        fun calls(method: app.morphe.patcher.util.proxy.mutableTypes.MutableMethod, name: String) =
            method.instructions.any {
                ((it as? ReferenceInstruction)?.reference as? MethodReference)?.name == name
            }

        val headers = dispatcher.methods.single { calls(it, "onResponseStarted") }
        if (headers.params.size != 2 || headers.params.last() != requestType) {
            throw PatchException("Response-start dispatcher shape changed")
        }
        val responseType = headers.params.first()
        val response = mutableClassDefBy(responseType)
        val constructor = response.methods.single {
            it.name == "<init>" && it.params == listOf(
                "Ljava/lang/String;", "Ljava/util/List;", "I", "I",
            )
        }
        // The first int in (String, headers, status, requestId) is the HTTP status.
        val statusRegister = constructor.implementation!!.registerCount - 2
        val statusField = constructor.instructions.filter { it.opcode == Opcode.IPUT }.single {
            (it as TwoRegisterInstruction).registerA == statusRegister
        }.let { (it as ReferenceInstruction).reference as FieldReference }

        mutableClassDefBy(RESPONSE_CLASS).apply {
            fun replace(name: String, result: String, code: String) {
                val stub = methods.single { it.name == name }
                methods.remove(stub)
                addBridge(name, listOf("Ljava/lang/Object;"), result, 2, code, stub.accessFlags)
            }
            replace("requestUri", "Ljava/net/URI;", """
                check-cast p0, $requestType
                iget-object v0, p0, $requestUri
                return-object v0
            """)
            replace("responseStatus", "I", """
                check-cast p0, $responseType
                iget v0, p0, $statusField
                return v0
            """)
        }
        headers.addInstructions(0,
            "invoke-static/range { p0 .. p2 }, $RESPONSE_CLASS->onResponse" +
                "(Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;)V")
        body.addInstructions(0, """
            invoke-static/range { p0 .. p2 }, $RESPONSE_CLASS->onBody(Ljava/lang/Object;Ljava/lang/Object;Ljava/nio/ByteBuffer;)Ljava/nio/ByteBuffer;
            move-result-object p2
        """.trimIndent())
        dispatcher.methods.single { calls(it, "onComplete") }.addInstructions(0,
            "invoke-static/range { p0 .. p0 }, $RESPONSE_CLASS->onComplete(Ljava/lang/Object;)V")
        dispatcher.methods.single { calls(it, "onFailed") }.addInstructions(0,
            "invoke-static/range { p0 .. p0 }, $RESPONSE_CLASS->onError(Ljava/lang/Object;)V")
    }
}
