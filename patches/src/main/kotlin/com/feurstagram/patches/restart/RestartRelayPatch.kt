package com.feurstagram.patches.restart

import app.morphe.patcher.patch.resourcePatch
import app.morphe.util.asSequence
import com.feurstagram.patches.shared.Constants.COMPATIBILITY_INSTAGRAM
import org.w3c.dom.Element

private const val ACTIVITY = "com.feurstagram.extension.RestartActivity"

/** Its own process is the whole point: see RestartActivity. */
private const val PROCESS = ":feurstagram_restart"

// The cache-clear restart kills every Instagram process, and a process cannot
// bring itself back: the activity it starts on the way out is dropped by the
// system ("app died, no saved state"), and one queued for afterwards is refused
// as a background activity launch. RestartActivity does it from the outside, but
// only if it is declared to live in a separate process — hence this patch.
@Suppress("unused")
val restartRelayPatch = resourcePatch(
    name = "Restart relay",
    description = "Declares the one-shot activity Feurstagram runs in its own process to bring " +
        "Instagram back after the cache-clear restart.",
    default = true,
) {
    compatibleWith(COMPATIBILITY_INSTAGRAM)

    execute {
        document("AndroidManifest.xml").use { document ->
            val application = document.documentElement
                .getElementsByTagName("application")
                .item(0) as? Element ?: return@use

            val alreadyDeclared = application.getElementsByTagName("activity")
                .asSequence()
                .map { it as Element }
                .any { it.getAttribute("android:name") == ACTIVITY }
            if (alreadyDeclared) return@use

            val activity = document.createElement("activity")
            activity.setAttribute("android:name", ACTIVITY)
            activity.setAttribute("android:process", PROCESS)
            activity.setAttribute("android:exported", "false")
            // Its own task, never in Recents, gone as soon as it finishes: the user
            // should never see that a second activity was involved at all.
            activity.setAttribute("android:launchMode", "singleInstance")
            activity.setAttribute("android:excludeFromRecents", "true")
            activity.setAttribute("android:noHistory", "true")
            activity.setAttribute("android:theme", "@android:style/Theme.Translucent.NoTitleBar")
            application.appendChild(activity)
        }
    }
}
