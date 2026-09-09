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
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
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
    private var currentSignalPercent = -1
    private var overlayAdded = false
    private var manuallyHidden = false
    private var settingsOpen = false
    private var currentStatus = WifiStatus.UNKNOWN
    private var networkCallback: ConnectivityManager.NetworkCallback? = null

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
            startPeriodicSelfCheck()
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
        val iconSize = prefs.getInt("icon_size", 60)
        val textSizeSp = prefs.getInt("text_size", 24)
        val textPosition = prefs.getInt("text_position", 0) // 0 = right, 1 = bottom
        isAutoHideEnabled = prefs.getBoolean("auto_hide", false)

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

        applyIconSize(iconSize)
        updateContainerLayout(textPosition)
        updateFontSize(textSizeSp)

        container.alpha = alpha / 255f
        try {
            windowManager.addView(container, params)
            overlayAdded = true
            scheduleAutoHide()
        } catch (e: Exception) {
            e.printStackTrace()
            stopSelf()
        }
    }

    /** Иконка и текст — независимые размеры, без взаимной привязки. */
    private fun applyIconSize(px: Int) {
        val h = px.coerceAtLeast(20)
        val lp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, h)
        wifiIcon.layoutParams = lp
    }

    private fun updateContainerLayout(position: Int) {
        container.orientation = if (position == 0) LinearLayout.HORIZONTAL else LinearLayout.VERTICAL
    }

    private fun updateFontSize(sp: Int) {
        wifiText.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp.coerceAtLeast(8).toFloat())
    }

    /** Единая точка планирования авто-скрытия: вызывается при КАЖДОМ
     *  показе оверлея (не только при движении ползунка позиции, как было
     *  раньше — из-за чего таймер часто вообще не запускался). */
    private fun scheduleAutoHide() {
        hideHandler.removeCallbacks(hideRunnable)
        if (isAutoHideEnabled && !manuallyHidden && !settingsOpen) {
            hideHandler.postDelayed(hideRunnable, 15000)
        }
    }

    /** Вызывается из MainActivity: пока экран настроек открыт (на переднем
     *  плане), таймер авто-скрытия приостановлен — удобнее подбирать
     *  позицию/размер, не отвлекаясь на пропадающую иконку. */
    fun setSettingsOpen(open: Boolean) {
        settingsOpen = open
        if (open) {
            hideHandler.removeCallbacks(hideRunnable)
            if (!manuallyHidden) {
                wifiIcon.visibility = android.view.View.VISIBLE
                wifiText.visibility = android.view.View.VISIBLE
            }
        } else {
            scheduleAutoHide()
        }
    }

    private fun startNetworkMonitoring() {
        val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val wm = getSystemService(Context.WIFI_SERVICE) as WifiManager

        // Старые sticky-broadcast'ы ловят момент ПОДКЛЮЧЕНИЯ, но не момент,
        // когда Android через пару секунд ЗАВЕРШАЕТ фоновую проверку
        // интернета на уже подключённой сети (NET_CAPABILITY_VALIDATED)
        // — из-за этого иконка застревала жёлтой навсегда. NetworkCallback
        // ловит именно это изменение через onCapabilitiesChanged.
        try {
            val request = NetworkRequest.Builder()
                .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                .build()
            val callback = object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    checkCurrentStatus()
                    updateWifiInfo(wm)
                }
                override fun onLost(network: Network) {
                    checkCurrentStatus()
                }
                override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
                    checkCurrentStatus()
                    updateWifiInfo(wm)
                }
            }
            networkCallback = callback
            cm.registerNetworkCallback(request, callback)
        } catch (e: Exception) {
            android.util.Log.e("WifiOverlayMod", "registerNetworkCallback failed", e)
        }

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

    /** Вызывается извне (из MainActivity) после того как пользователь мог
     *  выдать разрешение на геолокацию — событий подключения/отключения
     *  WiFi при этом не происходит, поэтому сервис сам не узнает,
     *  что теперь можно попробовать прочитать SSID ещё раз. */
    fun refreshStatus() {
        checkCurrentStatus()
    }

    /** Подстраховка на случай, если NetworkCallback/BroadcastReceiver по
     *  какой-то причине перестанут доставлять события (замечено на
     *  некоторых кастомных прошивках) — раз в минуту дополнительно
     *  перепроверяем статус вручную. */
    private fun startPeriodicSelfCheck() {
        val selfCheckHandler = Handler(Looper.getMainLooper())
        val selfCheckRunnable = object : Runnable {
            override fun run() {
                try {
                    checkCurrentStatus()
                } catch (e: Exception) {
                    android.util.Log.e("WifiOverlayMod", "Periodic self-check failed", e)
                }
                selfCheckHandler.postDelayed(this, 60_000)
            }
        }
        selfCheckHandler.postDelayed(selfCheckRunnable, 60_000)
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
            val rawSsid = connectionInfo?.ssid
            if (rawSsid != null) {
                var ssid = rawSsid.trim()
                if (ssid.startsWith("\"") && ssid.endsWith("\"") && ssid.length >= 2) {
                    ssid = ssid.substring(1, ssid.length - 1)
                }
                if (ssid.isNotBlank() && !ssid.equals("<unknown ssid>", ignoreCase = true) && ssid != "0x") {
                    if (ssid != currentSSID) {
                        android.util.Log.i("WifiOverlayMod", "SSID resolved: $ssid")
                    }
                    currentSSID = ssid
                } else {
                    android.util.Log.w("WifiOverlayMod", "SSID unavailable (raw='$rawSsid') — permission/location toggle likely missing")
                }

                try {
                    val rssi = connectionInfo.rssi
                    val level = WifiManager.calculateSignalLevel(rssi, 5) // 0..4
                    currentSignalPercent = (level * 100 / 4).coerceIn(0, 100)
                } catch (e: Exception) {
                    currentSignalPercent = -1
                }

                updateTextDisplay()
            }
        } catch (e: Exception) {
            android.util.Log.e("WifiOverlayMod", "updateWifiInfo failed", e)
        }
    }

    private fun updateTextDisplay() {
        wifiText.text = when (currentStatus) {
            WifiStatus.CONNECTED, WifiStatus.LIMITED -> {
                val name = if (currentSSID.isNotBlank()) currentSSID else "WiFi"
                if (currentSignalPercent >= 0) "$name  $currentSignalPercent%" else name
            }
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
            scheduleAutoHide()
        }
    }

    fun updatePosition(xPct: Int, yPct: Int, alpha: Int) {
        val (maxX, maxY) = computeMaxOffsets()
        params.x = (xPct / 100.0 * maxX).toInt()
        params.y = (yPct / 100.0 * maxY).toInt()
        wifiIcon.alpha = alpha / 255f
        wifiText.alpha = alpha / 255f
        if (overlayAdded) windowManager.updateViewLayout(container, params)

        prefs.edit().apply {
            putInt("pos_x", xPct)
            putInt("pos_y", yPct)
            putInt("alpha", alpha)
            apply()
        }
    }

    /** Размер иконки — независим от размера текста. */
    fun updateIconSize(px: Int) {
        applyIconSize(px)
        if (overlayAdded) windowManager.updateViewLayout(container, params)

        prefs.edit().apply {
            putInt("icon_size", px)
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

    /** Размер текста (SSID) в sp — независим от размера иконки. */
    fun updateTextSize(sp: Int) {
        updateFontSize(sp)
        if (overlayAdded) windowManager.updateViewLayout(container, params)

        prefs.edit().apply {
            putInt("text_size", sp)
            apply()
        }
    }

    fun updateAutoHideSetting(enabled: Boolean) {
        isAutoHideEnabled = enabled
        prefs.edit().putBoolean("auto_hide", enabled).apply()
        if (!enabled) {
            hideHandler.removeCallbacks(hideRunnable)
            if (!manuallyHidden) {
                wifiIcon.visibility = android.view.View.VISIBLE
                wifiText.visibility = android.view.View.VISIBLE
            }
        } else {
            // Включили галочку — запускаем отсчёт немедленно, а не ждём
            // следующего движения ползунка позиции (как было раньше).
            scheduleAutoHide()
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
        if (visible) scheduleAutoHide() else hideHandler.removeCallbacks(hideRunnable)
    }

    /** Синхронно убирает overlay и останавливает сервис. Безопасно вызывать
     *  напрямую из MainActivity в том же процессе — надёжнее, чем
     *  асинхронный stopService() на некоторых кастомных прошивках. */
    fun stopOverlay() {
        try {
            if (overlayAdded) {
                // На некоторых слабых GPU/композиторах (замечено на
                // Amlogic/Droidlogic прошивках) окно может визуально не
                // перерисоваться сразу после удаления — явно скрываем
                // содержимое и просим перерисовку перед removeView().
                wifiIcon.visibility = android.view.View.GONE
                wifiText.visibility = android.view.View.GONE
                container.invalidate()
                container.requestLayout()
                windowManager.removeView(container)
                overlayAdded = false
                android.util.Log.i("WifiOverlayMod", "Overlay removed via stopOverlay()")
            } else {
                android.util.Log.w("WifiOverlayMod", "stopOverlay() called but overlayAdded was already false")
            }
        } catch (e: Exception) {
            android.util.Log.e("WifiOverlayMod", "Error removing overlay in stopOverlay()", e)
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
        try {
            networkCallback?.let {
                (getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager).unregisterNetworkCallback(it)
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        instance = null
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
