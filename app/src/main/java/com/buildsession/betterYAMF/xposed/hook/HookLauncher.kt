package com.buildsession.betterYAMF.xposed.hook

import android.annotation.SuppressLint
import android.app.Activity
import android.app.ActivityManager
import android.app.AndroidAppHelper
import android.app.PendingIntent
import android.app.RemoteAction
import android.content.ComponentName
import android.content.Intent
import android.content.res.Resources
import android.graphics.Rect
import android.graphics.drawable.Icon
import android.os.Handler
import android.os.Build
import android.os.Looper
import android.os.UserHandle
import android.view.MotionEvent
import android.view.Gravity
import android.graphics.PixelFormat
import android.view.View
import android.view.WindowManager
import android.view.ViewGroup
import android.widget.ImageView
import androidx.core.graphics.drawable.toBitmap
import com.github.kyuubiran.ezxhelper.init.EzXHelperInit
import com.github.kyuubiran.ezxhelper.init.InitFields.moduleRes
import com.github.kyuubiran.ezxhelper.utils.argTypes
import com.github.kyuubiran.ezxhelper.utils.args
import com.github.kyuubiran.ezxhelper.utils.findAllMethods
import com.github.kyuubiran.ezxhelper.utils.findConstructor
import com.github.kyuubiran.ezxhelper.utils.findField
import com.github.kyuubiran.ezxhelper.utils.findMethod
import com.github.kyuubiran.ezxhelper.utils.findMethodOrNull
import com.github.kyuubiran.ezxhelper.utils.getObject
import com.github.kyuubiran.ezxhelper.utils.hookAfter
import com.github.kyuubiran.ezxhelper.utils.hookBefore
import com.github.kyuubiran.ezxhelper.utils.hookReplace
import com.github.kyuubiran.ezxhelper.utils.hookReturnConstant
import com.github.kyuubiran.ezxhelper.utils.invokeMethod
import com.github.kyuubiran.ezxhelper.utils.invokeMethodAuto
import com.github.kyuubiran.ezxhelper.utils.invokeMethodAutoAs
import com.github.kyuubiran.ezxhelper.utils.loadClass
import com.github.kyuubiran.ezxhelper.utils.loadClassOrNull
import com.github.kyuubiran.ezxhelper.utils.newInstance
import com.github.kyuubiran.ezxhelper.utils.paramCount
import com.buildsession.betterYAMF.R
import com.buildsession.betterYAMF.xposed.services.YAMFManager
import com.buildsession.betterYAMF.xposed.utils.dpToPx
import com.buildsession.betterYAMF.xposed.utils.log
import com.buildsession.betterYAMF.xposed.utils.registerReceiver
import de.robv.android.xposed.IXposedHookLoadPackage
import de.robv.android.xposed.IXposedHookZygoteInit
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XC_MethodReplacement
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage
import java.lang.reflect.Proxy
import kotlin.math.min
import kotlin.math.hypot


class HookLauncher : IXposedHookLoadPackage, IXposedHookZygoteInit {
    companion object {
        const val TAG = "BetterYAMF_HookLauncher"
        const val ACTION_RECEIVE_LAUNCHER_CONFIG =
            "com.buildsession.betterYAMF.ACTION_RECEIVE_LAUNCHER_CONFIG"

        const val EXTRA_HOOK_RECENT = "hookRecent"
        const val EXTRA_HOOK_TASKBAR = "hookTaskbar"
        const val EXTRA_HOOK_POPUP = "hookPopup"
        const val EXTRA_HOOK_TRANSIENT_TASKBAR = "hookTransientTaskbar"
        const val EXTRA_WINDOW_WIDTH = "gestureWindowWidth"
        const val EXTRA_WINDOW_HEIGHT = "gestureWindowHeight"
    }

    private var isRegistered = false

