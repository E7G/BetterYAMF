package com.buildsession.betterYAMF.xposed.hook

import com.buildsession.betterYAMF.common.gson
import com.buildsession.betterYAMF.xposed.services.YAMFManager
import com.buildsession.betterYAMF.xposed.utils.log
import com.google.gson.JsonParser
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge

/**
 * Conservative UI-only fixes that are safe to run in system_server.
 *
 * No native-freeform WCT hooks, no task-token reflection, and no runtime generated classes.
 */
object WindowUiFixes {
    private const val TAG = "reYAMF_WindowUiFixes"

    fun init() {
        hookLegacySurfaceBackendMigration()
    }

    private fun hookLegacySurfaceBackendMigration() {
        XposedBridge.hookAllMethods(YAMFManager::class.java, "systemReady", object : XC_MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) {
                runCatching {
                    val rawConfig = YAMFManager.configFile.readText()
                    val hasSchemaVersion = rawConfig.isNotBlank() &&
                        JsonParser.parseString(rawConfig).asJsonObject.has("schemaVersion")

                    if (!hasSchemaVersion || YAMFManager.config.schemaVersion < 1) {
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

}
