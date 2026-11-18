package com.feurstagram.patches.display

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.util.smali.ExternalLabel
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.feurstagram.patches.shared.Constants.COMPATIBILITY_INSTAGRAM
import com.feurstagram.patches.shared.Constants.EXTENSION

private const val GRAYSCALE_CLASS = "Lcom/feurstagram/extension/Grayscale;"

// The video player ("Groot") asks whether to render on a SurfaceView; a container
// can force its TextureView path through getEnforceTextureView(), an interface
// method whose name survives obfuscation. A SurfaceView's pixels bypass the
// window, so the grayscale filter cannot reach them.
internal object PlayerUsesSurfaceViewFingerprint : Fingerprint(
    returnType = "Z",
    parameters = emptyList(),
    custom = { method, _ ->
        method.implementation?.instructions?.any {
            ((it as? ReferenceInstruction)?.reference as? MethodReference)?.name == "getEnforceTextureView"
        } == true
    },
)

@Suppress("unused")
val grayscalePatch = bytecodePatch(
    name = "Grayscale",
    description = "Lets the Grayscale display option draw Instagram without colour, videos " +
        "included, by moving the video player onto a TextureView while it is on.",
    default = true,
) {
    compatibleWith(COMPATIBILITY_INSTAGRAM)

    extendWith(EXTENSION)

    execute {
        PlayerUsesSurfaceViewFingerprint.method.apply {
            if (implementation!!.registerCount < 2) throw PatchException("Surface choice has no free register")
            addInstructionsWithLabels(
                0,
                """
                    invoke-static { }, $GRAYSCALE_CLASS->avoidSurfaceView()Z
                    move-result v0
                    if-eqz v0, :instagram
                    const/4 v0, 0x0
                    return v0
                """,
                ExternalLabel("instagram", getInstruction(0)),
            )
        }
    }
}
