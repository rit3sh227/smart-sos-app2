package com.example.smartsos

import android.app.Activity
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.webkit.WebView
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.content.ContextCompat
import com.google.firebase.messaging.FirebaseMessaging
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

class MainActivity : Activity() {

    companion object {
        const val CHANNEL_ID = "sos_alerts"
        const val TOPIC = "smartcarsos"

        fun ensureChannel(ctx: Context) {
            if (Build.VERSION.SDK_INT >= 26) {
                ctx.getSystemService(NotificationManager::class.java).createNotificationChannel(
                    NotificationChannel(CHANNEL_ID, "SOS Alerts", NotificationManager.IMPORTANCE_HIGH).apply {
                        description = "Emergency alerts from Smart Car SOS"
                    }
                )
            }
        }

        /** Stores the current alert so the screen can show it (works with or without a server IP). */
        fun saveAlert(ctx: Context, active: Boolean, lat: String?, lon: String?) {
            val e = ctx.getSharedPreferences("settings", Context.MODE_PRIVATE).edit()
            e.putBoolean("alert_active", active)
            if (active) {
                if (!lat.isNullOrBlank() && !lon.isNullOrBlank()) {
                    e.putString("alert_lat", lat); e.putString("alert_lon", lon)
                }
            } else {
                e.remove("alert_lat"); e.remove("alert_lon")
            }
            e.apply()
        }
    }

    private val handler = Handler(Looper.getMainLooper())
    private var busy = false
    private var lastMapUrl = ""
    private var serverOnline: Boolean? = null   // null = not tried / no IP

