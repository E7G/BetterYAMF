package android.app

/**
 * Static task-listener adapter for system_server.
 *
 * Do not generate subclasses/dex at runtime here. Android 17's ART/JIT can be extremely sensitive
 * to runtime-generated framework subclasses inside system_server. The hidden framework
 * TaskStackListener already provides a forward-compatible no-op adapter, so subclass it directly.
 */
object ITaskStackListenerProxy {
    fun newInstance(
        onTaskMovedToFront: (ActivityManager.RunningTaskInfo) -> Unit,
        onTaskDescriptionChanged: (ActivityManager.RunningTaskInfo) -> Unit,
        onTaskRemoved: (Int) -> Unit
    ): ITaskStackListener {
        return object : TaskStackListener() {
            override fun onTaskMovedToFront(taskInfo: ActivityManager.RunningTaskInfo) {
                runCatching { onTaskMovedToFront(taskInfo) }
            }

            override fun onTaskDescriptionChanged(taskInfo: ActivityManager.RunningTaskInfo) {
                runCatching { onTaskDescriptionChanged(taskInfo) }
            }

            override fun onTaskRemovalStarted(taskInfo: ActivityManager.RunningTaskInfo) {
                runCatching { onTaskRemoved(taskInfo.taskId) }
            }

            override fun onTaskRemoved(taskId: Int) {
                runCatching { onTaskRemoved(taskId) }
            }
        }
    }
}
