package codes.pmh.connect

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class WatchdogReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        ConnectService.scheduleWatchdog(context)
        try {
            ConnectService.start(context)
        } catch (_: IllegalStateException) {
            // An inexact fallback alarm may not have foreground-start privileges; retry next alarm.
        }
    }
}
