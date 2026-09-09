package android.app;

import android.os.RemoteException;

/**
 * Compile-only stub for the framework's hidden TaskStackListener adapter.
 * The real implementation is provided by the boot class path at runtime.
 */
public class TaskStackListener extends ITaskStackListener.Stub {
    public void onTaskMovedToFront(ActivityManager.RunningTaskInfo taskInfo) throws RemoteException {}
    public void onTaskDescriptionChanged(ActivityManager.RunningTaskInfo taskInfo) throws RemoteException {}
    public void onTaskRemovalStarted(ActivityManager.RunningTaskInfo taskInfo) throws RemoteException {}
    public void onTaskRemoved(int taskId) throws RemoteException {}
}
