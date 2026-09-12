package android.app

import java.lang.reflect.Method

/**
 * Static task-listener adapter for system_server.
 *
 * Android 17/API 37 testing showed an ART/JIT native crash with the previous ByteBuddy generated
 * Stub subclass. Use the framework TaskStackListener adapter instead: no generated dex, no dynamic
 * class loading, and the platform owns forward compatibility for newly-added Binder callbacks.
 */
object ITaskStackListenerProxy {
    private class CallbackNames {
        fun onTaskStackChanged() = Unit
        fun onTaskMovedToFront() = Unit
        fun onTaskDescriptionChanged() = Unit
        fun onTaskRemovalStarted() = Unit
        fun onTaskRemoved() = Unit
    }

    private val movedMethod: Method by lazy {
        CallbackNames::class.java.getDeclaredMethod("onTaskMovedToFront")
    }
    private val stackChangedMethod: Method by lazy {
        CallbackNames::class.java.getDeclaredMethod("onTaskStackChanged")
    }
    private val descriptionMethod: Method by lazy {
        CallbackNames::class.java.getDeclaredMethod("onTaskDescriptionChanged")
    }
    private val removalStartedMethod: Method by lazy {
        CallbackNames::class.java.getDeclaredMethod("onTaskRemovalStarted")
    }
    private val removedMethod: Method by lazy {
        CallbackNames::class.java.getDeclaredMethod("onTaskRemoved")
    }

    @Suppress("UNUSED_PARAMETER")
    fun newInstance(
        classLoader: ClassLoader,
        intercept: (Array<Any?>, Method) -> Any?
    ): ITaskStackListener {
        return object : TaskStackListener() {
            override fun onTaskStackChanged() {
                runCatching { intercept(emptyArray(), stackChangedMethod) }
            }

            override fun onTaskMovedToFront(taskInfo: ActivityManager.RunningTaskInfo) {
                runCatching { intercept(arrayOf(taskInfo), movedMethod) }
            }

            override fun onTaskDescriptionChanged(taskInfo: ActivityManager.RunningTaskInfo) {
                runCatching { intercept(arrayOf(taskInfo), descriptionMethod) }
            }

            override fun onTaskRemovalStarted(taskInfo: ActivityManager.RunningTaskInfo) {
                // Keep displayId so removal can clean a virtual-display window
                // even if task ownership raced the initial move callback.
                runCatching { intercept(arrayOf(taskInfo), removalStartedMethod) }
            }

            override fun onTaskRemoved(taskId: Int) {
                // Some ROMs deliver only onTaskRemoved, normalize it to the same teardown path.
                runCatching { intercept(arrayOf(taskId), removalStartedMethod) }
                runCatching { intercept(arrayOf(taskId), removedMethod) }
            }
        }
    }
}
