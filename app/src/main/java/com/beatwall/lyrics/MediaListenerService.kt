package com.beatwall.lyrics

import android.content.ComponentName
import android.content.Context
import android.provider.Settings
import android.service.notification.NotificationListenerService

class MediaListenerService : NotificationListenerService() {
    override fun onListenerConnected() {
        super.onListenerConnected()
        MediaSessionTracker.getInstance(applicationContext).onListenerConnected()
    }

    companion object {
        fun componentName(context: Context): ComponentName =
            ComponentName(context, MediaListenerService::class.java)

        fun isEnabled(context: Context): Boolean {
            val flat = Settings.Secure.getString(
                context.contentResolver,
                "enabled_notification_listeners"
            ) ?: return false
            return flat.contains(componentName(context).flattenToString())
        }

        fun requestRebindSafe(context: Context) {
            try {
                if (isEnabled(context)) {
                    requestRebind(componentName(context))
                }
            } catch (_: Exception) {
            }
        }
    }
}
