package com.example.wifistatic.mod

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Notification
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.WindowManager
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.net.wifi.WifiManager
import android.util.TypedValue

class WifiOverlayService : Service() {

    private enum class WifiStatus { UNKNOWN, CONNECTED, LIMITED, DISCONNECTED }

    private lateinit var windowManager: WindowManager
    private lateinit var params: WindowManager.LayoutParams
    private lateinit var wifiIcon: ImageView
    private lateinit var wifiText: TextView
    private lateinit var container: LinearLayout
    private lateinit var prefs: SharedPreferences
    private lateinit var hideHandler: Handler
    private lateinit var hideRunnable: Runnable
    private var isAutoHideEnabled = false
    private var currentSSID = ""
    private var overlayAdded = false
    private var manuallyHidden = false
    private var currentStatus = WifiStatus.UNKNOWN

    companion object {
        const val CHANNEL_ID = "wifi_service_channel"
        const val NOTIFICATION_ID = 1001

        // Внешнее управление, например через:
        //   su 0 sh -c "am start-foreground-service -n com.example.wifistatic.mod/.WifiOverlayService -a com.example.wifistatic.mod.ACTION_SHOW"
        const val ACTION_SHOW = "com.example.wifistatic.mod.ACTION_SHOW"
        const val ACTION_HIDE = "com.example.wifistatic.mod.ACTION_HIDE"
        const val ACTION_TOGGLE = "com.example.wifistatic.mod.ACTION_TOGGLE"
        const val ACTION_STOP = "com.example.wifistatic.mod.ACTION_STOP"

        private var instance: WifiOverlayService? = null

        fun getInstance(): WifiOverlayService? = instance
    }