    private val mDropZoneRect = Rect()
    private val mMainHandler: Handler by lazy { Handler(Looper.getMainLooper()) }
    private var mScreenHeight = 0
    private var mScreenWidth = 0
    private var mStartX = 0f
    private var mStartY = 0f
    private var mCurrentTaskId = -1
    private var mDropZoneView: WindowDropZoneView? = null
    private var mDropZoneWindowManager: WindowManager? = null
    private var nativeAvailable = false
    private var gestureWindowWidth = 280
    private var gestureWindowHeight = 380
    private val nativeTransition = QuickstepWindowTransition { taskId ->
        AndroidAppHelper.currentApplication().sendBroadcast(
            Intent(YAMFManager.ACTION_OPEN_IN_YAMF).apply {
                setPackage("android")
                putExtra(YAMFManager.EXTRA_TASK_ID, taskId)
                putExtra(YAMFManager.EXTRA_SOURCE, YAMFManager.SOURCE_GESTURE)
            }
        )
    }

    private var mCurrentRotation = 0
    private var mIsAlreadyVisual = true
    private var mIsPotentialSwipeUp = false

    @Suppress("DEPRECATION")
    private fun captureTopTask(context: android.content.Context): Int {
        val activityManager = context.getSystemService(android.content.Context.ACTIVITY_SERVICE) as ActivityManager
        val keyguard = context.getSystemService(android.app.KeyguardManager::class.java)
        if (keyguard.isKeyguardLocked) return -1
        val task = activityManager.getRunningTasks(1).firstOrNull() ?: return -1
        if (XposedHelpers.getIntField(task, "displayId") != android.view.Display.DEFAULT_DISPLAY) return -1
        val mode = runCatching { XposedHelpers.callMethod(task, "getWindowingMode") as Int }.getOrDefault(1)
        if (mode != 1) return -1
        val packageName = task.topActivity?.packageName ?: task.baseActivity?.packageName
        if (task.taskId <= 0 || packageName == context.packageName ||
            packageName == "com.android.launcher3" ||
            packageName == "com.google.android.apps.nexuslauncher" ||
            packageName?.contains("launcher", ignoreCase = true) == true
        ) return -1
        return task.taskId
    }

    private fun updateDimensions(context: android.content.Context) {
        val wm = context.getSystemService(android.content.Context.WINDOW_SERVICE) as WindowManager
        val display = wm.defaultDisplay
        val displayMetrics = android.util.DisplayMetrics()
        display.getRealMetrics(displayMetrics)
        
        val newWidth = displayMetrics.widthPixels
        val newHeight = displayMetrics.heightPixels
        
        if (newWidth != mScreenWidth || newHeight != mScreenHeight) {
            mScreenWidth = newWidth
            mScreenHeight = newHeight
            
            // 横屏下让释放区更宽一些，竖屏下保持原
            // HyperOS-style compact quarter circle anchored to the upper-right corner.
            val zoneSize = (96 * context.resources.displayMetrics.density).toInt()
                .coerceAtMost((min(mScreenWidth, mScreenHeight) * .28f).toInt())
            mDropZoneRect.set(mScreenWidth - zoneSize, 0, mScreenWidth, zoneSize)
            
        }
    }

    override fun initZygote(startupParam: IXposedHookZygoteInit.StartupParam) {
        EzXHelperInit.initZygote(startupParam)
    }

