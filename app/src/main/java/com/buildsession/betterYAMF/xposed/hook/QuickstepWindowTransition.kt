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
        contentWidthDp: Int, contentHeightDp: Int, val topInset: Int) {
        var handler: Any? = null
        var controller: Any? = null
        var target: Any? = null
        var recents: View? = null
        var recentsAlpha = 1f
        var commit = false
        var claimed = false
        var ending = false
        var claimProgress = 0f
        var claimFingerX = 0f
        var claimFingerY = 0f
        var grabRatioX = .5f
        var grabRatioY = 1f
        var animator: ValueAnimator? = null
        var framePending = false
        val rect = RectF(0f, 0f, width.toFloat(), height.toFloat())
        val claimStart = RectF(rect)
        val followRect = RectF(rect)
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
    fun update(
        progress: Float,
        dx: Float,
        allowClaim: Boolean,
        proximity: Float = 0f,
        fingerX: Float = Float.NaN,
        fingerY: Float = Float.NaN
    ) {
        val s = session ?: return
        if (s.ending) return
        if (!s.claimed) {
            if (!allowClaim) return
            // Claim as soon as the paused gesture has a clear rightward
            // intention. Waiting for the recents card to fully settle makes
            // the handoff visibly jump from the centre of the screen.
            if (progress < .08f || dx < s.width * .025f) return
            val systemRect = readSystemRect(s)
            if (systemRect != null && isUsableSystemRect(s, systemRect)) {
                s.claimStart.set(systemRect)
            } else {
                // Some Quickstep builds expose getCurrentRect in the natural
                // display rotation. Using it in landscape is what caused the
                // centre -> lower-right -> upper-right jump. A near-fullscreen
                // fallback is visually continuous at our early claim point.
                val fallbackScale = 1f - progress.coerceIn(0f, .18f) * .22f
                val w = s.width * fallbackScale
                val h = s.height * fallbackScale
                s.claimStart.set(
                    (s.width - w) * .5f,
                    (s.height - h) * .5f - progress * s.height * .08f,
                    (s.width + w) * .5f,
                    (s.height + h) * .5f - progress * s.height * .08f
                )
            }
            s.rect.set(s.claimStart)
            s.followRect.set(s.claimStart)
            s.claimProgress = progress
            s.claimFingerX = fingerX.takeIf { it.isFinite() } ?: (s.width * .5f)
            s.claimFingerY = fingerY.takeIf { it.isFinite() } ?: (s.height * .5f)
            // Preserve the real grab point instead of assuming every gesture
            // starts at bottom-centre. Ratios are allowed slightly outside the
            // task bounds because the finger commonly sits below the system
            // card when Quickstep hands its leash to us.
            s.grabRatioX = ((s.claimFingerX - s.claimStart.left) /
                s.claimStart.width()).coerceIn(-.35f, 1.35f)
            s.grabRatioY = ((s.claimFingerY - s.claimStart.top) /
                s.claimStart.height()).coerceIn(-.25f, 1.50f)
            s.claimed = true
            // Once this stream belongs to YAMF, Overview must not be allowed to
            // peek through behind the live app leash—not even for the first
            // claimed frame.
            hideRecents(s)
        }

        val x = fingerX.takeIf { it.isFinite() } ?: (s.claimFingerX + dx)
        val y = fingerY.takeIf { it.isFinite() }
            ?: (s.claimFingerY - (progress - s.claimProgress) * s.height * .60f)
        // Direct manipulation: every MOVE places the leash from the current
        // pointer coordinates and the actual place where the user grabbed it.
        // Scaling the original grab offset with the shrinking window keeps an
        // off-centre swipe close to the finger instead of preserving a large,
        // fixed screen-space gap.
        val extraUp = (s.claimFingerY - y).coerceAtLeast(0f)
        val shrinkP = (extraUp / (s.height * .52f)).coerceIn(0f, 1f)
        val w = lerp(s.claimStart.width(), s.destination.width(), shrinkP)
        val h = lerp(s.claimStart.height(), s.destination.height(), shrinkP)

        // HyperOS-like edge response: movement stays fully direct until the
        // window reaches the top/right boundary. Continued pressure then
        // changes the grab pivot smoothly, making the finger travel from the
        // lower part of the window toward its upper-right rather than dragging
        // the entire surface off-screen.
        val rawLeft = x - s.grabRatioX * w
        val rawTop = y - s.grabRatioY * h
        val edgeRange = maxOf(48f * s.density, minOf(w, h) * .20f)
        val topContact = ((s.topInset - rawTop) / edgeRange).coerceIn(0f, 1f)
        val rightContact = ((rawLeft + w - s.width) / edgeRange).coerceIn(0f, 1f)
        val edgeGrabX = lerp(s.grabRatioX, .90f, smootherStep(rightContact))
        val edgeGrabY = lerp(s.grabRatioY, .10f, smootherStep(topContact))
        s.followRect.set(
            x - edgeGrabX * w,
            y - edgeGrabY * h,
            x + (1f - edgeGrabX) * w,
            y + (1f - edgeGrabY) * h
        )

        // Keep the complete surface inside the usable display while retaining
        // the edge-pivot response above.
        val minLeft = if (w <= s.width) 0f else s.width - w
        val minTop = if (h <= s.height - s.topInset) s.topInset.toFloat() else s.height - h
        if (s.followRect.left < minLeft) s.followRect.offset(minLeft - s.followRect.left, 0f)
        if (s.followRect.right > s.width) s.followRect.offset(s.width - s.followRect.right, 0f)
        if (s.followRect.top < minTop) s.followRect.offset(0f, minTop - s.followRect.top)
        if (s.followRect.bottom > s.height) s.followRect.offset(0f, s.height - s.followRect.bottom)

        // Magnetism is spatial, not an animation. It starts only inside the
        // narrow attraction band and blends from the finger-following rect,
        // avoiding the old hard switch to a fixed corner trajectory.
        val magneticInput = ((proximity.coerceIn(0f, 1f) - .35f) / .65f).coerceIn(0f, 1f)
        val magneticP = smootherStep(magneticInput)
        s.rect.set(
            lerp(s.followRect.left, s.destination.left, magneticP),
            lerp(s.followRect.top, s.destination.top, magneticP),
            lerp(s.followRect.right, s.destination.right, magneticP),
            lerp(s.followRect.bottom, s.destination.bottom, magneticP)
        )
        scheduleApply(s)
    }

    fun setCommit(commit: Boolean) { session?.commit = commit }

    fun abort() { session?.let(::restoreSystemGesture) }

    private fun scheduleApply(s: Session) {
        if (session !== s || s.framePending) return
        s.framePending = true
        Choreographer.getInstance().postFrameCallback {
            s.framePending = false
            if (session === s) apply(s)
        }
    }

    private fun lerp(from: Float, to: Float, progress: Float) = from + (to - from) * progress

    private fun smootherStep(value: Float): Float =
        value * value * value * (value * (value * 6f - 15f) + 10f)

    private fun isUsableSystemRect(s: Session, rect: RectF): Boolean {
        if (!rect.left.isFinite() || !rect.top.isFinite() ||
            !rect.right.isFinite() || !rect.bottom.isFinite() || rect.isEmpty) return false
        if (rect.width() > s.width * 1.25f || rect.height() > s.height * 1.25f) return false
        if (rect.centerX() !in -s.width * .10f..s.width * 1.10f ||
            rect.centerY() !in -s.height * .10f..s.height * 1.10f) return false
        // Reject the natural-rotation rectangle returned by affected landscape
        // launchers. Accept square-ish values because split/letterboxed apps can
        // legitimately differ from the display aspect ratio.
        val screenLandscape = s.width > s.height
        val rectStronglyLandscape = rect.width() > rect.height() * 1.15f
        val rectStronglyPortrait = rect.height() > rect.width() * 1.15f
        return if (screenLandscape) !rectStronglyPortrait else !rectStronglyLandscape
    }

    private fun apply(s: Session) {
        if (session !== s || !s.claimed) return
        runCatching {
            hideRecents(s)
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
        // On commit keep Recents hidden until Quickstep has actually returned
        // Launcher to NORMAL. Restoring it here caused a one-frame Overview
        // flash immediately before the YAMF window appeared.
        release(s, restoreRecents = !commit)
        runCatching {
            val done = Runnable {
                runCatching { handler?.let { XposedHelpers.callMethod(it, "reset") } }
                if (commit && handler != null) runCatching {
                    val container = XposedHelpers.getObjectField(handler, "mContainer")
                    val manager = XposedHelpers.callMethod(container, "getStateManager")
                    val state = XposedHelpers.findClass("com.android.launcher3.LauncherState", handler.javaClass.classLoader)
                    XposedHelpers.callMethod(manager, "goToState", XposedHelpers.getStaticObjectField(state, "NORMAL"), false)
                }
                if (commit) onCommit(s.taskId)
                if (commit) main.postDelayed({ s.recents?.alpha = s.recentsAlpha }, 140L)
            }
            if (controller != null) finishController(controller, commit, done)
            else done.run()
        }.onFailure {
            log(HookLauncher.TAG, "Unable to finish native transition", it)
            s.recents?.alpha = s.recentsAlpha
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

    private fun hideRecents(s: Session) {
        val recents = s.handler?.let {
            runCatching { XposedHelpers.getObjectField(it, "mRecentsView") as? View }.getOrNull()
        }
        if (s.recents == null && recents != null) {
            s.recents = recents
            s.recentsAlpha = recents.alpha
        }
        recents?.alpha = 0f
    }

    private fun release(s: Session, restoreRecents: Boolean = true) {
        if (session !== s) return
        session = null
        s.animator?.removeAllListeners()
        s.animator?.cancel()
        if (restoreRecents) s.recents?.alpha = s.recentsAlpha
        s.transaction.close()
    }
}
