package com.buildsession.betterYAMF.manager.ui.setting

import android.content.Intent
import android.content.SharedPreferences
import android.os.Build
import android.os.Bundle
import android.util.Log
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.PopupMenu
import androidx.core.net.toUri
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.preference.PreferenceManager
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.slider.Slider
import com.buildsession.betterYAMF.R
import com.buildsession.betterYAMF.common.gson
import com.buildsession.betterYAMF.databinding.ActivitySettingBinding
import com.buildsession.betterYAMF.manager.services.YAMFManagerProxy
import com.buildsession.betterYAMF.common.model.Config as YAMFConfig

class SettingActivity : AppCompatActivity() {

    private var _binding: ActivitySettingBinding? = null
    private val binding get() = _binding

    companion object {
        private const val API_37 = 37
        val flags = listOf(
            "VIRTUAL_DISPLAY_FLAG_PUBLIC",
            "VIRTUAL_DISPLAY_FLAG_PRESENTATION",
            "VIRTUAL_DISPLAY_FLAG_SECURE",
            "VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY",
            "VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR",
            "VIRTUAL_DISPLAY_FLAG_CAN_SHOW_WITH_INSECURE_KEYGUARD",
            "VIRTUAL_DISPLAY_FLAG_SUPPORTS_TOUCH",
            "VIRTUAL_DISPLAY_FLAG_ROTATES_WITH_CONTENT",
            "VIRTUAL_DISPLAY_FLAG_DESTROY_CONTENT_ON_REMOVAL",
            "VIRTUAL_DISPLAY_FLAG_SHOULD_SHOW_SYSTEM_DECORATIONS",
            "VIRTUAL_DISPLAY_FLAG_TRUSTED",
            "VIRTUAL_DISPLAY_FLAG_OWN_DISPLAY_GROUP",
            "VIRTUAL_DISPLAY_FLAG_ALWAYS_UNLOCKED",
            "VIRTUAL_DISPLAY_FLAG_TOUCH_FEEDBACK_DISABLED",
        )
    }