    @SuppressLint("UnspecifiedRegisterReceiverFlag")
    override fun handleLoadPackage(lpparam: XC_LoadPackage.LoadPackageParam) {
        EzXHelperInit.initHandleLoadPackage(lpparam)
        
        // Pixel Launcher: com.google.android.apps.nexuslauncher
        // Launcher3: com.android.launcher3
        val isLauncher = lpparam.packageName == "com.android.launcher3" || 
                        lpparam.packageName == "com.google.android.apps.nexuslauncher" ||
                        lpparam.packageName.contains("launcher")
        
        if (!isLauncher) return
        // log(TAG, "Handling package: ${lpparam.packageName} (Process: ${lpparam.processName})")

        // 尝试加载 TouchInteractionService，Pixel Launcher 可能会继承或直接使用该类
        val tisClass = loadClassOrNull("com.android.quickstep.TouchInteractionService")
        if (tisClass != null) {
            nativeAvailable = nativeTransition.install(lpparam.classLoader)
            // Android 17 moved input dispatch out of TouchInteractionService.
            val inputOwnerClass = loadClassOrNull("com.android.quickstep.TouchInteractionHandler") ?: tisClass
            XposedBridge.hookAllMethods(inputOwnerClass, "onInputEvent", object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    val event = param.args[0] as? MotionEvent ?: return
                    val action = event.actionMasked
                    if (action != MotionEvent.ACTION_DOWN && action != MotionEvent.ACTION_MOVE &&
                        action != MotionEvent.ACTION_UP && action != MotionEvent.ACTION_CANCEL &&
                        action != MotionEvent.ACTION_POINTER_DOWN) return

                    val context = AndroidAppHelper.currentApplication()
                    if (action == MotionEvent.ACTION_DOWN) {
                        updateDimensions(context)
                        val wm = context.getSystemService(android.content.Context.WINDOW_SERVICE) as WindowManager
                        mCurrentRotation = wm.defaultDisplay.rotation
                        val rawX = event.rawX
                        val rawY = event.rawY
                        mIsAlreadyVisual = Build.VERSION.SDK_INT >= 35 || if (mCurrentRotation == 0) true
                        else if (mScreenWidth > mScreenHeight) rawX > mScreenHeight + 100 || rawY > mScreenHeight + 100
                        else rawX > mScreenWidth + 100 || rawY > mScreenWidth + 100
                    }

                    val rawX = event.rawX
                    val rawY = event.rawY
                    val correctedX: Float
                    val correctedY: Float
                    if (mIsAlreadyVisual) { correctedX = rawX; correctedY = rawY }
                    else if (mCurrentRotation == 1) { correctedX = rawY; correctedY = mScreenHeight - rawX }
                    else if (mCurrentRotation == 3) { correctedX = mScreenWidth - rawY; correctedY = rawX }
                    else { correctedX = rawX; correctedY = rawY }

                    when (action) {
                        MotionEvent.ACTION_DOWN -> {
                            hideDropZone()
                            nativeTransition.abort()
                            mStartX = correctedX
                            mStartY = correctedY
                            mCurrentTaskId = runCatching { captureTopTask(context) }.getOrDefault(-1)
                            val landscape = mScreenWidth > mScreenHeight
                            mIsPotentialSwipeUp = nativeAvailable && mCurrentTaskId != -1 && event.pointerCount == 1 &&
                                correctedY > mScreenHeight * if (landscape) .90f else .94f
                            if (mIsPotentialSwipeUp) {
                                val topInset = (context.getSystemService(android.content.Context.WINDOW_SERVICE) as WindowManager)
                                    .currentWindowMetrics.windowInsets.getInsetsIgnoringVisibility(
                                        android.view.WindowInsets.Type.statusBars()).top
                                nativeTransition.begin(mCurrentTaskId, mScreenWidth, mScreenHeight,
                                    context.resources.displayMetrics.density, gestureWindowWidth,
                                    gestureWindowHeight, topInset)
                            }
                        }
                        MotionEvent.ACTION_MOVE -> if (mIsPotentialSwipeUp) {
                            val upward = mStartY - correctedY
                            val progress = (upward / (mScreenHeight * .60f)).coerceIn(0f, 1f)
                            val paused = nativeTransition.isMotionPaused()
                            if (paused && progress >= .08f) showDropZone(context)
                            nativeTransition.update(
                                progress,
                                correctedX - mStartX,
                                paused,
                                dropZoneProximity(correctedX, correctedY)
                            )
                            updateDropZone(nativeTransition.claimed &&
                                isInDropZone(correctedX, correctedY))
                        }
                        MotionEvent.ACTION_UP -> if (mIsPotentialSwipeUp) {
                            nativeTransition.setCommit(nativeTransition.claimed &&
                                isInDropZone(correctedX, correctedY))
                            hideDropZone()
                            resetGestureTracking(false)
                        }
                        MotionEvent.ACTION_CANCEL, MotionEvent.ACTION_POINTER_DOWN -> if (mIsPotentialSwipeUp) {
                            nativeTransition.setCommit(false)
                            hideDropZone()
                            resetGestureTracking(false)
                        }
                    }
                    // Quickstep always receives the complete stream. Non-target release
                    // therefore finishes its stock overview animation.
                }
            })

