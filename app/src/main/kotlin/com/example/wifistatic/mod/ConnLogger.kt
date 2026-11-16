package com.example.wifistatic.mod

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiManager
import android.os.Handler
import android.os.Looper
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

/**
 * Журнал подключений WiFi и доступности интернета.
 *
 * Нагрузка минимальна: события подключения/отключения приходят от системы
 * (NetworkCallback), включая смену состояния «интернет проверен системой»
 * (NET_CAPABILITY_VALIDATED). Раз в минуту добавляется одна крошечная
 * проверка (запрос generate_204, ~200 байт) — на случай, если система
 * сама не заметила пропажу интернета. Внешний IP запрашивается только
 * при подключении.
 */
class ConnLogger(private val ctx: Context, private val path: String) {

    private val cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    private val wm = ctx.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
    private val main = Handler(Looper.getMainLooper())
    private val io = Executors.newSingleThreadExecutor()

    private fun runIo(r: () -> Unit) {
        try { io.execute { try { r() } catch (_: Exception) {} } } catch (_: Exception) {}
    }

    private var cb: ConnectivityManager.NetworkCallback? = null
    private var current: Network? = null
    private var currentName = ""
    private var internetOk: Boolean? = null
    private var epoch = 0 // увеличивается при каждой новой сети — отменяет устаревшие проверки
    private var lastValidated: Boolean? = null
    @Volatile private var stopped = false

    private val minuteTick = object : Runnable {
        override fun run() {
            if (stopped) return
            current?.let { scheduleProbe(it, epoch, 0) }
            main.postDelayed(this, 60_000L)
        }
    }