    override fun onCreate() {
        super.onCreate()
        instance = this

        try {
            // Must call startForeground() ASAP after startForegroundService(),
            // otherwise Android 8+ kills the process with an exception.
            startForegroundWithNotification()

            // Without this permission, WindowManager.addView() with
            // TYPE_APPLICATION_OVERLAY throws and instantly crashes the service.
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(this)) {
                android.util.Log.e("WifiOverlayMod", "No overlay permission, stopping")
                stopSelf()
                return
            }

            windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
            prefs = getSharedPreferences("wifi_prefs", Context.MODE_PRIVATE)

            hideHandler = Handler(Looper.getMainLooper())
            hideRunnable = Runnable {
                wifiIcon.visibility = android.view.View.GONE
                wifiText.visibility = android.view.View.GONE
            }

            setupOverlay()
            startNetworkMonitoring()
            checkCurrentStatus()
        } catch (t: Throwable) {
            android.util.Log.e("WifiOverlayMod", "Fatal error in onCreate", t)
            stopSelf()
        }
    }

    private fun startForegroundWithNotification() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "WiFi Monitor",
                NotificationManager.IMPORTANCE_MIN
            )
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(channel)
        }

        val notification: Notification = Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("WiFi Monitor")
            .setContentText("Отображение статуса WiFi активно")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setOngoing(true)
            .build()

        startForeground(NOTIFICATION_ID, notification)
    }

    /** Реальный размер экрана в пикселях, чтобы ползунки позиции
     *  покрывали весь экран, а не только маленький угол. */
    private fun screenSize(): Pair<Int, Int> {
        val dm = android.util.DisplayMetrics()
        @Suppress("DEPRECATION")
        windowManager.defaultDisplay.getRealMetrics(dm)
        return dm.widthPixels to dm.heightPixels
    }

    private fun computeMaxOffsets(): Pair<Int, Int> {
        val (w, h) = screenSize()
        // Резервируем место под сам оверлей (иконка+текст), чтобы он не
        // вылезал за правый/нижний край экрана на 100%.
        val maxX = (w - 420).coerceAtLeast(50)
        val maxY = (h - 150).coerceAtLeast(50)
        return maxX to maxY
    }

    private fun setupOverlay() {
        container = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        wifiIcon = ImageView(this).apply {
            adjustViewBounds = true
            scaleType = ImageView.ScaleType.FIT_CENTER
        }

        wifiText = TextView(this).apply {
            setPadding(8, 0, 0, 0)
        }

        container.addView(wifiIcon)
        container.addView(wifiText)

        applyStatus(WifiStatus.UNKNOWN)

        val posX = prefs.getInt("pos_x", 50)
        val posY = prefs.getInt("pos_y", 50)
        val alpha = prefs.getInt("alpha", 200)
        val size = prefs.getInt("size", 30)
        val textPosition = prefs.getInt("text_position", 0) // 0 = right, 1 = bottom
        val fontSize = prefs.getInt("font_size", 100)

        params = WindowManager.LayoutParams().apply {
            type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            } else {
                @Suppress("DEPRECATION")
                WindowManager.LayoutParams.TYPE_SYSTEM_ALERT
            }
            format = android.graphics.PixelFormat.TRANSLUCENT
            flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
            width = WindowManager.LayoutParams.WRAP_CONTENT
            height = WindowManager.LayoutParams.WRAP_CONTENT
            gravity = Gravity.TOP or Gravity.START
        }

        val (maxX, maxY) = computeMaxOffsets()
        params.x = (posX / 100.0 * maxX).toInt()
        params.y = (posY / 100.0 * maxY).toInt()

        applyIconSize(size)
        updateContainerLayout(textPosition)
        updateFontSize(fontSize)
        updateAutoHideSetting(isAutoHideEnabled)

        container.alpha = alpha / 255f
        try {
            windowManager.addView(container, params)
            overlayAdded = true
        } catch (e: Exception) {
            e.printStackTrace()
            stopSelf()
        }
    }

    private fun applyIconSize(size: Int) {
        val px = (size * 2).coerceAtLeast(20)
        val lp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, px)
        wifiIcon.layoutParams = lp
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
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(broadcastReceiver, filter, Context.RECEIVER_EXPORTED)
        } else {
            registerReceiver(broadcastReceiver, filter)
        }
    }

    private fun checkCurrentStatus() {
        val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val activeNetwork = cm.activeNetwork
        val caps = cm.getNetworkCapabilities(activeNetwork)

        val newStatus = when {
            caps == null || !caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> WifiStatus.DISCONNECTED
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) -> WifiStatus.CONNECTED
            else -> WifiStatus.LIMITED
        }

        if (newStatus != currentStatus) {
            applyStatus(newStatus)
        }

        // На случай, если WifiManager к этому моменту уже успел вернуть SSID.
        if (newStatus != WifiStatus.DISCONNECTED) {
            val wm = getSystemService(Context.WIFI_SERVICE) as WifiManager
            updateWifiInfo(wm)
        }
    }

    private fun updateWifiInfo(wm: WifiManager) {
        try {
            val connectionInfo = wm.connectionInfo
            if (connectionInfo != null && connectionInfo.ssid != null) {
                var ssid = connectionInfo.ssid
                if (ssid.startsWith("\"") && ssid.endsWith("\"")) {
                    ssid = ssid.substring(1, ssid.length - 1)
                }
                if (ssid != "<unknown ssid>") {
                    currentSSID = ssid
                }
                updateTextDisplay()
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun updateTextDisplay() {
        wifiText.text = when (currentStatus) {
            WifiStatus.CONNECTED, WifiStatus.LIMITED ->
                if (currentSSID.isNotBlank()) currentSSID else "WiFi"
            WifiStatus.DISCONNECTED -> "No WiFi"
            WifiStatus.UNKNOWN -> "WiFi"
        }
    }

    /** Единая точка обновления иконки+цвета текста под 4 статуса сети,
     *  используя реальные ассеты приложения (зелёный/жёлтый/красный/серый). */
    private fun applyStatus(status: WifiStatus) {
        currentStatus = status
        val iconRes: Int
        val color: Int
        when (status) {
            WifiStatus.CONNECTED -> {
                iconRes = R.drawable.ic_wifi_green
                color = android.graphics.Color.parseColor("#00FF00")
            }
            WifiStatus.LIMITED -> {
                iconRes = R.drawable.ic_wifi_yellow
                color = android.graphics.Color.parseColor("#FFFF00")
            }
            WifiStatus.DISCONNECTED -> {
                iconRes = R.drawable.ic_wifi_red
                color = android.graphics.Color.parseColor("#FF0000")
            }
            WifiStatus.UNKNOWN -> {
                iconRes = R.drawable.ic_wifi_gray
                color = android.graphics.Color.parseColor("#888888")
            }
        }
        wifiIcon.setImageResource(iconRes)
        wifiText.setTextColor(color)
        updateTextDisplay()
        if (!manuallyHidden) {
            wifiIcon.visibility = android.view.View.VISIBLE
            wifiText.visibility = android.view.View.VISIBLE
        }
    }

    fun updatePosition(xPct: Int, yPct: Int, alpha: Int) {
        val (maxX, maxY) = computeMaxOffsets()
        params.x = (xPct / 100.0 * maxX).toInt()
        params.y = (yPct / 100.0 * maxY).toInt()
        wifiIcon.alpha = alpha / 255f
        wifiText.alpha = alpha / 255f
        if (overlayAdded) windowManager.updateViewLayout(container, params)

        if (isAutoHideEnabled) {
            hideHandler.removeCallbacks(hideRunnable)
            hideHandler.postDelayed(hideRunnable, 15000)
        }

        prefs.edit().apply {
            putInt("pos_x", xPct)
            putInt("pos_y", yPct)
            putInt("alpha", alpha)
            apply()
        }
    }

    fun updateSize(size: Int) {
        applyIconSize(size)
        if (overlayAdded) windowManager.updateViewLayout(container, params)

        prefs.edit().apply {
            putInt("size", size)
            apply()
        }
    }

    fun updateTextPosition(position: Int) {
        updateContainerLayout(position)
        if (overlayAdded) windowManager.updateViewLayout(container, params)

        prefs.edit().apply {
            putInt("text_position", position)
            apply()
        }
    }

    fun updateFontSizeMultiplier(percentage: Int) {
        updateFontSize(percentage)
        if (overlayAdded) windowManager.updateViewLayout(container, params)

        prefs.edit().apply {
            putInt("font_size", percentage)
            apply()
        }
    }

    fun updateAutoHideSetting(enabled: Boolean) {
        isAutoHideEnabled = enabled
        if (!enabled) {
            hideHandler.removeCallbacks(hideRunnable)
            if (!manuallyHidden) {
                wifiIcon.visibility = android.view.View.VISIBLE
                wifiText.visibility = android.view.View.VISIBLE
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_SHOW -> setOverlayVisible(true)
            ACTION_HIDE -> setOverlayVisible(false)
            ACTION_TOGGLE -> setOverlayVisible(!isOverlayCurrentlyVisible())
            ACTION_STOP -> stopOverlay()
        }
        return START_STICKY
    }

    private fun isOverlayCurrentlyVisible(): Boolean {
        return overlayAdded && wifiIcon.visibility == android.view.View.VISIBLE
    }

    private fun setOverlayVisible(visible: Boolean) {
        if (!overlayAdded) return
        manuallyHidden = !visible
        val v = if (visible) android.view.View.VISIBLE else android.view.View.GONE
        wifiIcon.visibility = v
        wifiText.visibility = v
    }

    /** Синхронно убирает overlay и останавливает сервис. Безопасно вызывать
     *  напрямую из MainActivity в том же процессе — надёжнее, чем
     *  асинхронный stopService() на некоторых кастомных прошивках. */
    fun stopOverlay() {
        try {
            if (overlayAdded) {
                windowManager.removeView(container)
                overlayAdded = false
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        stopSelf()
    }

    override fun onDestroy() {
        super.onDestroy()
        try {
            if (overlayAdded) {
                windowManager.removeView(container)
                overlayAdded = false
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        instance = null
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
