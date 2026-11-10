package com.feurstagram.patches.clone

import app.morphe.patcher.extensions.InstructionExtensions.replaceInstruction
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.patch.resourcePatch
import app.morphe.patcher.patch.stringOption
import app.morphe.util.asSequence
import app.morphe.util.findElementByAttributeValue
import app.morphe.util.findMutableMethodOf
import app.morphe.util.forEachChildElement
import app.morphe.util.getReference
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.builder.instruction.BuilderInstruction21c
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.reference.StringReference
import com.android.tools.smali.dexlib2.immutable.reference.ImmutableStringReference
import com.feurstagram.patches.shared.Constants.COMPATIBILITY_INSTAGRAM
import org.w3c.dom.Element

private const val ORIGINAL_PACKAGE_NAME = "com.instagram.android"

@Suppress("unused")
val clonePatch = resourcePatch(
    name = "Clone",
    description = "Renames the package and app label so the patched build installs " +
        "alongside a stock Instagram instead of replacing it.",
    default = false,
) {
    compatibleWith(COMPATIBILITY_INSTAGRAM)

    val packageName by stringOption(
        key = "packageName",
        default = "com.instagram.android.feurstagram",
        title = "Package name",
        description = "A new package name for the patched app.",
        required = true,
    ) {
        it!!.matches(Regex("^[a-z]\\w*(\\.[a-z]\\w*)+$"))
    }

    val appName by stringOption(
        key = "appName",
        default = "Feurstagram",
        title = "App name",
        description = "A new app name (label). Entering \"Instagram\" skips changing the label.",
        required = true,
    )

    var bytecodePatchContext: BytecodePatchContext? = null

    dependsOn(
        // Expose the bytecode context so we can rewrite string references from
        // within this resource patch.
        bytecodePatch {
            execute {
                bytecodePatchContext = this
            }
        },
    )

    execute {
        val newPackageName = packageName!!

        // Pairs of (original authority, renamed authority).
        val providerReplacements = mutableListOf<Pair<String, String>>()

        document("AndroidManifest.xml").use { document ->
            val manifest = document.documentElement

            manifest.setAttribute("package", newPackageName)

            val permissions = manifest.getElementsByTagName("permission")
            val usesPermissions = manifest.getElementsByTagName("uses-permission")

            permissions.asSequence().map { it as Element }.forEach {
                val oldName = it.getAttribute("android:name")
                if (oldName.startsWith('.')) return@forEach
                val newName = oldName.replace(ORIGINAL_PACKAGE_NAME, newPackageName)
                it.setAttribute("android:name", newName)

                usesPermissions
                    .findElementByAttributeValue("android:name", oldName)
                    ?.setAttribute("android:name", newName)
            }

            // Only the app's own providers. <queries> also names other apps'
            // providers by authority, and getElementsByTagName returns those too:
            // renaming them would break the query without avoiding any conflict.
            val providers = manifest.getElementsByTagName("provider").asSequence().map { it as Element }
                .filter { (it.parentNode as? Element)?.tagName == "application" }
            for (provider in providers) {
                // One provider may declare several authorities separated by ';', and
                // each must be unique on the device, so every one is renamed.
                val oldAuthorities = provider.getAttribute("android:authorities")
                    .split(';').map { it.trim() }.filter { it.isNotEmpty() }
                if (oldAuthorities.isEmpty()) continue
                val newAuthorities = oldAuthorities.map { oldAuthority ->
                    val newAuthority =
                        if (oldAuthority.startsWith("$ORIGINAL_PACKAGE_NAME.")) {
                            oldAuthority.replaceFirst(ORIGINAL_PACKAGE_NAME, newPackageName)
                        } else {
                            "${newPackageName}_$oldAuthority"
                        }
                    providerReplacements.add(oldAuthority to newAuthority)
                    newAuthority
                }

                provider.setAttribute("android:authorities", newAuthorities.joinToString(";"))
            }
        }

        if (!appName.isNullOrEmpty() && appName != "Instagram") {
            // The "Instagram" label is unlocalised and only lives under res/values.
            document("res/values/strings.xml").use { document ->
                document.documentElement.forEachChildElement {
                    if (it.textContent == "Instagram") {
                        it.textContent = appName
                    }
                }
            }
        }

        // Rewrite the package name (and provider authorities) wherever they are
        // referenced as string constants in the bytecode, otherwise some
        // surfaces (e.g. video stories playing audio only) break in the clone.
        context(bytecodePatchContext!!) {
            transformStringReferences { string ->
                if (string == ORIGINAL_PACKAGE_NAME) {
                    return@transformStringReferences newPackageName
                }
                val matched = providerReplacements.find { string.contains(it.first) }
                if (matched != null) {
                    string.replaceFirst(matched.first, matched.second)
                } else {
                    null
                }
            }
        }
        bytecodePatchContext = null
    }
}

context(patchContext: BytecodePatchContext)
private fun transformStringReferences(transform: (str: String) -> String?) {
    patchContext.getAllClassesWithStrings().forEach { classDef ->
        val mutableClass by lazy { patchContext.mutableClassDefBy(classDef) }

        classDef.methods.forEach { method ->
            val mutableMethod by lazy { mutableClass.findMutableMethodOf(method) }

            method.implementation?.instructions?.forEachIndexed { index, instruction ->
                val string = instruction.getReference<StringReference>()?.string
                    ?: return@forEachIndexed
                val transformed = transform(string) ?: return@forEachIndexed

                mutableMethod.replaceInstruction(
                    index,
                    BuilderInstruction21c(
                        Opcode.CONST_STRING,
                        (instruction as OneRegisterInstruction).registerA,
                        ImmutableStringReference(transformed),
                    ),
                )
            }
        }
    }
}
