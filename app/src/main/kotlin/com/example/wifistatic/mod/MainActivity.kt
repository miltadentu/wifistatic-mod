package com.example.wifistatic.mod

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.LocationManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.widget.Button
import android.widget.CheckBox
import android.widget.SeekBar
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

class MainActivity : AppCompatActivity() {

    private lateinit var sbX: SeekBar
    private lateinit var sbY: SeekBar
    private lateinit var sbAlpha: SeekBar
    private lateinit var sbTextPosition: SeekBar
    private lateinit var sbFontSize: SeekBar
    private lateinit var cbAutoHide: CheckBox
    private lateinit var tvTextPositionLabel: TextView
    private lateinit var tvFontSizeLabel: TextView
    private lateinit var tvPermissionsStatus: TextView
    private lateinit var btnSize20: Button
    private lateinit var btnSize30: Button
    private lateinit var btnSize40: Button
    private lateinit var btnMinimize: Button
    private lateinit var btnClose: Button

    companion object {
        private const val REQ_LOCATION_PERM = 501
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        initViews()
        loadSettings()
        setupListeners()
    }

    private fun initViews() {
        sbX = findViewById(R.id.sbX)
        sbY = findViewById(R.id.sbY)
        sbAlpha = findViewById(R.id.sbAlpha)
        sbTextPosition = findViewById(R.id.sbTextPosition)
        sbFontSize = findViewById(R.id.sbFontSize)
        cbAutoHide = findViewById(R.id.cbAutoHide)
        tvTextPositionLabel = findViewById(R.id.tvTextPositionLabel)
        tvFontSizeLabel = findViewById(R.id.tvFontSizeLabel)
        tvPermissionsStatus = findViewById(R.id.tvPermissionsStatus)
        btnSize20 = findViewById(R.id.btnSize20)
        btnSize30 = findViewById(R.id.btnSize30)
        btnSize40 = findViewById(R.id.btnSize40)
        btnMinimize = findViewById(R.id.btnMinimize)
        btnClose = findViewById(R.id.btnClose)
    }

    private fun loadSettings() {
        val prefs = getSharedPreferences("wifi_prefs", Context.MODE_PRIVATE)
        sbX.progress = prefs.getInt("pos_x", 50)
        sbY.progress = prefs.getInt("pos_y", 50)
        sbAlpha.progress = prefs.getInt("alpha", 200)
        sbTextPosition.progress = prefs.getInt("text_position", 0)
        sbFontSize.progress = prefs.getInt("font_size", 100)
        cbAutoHide.isChecked = prefs.getBoolean("auto_hide", false)

        updateTextPositionLabel()
        updateFontSizeLabel()
    }

