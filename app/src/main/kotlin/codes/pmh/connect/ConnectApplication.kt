package codes.pmh.connect

import android.app.Application
import android.os.Process
import kotlin.system.exitProcess

class ConnectApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        val systemHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            runCatching { CrashLogStore.record(this, thread, error) }
            if (systemHandler != null) {
                systemHandler.uncaughtException(thread, error)
            } else {
                Process.killProcess(Process.myPid())
                exitProcess(10)
            }
        }
    }
}
