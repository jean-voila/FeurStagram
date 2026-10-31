package com.feurstagram.patches.quoicouview

import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.resourcePatch
import com.feurstagram.patches.shared.Constants.COMPATIBILITY_INSTAGRAM

/** Bundled in the patch jar, copied to the same path under the APK's assets. */
private const val SCREENSHOT = "feurstagram/quoicouview.jpg"

// The extension is code only, so the screenshot shown by the card presenting
// Quoicouview mode (QuoicouviewIntro) travels in the patch jar and is written
// into the APK's assets, where the extension opens it with AssetManager.
@Suppress("unused")
val quoicouviewAssetsPatch = resourcePatch(
    name = "Quoicouview assets",
    description = "Adds the screenshot shown by the card presenting Quoicouview mode.",
    default = true,
) {
    compatibleWith(COMPATIBILITY_INSTAGRAM)

    execute {
        val bytes = object {}.javaClass.classLoader.getResourceAsStream(SCREENSHOT)?.use { it.readBytes() }
            ?: throw PatchException("$SCREENSHOT is missing from the patch bundle")
        val target = get("assets/$SCREENSHOT")
        target.parentFile.mkdirs()
        target.writeBytes(bytes)
    }
}