    lateinit var config: YAMFConfig
    private val preference: SharedPreferences by lazy { PreferenceManager.getDefaultSharedPreferences(this) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        _binding = ActivitySettingBinding.inflate(layoutInflater)
        setContentView(binding?.root)
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main)) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }
        initData()
    }

    private fun initData() {
        binding?.apply {
            config = gson.fromJson(YAMFManagerProxy.configJson, YAMFConfig::class.java)
            if (Build.VERSION.SDK_INT >= API_37) config.windowMode = 0
            applyConfigToViews()

            btnResetConfig.setOnClickListener {
                MaterialAlertDialogBuilder(this@SettingActivity)
                    .setTitle(R.string.reset_config_title)
                    .setMessage(R.string.reset_config_message)
                    .setNegativeButton(R.string.cancel, null)
                    .setPositiveButton(R.string.reset) { _, _ ->
                        config = YAMFConfig().also {
                            if (Build.VERSION.SDK_INT >= API_37) it.windowMode = 0
                        }
                        preference.edit().putBoolean("useAppList", true).apply()
                        applyConfigToViews()
                        YAMFManagerProxy.updateConfig(gson.toJson(config))
                    }
                    .show()
            }
            btnFlags.setOnClickListener {
                val checks = BooleanArray(flags.size) { i -> config.flags and (1 shl i) != 0 }
                MaterialAlertDialogBuilder(this@SettingActivity)
                    .setMultiChoiceItems(flags.toTypedArray(), checks) { _, i, c ->
                        checks[i] = c
                        btnFlags.text = checks.foldIndexed(0) { i, f, b -> if (b) f + (1 shl i) else f }.toString()
                    }
                    .setPositiveButton("about") { _, _ ->
                        startActivity(Intent(Intent.ACTION_VIEW).apply {
                            data = "https://cs.android.com/android/platform/superproject/+/master:frameworks/base/core/java/android/hardware/display/DisplayManager.java".toUri()
                        })
                    }
                    .show()
            }
            btnWindowsfy.setOnClickListener {
                PopupMenu(this@SettingActivity, btnWindowsfy).apply {
                    listOf("Move Task", "Start Activity", "Hybrid").forEach { i ->
                        menu.add(i).setOnMenuItemClickListener {
                            btnWindowsfy.text = i
                            true
                        }
                    }
                }.show()
            }
            btnSurface.setOnClickListener {
                PopupMenu(this@SettingActivity, btnSurface).apply {
                    listOf("Surface View", "Texture View").forEach { i ->
                        menu.add(i).setOnMenuItemClickListener {
                            btnSurface.text = i
                            true
                        }
                    }
                }.show()
            }

            if (Build.VERSION.SDK_INT >= API_37) {
                btnWindowMode.isEnabled = false
                btnWindowMode.alpha = 0.65f
                config.windowMode = 0
            } else {
                btnWindowMode.setOnClickListener {
                    PopupMenu(this@SettingActivity, btnWindowMode).apply {
                        val modes = listOf(getString(R.string.window_mode_vd), getString(R.string.window_mode_freeform))
                        modes.forEachIndexed { index, i ->
                            menu.add(i).setOnMenuItemClickListener {
                                btnWindowMode.text = i
                                config.windowMode = index
                                true
                            }
                        }
                    }.show()
                }
            }

            sliderRounded.addOnSliderTouchListener(object : Slider.OnSliderTouchListener {
                override fun onStartTrackingTouch(slider: Slider) {}
                override fun onStopTrackingTouch(slider: Slider) {
                    tvRoundedValue.text = "${slider.value.toInt()}"
                    config.windowRoundedCorner = slider.value.toInt()
                    YAMFManagerProxy.updateConfig(gson.toJson(config))
                }
            })

            sliderAnimationSpeed.addOnSliderTouchListener(object : Slider.OnSliderTouchListener {
                override fun onStartTrackingTouch(slider: Slider) {}
                override fun onStopTrackingTouch(slider: Slider) {
                    tvAnimationSpeedValue.text = if (slider.value == 5100f) getString(R.string.default_speed) else "${slider.value.toInt()}"
                    config.animationSpeed = slider.value
                    YAMFManagerProxy.updateConfig(gson.toJson(config))
                }
            })
        }
    }

    private fun applyConfigToViews() {
        binding?.apply {
            if (Build.VERSION.SDK_INT >= API_37) config.windowMode = 0
            etReduceDPI.setText(config.reduceDPI.toString())
            btnFlags.text = config.flags.toString()
            sColoerd.isChecked = config.coloredController
            sBackHome.isChecked = config.recentBackHome
            sShowIMEinWindow.isChecked = config.showImeInWindow
            etSizeH.setText(config.defaultWindowHeight.toString())
            etSizeW.setText(config.defaultWindowWidth.toString())
            sHookLauncherHookRecents.isChecked = config.hookLauncher.hookRecents
            sHookLauncherHookTaskbar.isChecked = config.hookLauncher.hookTaskbar
            sHookLauncherHookPopup.isChecked = config.hookLauncher.hookPopup
            sHookLauncherHookTransientTaskbar.isChecked = config.hookLauncher.hookTransientTaskbar
            sUseAppList.isChecked = preference.getBoolean("useAppList", true)
            sForceShowIME.isChecked = config.showForceShowIME
            sForcePreventRelaunch.isChecked = config.forcePreventRelaunch
            sliderRounded.value = config.windowRoundedCorner.toFloat()
            sliderAnimationSpeed.value = config.animationSpeed
            tvRoundedValue.text = "${config.windowRoundedCorner}"
            tvAnimationSpeedValue.text = if (config.animationSpeed == 5100f) getString(R.string.default_speed) else "${config.animationSpeed.toFloat()}"
            btnSurface.text = when (config.surfaceView) {
                0 -> "Surface View"
                1 -> "Texture View"
                else -> "Unavailable"
            }
            btnWindowMode.text = if (Build.VERSION.SDK_INT >= API_37) {
                "${getString(R.string.window_mode_vd)} (API 37 Safe)"
            } else {
                when (config.windowMode) {
                    0 -> getString(R.string.window_mode_vd)
                    1 -> getString(R.string.window_mode_freeform)
                    else -> "Unavailable"
                }
            }
            btnWindowsfy.text = when (config.windowfy) {
                0 -> "Move Task"
                1 -> "Start Activity"
                2 -> "Hybrid"
                else -> "Unavailable"
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        binding?.apply {
            config.reduceDPI = etReduceDPI.text.toString().toIntOrNull() ?: config.reduceDPI
            config.flags = btnFlags.text.toString().toIntOrNull() ?: config.flags
            config.surfaceView = when (btnSurface.text.toString()) {
                "Surface View" -> 0
                "Texture View" -> 1
                else -> 0
            }
            config.windowfy = when (btnWindowsfy.text.toString()) {
                "Move Task" -> 0
                "Start Activity" -> 1
                "Hybrid" -> 2
                else -> 0
            }
            config.windowMode = if (Build.VERSION.SDK_INT >= API_37) 0 else config.windowMode
            config.coloredController = sColoerd.isChecked
            config.recentBackHome = sBackHome.isChecked
            config.showImeInWindow = sShowIMEinWindow.isChecked
            config.defaultWindowHeight = etSizeH.text.toString().toIntOrNull() ?: config.defaultWindowHeight
            config.defaultWindowWidth = etSizeW.text.toString().toIntOrNull() ?: config.defaultWindowWidth
            config.hookLauncher.hookRecents = sHookLauncherHookRecents.isChecked
            config.hookLauncher.hookTaskbar = sHookLauncherHookTaskbar.isChecked
            config.hookLauncher.hookPopup = sHookLauncherHookPopup.isChecked
            config.hookLauncher.hookTransientTaskbar = sHookLauncherHookTransientTaskbar.isChecked
            config.showForceShowIME = sForceShowIME.isChecked
            config.forcePreventRelaunch = sForcePreventRelaunch.isChecked
            preference.edit().putBoolean("useAppList", sUseAppList.isChecked).apply()
            YAMFManagerProxy.updateConfig(gson.toJson(config))
        }
    }
}
