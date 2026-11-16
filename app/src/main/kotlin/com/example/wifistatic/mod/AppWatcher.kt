package com.example.wifistatic.mod

import android.app.AppOpsManager
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.Process

/**
 * Следит, какое приложение вышло на передний план (UsageStatsManager),
 * и просит сервис показать статус, если приложение выбрано в настройках.
 * Опрос раз в 1,5 с лёгкий: система просто отдаёт список последних событий.
 */
class AppWatcher(
    private val ctx: Context,
    private val packages: Set<String>,
    private val onForeground: () -> Unit
) {
    private val usm = ctx.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
    private val handler = Handler(Looper.getMainLooper())
    private var lastTs = System.currentTimeMillis()
    private var lastPkg: String? = null
    private var running = false

    private val tick = object : Runnable {
        override fun run() {
            if (!running) return
            try { poll() } catch (e: Exception) { e.printStackTrace() }
            handler.postDelayed(this, 1500L)
        }
    }

    fun start() {
        if (running) return
        running = true
        lastTs = System.currentTimeMillis()
        handler.postDelayed(tick, 1500L)
    }

    fun stop() {
        running = false
        handler.removeCallbacksAndMessages(null)
    }

    private fun poll() {
        val now = System.currentTimeMillis()
        val ev = usm.queryEvents(lastTs - 1000, now)
        val e = UsageEvents.Event()
        var fg: String? = null
        while (ev.hasNextEvent()) {
            ev.getNextEvent(e)
            if (e.eventType == 1) fg = e.packageName // MOVE_TO_FOREGROUND / ACTIVITY_RESUMED
        }
        lastTs = now
        if (fg != null && fg != lastPkg) {
            lastPkg = fg
            if (fg in packages) onForeground()
        }
    }

    companion object {
        fun hasUsageAccess(ctx: Context): Boolean {
            return try {
                val aom = ctx.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
                @Suppress("DEPRECATION")
                aom.checkOpNoThrow(
                    AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), ctx.packageName
                ) == AppOpsManager.MODE_ALLOWED
            } catch (e: Exception) {
                false
            }
        }
    }
}
