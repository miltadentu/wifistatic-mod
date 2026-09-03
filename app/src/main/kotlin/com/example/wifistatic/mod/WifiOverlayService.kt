package com.example.wifistatic.mod

import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.graphics.drawable.GradientDrawable
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.view.Gravity
import android.view.WindowManager
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import android.net.wifi.WifiManager
import android.util.TypedValue

class WifiOverlayService : Service() {

    private lateinit var windowManager: WindowManager
    private lateinit var params: WindowManager.LayoutParams
    private lateinit var wifiIcon: ImageView
    private lateinit var wifiText: TextView
    private lateinit var container: LinearLayout
    private lateinit var prefs: SharedPreferences
    private lateinit var backgroundDrawable: GradientDrawable
    private lateinit var hideHandler: Handler
    private lateinit var hideRunnable: Runnable
    private var isAutoHideEnabled = false
    private var isWifiConnected = false
    private var currentSSID = ""

    companion object {
        const val CHANNEL_ID = "wifi_service_channel"
        private var instance: WifiOverlayService? = null

        fun getInstance(): WifiOverlayService? = instance
    }

    override fun onCreate() {
        super.onCreate()
        instance = this

        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        prefs = getSharedPreferences("wifi_prefs", Context.MODE_PRIVATE)
        backgroundDrawable = GradientDrawable()

        hideHandler = Handler(Looper.getMainLooper())
        hideRunnable = Runnable {
            wifiIcon.visibility = android.view.View.GONE
            wifiText.visibility = android.view.View.GONE
        }

        setupOverlay()
        startNetworkMonitoring()
        checkCurrentStatus()
    }

    private fun setupOverlay() {
        container = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        wifiIcon = ImageView(this).apply {
            setImageResource(android.R.drawable.ic_dialog_info)
            setColorFilter(android.graphics.Color.GREEN)
        }

        wifiText = TextView(this).apply {
            text = "WiFi"
            setTextColor(android.graphics.Color.GREEN)
            setPadding(8, 0, 0, 0)
        }

        container.addView(wifiIcon)
        container.addView(wifiText)

        val posX = prefs.getInt("pos_x", 50)
        val posY = prefs.getInt("pos_y", 50)
        val alpha = prefs.getInt("alpha", 200)
        val size = prefs.getInt("size", 30)
        val textPosition = prefs.getInt("text_position", 0) // 0 = right, 1 = bottom
        val fontSize = prefs.getInt("font_size", 100)

        params = WindowManager.LayoutParams().apply {
            type = WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            format = android.graphics.PixelFormat.TRANSLUCENT
            flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
            width = size * 2
            height = size * 2
            x = (posX * 10)
            y = (posY * 10)
        }

        updateContainerLayout(textPosition)
        updateSize(size)
        updateFontSize(fontSize)
        updateAutoHideSetting(isAutoHideEnabled)

        container.alpha = alpha / 255f
        windowManager.addView(container, params)
    }

    private fun updateContainerLayout(position: Int) {
        container.orientation = if (position == 0) LinearLayout.HORIZONTAL else LinearLayout.VERTICAL
    }

    private fun updateFontSize(percentage: Int) {
        val baseSizeSp = prefs.getInt("size", 30)
        val textSizeSp = (baseSizeSp * percentage / 100f)
        wifiText.setTextSize(TypedValue.COMPLEX_UNIT_SP, textSizeSp)
    }

    private fun startNetworkMonitoring() {
        val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val wm = getSystemService(Context.WIFI_SERVICE) as WifiManager

        val broadcastReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                checkCurrentStatus()
                updateWifiInfo(wm)
            }
        }

        val filter = IntentFilter().apply {
            addAction(ConnectivityManager.CONNECTIVITY_ACTION)
            addAction(WifiManager.NETWORK_STATE_CHANGED_ACTION)
            addAction(WifiManager.SUPPLICANT_STATE_CHANGED_ACTION)
        }
        registerReceiver(broadcastReceiver, filter, Context.RECEIVER_EXPORTED)
    }

    private fun checkCurrentStatus() {
        val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val activeNetwork = cm.activeNetwork
        val caps = cm.getNetworkCapabilities(activeNetwork)

        if (caps != null) {
            val hasWifi = caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
            if (hasWifi && !isWifiConnected) {
                isWifiConnected = true
                updateIconStatus("connected")
            } else if (!hasWifi && isWifiConnected) {
                isWifiConnected = false
                updateIconStatus("disconnected")
            }
        }
    }

    private fun updateWifiInfo(wm: WifiManager) {
        try {
            val connectionInfo = wm.connectionInfo
            if (connectionInfo != null && connectionInfo.ssid != null) {
                var ssid = connectionInfo.ssid
                // Удаляем кавычки если они есть
                if (ssid.startsWith("\"") && ssid.endsWith("\"")) {
                    ssid = ssid.substring(1, ssid.length - 1)
                }
                currentSSID = ssid
                updateTextDisplay()
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun updateTextDisplay() {
        wifiText.text = if (isWifiConnected) currentSSID else "No WiFi"
    }

    private fun updateIconStatus(status: String) {
        if (status == "connected") {
            wifiIcon.setColorFilter(android.graphics.Color.GREEN)
            wifiIcon.visibility = android.view.View.VISIBLE
            wifiText.visibility = android.view.View.VISIBLE
            updateTextDisplay()
        } else {
            wifiIcon.setColorFilter(android.graphics.Color.RED)
            wifiIcon.visibility = android.view.View.GONE
            wifiText.visibility = android.view.View.GONE
        }
    }

    fun updatePosition(x: Int, y: Int, alpha: Int) {
        params.x = (x * 10)
        params.y = (y * 10)
        wifiIcon.alpha = alpha / 255f
        wifiText.alpha = alpha / 255f
        windowManager.updateViewLayout(container, params)

        if (isAutoHideEnabled) {
            hideHandler.removeCallbacks(hideRunnable)
            hideHandler.postDelayed(hideRunnable, 15000)
        }

        prefs.edit().apply {
            putInt("pos_x", x)
            putInt("pos_y", y)
            putInt("alpha", alpha)
            apply()
        }
    }

    fun updateSize(size: Int) {
        params.width = size * 2
        params.height = size * 2
        windowManager.updateViewLayout(container, params)

        prefs.edit().apply {
            putInt("size", size)
            apply()
        }
    }

    fun updateTextPosition(position: Int) {
        updateContainerLayout(position)
        windowManager.updateViewLayout(container, params)

        prefs.edit().apply {
            putInt("text_position", position)
            apply()
        }
    }

    fun updateFontSizeMultiplier(percentage: Int) {
        updateFontSize(percentage)
        windowManager.updateViewLayout(container, params)

        prefs.edit().apply {
            putInt("font_size", percentage)
            apply()
        }
    }

    fun updateAutoHideSetting(enabled: Boolean) {
        isAutoHideEnabled = enabled
        if (!enabled) {
            hideHandler.removeCallbacks(hideRunnable)
            wifiIcon.visibility = android.view.View.VISIBLE
            wifiText.visibility = android.view.View.VISIBLE
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        try {
            windowManager.removeView(container)
        } catch (e: Exception) {
            e.printStackTrace()
        }
        instance = null
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