    fun start() {
        stopped = false
        try {
            val req = NetworkRequest.Builder()
                .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                .build()
            val callback = object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    main.post { onWifiAvailable(network) }
                }

                override fun onLost(network: Network) {
                    main.post { onWifiLost(network) }
                }

                override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
                    val validated = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
                    main.post {
                        if (network == current && lastValidated != null && lastValidated != validated) {
                            // Система сама сообщила об изменении — проверяем сразу.
                            scheduleProbe(network, epoch, 2000L)
                        }
                        if (network == current) lastValidated = validated
                    }
                }
            }
            cb = callback
            cm.registerNetworkCallback(req, callback)
        } catch (e: Exception) {
            e.printStackTrace()
        }
        main.removeCallbacks(minuteTick)
        main.postDelayed(minuteTick, 60_000L)
    }

    fun stop() {
        stopped = true
        main.removeCallbacksAndMessages(null)
        try { cb?.let { cm.unregisterNetworkCallback(it) } } catch (_: Exception) {}
        cb = null
        io.shutdown()
        io2.shutdown()
    }

    // ---------- события ----------

    private fun onWifiAvailable(network: Network) {
        if (network == current) return
        // Переключение сети без события onLost для старой.
        if (current != null) {
            log("Отключено от $currentName")
        }
        epoch++
        current = network
        internetOk = null
        lastValidated = null
        currentName = ssidNow()
        val e = epoch
        // SSID иногда становится известен с небольшой задержкой, интернет — тоже.
        scheduleConnectCheck(network, e, 0, 3000L)
    }

    private fun onWifiLost(network: Network) {
        if (network != current) return
        log("Отключено от $currentName")
        current = null
        currentName = ""
        internetOk = null
        epoch++
    }

    private fun scheduleConnectCheck(network: Network, e: Int, attempt: Int, delay: Long) {
        main.postDelayed({
            if (stopped || e != epoch || network != current) return@postDelayed
            runIo {
                val ok = probe(network)
                main.post {
                    if (stopped || e != epoch || network != current) return@post
                    if (ok || attempt >= 3) {
                        if (currentName.isEmpty() || currentName == "WiFi") currentName = ssidNow()
                        val ip = localIp(network)
                        if (ok) {
                            runIo {
                                val ext = externalIp(network) ?: "внешний IP не определён"
                                main.post {
                                    if (e == epoch) {
                                        internetOk = true
                                        log("Подключено к $currentName $ip - $ext")
                                    }
                                }
                            }
                        } else {
                            internetOk = false
                            log("Подключено к $currentName $ip - нет интернета")
                        }
                    } else {
                        scheduleConnectCheck(network, e, attempt + 1, 5000L)
                    }
                }
            }
        }, delay)
    }

    /** Периодическая проверка после подключения: пропал/появился интернет. */
    private fun scheduleProbe(network: Network, e: Int, delay: Long) {
        if (internetOk == null) return // начальная проверка подключения ещё идёт
        main.postDelayed({
            if (stopped || e != epoch || network != current) return@postDelayed
            runIo {
                var ok = probe(network)
                if (!ok) { // защита от единичного сбоя: повтор через 3 секунды
                    try { Thread.sleep(3000) } catch (_: InterruptedException) {}
                    ok = probe(network)
                }
                main.post {
                    if (stopped || e != epoch || network != current) return@post
                    val prev = internetOk
                    if (prev != null && prev != ok) {
                        internetOk = ok
                        log(if (ok) "Интернет появился $currentName" else "Пропал интернет $currentName")
                    }
                }
            }
        }, delay)
    }

    // ---------- сетевые проверки ----------

    /**
     * Реальная проверка интернета через именно эту WiFi-сеть:
     * 1) HTTPS generate_204 (gstatic, cloudflare) — ждём ответ 204;
     * 2) HTTPS captive.apple.com — ждём 200;
     * 3) запасной вариант: TCP-соединение по IP (1.1.1.1:443, 8.8.8.8:443),
     *    если DNS или конкретный сайт недоступны.
     * (Обычный http:// на Android 9+ запрещён для приложений targetSdk 28+.)
     */
    private fun probe(network: Network): Boolean {
        val https = listOf(
            "https://connectivitycheck.gstatic.com/generate_204" to 204,
            "https://cp.cloudflare.com/generate_204" to 204,
            "https://captive.apple.com/hotspot-detect.html" to 200
        )
        for ((u, want) in https) {
            try {
                val c = network.openConnection(URL(u)) as HttpURLConnection
                c.connectTimeout = 4000
                c.readTimeout = 4000
                c.instanceFollowRedirects = false
                c.useCaches = false
                val code = c.responseCode
                c.disconnect()
                if (code == want) { lastProbeInfo("HTTPS ok: ${URL(u).host}"); return true }
            } catch (_: Exception) {
            }
        }
        for ((ip, port) in listOf("1.1.1.1" to 443, "8.8.8.8" to 443)) {
            try {
                val sock = network.socketFactory.createSocket()
                sock.connect(java.net.InetSocketAddress(ip, port), 4000)
                sock.close()
                lastProbeInfo("TCP ok: $ip")
                return true
            } catch (_: Exception) {
            }
        }
        lastProbeInfo("все проверки не прошли")
        return false
    }

    private fun lastProbeInfo(text: String) {
        try {
            val t = SimpleDateFormat("HH:mm:ss", Locale.US).format(Date())
            ctx.getSharedPreferences("wifi_prefs", Context.MODE_PRIVATE).edit()
                .putString("log_probe_info", "$t $text").apply()
        } catch (_: Exception) {}
    }

    private fun externalIp(network: Network): String? {
        for (u in listOf("https://api.ipify.org", "https://icanhazip.com")) {
            try {
                val c = network.openConnection(URL(u)) as HttpURLConnection
                c.connectTimeout = 5000
                c.readTimeout = 5000
                val s = c.inputStream.bufferedReader().readText().trim()
                c.disconnect()
                if (s.length in 7..45 && !s.contains(' ')) return s
            } catch (_: Exception) {
            }
        }
        return null
    }

    @Suppress("DEPRECATION")
    private fun localIp(network: Network): String {
        try {
            val lp = cm.getLinkProperties(network)
            lp?.linkAddresses?.firstOrNull { it.address is java.net.Inet4Address }?.let {
                return it.address.hostAddress ?: ""
            }
        } catch (_: Exception) {}
        try {
            val ip = wm.connectionInfo.ipAddress
            if (ip != 0) return "${ip and 0xff}.${ip shr 8 and 0xff}.${ip shr 16 and 0xff}.${ip shr 24 and 0xff}"
        } catch (_: Exception) {}
        return "IP неизвестен"
    }

    @Suppress("DEPRECATION")
    private fun ssidNow(): String {
        return try {
            val s = wm.connectionInfo?.ssid?.trim('"') ?: ""
            if (s.isEmpty() || s == "<unknown ssid>") "WiFi" else s
        } catch (_: Exception) {
            "WiFi"
        }
    }

    // ---------- запись в файл ----------

    private val days = arrayOf("Вс", "Пн", "Вт", "Ср", "Чт", "Пт", "Сб")

    private fun log(text: String) {
        val now = Date()
        val cal = Calendar.getInstance().apply { time = now }
        val day = days[cal.get(Calendar.DAY_OF_WEEK) - 1]
        val t = SimpleDateFormat("HH:mm:ss", Locale.US).format(now)
        val d = SimpleDateFormat("dd.MM.yyyy", Locale.US).format(now)
        val line = "$t  $d $day - $text\n"
        val prefs = ctx.getSharedPreferences("wifi_prefs", Context.MODE_PRIVATE)
        try {
            io2.execute {
                try {
                    val f = File(path)
                    f.parentFile?.mkdirs()
                    FileOutputStream(f, true).use { it.write(line.toByteArray(Charsets.UTF_8)) }
                    prefs.edit().putString("log_error", "").apply()
                } catch (e: Exception) {
                    prefs.edit().putString("log_error", "Не могу писать в $path: ${e.message}").apply()
                }
            }
        } catch (_: Exception) {}
    }

    private val io2 = Executors.newSingleThreadExecutor()
}
