package com.buildsession.betterYAMF.xposed.ui.window

import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.app.ActivityManager
import android.app.ActivityTaskManager
import android.app.ITaskStackListenerProxy
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Context.DISPLAY_SERVICE
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.IPackageManagerHidden
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.SurfaceTexture
import android.graphics.drawable.BitmapDrawable
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.os.Build
import android.os.SystemClock
import android.util.Log
import android.util.TypedValue
import android.view.Display
import android.view.GestureDetector
import android.view.Gravity
import android.view.IRotationWatcher
import android.view.InputDevice
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.Surface
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.TextureView
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.WindowManagerHidden
import android.view.animation.DecelerateInterpolator
import android.widget.ImageView
import android.widget.ImageButton
import androidx.core.graphics.ColorUtils
import androidx.core.view.isVisible
import androidx.core.view.updateLayoutParams
import androidx.dynamicanimation.animation.FlingAnimation
import androidx.dynamicanimation.animation.flingAnimationOf
import androidx.wear.widget.RoundedDrawable
import com.github.kyuubiran.ezxhelper.utils.argTypes
import com.github.kyuubiran.ezxhelper.utils.args
import com.github.kyuubiran.ezxhelper.utils.getObject
import com.github.kyuubiran.ezxhelper.utils.getObjectAs
import com.github.kyuubiran.ezxhelper.utils.invokeMethod
import com.google.android.material.color.MaterialColors
import com.buildsession.betterYAMF.common.getAttr
import com.buildsession.betterYAMF.common.model.StartCmd
import com.buildsession.betterYAMF.common.onException
import com.buildsession.betterYAMF.common.runMain
import com.buildsession.betterYAMF.databinding.LeftBackGestureOverlayBinding
import com.buildsession.betterYAMF.databinding.RightBackGestureOverlayBinding
import com.buildsession.betterYAMF.databinding.WindowAppBinding
import com.buildsession.betterYAMF.xposed.services.YAMFManager
import com.buildsession.betterYAMF.xposed.services.YAMFManager.config
import com.buildsession.betterYAMF.xposed.utils.AppInfoCache
import com.buildsession.betterYAMF.xposed.utils.Instances
import com.buildsession.betterYAMF.xposed.utils.TipUtil
import com.buildsession.betterYAMF.xposed.utils.animateAlpha
import com.buildsession.betterYAMF.xposed.utils.animateResize
import com.buildsession.betterYAMF.xposed.utils.animateScaleThenResize
import com.buildsession.betterYAMF.xposed.utils.dpToPx
import com.buildsession.betterYAMF.xposed.utils.getActivityInfoCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.lang.reflect.Method
import kotlin.math.floor
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sign
import kotlin.math.sqrt
import androidx.core.graphics.createBitmap
import androidx.core.graphics.drawable.toDrawable


