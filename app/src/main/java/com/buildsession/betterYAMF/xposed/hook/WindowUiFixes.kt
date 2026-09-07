package com.buildsession.betterYAMF.xposed.hook

import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.widget.ImageView
import com.buildsession.betterYAMF.common.gson
import com.buildsession.betterYAMF.xposed.services.YAMFManager
import com.buildsession.betterYAMF.xposed.ui.window.AppWindow
import com.buildsession.betterYAMF.xposed.utils.Instances
import com.buildsession.betterYAMF.xposed.utils.dpToPx
import com.buildsession.betterYAMF.xposed.utils.log
import com.google.gson.JsonParser
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Small, isolated compatibility/performance fixes for the overlay window.
 *
 * Keeping these hooks separate avoids touching AppWindow's large UI state machine while still
 * letting us fix hot paths on multiple Android 13-16 ROM variants.
 */
object WindowUiFixes {
    private const val TAG = "reYAMF_WindowUiFixes"
    private const val BOUNDS_FRAME_DELAY_MS = 8L

    private val mainHandler by lazy { Handler(Looper.getMainLooper()) }
    private val pendingBounds = ConcurrentHashMap<Int, Rect>()
    private val lastAppliedBounds = ConcurrentHashMap<Int, Rect>()
    private val taskTokens = ConcurrentHashMap<Int, Any>()
    private val boundsFrameScheduled = AtomicBoolean(false)

    private val wctClass by lazy {
        Instances.systemContext.classLoader.loadClass("android.window.WindowContainerTransaction")
    }
    private val windowOrganizerController by lazy {
        XposedHelpers.callMethod(Instances.activityTaskManager, "getWindowOrganizerController")
    }

    fun init() {
        hookLegacySurfaceBackendMigration()
        hookWindowIconNormalization()
        hookSmoothBoundsHotPath()
    }

    private fun hookLegacySurfaceBackendMigration() {
        XposedBridge.hookAllMethods(YAMFManager::class.java, "systemReady", object : XC_MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) {
                runCatching {
                    val rawConfig = YAMFManager.configFile.readText()
                    val hasSchemaVersion = rawConfig.isNotBlank() &&
                        JsonParser.parseString(rawConfig).asJsonObject.has("schemaVersion")

                    if (!hasSchemaVersion || YAMFManager.config.schemaVersion < 1) {
                        // Legacy UI said 0=Texture/1=Surface while AppWindow actually used
                        // 0=Surface/1=Texture. Invert once so we preserve the user's intent.
                        YAMFManager.config.surfaceView = when (YAMFManager.config.surfaceView) {
                            0 -> 1
                            1 -> 0
                            else -> 0
                        }
                        YAMFManager.config.schemaVersion = 1
                        YAMFManager.configFile.writeText(gson.toJson(YAMFManager.config))
                        log(TAG, "Migrated legacy surface backend mapping")
                    }
                }.onFailure {
                    log(TAG, "Surface backend migration failed", it)
                }
            }
        })
    }

    private fun hookWindowIconNormalization() {
        val normalizeAfter = object : XC_MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) {
                normalizeWindowIcon(param.thisObject as? AppWindow ?: return)
            }
        }

        // updateTask is private, therefore hook by method name rather than normal invocation.
        XposedBridge.hookAllMethods(AppWindow::class.java, "updateTask", normalizeAfter)
        XposedBridge.hookAllMethods(AppWindow::class.java, "onTaskDescriptionChanged", normalizeAfter)
    }

    private fun normalizeWindowIcon(window: AppWindow) {
        runCatching {
            val icon = window.binding.appIcon
            val inset = 3.dpToPx().toInt()
            icon.scaleType = ImageView.ScaleType.FIT_CENTER
            if (icon.paddingLeft != inset || icon.paddingTop != inset ||
                icon.paddingRight != inset || icon.paddingBottom != inset
            ) {
                icon.setPadding(inset, inset, inset, inset)
            }
        }.onFailure {
            log(TAG, "Unable to normalize collapsed app icon", it)
        }
    }

    private fun hookSmoothBoundsHotPath() {
        XposedBridge.hookAllMethods(YAMFManager::class.java, "updateSmoothBounds", object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                if (YAMFManager.config.windowMode != 1) return

                val taskId = param.args.getOrNull(0) as? Int ?: return
                val bounds = param.args.getOrNull(1) as? Rect ?: return
                val copiedBounds = Rect(bounds)

                // Update the public cache immediately. AppWindow consults this map before calling
                // updateSmoothBounds again, which suppresses duplicate work within the same frame.
                YAMFManager.smoothFreeformBounds[taskId] = copiedBounds
                pendingBounds[taskId] = copiedBounds
                scheduleBoundsFrame()

                // Skip the original implementation, which performs getTasks(Int.MAX_VALUE) and
                // several reflective lookups on every pointer event.
                param.result = null
            }
        })
    }

    private fun scheduleBoundsFrame() {
        if (!boundsFrameScheduled.compareAndSet(false, true)) return
        mainHandler.postDelayed({
            boundsFrameScheduled.set(false)
            drainPendingBounds()
            if (pendingBounds.isNotEmpty()) scheduleBoundsFrame()
        }, BOUNDS_FRAME_DELAY_MS)
    }

    private fun drainPendingBounds() {
        val batch = pendingBounds.entries.associate { it.key to Rect(it.value) }
        batch.forEach { (taskId, bounds) ->
            pendingBounds.remove(taskId, bounds)
            if (lastAppliedBounds[taskId] == bounds) return@forEach
            applyBounds(taskId, bounds)
        }
    }

    private fun applyBounds(taskId: Int, bounds: Rect) {
        runCatching {
            val token = taskTokens[taskId] ?: resolveTaskToken(taskId)?.also {
                taskTokens[taskId] = it
            } ?: return

            val wct = XposedHelpers.newInstance(wctClass)
            XposedHelpers.callMethod(wct, "setBounds", token, bounds)
            XposedHelpers.callMethod(windowOrganizerController, "applyTransaction", wct)
            lastAppliedBounds[taskId] = Rect(bounds)
        }.onFailure {
            // A task token becomes invalid when the app/task dies. Drop it so a reused task ID
            // resolves a fresh token on the next frame instead of repeatedly failing.
            taskTokens.remove(taskId)
            lastAppliedBounds.remove(taskId)
            log(TAG, "Unable to apply smooth bounds for task $taskId", it)
        }
    }

    private fun resolveTaskToken(taskId: Int): Any? {
        val tasks = XposedHelpers.callMethod(
            Instances.activityTaskManager,
            "getTasks",
            64,
            false,
            false,
            -1
        ) as? List<*> ?: return null

        val taskInfo = tasks.firstOrNull {
            runCatching { XposedHelpers.getIntField(it, "taskId") == taskId }.getOrDefault(false)
        } ?: return null

        return XposedHelpers.getObjectField(taskInfo, "token")
    }
}
