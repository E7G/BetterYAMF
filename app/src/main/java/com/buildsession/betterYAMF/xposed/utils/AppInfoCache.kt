package com.buildsession.betterYAMF.xposed.utils

import android.content.pm.ActivityInfo
import android.graphics.drawable.Drawable
import android.util.LruCache
import androidx.wear.widget.RoundedDrawable

object AppInfoCache {
    private val iconCache = LruCache<String, Drawable.ConstantState>(64)

    fun getIcon(info: ActivityInfo): Drawable {
        val key = "${info.applicationInfo.uid}:${info.packageName}/${info.name}"
        synchronized(iconCache) {
            iconCache.get(key)?.let { return it.newDrawable().mutate() }
        }

        // Unbadged icons match launcher/system-window rendering. Badges are overlayed separately
        // by launchers and were being clipped by the old CENTER_CROP bubble ImageView.
        val icon = info.loadUnbadgedIcon(Instances.packageManager).mutate()
        icon.constantState?.let { state ->
            synchronized(iconCache) { iconCache.put(key, state) }
        }
        return icon
    }

    fun getIconLabel(info: ActivityInfo): Pair<Drawable, CharSequence> {
        return Pair(
            RoundedDrawable().apply {
                isClipEnabled = true
                radius = 100
                drawable = getIcon(info)
            },
            info.loadLabel(Instances.packageManager)
        )
    }
}
