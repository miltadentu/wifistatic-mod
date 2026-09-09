package com.example.wifistatic.mod

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Settings

/**
 * Будильник вне процесса приложения. Даже если систему/прошивка убила наш
 * процесс целиком (что на некоторых Amlogic/Droidlogic TV-box прошивках
 * происходит несмотря на исключение из "оптимизации батареи" через
 * официальный Android API), AlarmManager всё равно разбудит именно этот
 * BroadcastReceiver в новом процессе — и мы сможем перезапустить сервис.
 */
class WatchdogReceiver : BroadcastReceiver() {

    companion object {
        private const val ACTION_WATCHDOG = "com.example.wifistatic.mod.ACTION_WATCHDOG"
        private const val INTERVAL_MS = 2 * 60 * 1000L // 2 минуты

        fun schedule(context: Context) {
            try {
                val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
                val intent = Intent(context, WatchdogReceiver::class.java).apply {
                    action = ACTION_WATCHDOG
                }
                val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                } else {
                    PendingIntent.FLAG_UPDATE_CURRENT
                }
                val pendingIntent = PendingIntent.getBroadcast(context, 0, intent, flags)
                val triggerAt = System.currentTimeMillis() + INTERVAL_MS
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pendingIntent)
                } else {
                    am.setExact(AlarmManager.RTC_WAKEUP, triggerAt, pendingIntent)
                }
            } catch (e: Exception) {
                android.util.Log.e("WifiOverlayMod", "WatchdogReceiver.schedule failed", e)
            }
        }

        /** Вызывается при явном нажатии "Закрыть" — иначе watchdog воскресит
         *  сервис через пару минут, хотя пользователь его сознательно закрыл. */
        fun cancel(context: Context) {
            try {
                val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
                val intent = Intent(context, WatchdogReceiver::class.java).apply {
                    action = ACTION_WATCHDOG
                }
                val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                } else {
                    PendingIntent.FLAG_UPDATE_CURRENT
                }
                val pendingIntent = PendingIntent.getBroadcast(context, 0, intent, flags)
                am.cancel(pendingIntent)
            } catch (e: Exception) {
                android.util.Log.e("WifiOverlayMod", "WatchdogReceiver.cancel failed", e)
            }
        }
    }

    override fun onReceive(context: Context, intent: Intent?) {
        try {
            android.util.Log.i("WifiOverlayMod", "Watchdog tick — service alive: ${WifiOverlayService.getInstance() != null}")

            val overlayOk = Build.VERSION.SDK_INT < Build.VERSION_CODES.M || Settings.canDrawOverlays(context)
            if (overlayOk && WifiOverlayService.getInstance() == null) {
                android.util.Log.w("WifiOverlayMod", "Watchdog: service is dead, restarting it")
                val serviceIntent = Intent(context, WifiOverlayService::class.java)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(serviceIntent)
                } else {
                    context.startService(serviceIntent)
                }
            }
        } catch (e: Exception) {
            android.util.Log.e("WifiOverlayMod", "WatchdogReceiver.onReceive failed", e)
        } finally {
            // Планируем следующую проверку — будильники одноразовые.
            schedule(context)
        }
    }
}