    private fun setupListeners() {
        val prefs = getSharedPreferences("wifi_prefs", Context.MODE_PRIVATE)

        sbX.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) {
                    WifiOverlayService.getInstance()?.updatePosition(progress, sbY.progress, sbAlpha.progress)
                }
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })

        sbY.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) {
                    WifiOverlayService.getInstance()?.updatePosition(sbX.progress, progress, sbAlpha.progress)
                }
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })

        sbAlpha.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) {
                    WifiOverlayService.getInstance()?.updatePosition(sbX.progress, sbY.progress, progress)
                }
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })

        sbTextPosition.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) {
                    updateTextPositionLabel()
                    WifiOverlayService.getInstance()?.updateTextPosition(progress)
                    prefs.edit().putInt("text_position", progress).apply()
                }
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })

        sbFontSize.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) {
                    updateFontSizeLabel()
                    WifiOverlayService.getInstance()?.updateFontSizeMultiplier(progress)
                    prefs.edit().putInt("font_size", progress).apply()
                }
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })

        cbAutoHide.setOnCheckedChangeListener { _, isChecked ->
            WifiOverlayService.getInstance()?.updateAutoHideSetting(isChecked)
            prefs.edit().putBoolean("auto_hide", isChecked).apply()
        }

        btnSize20.setOnClickListener {
            WifiOverlayService.getInstance()?.updateSize(20)
            sbX.progress = 50
            sbY.progress = 50
        }

        btnSize30.setOnClickListener {
            WifiOverlayService.getInstance()?.updateSize(30)
            sbX.progress = 50
            sbY.progress = 50
        }

        btnSize40.setOnClickListener {
            WifiOverlayService.getInstance()?.updateSize(40)
            sbX.progress = 50
            sbY.progress = 50
        }

        // Свернуть — просто закрывает окно настроек, оверлей продолжает работать.
        btnMinimize.setOnClickListener {
            finish()
        }

        // Закрыть — полностью останавливает оверлей и сервис.
        btnClose.setOnClickListener {
            WifiOverlayService.getInstance()?.stopOverlay()
            stopService(Intent(this, WifiOverlayService::class.java))
            finish()
        }
    }

    private fun updateTextPositionLabel() {
        tvTextPositionLabel.text = if (sbTextPosition.progress == 0) "Справа" else "Снизу"
    }

    private fun updateFontSizeLabel() {
        tvFontSizeLabel.text = "${sbFontSize.progress}%"
    }

    // ---------------------------------------------------------------
    // Цепочка проверки разрешений. Вызывается при каждом запуске/возврате
    // в приложение (onResume), по одному шагу за раз: если чего-то не
    // хватает — открывает нужный экран настроек и останавливается; при
    // следующем onResume (когда пользователь вернётся) проверяет дальше.
    // ---------------------------------------------------------------
    private fun ensurePermissionsThenStartService() {
        // 1. Разрешение "поверх других приложений" — без него сервис не стартует.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(this)) {
            updatePermissionsStatus()
            try {
                startActivity(
                    Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))
                )
            } catch (e: Exception) {
                e.printStackTrace()
            }
            return
        }

        // Запускаем сервис сразу, как только это возможно — иконка появится,
        // даже если геолокация/батарея ещё не настроены.
        if (WifiOverlayService.getInstance() == null) {
            startWifiService()
        }

        // 2. Разрешение на геолокацию — без него Android не отдаёт SSID сети.
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
            != PackageManager.PERMISSION_GRANTED
        ) {
            updatePermissionsStatus()
            ActivityCompat.requestPermissions(
                this, arrayOf(Manifest.permission.ACCESS_FINE_LOCATION), REQ_LOCATION_PERM
            )
            return
        }

        // 3. Системный тумблер "Геолокация" — даже с разрешением, если он
        // выключен целиком, SSID не отдаётся.
        if (!isLocationServiceEnabled()) {
            updatePermissionsStatus()
            try {
                startActivity(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS))
            } catch (e: Exception) {
                e.printStackTrace()
            }
            return
        }

        // 4. Исключение из оптимизации батареи — чтобы система не убивала сервис.
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !pm.isIgnoringBatteryOptimizations(packageName)) {
            updatePermissionsStatus()
            try {
                startActivity(
                    Intent(
                        Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                        Uri.parse("package:$packageName")
                    )
                )
            } catch (e: Exception) {
                try {
                    startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
                } catch (e2: Exception) {
                    e2.printStackTrace()
                }
            }
            return
        }

        updatePermissionsStatus()
    }

    private fun isLocationServiceEnabled(): Boolean {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                val lm = getSystemService(Context.LOCATION_SERVICE) as LocationManager
                lm.isLocationEnabled
            } else {
                val mode = Settings.Secure.getInt(
                    contentResolver, Settings.Secure.LOCATION_MODE, Settings.Secure.LOCATION_MODE_OFF
                )
                mode != Settings.Secure.LOCATION_MODE_OFF
            }
        } catch (e: Exception) {
            true
        }
    }

    private fun updatePermissionsStatus() {
        val overlayOk = Build.VERSION.SDK_INT < Build.VERSION_CODES.M || Settings.canDrawOverlays(this)
        val locPermOk = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) ==
                PackageManager.PERMISSION_GRANTED
        val locServiceOk = isLocationServiceEnabled()
        val batteryOk = Build.VERSION.SDK_INT < Build.VERSION_CODES.M ||
                (getSystemService(Context.POWER_SERVICE) as PowerManager).isIgnoringBatteryOptimizations(packageName)

        fun mark(ok: Boolean) = if (ok) "\u2713" else "\u2717"
        tvPermissionsStatus.text = "Overlay ${mark(overlayOk)}  Геолокация ${mark(locPermOk && locServiceOk)}  Батарея ${mark(batteryOk)}"
    }

    private fun startWifiService() {
        val intent = Intent(this, WifiOverlayService::class.java)
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(intent)
            } else {
                startService(intent)
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        // Независимо от результата продолжаем цепочку проверок дальше —
        // если отказали, при следующем открытии приложения спросим снова.
        ensurePermissionsThenStartService()
    }

    override fun onResume() {
        super.onResume()
        try {
            ensurePermissionsThenStartService()
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    // Специально НЕ останавливаем сервис в onDestroy — оверлей должен жить
    // независимо от того, закрыт ли экран настроек (через back, сворачивание
    // и т.д.). Полная остановка — только через явную кнопку "Закрыть".
}
