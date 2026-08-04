package codes.pmh.connect

import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification

class ConnectNotificationListener : NotificationListenerService() {
    override fun onListenerConnected() {
        super.onListenerConnected()
        refreshNotifications()
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        sbn?.let { CollectedDataRepository.recordNotification(packageName, it) }
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?) {
        // Keep removed notifications in the bounded in-memory collection history.
    }

    private fun refreshNotifications() {
        val notifications = runCatching { activeNotifications }.getOrDefault(emptyArray())
        CollectedDataRepository.updateNotifications(packageName, notifications)
    }
}
