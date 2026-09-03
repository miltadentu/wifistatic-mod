package com.example.wifistatic.mod

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.widget.Button
import android.widget.CheckBox
import android.widget.SeekBar
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

class MainActivity : AppCompatActivity() {

    private lateinit var sbX: SeekBar
    private lateinit var sbY: SeekBar
    private lateinit var sbAlpha: SeekBar
    private lateinit var sbTextPosition: SeekBar
    private lateinit var sbFontSize: SeekBar
    private lateinit var cbAutoHide: CheckBox
    private lateinit var tvTextPositionLabel: TextView
    private lateinit var tvFontSizeLabel: TextView
    private lateinit var btnSize20: Button
    private lateinit var btnSize30: Button
    private lateinit var btnSize40: Button
    private lateinit var btnClose: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        initViews()
        loadSettings()
        setupListeners()
        startWifiService()
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
        btnSize20 = findViewById(R.id.btnSize20)
        btnSize30 = findViewById(R.id.btnSize30)
        btnSize40 = findViewById(R.id.btnSize40)
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
        val service = WifiOverlayService.getInstance()

        sbX.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) {
                    service?.updatePosition(progress, sbY.progress, sbAlpha.progress)
                }
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })

        sbY.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) {
                    service?.updatePosition(sbX.progress, progress, sbAlpha.progress)
                }
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })

        sbAlpha.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) {
                    service?.updatePosition(sbX.progress, sbY.progress, progress)
                }
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })

        sbTextPosition.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) {
                    updateTextPositionLabel()
                    service?.updateTextPosition(progress)
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
                    service?.updateFontSizeMultiplier(progress)
                    prefs.edit().putInt("font_size", progress).apply()
                }
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })

        cbAutoHide.setOnCheckedChangeListener { _, isChecked ->
            service?.updateAutoHideSetting(isChecked)
            prefs.edit().putBoolean("auto_hide", isChecked).apply()
        }

        btnSize20.setOnClickListener {
            service?.updateSize(20)
            sbX.progress = 50
            sbY.progress = 50
        }

        btnSize30.setOnClickListener {
            service?.updateSize(30)
            sbX.progress = 50
            sbY.progress = 50
        }

        btnSize40.setOnClickListener {
            service?.updateSize(40)
            sbX.progress = 50
            sbY.progress = 50
        }

        btnClose.setOnClickListener {
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

    private fun startWifiService() {
        val intent = Intent(this, WifiOverlayService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        stopService(Intent(this, WifiOverlayService::class.java))
    }
}
