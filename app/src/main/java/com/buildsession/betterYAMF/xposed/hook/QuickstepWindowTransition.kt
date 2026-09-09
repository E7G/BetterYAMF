package com.buildsession.betterYAMF.xposed.hook

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.graphics.Matrix
import android.graphics.Rect
import android.graphics.RectF
import android.os.Handler
import android.os.Looper
import android.view.SurfaceControl
import android.view.Choreographer
import android.view.View
import android.view.animation.PathInterpolator
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import com.buildsession.betterYAMF.xposed.utils.log
import kotlin.math.abs
import kotlin.math.exp

/** Uses Quickstep's existing remote-animation leash, never a task screenshot or an overlay.
 * Only a stream explicitly claimed by HookLauncher may change the stock handler's behavior.
 */
internal class QuickstepWindowTransition(private val onCommit: (Int) -> Unit) {
    // Xposed constructs entry points before the app's main Looper exists.
    private val main by lazy { Handler(Looper.getMainLooper()) }
    private var session: Session? = null
    val active: Boolean get() = session != null
    val claimed: Boolean get() = session?.claimed == true

    private class Session(val taskId: Int, val width: Int, val height: Int, val density: Float,
        contentWidthDp: Int, contentHeightDp: Int, topInset: Int) {
        var handler: Any? = null
        var controller: Any? = null
        var target: Any? = null
        var recents: View? = null
        var recentsAlpha = 1f
        var commit = false
        var claimed = false
        var ending = false
        var visualProgress = 0f
        var desiredProgress = 0f
        var lastProgressNanos = 0L
        var animator: ValueAnimator? = null
        var framePending = false
        val rect = RectF(0f, 0f, width.toFloat(), height.toFloat())
        val claimStart = RectF(rect)
        val destination = RectF().apply {
            val w = (contentWidthDp * density).toInt().toFloat()
            val h = (contentHeightDp * density).toInt().toFloat()
            val left = (width - w - (18 * density).toInt()).coerceAtLeast(0f)
            val top = topInset + (24 * density).toInt().coerceAtMost((height - h).toInt().coerceAtLeast(0)) + 28 * density
            set(left, top, left + w, top + h)
        }
        val matrix = Matrix()
        val crop = Rect()
        val matrixValues = FloatArray(9)
        val transaction = SurfaceControl.Transaction()
    }

