package android.app

import net.bytebuddy.ByteBuddy
import net.bytebuddy.android.AndroidClassLoadingStrategy
import net.bytebuddy.implementation.MethodDelegation
import net.bytebuddy.implementation.bind.annotation.AllArguments
import net.bytebuddy.implementation.bind.annotation.Origin
import net.bytebuddy.implementation.bind.annotation.RuntimeType
import net.bytebuddy.matcher.ElementMatchers
import java.io.File
import java.lang.reflect.Method

object ITaskStackListenerProxy {
    val byteBuddyStrategy = AndroidClassLoadingStrategy.Wrapping(File("/data/system/reYAMF").also { it.mkdirs() })

    /**
     * Optional observer used by BetterYAMF's own window registry. The platform task listener is
     * shared by legacy virtual-display windows and native-freeform windows, so observing removals
     * here avoids relying on displayId as a window identity (native freeform always uses display 0).
     */
    @Volatile
    var taskRemovalObserver: ((Int) -> Unit)? = null

    private fun normalizeArguments(method: Method, allArguments: Array<Any?>): Array<Any?> {
        if (method.name == "onTaskRemovalStarted" || method.name == "onTaskRemoved") {
            val first = allArguments.firstOrNull()
            val taskId = when (first) {
                is ActivityManager.RunningTaskInfo -> first.taskId
                is Number -> first.toInt()
                else -> null
            }

            if (taskId != null) {
                // Never let an optional observer break the system Binder callback.
                runCatching { taskRemovalObserver?.invoke(taskId) }
            }

            // Android 12+ exposes onTaskRemovalStarted(RunningTaskInfo), while the existing
            // YAMFManager callback consumes the legacy taskId form. Normalize only that callback;
            // onTaskRemoved already carries an int and is left untouched for ROM compatibility.
            if (method.name == "onTaskRemovalStarted" && first is ActivityManager.RunningTaskInfo) {
                return arrayOf(first.taskId)
            }
        }
        return allArguments
    }

    fun newInstance(
        classLoader: ClassLoader,
        intercept: (Array<Any?>, Method) -> Any?
    ): ITaskStackListener {
        return ByteBuddy()
            .subclass(ITaskStackListener.Stub::class.java)
            .method(ElementMatchers.any())
            .intercept(MethodDelegation.to(object {
                @RuntimeType
                fun intercept(
                    @AllArguments allArguments: Array<Any?>,
                    @Origin method: Method
                ) {
                    intercept(normalizeArguments(method, allArguments), method)
                }
            }))
            .make()
            .load(classLoader, byteBuddyStrategy)
            .loaded
            .getDeclaredConstructor()
            .newInstance()
    }
}
