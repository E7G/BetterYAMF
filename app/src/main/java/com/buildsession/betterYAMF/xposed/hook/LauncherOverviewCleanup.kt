package com.buildsession.betterYAMF.xposed.hook

import android.view.View
import com.buildsession.betterYAMF.xposed.utils.log
import de.robv.android.xposed.XposedHelpers

/** Complete the state application skipped when YAMF consumes onGestureEnded. */
internal object LauncherOverviewCleanup {
    fun finishHome(launcher: Any, overview: View? = null) {
        // Use the stock state handlers, rather than leaving a transparent but
        // interactive task carousel behind Home. This also cancels the gesture's
        // pending property animator, which could otherwise resurrect the headers.
        val manager = runCatching {
            XposedHelpers.callMethod(launcher, "getStateManager")
        }.getOrElse {
            log(HookLauncher.TAG, "Unable to obtain Launcher state manager", it)
            return
        }
        // Delayed cleanup catches the cross-display transition that follows a
        // successful commit. Never close a real Overview gesture the user may
        // have started meanwhile.
        val isNormal = runCatching {
            val launcherState = XposedHelpers.findClass(
                "com.android.launcher3.LauncherState", launcher.javaClass.classLoader
            )
            XposedHelpers.callMethod(manager, "getState") ===
                XposedHelpers.getStaticObjectField(launcherState, "NORMAL")
        }.getOrDefault(true)
        if (!isNormal) return

        runCatching {
            XposedHelpers.callMethod(manager, "reapplyState", true)
        }.onFailure { log(HookLauncher.TAG, "Unable to reapply Launcher home state", it) }

        val recents = overview ?: runCatching {
            XposedHelpers.callMethod(launcher, "getOverviewPanel") as? View
        }.getOrNull() ?: return
        recents.animate().cancel()
        // reset() unloads task data, not visibility. Content alpha can already
        // be zero while visibility is frozen, so explicitly unfreeze and hide.
        call(recents, "setOverviewStateEnabled", false)
        call(recents, "setOverlayEnabled", false)
        call(recents, "setFreezeViewVisibility", false)
        call(recents, "setContentAlpha", 0f)
        recents.visibility = View.INVISIBLE
        recents.alpha = 1f
        // A subsequent real Overview transition owns visibility/content alpha
        // again. No timer or unrelated ACTION_DOWN may expose stale task views.
    }

    private fun call(receiver: Any, method: String, value: Any) {
        runCatching { XposedHelpers.callMethod(receiver, method, value) }
            .onFailure { log(HookLauncher.TAG, "Overview cleanup: $method unavailable", it) }
    }
}
