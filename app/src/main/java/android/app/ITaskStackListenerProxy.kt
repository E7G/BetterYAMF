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

    private fun normalizeArguments(method: Method, allArguments: Array<Any?>): Array<Any?> {
        // Android 12+ exposes onTaskRemovalStarted(RunningTaskInfo), while older
        // code paths (and YAMFManager) consume the legacy taskId form. Some ROMs
        // still dispatch the legacy signature, so keep this normalization tolerant.
        if (method.name == "onTaskRemovalStarted") {
            val taskInfo = allArguments.firstOrNull() as? ActivityManager.RunningTaskInfo
            if (taskInfo != null) {
                return arrayOf(taskInfo.taskId)
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