@SuppressLint("ClickableViewAccessibility", "SetTextI18n")
class AppWindow(
    val context: Context,
    private val flags: Int,
    private val startCmd: StartCmd?,
    private val onVirtualDisplayCreated: (AppWindow, Int) -> Unit
) :
    TextureView.SurfaceTextureListener, SurfaceHolder.Callback {
    companion object {
        const val TAG = "reYAMF_AppWindow"
        const val ACTION_RESET_ALL_WINDOW = "com.buildsession.betterYAMF.ui.window.action.ACTION_RESET_ALL_WINDOW"
    }

    lateinit var binding: WindowAppBinding
    lateinit var bindingLeftBackGesture: LeftBackGestureOverlayBinding
    lateinit var bindingRightBackGesture: RightBackGestureOverlayBinding
    private lateinit var virtualDisplay: VirtualDisplay
    
    var currentTaskId = startCmd?.taskId ?: -1
    private var isDestroyed = false
    private val mainScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private val rotationWatcher = RotationWatcher()
    private val screenRotationWatcher = object : IRotationWatcher.Stub() {
        override fun onRotationChanged(rotation: Int) {
            runMain { keepInScreenAfterRotation(rotation) }
        }
    }
    private val surfaceOnTouchListener = SurfaceOnTouchListener()
    private val surfaceOnGenericMotionListener = SurfaceOnGenericMotionListener()
    var displayId = -1
    var rotateLock = false
    var isMini = false
    var isCollapsed = false
    private var halfWidth = 0
    private var halfHeight = 0
    lateinit var surfaceView: View
    private var newDpi = (context.resources.displayMetrics.densityDpi - config.reduceDPI)
        .coerceAtLeast(72)
    private var textureSurface: Surface? = null
    private var bootstrapSurfaceTexture: SurfaceTexture? = null
    private var bootstrapSurface: Surface? = null
    private var originalWidth: Int = 0
    private var originalHeight: Int = 0
    private var isResize: Boolean = true
    private var orientation = 0
    private var params = WindowManager.LayoutParams()
    private var paramsBg = WindowManager.LayoutParams()
    private var keepInScreenAnimator: ValueAnimator? = null
    private var xFlingAnimation: FlingAnimation? = null
    private var yFlingAnimation: FlingAnimation? = null
    private var lastSurfaceWidth = 0
    private var lastSurfaceHeight = 0
    private var lastSurfaceDpi = 0
    private var lastTaskSignature: String? = null
    private var inputForwardingErrorLogged = false
    private val homePackage: String? by lazy {
        context.packageManager.resolveActivity(
            Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME),
            PackageManager.MATCH_DEFAULT_ONLY
        )?.activityInfo?.packageName
    }
    private val setInputDisplayId: Method? by lazy {
        runCatching {
            Class.forName("android.view.InputEvent")
                .getDeclaredMethod("setDisplayId", Integer.TYPE)
                .apply { isAccessible = true }
        }.getOrNull()
    }
    private var lastClickTime = 0L
    private val DOUBLE_CLICK_TIME_DELTA: Long = 300
    private var isSuperShown = false
    private var cornerDropZoneView: CornerDropZoneView? = null
    private var currentHighlightedCorner = -1

    private val broadcastReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == ACTION_RESET_ALL_WINDOW) {
                val lp = binding.root.layoutParams as WindowManager.LayoutParams
                val dm = context.resources.displayMetrics
                val width = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 200F, context.resources.displayMetrics).toInt()
                val height = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 300F, context.resources.displayMetrics).toInt()
                
                lp.apply {
                    x = (dm.widthPixels - width) / 2
                    y = (dm.heightPixels - height) / 2
                }
                Instances.windowManager.updateViewLayout(binding.root, lp)
                
                binding.vSizePreviewer.updateLayoutParams {
                    this.width = width
                    this.height = height
                }
                surfaceView.updateLayoutParams {
                    this.width = width
                    this.height = height
                }
            }
        }
    }

    init {
        runCatching {
            binding = WindowAppBinding.inflate(LayoutInflater.from(context))
            bindingLeftBackGesture = LeftBackGestureOverlayBinding.inflate(LayoutInflater.from(context))
            bindingRightBackGesture = RightBackGestureOverlayBinding.inflate(LayoutInflater.from(context))
        }.onException { e ->
            Log.e(TAG, "Failed to create new window, did you reboot?", e)
            TipUtil.showToast("Failed to create new window, did you reboot?")
        }.onSuccess {
            doInit()
        }
    }

    private fun doInit() {
        when(config.surfaceView) {
            0 -> {
                surfaceView = binding.viewSurface
                binding.viewTexture.visibility = View.GONE
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                    binding.viewSurface.setSurfaceLifecycle(
                        SurfaceView.SURFACE_LIFECYCLE_FOLLOWS_ATTACHMENT
                    )
                }
            }
            1 -> {
                surfaceView = binding.viewTexture
                binding.viewSurface.visibility = View.GONE
            }
        }

        // SurfaceView owns a separate compositor layer; the CardView's radius alone
        // cannot clip it. Extend the outline above the content to round only the bottom.
        surfaceView.outlineProvider = object : android.view.ViewOutlineProvider() {
            override fun getOutline(view: View, outline: android.graphics.Outline) {
                val radius = config.windowRoundedCorner.dpToPx()
                outline.setRoundRect(0, -radius.toInt(), view.width, view.height, radius)
            }
        }
        surfaceView.clipToOutline = true
        surfaceView.addOnLayoutChangeListener { view, _, _, _, _, _, _, _, _ -> view.invalidateOutline() }
        (surfaceView as? SurfaceView)?.let { view ->
            // SurfaceView also punches a hole in its parent. Its native radius must
            // match the UI radius, otherwise that hole stays rectangular.
            runCatching {
                view.invokeMethod("setCornerRadius", args(config.windowRoundedCorner.dpToPx()), argTypes(Float::class.javaPrimitiveType!!))
            }.onFailure { Log.w(TAG, "Surface corner radius unavailable", it) }
        }

        // Show the correct icon immediately. Waiting for a task-stack callback left the
        // collapsed bubble blank or displaying a previous task's icon.
        startCmd?.componentName?.let { component ->
            updateAppIcon(component, startCmd.userId ?: 0)
        }

        params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                    WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED or
                    WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM,
            PixelFormat.TRANSLUCENT
        )

        val displayManager = context.getSystemService(DISPLAY_SERVICE) as DisplayManager
        val display = displayManager.getDisplay(Display.DEFAULT_DISPLAY)
        val rotation = display?.rotation ?: Surface.ROTATION_0
        
        val dm = getRealScreenMetrics()
        val screenWidth = dm.widthPixels
        val screenHeight = dm.heightPixels
        val windowWidth = config.defaultWindowWidth.dpToPx().toInt()
        val windowHeight = config.defaultWindowHeight.dpToPx().toInt()

        params.apply {
            gravity = Gravity.TOP or Gravity.START
            
            when (rotation) {
                Surface.ROTATION_0, Surface.ROTATION_180 -> {
                    orientation = 0
                    // Center horizontally, use config.portraitY if set, else center vertically with offset
                    x = (screenWidth - windowWidth) / 2
                    y = if (config.portraitY != 0) config.portraitY else (screenHeight - windowHeight) / 2 - 80.dpToPx().toInt()
                }
                Surface.ROTATION_90, Surface.ROTATION_270 -> {
                    orientation = 1
                    // Center horizontally, use config.landscapeY if set, else center vertically
                    x = (screenWidth - windowWidth) / 2
                    y = if (config.landscapeY != 0) config.landscapeY else (screenHeight - windowHeight) / 2
                }
                else -> {
                    x = 0
                    y = 0
                }
            }

            if (startCmd?.fromGesture == true) {
                val margin = 18.dpToPx().toInt()
                x = (screenWidth - windowWidth - margin).coerceAtLeast(0)
                y = 24.dpToPx().toInt().coerceAtMost((screenHeight - windowHeight).coerceAtLeast(0))
            }
        }

        // HyperOS keeps the white resize handle under the window in every
        // orientation.  The old landscape side handle was visually noisy and
        // could end up outside the rotated content bounds.
        updateBarControllerVisibility()


        paramsBg = WindowManager.LayoutParams(
            20.dpToPx().toInt(),
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT
        )
        paramsBg.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS

        bindingLeftBackGesture.root.let {
            paramsBg.gravity = Gravity.START or Gravity.TOP
            Instances.windowManager.addView(bindingLeftBackGesture.root, paramsBg)
        }

        bindingRightBackGesture.root.let {
            val paramsBgR = WindowManager.LayoutParams(
                20.dpToPx().toInt(),
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
                PixelFormat.TRANSLUCENT
            )
            paramsBgR.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
            paramsBgR.gravity = Gravity.END or Gravity.TOP
            Instances.windowManager.addView(bindingRightBackGesture.root, paramsBgR)
        }

        binding.root.let { layout ->
            Instances.windowManager.addView(layout, params)
        }
        runCatching {
            Instances.iWindowManager.watchRotation(screenRotationWatcher, Display.DEFAULT_DISPLAY)
        }.onFailure { error ->
            Log.w(TAG, "Unable to watch main display rotation", error)
        }

        binding.rootClickMask.setOnTouchListener { _, event ->
            moveGestureDetector.onTouchEvent(event)
            moveToTopIfNeed(event)

            finishMoveGesture(event)
            true
        }

        binding.cvBarClickMask.setOnClickListener {
            val clickTime = System.currentTimeMillis()
            if (clickTime - lastClickTime < DOUBLE_CLICK_TIME_DELTA) {
                isResize = false

                binding.cvappIcon.visibility = View.INVISIBLE
                animateAlpha(binding.rlBarControllerBottom, 1f, 0f) {
                    binding.rlBarControllerBottom.visibility = View.GONE
                }

                animateScaleThenResize(
                    binding.cvParent,
                    1F, 1F,
                    0F, 0F,
                    0.5F, 0.5F,
                    0, 0,
                    context
                ) {
                    onDestroy()
                }
            }
            lastClickTime = clickTime
        }

        binding.cvBarSideClickMask.setOnClickListener {
            val clickTime = System.currentTimeMillis()
            if (clickTime - lastClickTime < DOUBLE_CLICK_TIME_DELTA) {
                isResize = false

                binding.cvappIcon.visibility = View.INVISIBLE
                animateAlpha(binding.rlBarControllerBottom, 1f, 0f) {
                    binding.rlBarControllerBottom.visibility = View.GONE
                }

                animateScaleThenResize(
                    binding.cvParent,
                    1F, 1F,
                    0F, 0F,
                    0.5F, 0.5F,
                    0, 0,
                    context
                ) {
                    onDestroy()
                }
            }
            lastClickTime = clickTime
        }

        binding.ibSuper.setOnTouchListener { _, event ->
            moveGestureDetector.onTouchEvent(event)
            moveToTopIfNeed(event)

            finishMoveGesture(event)
            false
        }

        binding.ibSuper.setOnClickListener {
            isSuperShown = true
            animateAlpha(binding.clSuperLayout, 0f, 1f)
        }

        binding.cvBarClickMask.setOnTouchListener(object : View.OnTouchListener {
             private var startRawY = 0f
             private val threshold = 80.dpToPx()
             private var isSwiping = false

            override fun onTouch(v: View, event: MotionEvent): Boolean {
                moveToTopIfNeed(event)
                when (event.action) {
                    MotionEvent.ACTION_DOWN -> {
                        startRawY = event.rawY
                        isSwiping = true
                        return true
                    }
                    MotionEvent.ACTION_MOVE -> {
                        if (!isSwiping) return false
                        val diffY = event.rawY - startRawY
                        
                        if (diffY < 0) { // Swipe up - Shrink and fade
                            val progress = (-diffY / threshold).coerceIn(0f, 1f)
                            val scale = 1f - (progress * 0.3f) // Scale down to 70%
                            val alpha = 1f - progress
                            binding.cvParent.scaleX = scale
                            binding.cvParent.scaleY = scale
                            binding.cvParent.alpha = alpha
                            
                            binding.viewFullScreenMask.visibility = View.GONE
                            binding.tvFullScreenPrompt.visibility = View.GONE
                        } else { // Swipe down - Enlarge and show full screen mask
                            val progress = (diffY / threshold).coerceIn(0f, 1f)
                            val scale = 1f + (progress * 0.2f) // Scale up to 120%
                            binding.cvParent.scaleX = scale
                            binding.cvParent.scaleY = scale
                            binding.cvParent.alpha = 1f
                            
                            if (diffY > threshold) {
                                binding.viewFullScreenMask.visibility = View.VISIBLE
                                binding.tvFullScreenPrompt.visibility = View.VISIBLE
                            } else {
                                binding.viewFullScreenMask.visibility = View.GONE
                                binding.tvFullScreenPrompt.visibility = View.GONE
                            }
                        }
                        return true
                    }
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                        if (!isSwiping) return false
                        val diffY = event.rawY - startRawY
                        
                        if (diffY < -threshold) { // Swipe up past threshold - Close
                            animateScaleThenResize(
                                binding.cvParent,
                                binding.cvParent.scaleX, binding.cvParent.scaleY,
                                0f, 0f,
                                0.5f, 0.5f,
                                0, 0,
                                context
                            ) {
                                onDestroy()
                            }
                        } else if (diffY > threshold) { // Swipe down past threshold - Full Screen
                            getTopRootTask()?.runCatching {
                                Instances.activityTaskManager.moveRootTaskToDisplay(taskId, 0)
                                Instances.activityManager.moveTaskToFront(taskId, 0)
                            }?.onSuccess {
                                onDestroy()
                            }?.onFailure {
                                resetWindow()
                            }
                        } else {
                            resetWindow()
                        }
                        isSwiping = false
                        return true
                    }
                }
                return false
            }

            private fun resetWindow() {
                binding.cvParent.animate()
                    .scaleX(1f)
                    .scaleY(1f)
                    .alpha(1f)
                    .setDuration(200)
                    .start()
                binding.viewFullScreenMask.visibility = View.GONE
                binding.tvFullScreenPrompt.visibility = View.GONE
            }
        })

        binding.cvBarSideClickMask.setOnTouchListener(object : View.OnTouchListener {
             private var startRawY = 0f
             private val threshold = 80.dpToPx()
             private var isSwiping = false

            override fun onTouch(v: View, event: MotionEvent): Boolean {
                moveToTopIfNeed(event)
                when (event.action) {
                    MotionEvent.ACTION_DOWN -> {
                        startRawY = event.rawY
                        isSwiping = true
                        return true
                    }
                    MotionEvent.ACTION_MOVE -> {
                        if (!isSwiping) return false
                        val diffY = event.rawY - startRawY
                        
                        if (diffY < 0) { // Swipe up - Shrink and fade
                            val progress = (-diffY / threshold).coerceIn(0f, 1f)
                            val scale = 1f - (progress * 0.3f)
                            val alpha = 1f - progress
                            binding.cvParent.scaleX = scale
                            binding.cvParent.scaleY = scale
                            binding.cvParent.alpha = alpha
                            
                            binding.viewFullScreenMask.visibility = View.GONE
                            binding.tvFullScreenPrompt.visibility = View.GONE
                        } else { // Swipe down - Enlarge and show full screen mask
                            val progress = (diffY / threshold).coerceIn(0f, 1f)
                            val scale = 1f + (progress * 0.2f)
                            binding.cvParent.scaleX = scale
                            binding.cvParent.scaleY = scale
                            binding.cvParent.alpha = 1f
                            
                            if (diffY > threshold) {
                                binding.viewFullScreenMask.visibility = View.VISIBLE
                                binding.tvFullScreenPrompt.visibility = View.VISIBLE
                            } else {
                                binding.viewFullScreenMask.visibility = View.GONE
                                binding.tvFullScreenPrompt.visibility = View.GONE
                            }
                        }
                        return true
                    }
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                        if (!isSwiping) return false
                        val diffY = event.rawY - startRawY
                        
                        if (diffY < -threshold) { // Swipe up past threshold - Close
                            animateScaleThenResize(
                                binding.cvParent,
                                binding.cvParent.scaleX, binding.cvParent.scaleY,
                                0f, 0f,
                                0.5f, 0.5f,
                                0, 0,
                                context
                            ) {
                                onDestroy()
                            }
                        } else if (diffY > threshold) { // Swipe down past threshold - Full Screen
                            getTopRootTask()?.runCatching {
                                Instances.activityTaskManager.moveRootTaskToDisplay(taskId, 0)
                                Instances.activityManager.moveTaskToFront(taskId, 0)
                            }?.onSuccess {
                                onDestroy()
                            }?.onFailure {
                                resetWindow()
                            }
                        } else {
                            resetWindow()
                        }
                        isSwiping = false
                        return true
                    }
                }
                return false
            }

            private fun resetWindow() {
                binding.cvParent.animate()
                    .scaleX(1f)
                    .scaleY(1f)
                    .alpha(1f)
                    .setDuration(200)
                    .start()
                binding.viewFullScreenMask.visibility = View.GONE
                binding.tvFullScreenPrompt.visibility = View.GONE
            }
        })

        rightResize(binding.ibRightResize)

        surfaceView.setOnTouchListener(surfaceOnTouchListener)
        surfaceView.setOnGenericMotionListener(surfaceOnGenericMotionListener)

        binding.ibClose.setOnClickListener {
            isResize = false

            binding.cvappIcon.visibility = View.INVISIBLE
            animateAlpha(binding.rlBarControllerBottom, 1f, 0f) {
                binding.rlBarControllerBottom.visibility = View.GONE
            }

            animateScaleThenResize(
                binding.cvParent,
                1F, 1F,
                0F, 0F,
                0.5F, 0.5F,
                0, 0,
                context
            ) {
                onDestroy()
            }
        }

        binding.ibFullscreen.setOnClickListener {
            animateAlpha(binding.clSuperLayout, 1f, 0f)
            getTopRootTask()?.runCatching {
                Instances.activityTaskManager.moveRootTaskToDisplay(taskId, 0)
            }?.onFailure { t ->
                if (t is Error) throw t
                TipUtil.showToast("${t.message}")
            }?.onSuccess {
                binding.ibClose.callOnClick()
            }
        }

        binding.ibMinimize.setOnClickListener {
            isSuperShown = false
            binding.apply {
                animateAlpha(binding.clSuperLayout, 1f, 0f)
                binding.clSuperLayout.visibility = View.GONE
                changeMini()
            }
        }

        binding.ibCollapse.setOnClickListener {
            isSuperShown = false
            binding.apply {
                animateAlpha(binding.clSuperLayout, 1f, 0f)
                binding.clSuperLayout.visibility = View.GONE
                changeCollapsed()
            }
            true
        }

        binding.ibSuperClose.setOnClickListener {
            isSuperShown = false
            animateAlpha(binding.clSuperLayout, 1f, 0f)
        }

        if (config.windowMode == 0) { // Virtual Display
            val initialWidth = config.defaultWindowWidth.dpToPx().toInt()
            val initialHeight = config.defaultWindowHeight.dpToPx().toInt()
            val effectiveFlags = if (Build.VERSION.SDK_INT >= 37) {
                flags and (1 shl 9).inv() // VIRTUAL_DISPLAY_FLAG_SHOULD_SHOW_SYSTEM_DECORATIONS
            } else {
                flags
            }
            bootstrapSurfaceTexture = SurfaceTexture(false).apply {
                setDefaultBufferSize(initialWidth, initialHeight)
            }
            bootstrapSurface = Surface(bootstrapSurfaceTexture)
            virtualDisplay = Instances.displayManager.createVirtualDisplay(
                "yamf${System.currentTimeMillis()}",
                initialWidth,
                initialHeight,
                newDpi.coerceAtLeast(72),
                bootstrapSurface,
                effectiveFlags
            )
            lastSurfaceWidth = initialWidth
            lastSurfaceHeight = initialHeight
            lastSurfaceDpi = newDpi
            displayId = virtualDisplay.display.displayId
            binding.root.post(::attachRenderingSurfaceIfReady)
            (Instances.windowManager as WindowManagerHidden).setDisplayImePolicy(displayId, if (config.showImeInWindow) WindowManagerHidden.DISPLAY_IME_POLICY_LOCAL else WindowManagerHidden.DISPLAY_IME_POLICY_FALLBACK_DISPLAY)
            
            (surfaceView as? TextureView)?.surfaceTextureListener = this
            (surfaceView as? SurfaceView)?.holder?.addCallback(this)
            runCatching {
                Instances.iWindowManager.watchRotation(rotationWatcher, displayId)
            }.onFailure { error ->
                Log.w(TAG, "Unable to watch display $displayId rotation", error)
            }
        } else { // Smooth Freeform
            displayId = 0 // Main display
            surfaceView.visibility = View.GONE // Hide the surface view, task renders directly
            binding.cvParent.post(::updateSmoothBounds)
        }
        
        context.registerReceiver(broadcastReceiver, IntentFilter(ACTION_RESET_ALL_WINDOW), Context.RECEIVER_EXPORTED)
        val width = config.defaultWindowWidth.dpToPx().toInt()
        val height = config.defaultWindowHeight.dpToPx().toInt()
        surfaceView.updateLayoutParams {
            this.width = width
            this.height = height
        }
        binding.vSizePreviewer.updateLayoutParams {
            this.width = width
            this.height = height
        }
        onVirtualDisplayCreated(this, displayId)
        updateFocusedDisplay(YAMFManager.currentDisplayId)

        isResize = false
        binding.cvBackground.post {
            originalWidth = binding.cvBackground.width
            originalHeight = binding.cvBackground.height
            binding.cvBackground.visibility = View.VISIBLE

            binding.cvBackground.radius = config.windowRoundedCorner.dpToPx()
            binding.cvBackground.clipToOutline = true
            binding.cvParent.clipToOutline = true
            binding.cvappIcon.radius = config.windowRoundedCorner.dpToPx()


            binding.cvParent.radius = (config.windowRoundedCorner+2).dpToPx()
            originalWidth = binding.cvParent.width
            originalHeight = binding.cvParent.height
            binding.cvParent.visibility = View.VISIBLE

            if (startCmd?.fromGesture == true) {
                setBackgroundWrapContent()
                binding.cvBackground.scaleX = 1f
                binding.cvBackground.scaleY = 1f
                binding.root.alpha = 1f
                binding.cvParent.strokeWidth = 1.dpToPx().toInt()
                isResize = true
            } else {
                animateScaleThenResize(
                    binding.cvBackground,
                    0F, 0F,
                    1F, 1F,
                    0.5F, 0.5F,
                    originalWidth, originalHeight,
                    context
                ) {
                    setBackgroundWrapContent()

                    mainScope.launch {
                        delay(200)
                        if (isDestroyed) return@launch
                        binding.cvParent.strokeWidth = 1.dpToPx().toInt()
                    }

                    isResize = true
                }
            }
        }

    }

    /** Updates chrome immediately when focus changes; replaces a permanent 500 ms polling loop. */
    fun updateFocusedDisplay(focusedDisplayId: Int) {
        if (isDestroyed || !::binding.isInitialized) return
        val showGestures = config.windowMode == 0 && !isMini && !isCollapsed &&
            focusedDisplayId == displayId
        bindingLeftBackGesture.root.visibility = if (showGestures) View.VISIBLE else View.GONE
        bindingRightBackGesture.root.visibility = if (showGestures) View.VISIBLE else View.GONE

        updateBarControllerVisibility()
    }

    /** Keep the resize affordance below the content, including landscape. */
    private fun updateBarControllerVisibility() {
        if (!::binding.isInitialized) return
        val show = !isMini && !isCollapsed
        binding.rlBarControllerBottom.visibility = if (show) View.VISIBLE else View.GONE
        binding.rlBarControllerSide.visibility = View.GONE
    }

    fun onDestroy() {
        if (isDestroyed) return
        isDestroyed = true

        mainScope.cancel()
        keepInScreenAnimator?.cancel()
        xFlingAnimation?.cancel()
        yFlingAnimation?.cancel()
        surfaceView.animate().cancel()
        binding.cvParent.animate().cancel()

        runCatching { context.unregisterReceiver(broadcastReceiver) }
        runCatching { Instances.iWindowManager.removeRotationWatcher(rotationWatcher) }
        runCatching { Instances.iWindowManager.removeRotationWatcher(screenRotationWatcher) }
        
        YAMFManager.removeWindow(displayId)
        if (config.windowMode == 0) {
            textureSurface?.release()
            textureSurface = null
            releaseBootstrapSurface()
            runCatching { virtualDisplay.release() }
        } else {
            // In smooth mode, if the task is still alive, we should move it back to full screen or close it
            // For now, let's just stop tracking it
            YAMFManager.smoothFreeformTasks.remove(currentTaskId)
            YAMFManager.smoothFreeformBounds.remove(currentTaskId)
        }
        
        runMain {
            if (binding.root.isAttachedToWindow) {
                runCatching { Instances.windowManager.removeViewImmediate(binding.root) }
            }
            if (bindingLeftBackGesture.root.isAttachedToWindow) {
                runCatching { Instances.windowManager.removeViewImmediate(bindingLeftBackGesture.root) }
            }
            if (bindingRightBackGesture.root.isAttachedToWindow) {
                runCatching { Instances.windowManager.removeViewImmediate(bindingRightBackGesture.root) }
            }
            cornerDropZoneView?.let {
                if (it.isAttachedToWindow) {
                    runCatching { Instances.windowManager.removeViewImmediate(it) }
                }
                cornerDropZoneView = null
            }
        }
    }

    private fun getTopRootTask(): ActivityTaskManager.RootTaskInfo? {
        Instances.activityTaskManager.getAllRootTaskInfosOnDisplay(displayId).forEach { task ->
            if (task.visible)
                return task
        }
        return null
    }

    private fun moveToTop() {
        if (bindingLeftBackGesture.root.isAttachedToWindow) {
            Instances.windowManager.removeView(bindingLeftBackGesture.root)
        }
        Instances.windowManager.addView(bindingLeftBackGesture.root, bindingLeftBackGesture.root.layoutParams)
        
        if (bindingRightBackGesture.root.isAttachedToWindow) {
            Instances.windowManager.removeView(bindingRightBackGesture.root)
        }
        Instances.windowManager.addView(bindingRightBackGesture.root, bindingRightBackGesture.root.layoutParams)

        if (binding.root.isAttachedToWindow) {
            Instances.windowManager.removeView(binding.root)
        }
        Instances.windowManager.addView(binding.root, binding.root.layoutParams)
        YAMFManager.moveToTop(displayId)
    }

    private fun moveToTopIfNeed(event: MotionEvent) {
        if (event.action == MotionEvent.ACTION_UP && YAMFManager.isTop(displayId).not()) {
            moveToTop()
        }
        if (config.windowMode == 1) {
            updateSmoothBounds()
        }
    }

    private fun updateSmoothBounds() {
        if (config.windowMode != 1 || currentTaskId == -1) return
        
        val location = IntArray(2)
        binding.cvParent.getLocationOnScreen(location)
        val rect = android.graphics.Rect(
            location[0], 
            location[1], 
            location[0] + binding.cvParent.width, 
            location[1] + binding.cvParent.height
        )
        
        if (YAMFManager.smoothFreeformBounds[currentTaskId] != rect) {
            YAMFManager.updateSmoothBounds(currentTaskId, rect)
        }
    }

    private fun updateTask(taskInfo: ActivityManager.RunningTaskInfo) {
        currentTaskId = taskInfo.taskId
        val topActivity = taskInfo.topActivity ?: taskInfo.baseActivity ?: return
        val taskDescription = taskInfo.taskDescription
        val backgroundColor = taskDescription?.backgroundColor ?: Color.TRANSPARENT
        val signature = "${taskInfo.taskId}:$topActivity:$backgroundColor"
        if (signature == lastTaskSignature) return
        lastTaskSignature = signature

        val userId = runCatching { taskInfo.getObjectAs<Int>("userId") }.getOrDefault(0)
        updateAppIcon(topActivity, userId)

        val statusBarColor = backgroundColor
        val navigationBarColor = backgroundColor

        if (config.coloredController) {
            val onStateBar = if (MaterialColors.isColorLight(ColorUtils.compositeColors(statusBarColor, backgroundColor)) xor ((context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES)) {
                context.theme.getAttr(com.google.android.material.R.attr.colorOnPrimaryContainer).data
            } else {
                context.theme.getAttr(com.google.android.material.R.attr.colorOnPrimary).data
            }

            binding.ibClose.imageTintList = ColorStateList.valueOf(onStateBar)
            binding.background.setBackgroundColor(navigationBarColor)

            val onNavigationBar = if (MaterialColors.isColorLight(ColorUtils.compositeColors(navigationBarColor, backgroundColor)) xor ((context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES)) {
                context.theme.getAttr(com.google.android.material.R.attr.colorOnPrimaryContainer).data
            } else {
                context.theme.getAttr(com.google.android.material.R.attr.colorOnPrimary).data
            }

            binding.ibMinimize.imageTintList = ColorStateList.valueOf(onNavigationBar)
            binding.ibFullscreen.imageTintList = ColorStateList.valueOf(onNavigationBar)
            binding.ibRightResize.imageTintList = ColorStateList.valueOf(onNavigationBar)
        }
    }

    private fun updateAppIcon(componentName: android.content.ComponentName, userId: Int) {
        runCatching {
            val activityInfo = (Instances.iPackageManager as IPackageManagerHidden)
                .getActivityInfoCompat(componentName, 0, userId)
            binding.appIcon.setImageDrawable(AppInfoCache.getIcon(activityInfo))
            binding.appIcon.scaleType = ImageView.ScaleType.FIT_CENTER
        }.onFailure { error ->
            Log.w(TAG, "Unable to load icon for $componentName", error)
        }
    }

    fun onTaskMovedToFront(taskInfo: ActivityManager.RunningTaskInfo) {
        val taskDisplayId = runCatching { taskInfo.getObject("displayId") as Int }.getOrDefault(-1)
        if (taskDisplayId == displayId) {
            if (isHomeTask(taskInfo)) {
                onDestroy()
                return
            }

            updateTask(taskInfo)
        }
    }

    fun onTaskDescriptionChanged(taskInfo: ActivityManager.RunningTaskInfo) {
        val taskDisplayId = runCatching { taskInfo.getObject("displayId") as Int }.getOrDefault(-1)
        if (taskDisplayId == displayId) {
            if(!taskInfo.isVisible){
                return
            }

            if (isHomeTask(taskInfo)) {
                onDestroy()
                return
            }
            
            updateTask(taskInfo)
        }
    }

    inner class RotationWatcher : IRotationWatcher.Stub() {
        override fun onRotationChanged(rotation: Int) {
            runMain {
                if (rotateLock.not())
                    rotate(rotation)
            }
        }
    }

    fun rotate(rotation: Int) {
        val isLandscape = rotation == 1 || rotation == 3
        val newOrientation = if (isLandscape) 1 else 0
        
        if (orientation != newOrientation) {
            orientation = newOrientation
            
            // Swap surface view dimensions if transitioning between portrait and landscape
            val surfaceWidth = surfaceView.width
            val surfaceHeight = surfaceView.height
            binding.vSizePreviewer.updateLayoutParams {
                width = surfaceHeight
                height = surfaceWidth
            }
            surfaceView.updateLayoutParams {
                width = surfaceHeight
                height = surfaceWidth
            }

            // Keep the handle below the window after rotation as well.
            updateBarControllerVisibility()
            
            // Rotation callback can precede new display metrics and WRAP_CONTENT layout.
            // Re-clamp over several frames with bounds corrected for this rotation.
            keepInScreenAfterRotation(rotation)
        }
    }

    override fun onSurfaceTextureAvailable(surface: SurfaceTexture, width: Int, height: Int) {
        val targetWidth = if (!isMini && !isCollapsed) width else width * 2 + halfWidth
        val targetHeight = if (!isMini && !isCollapsed) height else height * 2 + halfHeight
        if (!isMini && !isCollapsed) {
            halfWidth = width % 2
            halfHeight = height % 2
        }
        resizeVirtualDisplay(targetWidth, targetHeight)
        surface.setDefaultBufferSize(targetWidth, targetHeight)
        textureSurface?.release()
        textureSurface = Surface(surface).also { virtualDisplay.surface = it }
        releaseBootstrapSurface()
    }

    override fun onSurfaceTextureSizeChanged(surface: SurfaceTexture, width: Int, height: Int) {
        if (!isResize) return
        val targetWidth = if (!isMini) width else width * 2 + halfWidth
        val targetHeight = if (!isMini) height else height * 2 + halfHeight
        if (!isMini) {
            halfWidth = width % 2
            halfHeight = height % 2
        }
        resizeVirtualDisplay(targetWidth, targetHeight)
        surface.setDefaultBufferSize(targetWidth, targetHeight)
    }

    override fun onSurfaceTextureDestroyed(surface: SurfaceTexture): Boolean {
        if (::virtualDisplay.isInitialized) virtualDisplay.surface = null
        textureSurface?.release()
        textureSurface = null
        return true
    }

    override fun onSurfaceTextureUpdated(surface: SurfaceTexture) {

    }

    private fun resizeVirtualDisplay(width: Int, height: Int) {
        if (isDestroyed || !::virtualDisplay.isInitialized || width <= 0 || height <= 0) return
        if (lastSurfaceWidth == width && lastSurfaceHeight == height && lastSurfaceDpi == newDpi) return
        virtualDisplay.resize(width, height, newDpi)
        lastSurfaceWidth = width
        lastSurfaceHeight = height
        lastSurfaceDpi = newDpi
    }

    private fun getRealScreenMetrics(): android.util.DisplayMetrics {
        return android.util.DisplayMetrics().also { metrics ->
            context.display?.getRealMetrics(metrics)
            if (metrics.widthPixels <= 0 || metrics.heightPixels <= 0) {
                metrics.setTo(context.resources.displayMetrics)
            }
        }
    }

    private fun finishMoveGesture(event: MotionEvent) {
        if (event.action != MotionEvent.ACTION_UP && event.action != MotionEvent.ACTION_CANCEL) return

        xFlingAnimation?.cancel()
        yFlingAnimation?.cancel()
        val dropTarget = currentHighlightedCorner
        currentHighlightedCorner = -1
        hideCornerDropZone()

        if (event.action == MotionEvent.ACTION_CANCEL) {
            keepInScreen()
            return
        }
        when (dropTarget) {
            in 0..3 -> changeMini(dropTarget)
            4, 5 -> changeCollapsed()
            else -> keepInScreen()
        }
    }

    private fun getScreenSize(expectedRotation: Int? = null): Pair<Int, Int> {
        val metrics = getRealScreenMetrics()
        var width = metrics.widthPixels
        var height = metrics.heightPixels
        if (expectedRotation != null) {
            val expectedLandscape = expectedRotation == Surface.ROTATION_90 ||
                    expectedRotation == Surface.ROTATION_270
            if (expectedLandscape != (width > height)) {
                val oldWidth = width
                width = height
                height = oldWidth
            }
        }
        return width to height
    }

    private fun keepInScreenAfterRotation(rotation: Int, pass: Int = 0) {
        binding.root.postOnAnimation {
            keepInScreen(animate = false, expectedRotation = rotation)
            if (pass < 2) {
                binding.root.postDelayed({ keepInScreenAfterRotation(rotation, pass + 1) }, 32L)
            }
        }
    }

    private fun keepInScreen(animate: Boolean = true, expectedRotation: Int? = null) {
        binding.root.post {
            val params = binding.root.layoutParams as WindowManager.LayoutParams
            val (screenWidth, screenHeight) = getScreenSize(expectedRotation)

            val windowWidth = binding.root.width
            val windowHeight = binding.root.height

            val minX = 0
            val minY = 0
            val maxX = (screenWidth - windowWidth).coerceAtLeast(0)
            val maxY = (screenHeight - windowHeight).coerceAtLeast(0)

            val targetX = params.x.coerceIn(minX, maxX)
            val targetY = params.y.coerceIn(minY, maxY)

            if (targetX == params.x && targetY == params.y) return@post

            keepInScreenAnimator?.cancel()

            if (animate) {
                val startX = params.x
                val startY = params.y
                keepInScreenAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
                    duration = 300
                    interpolator = DecelerateInterpolator()
                    addUpdateListener { animation ->
                        val fraction = animation.animatedValue as Float
                        params.x = (startX + (targetX - startX) * fraction).toInt()
                        params.y = (startY + (targetY - startY) * fraction).toInt()
                        runCatching {
                            Instances.windowManager.updateViewLayout(binding.root, params)
                        }
                    }
                    start()
                }
            } else {
                params.x = targetX
                params.y = targetY
                runCatching {
                    Instances.windowManager.updateViewLayout(binding.root, params)
                }
            }
        }
    }

    private fun animateResizeCentered(
        view: View,
        startWidth: Int,
        endWidth: Int,
        startHeight: Int,
        endHeight: Int,
        onEnd: (() -> Unit)? = null
    ) {
        val params = binding.root.layoutParams as WindowManager.LayoutParams
        val initialWindowWidth = binding.root.width
        val initialWindowHeight = binding.root.height
        val initialViewWidth = if (view.width > 0) view.width else startWidth
        val initialViewHeight = if (view.height > 0) view.height else startHeight
        
        val centerX = params.x + initialWindowWidth / 2
        val centerY = params.y + initialWindowHeight / 2

        animateResize(
            view, startWidth, endWidth, startHeight, endHeight, context,
            onUpdate = { currentViewWidth, currentViewHeight ->
                val currentParams = binding.root.layoutParams as WindowManager.LayoutParams
                val displayMetrics = getRealScreenMetrics()
                val screenWidth = displayMetrics.widthPixels
                val screenHeight = displayMetrics.heightPixels
                
                val currentWindowWidth = initialWindowWidth + (currentViewWidth - initialViewWidth)
                val currentWindowHeight = initialWindowHeight + (currentViewHeight - initialViewHeight)
                
                // Keep window within screen boundaries during resize
                val targetX = centerX - currentWindowWidth / 2
                val targetY = centerY - currentWindowHeight / 2
                
                currentParams.x = targetX.coerceIn(0, (screenWidth - currentWindowWidth).coerceAtLeast(0))
                currentParams.y = targetY.coerceIn(0, (screenHeight - currentWindowHeight).coerceAtLeast(0))
                
                runCatching {
                    Instances.windowManager.updateViewLayout(binding.root, currentParams)
                }
            },
            onEnd = onEnd
        )
    }

    // minimizes the floating window a bar-less only-content floating window
    private fun changeMini(targetCorner: Int? = null) {
        xFlingAnimation?.cancel()
        yFlingAnimation?.cancel()
        cancelVirtualDisplayTouch()
        isCollapsed = false
        isResize = false

        if (isMini) {
            isMini = false
            isResize = true
            if (surfaceView is SurfaceView) {
                binding.cvBackground.updateLayoutParams {
                    width = originalWidth
                    height = originalHeight
                }
                setBackgroundWrapContent()
                setParrentWrapContent()
                keepInScreen()
                updateBarControllerVisibility()
            } else {
                binding.cvBackground.updateLayoutParams {
                    width = originalWidth
                    height = originalHeight
                }
                animateScaleThenResize(
                    binding.cvBackground,
                    0.5F, 0.5F,
                    1F, 1F,
                    0.5F, 0.5F,
                    originalWidth, originalHeight,
                    context
                ){
                    setBackgroundWrapContent()
                    setParrentWrapContent()
                    bindingLeftBackGesture.root.visibility = View.VISIBLE
                    bindingRightBackGesture.root.visibility = View.VISIBLE
                    keepInScreen()
                }
            }

            binding.ibRightResize.visibility = View.VISIBLE
            updateBarControllerVisibility()
            restoreSurfaceInteraction()

            return
        }
        else if (!isMini) {
            binding.rootClickMask.visibility = View.VISIBLE
            binding.rlBarControllerBottom.visibility = View.GONE
            binding.rlBarControllerSide.visibility = View.GONE
            isMini = true

            if (config.surfaceView == 1) {
                binding.cvBackground.updateLayoutParams {
                    width = originalWidth/2
                    height = originalHeight/2
                }
                if (targetCorner != null) snapMiniToCorner(targetCorner) else keepInScreen()
            } else {
                animateResizeCentered(
                    binding.cvBackground,
                    originalWidth, originalWidth/2,
                    originalHeight, originalHeight/2
                ){
                    isResize = true
                    bindingLeftBackGesture.root.visibility = View.GONE
                    bindingRightBackGesture.root.visibility = View.GONE
                    if (targetCorner != null) snapMiniToCorner(targetCorner) else keepInScreen()
                }
            }

            binding.ibRightResize.visibility = View.GONE
            surfaceView.setOnTouchListener(null)
            surfaceView.setOnGenericMotionListener(null)

            return
        }
    }

    private fun setBackgroundWrapContent() {
        val layoutParams = binding.cvBackground.layoutParams
        layoutParams.width = ViewGroup.LayoutParams.WRAP_CONTENT
        layoutParams.height = ViewGroup.LayoutParams.WRAP_CONTENT
        binding.cvBackground.layoutParams = layoutParams
    }

    private fun setParrentWrapContent() {
        val layoutParams = binding.cvParent.layoutParams
        layoutParams.width = ViewGroup.LayoutParams.WRAP_CONTENT
        layoutParams.height = ViewGroup.LayoutParams.WRAP_CONTENT
        binding.cvParent.layoutParams = layoutParams
    }

    private fun changeCollapsed() {
        cancelVirtualDisplayTouch()
        isResize = false
        if (isCollapsed) {
            expandWindow()
            bindingLeftBackGesture.root.visibility = View.VISIBLE
            bindingRightBackGesture.root.visibility = View.VISIBLE
        } else {
            binding.rootClickMask.visibility = View.VISIBLE
            binding.rlBarControllerBottom.visibility = View.GONE
            binding.rlBarControllerSide.visibility = View.GONE
            collapseWindow()
            bindingLeftBackGesture.root.visibility = View.GONE
            bindingRightBackGesture.root.visibility = View.GONE
        }
    }

    private fun expandWindow() {
        isCollapsed = false
        binding.background.visibility = View.VISIBLE
        binding.cvParent.setContentPadding(0, 0, 0, 0)

        animateResizeCentered(
            binding.appIcon, 40.dpToPx().toInt(), 0, 40.dpToPx().toInt(), 0) {
            binding.cvappIcon.visibility = View.GONE
            animateResizeCentered(binding.cvBackground, 0, originalWidth, 0, originalHeight) {
                setBackgroundWrapContent()
                setParrentWrapContent()
                updateBarControllerVisibility()

                binding.cvappIcon.visibility = View.GONE
                isResize = true
                keepInScreen()
                restoreSurfaceInteraction()
            }
        }
    }

    private fun collapseWindow() {
        isCollapsed = true
        binding.cvParent.setContentPadding(0, 0, 0, 0)

        animateResizeCentered(binding.cvBackground, binding.cvBackground.width, 0, binding.cvBackground.height, 0) {
            binding.cvappIcon.visibility = View.VISIBLE
            binding.cvappIcon.radius = 24.dpToPx()
            binding.background.visibility = View.GONE
            animateResizeCentered(binding.appIcon, 0, 40.dpToPx().toInt(), 0, 40.dpToPx().toInt()) {
                keepInScreen()
            }

            isResize = true
        }
    }

    private fun snapMiniToCorner(corner: Int) {
        binding.root.post {
            if (isDestroyed || !isMini) return@post
            val metrics = getRealScreenMetrics()
            val maxX = (metrics.widthPixels - binding.root.width).coerceAtLeast(0)
            val maxY = (metrics.heightPixels - binding.root.height).coerceAtLeast(0)
            val margin = 12.dpToPx().toInt()
            val params = binding.root.layoutParams as WindowManager.LayoutParams
            params.x = if (corner == 1 || corner == 3) {
                (maxX - margin).coerceAtLeast(0)
            } else {
                margin.coerceAtMost(maxX)
            }
            params.y = if (corner == 2 || corner == 3) {
                (maxY - margin).coerceAtLeast(0)
            } else {
                margin.coerceAtMost(maxY)
            }
            runCatching { Instances.windowManager.updateViewLayout(binding.root, params) }
        }
    }

    private fun isHomeTask(taskInfo: ActivityManager.RunningTaskInfo): Boolean {
        val activityType = runCatching {
            val configuration = taskInfo.getObject("configuration")
            val windowConfig = configuration.getObject("windowConfiguration")
            windowConfig.invokeMethod("getActivityType") as Int
        }.getOrDefault(0)
        if (activityType == 2) return true // ACTIVITY_TYPE_HOME

        val pkg = taskInfo.topActivity?.packageName ?: return false
        return pkg == homePackage || pkg == "com.android.launcher3" ||
            pkg == "com.google.android.apps.nexuslauncher" ||
            (pkg.contains("launcher", ignoreCase = true) &&
                !pkg.contains("service", ignoreCase = true))
    }

    private fun calculateScreenInches(width: Int, height: Int): Float {
        val x = (width / context.resources.displayMetrics.xdpi).pow(2)
        val y = (height / context.resources.displayMetrics.ydpi).pow(2)

        return sqrt(x + y)
    }

    private fun calculateDpi(width: Int, height: Int, screenSizeInInches: Float): Int {
        val widthSqr = width.toFloat().pow(2)
        val heightSqr = height.toFloat().pow(2)
        val diagonalPixels = sqrt(widthSqr + heightSqr)

        return floor(diagonalPixels / screenSizeInInches).toInt()
    }

    private fun rightResize(ibResize: ImageButton) {
        ibResize.setOnTouchListener(object : View.OnTouchListener {
            var beginX = 0F
            var beginY = 0F
            var beginWidth = 0
            var beginHeight = 0

            var offsetX = 0F
            var offsetY = 0F

            override fun onTouch(v: View, event: MotionEvent): Boolean {
                when(event.action) {
                    MotionEvent.ACTION_DOWN -> {
                        beginX = event.rawX
                        beginY = event.rawY
                        binding.vSizePreviewer.layoutParams.let {
                            beginWidth = it.width
                            beginHeight = it.height
                        }
                        binding.vSizePreviewer.visibility = View.VISIBLE
                        binding.cvParent.strokeWidth = 0
                    }
                    MotionEvent.ACTION_MOVE -> {
                        offsetX = event.rawX - beginX
                        offsetY = event.rawY - beginY
                        val metrics = context.resources.displayMetrics
                        val minWidth = 180.dpToPx().toInt()
                        val minHeight = 220.dpToPx().toInt()
                        val maxWidth = (metrics.widthPixels - params.x.coerceAtLeast(0))
                            .coerceAtLeast(minWidth)
                        val maxHeight = (metrics.heightPixels - params.y.coerceAtLeast(0))
                            .coerceAtLeast(minHeight)
                        binding.vSizePreviewer.updateLayoutParams {
                            width = (beginWidth + offsetX.toInt()).coerceIn(minWidth, maxWidth)
                            height = (beginHeight + offsetY.toInt()).coerceIn(minHeight, maxHeight)
                        }
                    }
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                        binding.vSizePreviewer.post {
                            surfaceView.updateLayoutParams {
                                width = binding.vSizePreviewer.width
                                height = binding.vSizePreviewer.height
                            }
                        }

                        binding.vSizePreviewer.visibility = View.GONE
                        moveToTopIfNeed(event)
                        binding.cvParent.strokeWidth = 2.dpToPx().toInt()
                        keepInScreen()
                    }
                }
                return true
            }
        })
    }

    fun forwardMotionEvent(event: MotionEvent) {
        if (!isSuperShown) {
            val newEvent = MotionEvent.obtain(event)
            try {
                val method = setInputDisplayId
                val setDirectly = method != null && runCatching {
                    method.invoke(newEvent, displayId)
                }.isSuccess
                if (!setDirectly) {
                    newEvent.invokeMethod("setDisplayId", args(displayId), argTypes(Integer.TYPE))
                }
                Instances.inputManager.injectInputEvent(newEvent, 0)
            } catch (error: Throwable) {
                if (!inputForwardingErrorLogged) {
                    inputForwardingErrorLogged = true
                    Log.e(TAG, "Unable to forward input to display $displayId", error)
                }
            } finally {
                newEvent.recycle()
            }
        }
    }

    private fun cancelVirtualDisplayTouch() {
        if (config.windowMode != 0 || displayId < 0) return
        val now = SystemClock.uptimeMillis()
        val cancel = MotionEvent.obtain(now, now, MotionEvent.ACTION_CANCEL, 0f, 0f, 0).apply {
            source = InputDevice.SOURCE_TOUCHSCREEN
        }
        try {
            val method = setInputDisplayId
            val setDirectly = method != null && runCatching {
                method.invoke(cancel, displayId)
            }.isSuccess
            if (!setDirectly) {
                cancel.invokeMethod("setDisplayId", args(displayId), argTypes(Integer.TYPE))
            }
            Instances.inputManager.injectInputEvent(cancel, 0)
        } catch (error: Throwable) {
            Log.e(TAG, "Unable to cancel display input $displayId", error)
        } finally {
            cancel.recycle()
        }
    }

    private fun restoreSurfaceInteraction() {
        isSuperShown = false
        binding.clSuperLayout.animate().cancel()
        binding.clSuperLayout.visibility = View.GONE
        binding.rootClickMask.visibility = View.GONE
        surfaceView.visibility = View.VISIBLE
        surfaceView.isEnabled = true
        surfaceView.isClickable = true
        surfaceView.setOnTouchListener(surfaceOnTouchListener)
        surfaceView.setOnGenericMotionListener(surfaceOnGenericMotionListener)
        (surfaceView as? SurfaceView)?.holder?.surface?.takeIf { it.isValid }?.let { surface ->
            if (!isDestroyed && ::virtualDisplay.isInitialized) virtualDisplay.surface = surface
        }
        cancelVirtualDisplayTouch()
        YAMFManager.moveToTop(displayId)
        updateFocusedDisplay(displayId)
    }

    inner class SurfaceOnTouchListener : View.OnTouchListener {
        override fun onTouch(v: View, event: MotionEvent): Boolean {
            bindingLeftBackGesture.root.visibility = View.VISIBLE
            bindingRightBackGesture.root.visibility = View.VISIBLE
            forwardMotionEvent(event)
            moveToTopIfNeed(event)
            return true
        }
    }

    inner class SurfaceOnGenericMotionListener : View.OnGenericMotionListener {
        override fun onGenericMotion(v: View, event: MotionEvent): Boolean {
            bindingLeftBackGesture.root.visibility = View.VISIBLE
            bindingRightBackGesture.root.visibility = View.VISIBLE
            forwardMotionEvent(event)
            return true
        }
    }

    override fun surfaceCreated(holder: SurfaceHolder) {
        if (!isDestroyed && ::virtualDisplay.isInitialized) {
            virtualDisplay.surface = holder.surface
            releaseBootstrapSurface()
        }
    }

    private fun releaseBootstrapSurface() {
        bootstrapSurface?.release()
        bootstrapSurface = null
        bootstrapSurfaceTexture?.release()
        bootstrapSurfaceTexture = null
    }

    private fun attachRenderingSurfaceIfReady() {
        if (isDestroyed || !::virtualDisplay.isInitialized) return
        when (val view = surfaceView) {
            is SurfaceView -> {
                if (view.holder.surface.isValid) {
                    virtualDisplay.surface = view.holder.surface
                    releaseBootstrapSurface()
                } else {
                    binding.root.postDelayed(::attachRenderingSurfaceIfReady, 16L)
                }
            }
            is TextureView -> {
                view.surfaceTexture?.let { surfaceTexture ->
                    textureSurface?.release()
                    textureSurface = Surface(surfaceTexture).also { virtualDisplay.surface = it }
                    releaseBootstrapSurface()
                }
            }
        }
    }

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
        resizeVirtualDisplay(width, height)
    }

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        if (!isDestroyed && ::virtualDisplay.isInitialized) {
            virtualDisplay.surface = null
        }
    }

    private val moveGestureDetector = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        var startX = 0
        var startY = 0
        var lastX = 0F
        var lastY = 0F
        var last2X = 0F
        var last2Y = 0F

        override fun onDown(e: MotionEvent): Boolean {
            xFlingAnimation?.cancel()
            yFlingAnimation?.cancel()
            keepInScreenAnimator?.cancel()
            val params = binding.root.layoutParams as WindowManager.LayoutParams
            startX = params.x
            startY = params.y
            currentHighlightedCorner = -1
            return true
        }


        override fun onScroll(
            e1: MotionEvent?,
            e2: MotionEvent,
            distanceX: Float,
            distanceY: Float
        ): Boolean {
            e1 ?: return false
            val params = binding.root.layoutParams as WindowManager.LayoutParams
            params.x = (startX + (e2.rawX - e1.rawX)).toInt()
            params.y = (startY + (e2.rawY - e1.rawY)).toInt()
            Instances.windowManager.updateViewLayout(binding.root, params)
            if (config.windowMode == 1) updateSmoothBounds()
            last2X = lastX
            last2Y = lastY
            lastX = e2.rawX
            lastY = e2.rawY

            showCornerDropZone()
            currentHighlightedCorner = cornerDropZoneView?.updateHighlight(e2.rawX, e2.rawY) ?: -1
            return true
        }

        override fun onFling(
            e1: MotionEvent?,
            e2: MotionEvent,
            velocityX: Float,
            velocityY: Float
        ): Boolean {
            e1 ?: return false
            if (e1.source == InputDevice.SOURCE_MOUSE) return false
            if (currentHighlightedCorner != -1) return false
            val params = binding.root.layoutParams as WindowManager.LayoutParams
            val displayMetrics = getRealScreenMetrics()
            val screenWidth = displayMetrics.widthPixels
            val screenHeight = displayMetrics.heightPixels
            val windowWidth = binding.root.width
            val windowHeight = binding.root.height

            val minX = 0f
            val maxX = (screenWidth - windowWidth).toFloat()
            val minY = 0f
            val maxY = (screenHeight - windowHeight).toFloat()

            runCatching {
                if (sign(velocityX) != sign(e2.rawX - last2X)) return@runCatching
                xFlingAnimation = flingAnimationOf({
                    params.x = it.toInt()
                    runCatching { 
                        Instances.windowManager.updateViewLayout(binding.root, params)
                        if (config.windowMode == 1) updateSmoothBounds()
                    }
                }, {
                    params.x.toFloat()
                })
                    .setStartVelocity(velocityX)
                    // If already outside, don't set boundaries here to avoid instant snap.
                    // keepInScreen() will handle it after the fling or on release.
                    .apply {
                        if (params.x >= minX && params.x <= maxX) {
                            setMinValue(minX)
                            setMaxValue(maxX)
                        }
                    }
                xFlingAnimation?.addEndListener { _, _, _, _ ->
                    keepInScreen()
                }
                xFlingAnimation?.start()
            }
            runCatching {
                if (sign(velocityY) != sign(e2.rawY - last2Y)) return@runCatching
                yFlingAnimation = flingAnimationOf({
                    params.y = it.toInt()
                    runCatching { 
                        Instances.windowManager.updateViewLayout(binding.root, params)
                        if (config.windowMode == 1) updateSmoothBounds()
                    }
                }, {
                    params.y.toFloat()
                })
                    .setStartVelocity(velocityY)
                    .apply {
                        if (params.y >= minY && params.y <= maxY) {
                            setMinValue(minY)
                            setMaxValue(maxY)
                        }
                    }
                yFlingAnimation?.addEndListener { _, _, _, _ ->
                    keepInScreen()
                }
                yFlingAnimation?.start()
            }
            return true
        }

        override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
            if (isCollapsed) {
                isMini = false // Always restore to Normal window from Collapsed state
                changeCollapsed()
            } else if (isMini) {
                changeMini()
            }
            return true
        }
    })

    private fun showCornerDropZone() {
        if (cornerDropZoneView == null) {
            cornerDropZoneView = CornerDropZoneView(context)
            val lp = WindowManager.LayoutParams().apply {
                type = WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                format = PixelFormat.TRANSLUCENT
                flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                        WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                        WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                        WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
                width = WindowManager.LayoutParams.MATCH_PARENT
                height = WindowManager.LayoutParams.MATCH_PARENT
            }
            Instances.windowManager.addView(cornerDropZoneView, lp)
        }
        cornerDropZoneView?.visibility = View.VISIBLE
    }

    private fun hideCornerDropZone() {
        cornerDropZoneView?.visibility = View.GONE
    }

    private inner class CornerDropZoneView(context: Context) : View(context) {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val dashPaint = Paint(Paint.ANTI_ALIAS_FLAG)

        private val radius: Float
            get() {
                val dm = getRealScreenMetrics()
                return if (dm.widthPixels > dm.heightPixels) {
                    dm.heightPixels * 0.20f
                } else {
                    dm.widthPixels * 0.20f
                }
            }
        private val proximityRadius: Float
            get() = radius * 1.75f
        
        private var currentCorner = -1 // -1: none, 0: TL, 1: TR, 2: BL, 3: BR
        private var isInsideActiveZone = false

        init {
            paint.style = Paint.Style.FILL
            dashPaint.style = Paint.Style.STROKE
            dashPaint.strokeWidth = 2f.dpToPx()
            dashPaint.pathEffect = DashPathEffect(floatArrayOf(10f.dpToPx(), 10f.dpToPx()), 0f)
        }

        fun updateHighlight(rawX: Float, rawY: Float): Int {
            if (isCollapsed) return -1 // Collapsed mode: no edge interaction

            val dm = getRealScreenMetrics()
            val sw = dm.widthPixels.toFloat()
            val sh = dm.heightPixels.toFloat()
            
            val x = rawX
            val y = rawY
            
            if (sw == 0f || sh == 0f) return -1

            // Calculate distance to each corner using orientation-corrected coordinates
            val distTL = sqrt(x.pow(2) + y.pow(2))
            val distTR = sqrt((sw - x).pow(2) + y.pow(2))
            val distBL = sqrt(x.pow(2) + (sh - y).pow(2))
            val distBR = sqrt((sw - x).pow(2) + (sh - y).pow(2))

            val minDist = min(distTL, min(distTR, min(distBL, distBR)))
            
            val corner = if (!isMini) { // Only Normal mode allows corner interaction
                when {
                    distTL == minDist && distTL < proximityRadius -> 0
                    distTR == minDist && distTR < proximityRadius -> 1
                    distBL == minDist && distBL < proximityRadius -> 2
                    distBR == minDist && distBR < proximityRadius -> 3
                    else -> -1
                }
            } else -1

            if (corner != -1) {
                currentCorner = corner
                isInsideActiveZone = minDist < radius
            } else {
                // Check sides: 4 for Left, 5 for Right. Both Normal and Mini modes allow side interaction
                currentCorner = when {
                    x < proximityRadius -> 4
                    x > sw - proximityRadius -> 5
                    else -> -1
                }
                // Active zone: 5% width, 80% height centered
                isInsideActiveZone = when (currentCorner) {
                    4 -> x < sw * 0.05f && y > sh * 0.1f && y < sh * 0.9f
                    5 -> x > sw * 0.95f && y > sh * 0.1f && y < sh * 0.9f
                    else -> false
                }
            }

            invalidate()
            return if (isInsideActiveZone) currentCorner else -1
        }

        override fun onDraw(canvas: Canvas) {
            val dm = getRealScreenMetrics()
            val w = dm.widthPixels.toFloat()
            val h = dm.heightPixels.toFloat()
            if (w == 0f || h == 0f || currentCorner == -1) return

            when (currentCorner) {
                0 -> drawCorner(canvas, 0f, 0f, 0f, 90f) // TL
                1 -> drawCorner(canvas, w, 0f, 90f, 90f) // TR
                2 -> drawCorner(canvas, 0f, h, 270f, 90f) // BL
                3 -> drawCorner(canvas, w, h, 180f, 90f) // BR
                4 -> drawSide(canvas, 0f, h * 0.1f, w * 0.05f, h * 0.9f) // Left
                5 -> drawSide(canvas, w * 0.95f, h * 0.1f, w, h * 0.9f) // Right
            }
        }

        private fun drawCorner(canvas: Canvas, cx: Float, cy: Float, startAngle: Float, sweepAngle: Float) {
            val color = if (isInsideActiveZone) Color.parseColor("#CC61D4FF") else Color.parseColor("#40FFFFFF")
            val strokeColor = if (isInsideActiveZone) Color.parseColor("#FF61D4FF") else Color.WHITE

            paint.color = color
            dashPaint.color = strokeColor

            val rectF = RectF(cx - radius, cy - radius, cx + radius, cy + radius)
            canvas.drawArc(rectF, startAngle, sweepAngle, true, paint)
            canvas.drawArc(rectF, startAngle, sweepAngle, true, dashPaint)
        }

        private fun drawSide(canvas: Canvas, left: Float, top: Float, right: Float, bottom: Float) {
            val color = if (isInsideActiveZone) Color.parseColor("#CC61D4FF") else Color.parseColor("#40FFFFFF")
            val strokeColor = if (isInsideActiveZone) Color.parseColor("#FF61D4FF") else Color.WHITE

            paint.color = color
            dashPaint.color = strokeColor

            val rectF = RectF(left, top, right, bottom)
            val rx = 10f.dpToPx()
            canvas.drawRoundRect(rectF, rx, rx, paint)
            canvas.drawRoundRect(rectF, rx, rx, dashPaint)
        }
    }
}