            findMethod(tisClass) { name == "onDestroy" }.hookBefore {
                nativeTransition.abort()
                resetGestureTracking(removeZone = true)
            }
        }

        findMethod("com.android.launcher3.Launcher") {
            name == "onCreate"
        }.hookAfter {
            if (!isRegistered) {
                val activity = it.thisObject as Activity
                val application = activity.application
                application.registerReceiver(ACTION_RECEIVE_LAUNCHER_CONFIG) { _, intent ->
                    gestureWindowWidth = intent.getIntExtra(EXTRA_WINDOW_WIDTH, 280)
                    gestureWindowHeight = intent.getIntExtra(EXTRA_WINDOW_HEIGHT, 380)
                    val hookRecent = intent.getBooleanExtra(EXTRA_HOOK_RECENT, false)
                    val hookTaskbar = intent.getBooleanExtra(EXTRA_HOOK_TASKBAR, false)
                    val hookPopup = intent.getBooleanExtra(EXTRA_HOOK_POPUP, false)
                    val hookTransientTaskbar =
                        intent.getBooleanExtra(EXTRA_HOOK_TRANSIENT_TASKBAR, false)
                    /* log(
                        TAG,
                        "receive config hookRecent=$hookRecent hookTaskbar=$hookTaskbar hookPopup=$hookPopup hookTranslucentTaskbar=$hookTransientTaskbar"
                    ) */
                    if (hookRecent) runCatching { hookRecent(lpparam) }.onFailure { e ->
                        log(TAG, "hook recent failed", e) }
                    if (hookTaskbar) runCatching { hookTaskbar(lpparam) }.onFailure { e ->
                        log(TAG, "hook taskbar failed", e) }
                    if (hookPopup) runCatching { hookPopup(lpparam) }.onFailure { e ->
                        log(TAG, "hook popup failed", e) }
                    if (hookTransientTaskbar) runCatching { hookTransientTaskbar(lpparam) }.onFailure { e ->
                        log(TAG, "hook transient failed", e) }
                    application.unregisterReceiver(this)
                }
                application.sendBroadcast(Intent(YAMFManager.ACTION_GET_LAUNCHER_CONFIG).apply {
                    `package` = "android"
                    putExtra("sender", application.packageName)
                })

                isRegistered = true
            }
        }
    }

    private fun hookRecent(lpparam: XC_LoadPackage.LoadPackageParam) {
        // log(TAG, "hooking recent ${lpparam.packageName}")
        XposedBridge.hookAllMethods(
            XposedHelpers.findClass(
                "com.android.quickstep.TaskOverlayFactory",
                lpparam.classLoader
            ), "getEnabledShortcuts", object : XC_MethodHook() {
                @SuppressLint("UseCompatLoadingForDrawables")
                override fun afterHookedMethod(param: MethodHookParam) {
                    val taskView = param.args[0] as View
                    val shortcuts = param.result as MutableList<Any>
                    val itemInfo = XposedHelpers.getObjectField(shortcuts[0], "mItemInfo")

                    var task: Any = Unit

                    runCatching {
                        task = XposedHelpers.callMethod(taskView, "getTask")
                    }.onFailure {
                        val taskContainers = XposedHelpers.getObjectField(taskView, "taskContainers") as List<*>
                        val firstContainer = taskContainers[0]
                        task = XposedHelpers.getObjectField(firstContainer, "task")
                    }

                    val activity = taskView.context
                    val key = XposedHelpers.getObjectField(task, "key")
                    val taskId = XposedHelpers.getIntField(key, "id")

                    val userId = XposedHelpers.getIntField(key, "userId")

                    val classRemoteActionShortcut = XposedHelpers.findClass(
                        "com.android.launcher3.popup.RemoteActionShortcut",
                        lpparam.classLoader
                    )

                    val intent = Intent(YAMFManager.ACTION_OPEN_IN_YAMF).apply {
                        setPackage("android")
                    }

                    runCatching {
                        val itemInfoTmp =
                            itemInfo.javaClass.newInstance(args(itemInfo), argTypes(itemInfo.javaClass))
                        val topComponent = XposedHelpers.callMethod(itemInfoTmp, "getTargetComponent") as ComponentName
                        intent.putExtra(YAMFManager.EXTRA_COMPONENT_NAME, topComponent)
                    }.onFailure {
                        val topComponent = extractComponentInfo(itemInfo.toString()).toString()
                        intent.putExtra(YAMFManager.EXTRA_COMPONENT_NAME, topComponent)
                    }
                    intent.putExtra(YAMFManager.EXTRA_TASK_ID, taskId)
                    intent.putExtra(YAMFManager.EXTRA_USER_ID, userId)
                    intent.putExtra(YAMFManager.EXTRA_SOURCE, YAMFManager.SOURCE_RECENT)

                    val action = RemoteAction(
                        Icon.createWithBitmap(
                            moduleRes.getDrawable(R.drawable.ic_picture_in_picture_alt_24, null)
                                .toBitmap()
                        ),
                        moduleRes.getString(R.string.open_with_yamf), // + if (BuildConfig.DEBUG) " ($taskId)" else "",
                        "",
                        PendingIntent.getBroadcast(
                            AndroidAppHelper.currentApplication(),
                            1345,
                            intent,
                            PendingIntent.FLAG_ONE_SHOT or PendingIntent.FLAG_IMMUTABLE
                        )
                    )
                    val c = classRemoteActionShortcut.constructors[0]
                    val shortcut = when (c.parameterCount) {
                        4 -> c.newInstance(action, activity, itemInfo, null)
                        3 -> c.newInstance(action, activity, itemInfo)
                        else -> {
                            log(
                                TAG,
                                "unknown RemoteActionShortcut constructor: ${c.toGenericString()}"
                            )
                            null
                        }
                    }

                    if (shortcut != null) {
                        shortcuts.add(shortcut)
                    }
                }
            })
    }

    private fun hookTaskbar(lpparam: XC_LoadPackage.LoadPackageParam) {
        // log(TAG, "hooking taskbar ${lpparam.packageName}")
        loadClass("com.android.launcher3.taskbar.TaskbarActivityContext").apply {
            findMethodOrNull { name == "startItemInfoActivity" }
                ?.hookReplace {
                    val infoIntent = it.args[0].invokeMethodAutoAs<Intent>("getIntent")!!
                    val intent = Intent(YAMFManager.ACTION_OPEN_IN_YAMF).apply {
                        setPackage("android")
                        putExtra(YAMFManager.EXTRA_COMPONENT_NAME, infoIntent.component)
                        putExtra(YAMFManager.EXTRA_SOURCE, YAMFManager.SOURCE_TASKBAR)
                    }
                    AndroidAppHelper.currentApplication().sendBroadcast(intent)
                }
            val classWorkspaceiteminfo =
                loadClass("com.android.launcher3.model.data.WorkspaceItemInfo")
            findMethod { name == "onTaskbarIconClicked" }
                .hookBefore {
                    val tag = it.args[0].invokeMethodAuto("getTag")!!
                    if (classWorkspaceiteminfo.isInstance(tag)) {
                        val infoIntent = tag.invokeMethodAutoAs<Intent>("getIntent")!!
                        val intent = Intent(YAMFManager.ACTION_OPEN_IN_YAMF).apply {
                            setPackage("android")
                            putExtra(YAMFManager.EXTRA_COMPONENT_NAME, infoIntent.component)
                            putExtra(YAMFManager.EXTRA_SOURCE, YAMFManager.SOURCE_TASKBAR)
                        }
                        AndroidAppHelper.currentApplication().sendBroadcast(intent)
                        it.result = Unit
                    }
                }
        }

    }

    private var proxyClass: Any? = null

    private fun hookPopup(lpparam: XC_LoadPackage.LoadPackageParam) {
        // log(TAG, "hooking popup ${lpparam.packageName}")
        loadClass("com.android.launcher3.popup.ArrowPopup").apply {
            findMethodOrNull { name == "onVisibilityAggregated" }?.hookAfter {
                if (it.args[0] as Boolean) {
                    val popup = it.thisObject as View
                    val container = popup.parent as ViewGroup
                    for (i in 0 until container.childCount) {
                        val child = container.getChildAt(i)
                        if (child::class.java.name.contains("SystemShortcut")) {
                            // TODO
                        }
                    }
                }
            }
        }
    }

    private fun hookTransientTaskbar(lpparam: XC_LoadPackage.LoadPackageParam) {
        // log(TAG, "hooking transient taskbar ${lpparam.packageName}")
    }

    private fun showDropZone(context: android.content.Context) {
        mMainHandler.post {
            if (mDropZoneView != null) return@post
            val wm = context.getSystemService(android.content.Context.WINDOW_SERVICE) as WindowManager
            val view = WindowDropZoneView(context)
            view.alpha = 0f
            view.scaleX = .78f
            view.scaleY = .78f
            view.pivotX = mDropZoneRect.width().toFloat()
            view.pivotY = 0f
            val lp = WindowManager.LayoutParams().apply {
                type = WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                format = PixelFormat.TRANSLUCENT
                flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                width = mDropZoneRect.width()
                height = mDropZoneRect.height()
                gravity = Gravity.TOP or Gravity.END
            }
            runCatching { wm.addView(view, lp) }.onSuccess {
                mDropZoneView = view
                mDropZoneWindowManager = wm
                view.animate().alpha(1f).scaleX(1f).scaleY(1f)
                    .setDuration(180L)
                    .setInterpolator(android.view.animation.PathInterpolator(.16f, .84f, .24f, 1f))
                    .start()
            }.onFailure { log(TAG, "Unable to show window drop zone", it) }
        }
    }

    private fun updateDropZone(highlighted: Boolean) {
        mMainHandler.post { mDropZoneView?.highlighted = highlighted }
    }

    private fun isInDropZone(x: Float, y: Float): Boolean {
        if (!mDropZoneRect.contains(x.toInt(), y.toInt())) return false
        val dx = mScreenWidth - x
        val radius = mDropZoneRect.width().toFloat()
        return dx * dx + y * y <= radius * radius
    }

    /** Soft attraction band around the quarter-circle target. */
    private fun dropZoneProximity(x: Float, y: Float): Float {
        val radius = mDropZoneRect.width().toFloat()
        if (radius <= 0f) return 0f
        val distance = hypot(mScreenWidth - x, y)
        // Do not pull from the middle of the screen: attraction begins only
        // just outside the quarter-circle and ramps over a short final band.
        val start = radius * 1.12f
        val range = radius * .30f
        return ((start - distance) / range).coerceIn(0f, 1f)
    }

    private fun hideDropZone() {
        mMainHandler.post {
            val view = mDropZoneView ?: return@post
            mDropZoneView = null
            runCatching { if (view.isAttachedToWindow) mDropZoneWindowManager?.removeViewImmediate(view) }
            mDropZoneWindowManager = null
        }
    }

    private fun resetGestureTracking(removeZone: Boolean) {
        mIsPotentialSwipeUp = false
        mCurrentTaskId = -1
        if (removeZone) hideDropZone()
    }

    private fun extractComponentInfo(input: String): ComponentName? {
        val regex = Regex("""ComponentInfo\{([^/]+)/([^}]+)\}""")
        val matchResult = regex.find(input)
        return if (matchResult != null) {
            val (packageName, className) = matchResult.destructured
            ComponentName(packageName, className)
        } else {
            null
        }
    }
}