    private lateinit var prefs: android.content.SharedPreferences
    private lateinit var conn: TextView
    private lateinit var status: TextView
    private lateinit var desc: TextView
    private lateinit var locPanel: LinearLayout
    private lateinit var latView: TextView
    private lateinit var lonView: TextView
    private lateinit var mapBtn: Button
    private lateinit var web: WebView
    private lateinit var ipEdit: EditText

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun tv(text: String, size: Float, bold: Boolean = false, color: Int = Color.WHITE) =
        TextView(this).apply {
            this.text = text; textSize = size; setTextColor(color)
            if (bold) setTypeface(null, Typeface.BOLD)
            setPadding(0, dp(4), 0, dp(4))
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = getSharedPreferences("settings", Context.MODE_PRIVATE)
        ensureChannel(this)
        if (Build.VERSION.SDK_INT >= 33) requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 1)
        FirebaseMessaging.getInstance().subscribeToTopic(TOPIC)
        handleIntent(intent)
        if (!prefs.getString("ip", "").isNullOrEmpty()) startMonitor()

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(24), dp(16), dp(24))
        }
        root.addView(tv("🚨 CAR ACCIDENT ALERT", 22f, true))
        conn = tv("", 13f)
        root.addView(conn)

        status = tv("NO ACCIDENT", 24f, true).apply {
            gravity = Gravity.CENTER
            setBackgroundColor(Color.parseColor("#00C853"))
            setPadding(dp(8), dp(18), dp(8), dp(18))
        }
        root.addView(status, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = dp(8) })
        desc = tv("Your car is safe.", 16f, false, Color.parseColor("#B0BEC5")).apply { gravity = Gravity.CENTER }
        root.addView(desc)

        locPanel = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; visibility = View.GONE }
        locPanel.addView(tv("ACCIDENT LOCATION", 18f, true, Color.parseColor("#FF5252")))
        latView = tv("Latitude: -", 16f); locPanel.addView(latView)
        lonView = tv("Longitude: -", 16f); locPanel.addView(lonView)
        mapBtn = Button(this).apply {
            text = "Open in Google Maps"; isEnabled = false
            setOnClickListener {
                val la = prefs.getString("alert_lat", null); val lo = prefs.getString("alert_lon", null)
                if (la != null && lo != null) startActivity(Intent(Intent.ACTION_VIEW,
                    Uri.parse("https://www.google.com/maps/search/?api=1&query=$la,$lo")))
            }
        }
        locPanel.addView(mapBtn)
        locPanel.addView(Button(this).apply {
            text = "Dismiss alert"
            setOnClickListener { saveAlert(this@MainActivity, false, null, null); render() }
        })
        web = WebView(this).apply { settings.javaScriptEnabled = true }
        locPanel.addView(web, LinearLayout.LayoutParams(MATCH_PARENT, dp(320)).apply { topMargin = dp(8) })
        root.addView(locPanel, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = dp(16) })

        root.addView(tv("Server IP (optional - only for live updates on the same Wi-Fi)", 13f, false, Color.parseColor("#B0BEC5")),
            LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = dp(24) })
        ipEdit = EditText(this).apply {
            setText(prefs.getString("ip", "")); hint = "leave empty to use push alerts only"
            setTextColor(Color.WHITE); setHintTextColor(Color.GRAY)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
        }
        root.addView(ipEdit)
        root.addView(Button(this).apply {
            text = "Save"
            setOnClickListener {
                val ip = ipEdit.text.toString().trim()
                prefs.edit().putString("ip", ip).apply()
                serverOnline = null
                if (ip.isNotEmpty()) startMonitor() else stopService(Intent(this@MainActivity, PollService::class.java))
                render()
            }
        })

        setContentView(ScrollView(this).apply {
            setBackgroundColor(Color.parseColor("#111827"))
            addView(root)
        })
        render()
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        handleIntent(intent)
        render()
    }

    private fun handleIntent(i: Intent?) {
        if (i?.getStringExtra("type") == "accident") {
            saveAlert(this, true, i.getStringExtra("lat"), i.getStringExtra("lon"))
        }
    }

    private fun startMonitor() {
        ContextCompat.startForegroundService(this, Intent(this, PollService::class.java))
    }

    private val poll = object : Runnable {
        override fun run() {
            fetch()
            render()
            handler.postDelayed(this, 1000)
        }
    }

    override fun onResume() { super.onResume(); handler.post(poll) }
    override fun onPause() { super.onPause(); handler.removeCallbacks(poll) }

    private fun num(j: JSONObject, key: String): Double? =
        if (j.has(key) && !j.isNull(key)) j.optDouble(key, Double.NaN).takeIf { !it.isNaN() } else null

    private fun fetch() {
        val ip = ipEdit.text.toString().trim()
        if (ip.isEmpty() || busy) return
        busy = true
        Thread {
            try {
                val c = URL("http://$ip:5000/api/status").openConnection() as HttpURLConnection
                c.connectTimeout = 3000; c.readTimeout = 3000
                val text = c.inputStream.bufferedReader().use { it.readText() }
                c.disconnect()
                val j = JSONObject(text)
                var la: Double? = null; var lo: Double? = null
                if (j.optBoolean("gps", false)) { la = num(j, "latitude"); lo = num(j, "longitude") }
                if (la == null || lo == null) { la = num(j, "pc_latitude"); lo = num(j, "pc_longitude") }
                val sos = j.optBoolean("sos", false)
                runOnUiThread {
                    serverOnline = true
                    saveAlert(this, sos, la?.toString(), lo?.toString())
                }
            } catch (e: Exception) {
                runOnUiThread { serverOnline = false }
            } finally { busy = false }
        }.start()
    }

    private fun render() {
        // connection line
        val ip = prefs.getString("ip", "") ?: ""
        when {
            ip.isEmpty() -> { conn.text = "● Push alerts active (no server IP set)"; conn.setTextColor(Color.parseColor("#B0BEC5")) }
            serverOnline == true -> { conn.text = "● Server connected + push alerts"; conn.setTextColor(Color.parseColor("#00C853")) }
            serverOnline == false -> { conn.text = "● Server offline - push alerts still active"; conn.setTextColor(Color.parseColor("#FFC800")) }
            else -> { conn.text = "● Connecting..."; conn.setTextColor(Color.parseColor("#FFC800")) }
        }

        if (!prefs.getBoolean("alert_active", false)) {
            status.text = "NO ACCIDENT"
            status.setBackgroundColor(Color.parseColor("#00C853"))
            desc.text = "Your car is safe."
            locPanel.visibility = View.GONE
            lastMapUrl = ""
            return
        }

        status.text = "🚨 ACCIDENT HAPPENED"
        status.setBackgroundColor(Color.parseColor("#F44336"))
        desc.text = "Alert: Your car is in danger"
        locPanel.visibility = View.VISIBLE

        val la = prefs.getString("alert_lat", null)?.toDoubleOrNull()
        val lo = prefs.getString("alert_lon", null)?.toDoubleOrNull()
        if (la != null && lo != null) {
            latView.text = "Latitude: %.6f".format(la)
            lonView.text = "Longitude: %.6f".format(lo)
            mapBtn.isEnabled = true
            val url = "https://maps.google.com/maps?q=$la,$lo&z=17&output=embed"
            if (url != lastMapUrl) { lastMapUrl = url; web.loadUrl(url) }
        } else {
            latView.text = "Latitude: unavailable"
            lonView.text = "Longitude: unavailable"
            mapBtn.isEnabled = false
        }
    }
}
