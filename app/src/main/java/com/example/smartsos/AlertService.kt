package com.example.smartsos

import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage

/**
 * Receives the push message from the server (works without any server IP).
 * The message carries the accident location, which is stored so the app can show it.
 */
class AlertService : FirebaseMessagingService() {

    override fun onMessageReceived(message: RemoteMessage) {
        val d = message.data
        if (d["type"] == "accident" || message.notification != null) {
            MainActivity.saveAlert(this, true, d["lat"], d["lon"])
            Notifier.showAlert(
                this,
                d["title"] ?: message.notification?.title,
                d["body"] ?: message.notification?.body
            )
        }
    }
}
