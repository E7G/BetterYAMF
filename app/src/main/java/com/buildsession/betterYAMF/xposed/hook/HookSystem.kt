package com.buildsession.betterYAMF.xposed.hook

import android.content.Intent
import android.content.pm.IPackageManager
import android.os.Build
import com.github.kyuubiran.ezxhelper.init.EzXHelperInit
import com.github.kyuubiran.ezxhelper.utils.findMethod
import com.github.kyuubiran.ezxhelper.utils.hookAfter
import com.github.kyuubiran.ezxhelper.utils.hookBefore
import com.buildsession.betterYAMF.common.gson
import com.buildsession.betterYAMF.xposed.services.UserService
import com.buildsession.betterYAMF.xposed.services.YAMFManager
import com.buildsession.betterYAMF.xposed.utils.log
import com.qauxv.util.Initiator
import de.robv.android.xposed.IXposedHookLoadPackage
import de.robv.android.xposed.IXposedHookZygoteInit
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.XposedHelpers.findAndHookMethod
import de.robv.android.xposed.callbacks.XC_LoadPackage
import kotlin.concurrent.thread


class HookSystem : IXposedHookZygoteInit, IXposedHookLoadPackage {
    companion object {
        private const val TAG = "reYAMF_HookSystem"
        private const val API_37 = 37
    }

    override fun initZygote(startupParam: IXposedHookZygoteInit.StartupParam) {
        EzXHelperInit.initZygote(startupParam)
    }

    override fun handleLoadPackage(lpparam: XC_LoadPackage.LoadPackageParam) {
        if (lpparam.packageName != "android") return
        EzXHelperInit.initHandleLoadPackage(lpparam)
        Initiator.init(lpparam.classLoader)

        // The UI fixes now contain only conservative config/icon handling and are safe on API 37.
        runCatching {
            WindowUiFixes.init()
        }.onFailure {
            log(TAG, "WindowUiFixes init failed, continuing without optional UI fixes", it)
        }

        // Android 17/API 37: do not install Smooth Native Freeform WMS hooks.
        // Legacy VirtualDisplay keeps AppWindow's direct HyperOS-like drag/swipe/resize gestures.
        if (Build.VERSION.SDK_INT < API_37) {
            runCatching {
                FreeformHook.init(lpparam.classLoader)
            }.onFailure {
                log(TAG, "FreeformHook init failed, but continuing for legacy support", it)
            }
        } else {
            XposedBridge.log("$TAG: API ${Build.VERSION.SDK_INT}: safe legacy mode; native-freeform hooks disabled")
        }

         var serviceManagerHook: XC_MethodHook.Unhook? = null
         serviceManagerHook = findMethod("android.os.ServiceManager") {
             name == "addService"
         }.hookBefore { param ->
             if (param.args[0] == "package") {
                 serviceManagerHook?.unhook()
                 val pms = param.args[1] as IPackageManager
                 thread {
                     runCatching {
                         UserService.register(pms)
                     }.onFailure {
                         log(TAG, "UserService failed to start", it)
                     }
                 }
             }
         }

         var activityManagerServiceSystemReadyHook: XC_MethodHook.Unhook? = null
         activityManagerServiceSystemReadyHook = findMethod("com.android.server.am.ActivityManagerService") {
            name == "systemReady"
        }.hookAfter {
            activityManagerServiceSystemReadyHook?.unhook()
            YAMFManager.activityManagerService = it.thisObject
            YAMFManager.systemReady()

            // An older saved Smooth Native Freeform selection must not reactivate the unsafe API 37
            // path. Persist VirtualDisplay mode before any window is opened.
            if (Build.VERSION.SDK_INT >= API_37 && YAMFManager.config.windowMode != 0) {
                YAMFManager.config.windowMode = 0
                runCatching {
                    YAMFManager.configFile.writeText(gson.toJson(YAMFManager.config))
                }.onFailure { error ->
                    log(TAG, "Unable to persist API 37 safe legacy mode", error)
                }
            }

            XposedBridge.log("$TAG: System ready, reYAMF services initialized.")
        }
        runCatching {
            findMethod("com.android.server.am.ActivityManagerService") {
                name == "checkBroadcastFromSystem"
            }.hookBefore {
                val intent = it.args[0] as Intent
                if (intent.action == HookLauncher.ACTION_RECEIVE_LAUNCHER_CONFIG)
                    it.result = Unit
            }
        }.onFailure {
            log(TAG, "ActivityManagerService checkBroadcastFromSystem fail")
        }

        runCatching {
            findMethod("com.android.server.am.BroadcastController") {
                name == "checkBroadcastFromSystem"
            }.hookBefore {
                val intent = it.args[0] as Intent
                if (intent.action == HookLauncher.ACTION_RECEIVE_LAUNCHER_CONFIG)
                    it.result = Unit
            }
        }.onFailure {
            log(TAG, "BroadcastController checkBroadcastFromSystem fail")
        }

        try {
            val clazz = XposedHelpers.findClass(
                "com.android.server.wm.InputMonitor",
                lpparam.classLoader
            )

            findAndHookMethod(
                clazz,
                "requestFocus",
                android.os.IBinder::class.java,
                String::class.java,
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        val thisObject = param.thisObject
                        val displayId = XposedHelpers.getIntField(thisObject, "mDisplayId")
                        YAMFManager.currentDisplayId = displayId
                    }
                }
            )
        } catch (e: Throwable) {
            XposedBridge.log("[XposedHook] Error hooking requestFocus: ${e.message}")
        }
    }
}
