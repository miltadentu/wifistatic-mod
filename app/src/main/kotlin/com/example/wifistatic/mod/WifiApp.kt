package com.example.wifistatic.mod

import android.app.Application
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Ловит ЛЮБОЙ необработанный краш (в любом компоненте — Service, Activity,
 * колбэках) и дописывает его в файл на внешнем хранилище приложения.
 *
 * Это нужно потому что стандартный `logcat -b crash` буфер маленький и на
 * нагруженной системе TV-box перезаписывается за секунды — поймать момент
 * краха вручную почти невозможно. Этот файл переживает любой краш и любой
 * перезапуск процесса, и его можно прочитать в любой момент:
 *
 *   su 0 sh -c "cat /sdcard/Android/data/com.example.wifistatic.mod/files/crash_log.txt"
 */
class WifiApp : Application() {

    override fun onCreate() {
        super.onCreate()
        val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                val sw = StringWriter()
                throwable.printStackTrace(PrintWriter(sw))
                val timestamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())
                val entry = "\n===== $timestamp (thread: ${thread.name}) =====\n$sw"

                val dir = getExternalFilesDir(null) ?: filesDir
                val file = File(dir, "crash_log.txt")
                // Не даём файлу расти бесконечно — держим последние ~200КБ.
                val existing = if (file.exists() && file.length() > 200_000) {
                    file.readText().takeLast(150_000)
                } else if (file.exists()) {
                    file.readText()
                } else {
                    ""
                }
                file.writeText(existing + entry)
            } catch (e: Exception) {
                // Если даже логирование краха не удалось — ничего не поделать,
                // просто передаём управление дальше.
            }
            defaultHandler?.uncaughtException(thread, throwable)
        }
    }
}
