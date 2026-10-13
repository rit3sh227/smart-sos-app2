package com.example.smartsos

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * OPTIONAL: only runs when a server IP has been entered. Watches the dashboard server
 * on the local Wi-Fi so the alert also works when the internet push is not set up.
 */
class PollService : Service() {

    private var running = false
    private var wasSos = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        MainActivity.ensureChannel(this)
        if (Build.VERSION.SDK_INT >= 26) {
            getSystemService(NotificationManager::class.java).createNotificationChannel(
                NotificationChannel("monitor", "Car monitoring", NotificationManager.IMPORTANCE_LOW)
            )
        }
        val monitor: Notification = NotificationCompat.Builder(this, "monitor")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("Car Accident Alert")
            .setContentText("Monitoring your car")
            .setOngoing(true)
            .build()
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(2001, monitor, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(2001, monitor)
        }
        if (!running) {
            running = true
            Thread { loop() }.start()
        }
        return START_STICKY
    }

    private fun num(j: JSONObject, key: String): Double? =
        if (j.has(key) && !j.isNull(key)) j.optDouble(key, Double.NaN).takeIf { !it.isNaN() } else null

    private fun loop() {
        while (running) {
            val ip = getSharedPreferences("settings", Context.MODE_PRIVATE).getString("ip", "") ?: ""
            if (ip.isNotEmpty()) {
                try {
                    val c = URL("http://$ip:5000/api/status").openConnection() as HttpURLConnection
                    c.connectTimeout = 3000; c.readTimeout = 3000
                    val text = c.inputStream.bufferedReader().use { it.readText() }
                    c.disconnect()
                    val j = JSONObject(text)
                    val sos = j.optBoolean("sos", false)
                    if (sos) {
                        var la: Double? = null; var lo: Double? = null
                        if (j.optBoolean("gps", false)) { la = num(j, "latitude"); lo = num(j, "longitude") }
                        if (la == null || lo == null) { la = num(j, "pc_latitude"); lo = num(j, "pc_longitude") }
                        MainActivity.saveAlert(this, true, la?.toString(), lo?.toString())
                        if (!wasSos) Notifier.showAlert(this)
                    } else if (wasSos) {
                        MainActivity.saveAlert(this, false, null, null)
                    }
                    wasSos = sos
                } catch (e: Exception) {
                    // server not reachable right now - try again next round
                }
            }
            try { Thread.sleep(2000) } catch (e: InterruptedException) { return }
        }
    }

    override fun onDestroy() {
        running = false
        super.onDestroy()
    }
}