    fun install(loader: ClassLoader): Boolean = runCatching {
        val handler = XposedHelpers.findClass("com.android.quickstep.AbsSwipeUpHandler", loader)
        require(handler.declaredMethods.any { it.name == "applyScrollAndTransform" })
        require(handler.declaredMethods.any { it.name == "onRecentsAnimationStart" })
        XposedBridge.hookAllConstructors(handler, object : XC_MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) {
                session?.takeIf { it.handler == null }?.handler = param.thisObject
            }
        })
        XposedBridge.hookAllMethods(handler, "onRecentsAnimationStart", object : XC_MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) {
                val s = owned(param) ?: return
                s.controller = param.args[0]
                val targets = XposedHelpers.getObjectField(param.args[1], "apps") as Array<*>
                s.target = targets.filterNotNull().firstOrNull {
                    XposedHelpers.getIntField(it, "taskId") == s.taskId
                }
                if (s.target == null) { release(s); return }
                log(HookLauncher.TAG, "Native transition attached task=${s.taskId}")
                if (s.claimed) apply(s)
            }
        })
        for (method in arrayOf("onCurrentShiftUpdated", "applyScrollAndTransform", "updateLauncherTransitionProgress")) {
            XposedBridge.hookAllMethods(handler, method, object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    val s = owned(param) ?: return
                    if (!s.claimed) return
                    scheduleApply(s)
                    param.result = null
                }
            })
        }
        for (method in arrayOf("onGestureEnded", "onGestureCancelled")) {
            XposedBridge.hookAllMethods(handler, method, object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    val s = owned(param) ?: return
                    if (method == "onGestureCancelled" || !s.commit) {
                        restoreSystemGesture(s)
                        return
                    }
                    s.ending = true
                    settle(s)
                    param.result = null
                }
            })
        }
        for (method in arrayOf("onRecentsAnimationCanceled", "onRecentsAnimationStartTimedOut")) {
            XposedBridge.hookAllMethods(handler, method, object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    owned(param)?.let { release(it) }
                }
            })
        }
        true
    }.onFailure { log(HookLauncher.TAG, "Native gesture adapter unavailable; keep system gestures", it) }
        .getOrDefault(false)

    private fun owned(param: XC_MethodHook.MethodHookParam) =
        session?.takeIf { it.handler === param.thisObject }

    fun begin(taskId: Int, width: Int, height: Int, density: Float,
        contentWidthDp: Int, contentHeightDp: Int, topInset: Int) {
        abort()
        val s = Session(taskId, width, height, density, contentWidthDp, contentHeightDp, topInset)
        session = s
        // No target callback must never leave the input consumer captured indefinitely.
        main.postDelayed({ if (session === s && s.controller == null) release(s) }, 1500)
    }

    fun isMotionPaused(): Boolean {
        val handler = session?.handler ?: return false
        return runCatching { XposedHelpers.getBooleanField(handler, "mIsMotionPaused") }
            .getOrDefault(false)
    }

    /**
     * Update the live leash while the user steers the gesture.  `proximity` is
     * deliberately separate from gesture progress: the window should only be
     * magnetised after the pointer is genuinely close to the corner target.
     */
    fun update(progress: Float, dx: Float, allowClaim: Boolean, proximity: Float = 0f) {
        val s = session ?: return
        if (s.ending) return
        if (!s.claimed) {
            if (!allowClaim) return
            // Claim as soon as the paused gesture has a clear rightward
            // intention. Waiting for the recents card to fully settle makes
            // the handoff visibly jump from the centre of the screen.
            if (progress < .08f || dx < s.width * .025f) return
            readSystemRect(s)?.let(s.claimStart::set)
            s.rect.set(s.claimStart)
            s.claimed = true
        }
        // Let the live task follow both axes. A paused horizontal steer must continue
        // the same transformation instead of waiting for another vertical swipe.
        val verticalP = ((progress - .08f) / .62f).coerceIn(0f, 1f)
        val horizontalP = ((dx / s.width - .025f) / .42f).coerceIn(0f, 1f)
        // HyperOS treats this as one continuous route: the upward motion
        // prepares the handoff, then rightward steering progressively hands
        // control to the corner. This avoids the old centre -> corner jump.
        val lateralEase = horizontalP * horizontalP * (3f - 2f * horizontalP)
        val routeP = (verticalP * (.08f + .78f * lateralEase) + horizontalP * .14f)
            .coerceIn(0f, .84f)
        val near = proximity.coerceIn(0f, 1f)
        // Smootherstep gives zero velocity at both ends of the magnetic band,
        // matching the soft "glide then dock" feel of HyperOS.
        val magneticP = near * near * near * (near * (near * 6f - 15f) + 10f)
        s.desiredProgress = maxOf(routeP, magneticP)
        advanceVisualProgress(s)
        updateRect(s)
        scheduleApply(s)
    }

    fun setCommit(commit: Boolean) { session?.commit = commit }

    fun abort() { session?.let(::restoreSystemGesture) }

    private fun scheduleApply(s: Session) {
        if (session !== s || s.framePending) return
        s.framePending = true
        Choreographer.getInstance().postFrameCallback {
            s.framePending = false
            if (session === s) {
                if (!s.ending && s.claimed) {
                    val moving = advanceVisualProgress(s)
                    updateRect(s)
                    apply(s)
                    if (moving) scheduleApply(s)
                } else {
                    apply(s)
                }
            }
        }
    }

    /** Critically-damped handoff: no discontinuity when magnetic target engages. */
    private fun advanceVisualProgress(s: Session): Boolean {
        val now = System.nanoTime()
        val dtMs = if (s.lastProgressNanos == 0L) 16f
        else ((now - s.lastProgressNanos) / 1_000_000f).coerceIn(1f, 32f)
        s.lastProgressNanos = now
        val delta = s.desiredProgress - s.visualProgress
        if (abs(delta) < .001f) {
            s.visualProgress = s.desiredProgress
            return false
        }
        val responseMs = if (delta > 0f) 145f else 95f
        val blend = 1f - exp(-dtMs / responseMs)
        s.visualProgress += delta * blend
        return true
    }

    private fun updateRect(s: Session) {
        val p = s.visualProgress.coerceIn(0f, 1f)
        val w = s.claimStart.width() + (s.destination.width() - s.claimStart.width()) * p
        val h = s.claimStart.height() + (s.destination.height() - s.claimStart.height()) * p
        val left = s.claimStart.left + (s.destination.left - s.claimStart.left) * p
        val top = s.claimStart.top + (s.destination.top - s.claimStart.top) * p
        s.rect.set(left, top, left + w, top + h)
    }

    private fun apply(s: Session) {
        if (session !== s || !s.claimed) return
        runCatching {
            val recents = s.handler?.let { XposedHelpers.getObjectField(it, "mRecentsView") } as? View
            if (s.recents == null && recents != null) {
                s.recents = recents
                s.recentsAlpha = recents.alpha
            }
            // Keep the launcher at app-state progress, suppress only its overview view.
            val fadeP = (s.visualProgress / .30f).coerceIn(0f, 1f)
            recents?.alpha = s.recentsAlpha * (1f - fadeP * fadeP * (3f - 2f * fadeP))
            val target = s.target ?: return
            val leash = XposedHelpers.getObjectField(target, "leash") as SurfaceControl
            if (!leash.isValid) return
            val bounds = XposedHelpers.getObjectField(target, "screenSpaceBounds") as Rect
            val source = RectF(0f, 0f, bounds.width().toFloat(), bounds.height().toFloat())
            if (source.isEmpty) return
            // Crop with a uniform scale, as system app transitions do. Non-uniform
            // FILL visibly stretches icons/text when landscape becomes a portrait window.
            val scale = maxOf(s.rect.width() / source.width(), s.rect.height() / source.height())
            val cropWidth = (s.rect.width() / scale).toInt().coerceIn(1, bounds.width())
            val cropHeight = (s.rect.height() / scale).toInt().coerceIn(1, bounds.height())
            val cropLeft = (bounds.width() - cropWidth) / 2
            s.crop.set(cropLeft, 0, cropLeft + cropWidth, cropHeight)
            s.matrix.setScale(scale, scale)
            s.matrix.postTranslate(s.rect.left - cropLeft * scale, s.rect.top)
            XposedHelpers.callMethod(s.transaction, "setMatrix", leash, s.matrix, s.matrixValues)
            XposedHelpers.callMethod(s.transaction, "setWindowCrop", leash, s.crop)
            XposedHelpers.callMethod(s.transaction, "setCornerRadius", leash,
                22 * s.density)
            s.transaction.setAlpha(leash, 1f).apply()
        }.onFailure {
            log(HookLauncher.TAG, "Native transition failed; restore app", it)
            finish(s, false)
        }
    }

    private fun readSystemRect(s: Session): RectF? = runCatching {
        val handles = XposedHelpers.getObjectField(s.handler, "mRemoteTargetHandles") as Array<*>
        val simulator = XposedHelpers.callMethod(handles.first(), "getTaskViewSimulator")
        RectF(XposedHelpers.callMethod(simulator, "getCurrentRect") as RectF)
    }.getOrNull()

    private fun restoreSystemGesture(s: Session) {
        val handler = s.handler
        release(s)
        runCatching { handler?.let { XposedHelpers.callMethod(it, "applyScrollAndTransform") } }
    }

    private fun settle(s: Session) {
        if (session !== s || s.controller == null || s.animator != null) return
        val from = RectF(s.rect)
        val to = if (s.commit) s.destination else RectF(0f, 0f, s.width.toFloat(), s.height.toFloat())
        s.animator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = if (s.commit) 180 else 140
            interpolator = PathInterpolator(.22f, .80f, .30f, 1f)
            addUpdateListener {
                val p = it.animatedValue as Float
                s.rect.set(from.left + (to.left - from.left) * p, from.top + (to.top - from.top) * p,
                    from.right + (to.right - from.right) * p, from.bottom + (to.bottom - from.bottom) * p)
                apply(s)
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    if (session === s) finish(s, s.commit)
                }
            })
            start()
        }
    }

    private fun finish(s: Session, commit: Boolean) {
        if (session !== s) return
        val controller = s.controller
        val handler = s.handler
        release(s)
        runCatching {
            val done = Runnable {
                runCatching { handler?.let { XposedHelpers.callMethod(it, "reset") } }
                if (commit && handler != null) runCatching {
                    val container = XposedHelpers.getObjectField(handler, "mContainer")
                    val manager = XposedHelpers.callMethod(container, "getStateManager")
                    val state = XposedHelpers.findClass("com.android.launcher3.LauncherState", handler.javaClass.classLoader)
                    XposedHelpers.callMethod(manager, "goToState", XposedHelpers.getStaticObjectField(state, "NORMAL"), false)
                }
            }
            if (controller != null) finishController(controller, commit, done)
            else done.run()
        }.onFailure {
            log(HookLauncher.TAG, "Unable to finish native transition", it)
            runCatching { handler?.let { XposedHelpers.callMethod(it, "onGestureCancelled") } }
        }
    }

    private fun finishController(controller: Any, toHome: Boolean, done: Runnable) {
        val methods = generateSequence(controller.javaClass as Class<*>?) { it.superclass }
            .flatMap { it.declaredMethods.asSequence() }.toList()
        val method = methods.firstOrNull {
            it.name == "finish" && it.parameterTypes.contentEquals(
                arrayOf(Boolean::class.javaPrimitiveType, Runnable::class.java))
        }
        if (method != null) { method.isAccessible = true; method.invoke(controller, toHome, done); return }
        // Android 17 adds an explicit diagnostic reason to the finish callback.
        val withReason = methods.firstOrNull {
            it.name == "finish" && it.parameterCount == 3 &&
                it.parameterTypes[0] == Boolean::class.javaPrimitiveType &&
                it.parameterTypes[1] == Runnable::class.java &&
                it.parameterTypes[2].name.endsWith("\$CompoundString")
        } ?: error("Unsupported controller ${controller.javaClass.name}: ${methods.filter { it.name == "finish" }}")
        withReason.isAccessible = true
        val reason = XposedHelpers.newInstance(withReason.parameterTypes[2], false)
        withReason.invoke(controller, toHome, done, reason)
    }

    private fun release(s: Session) {
        if (session !== s) return
        session = null
        s.animator?.removeAllListeners()
        s.animator?.cancel()
        s.recents?.alpha = s.recentsAlpha
        s.transaction.close()
    }
}
