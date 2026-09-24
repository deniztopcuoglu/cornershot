package io.github.deniztopcuoglu.cornershot

import android.content.ComponentName
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextView
import android.view.View
import androidx.activity.ComponentActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat

class MainActivity : ComponentActivity() {
    private lateinit var captureSwitch: Switch
    private lateinit var serviceStatus: TextView
    private lateinit var buttonSizeSeekBar: SeekBar
    private lateinit var buttonSizeValue: TextView
    private var refreshing = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        setContentView(R.layout.activity_main)
        val content = findViewById<View>(android.R.id.content)
        ViewCompat.setOnApplyWindowInsetsListener(content) { view, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }
        ViewCompat.requestApplyInsets(content)
        captureSwitch = findViewById(R.id.capture_switch)
        serviceStatus = findViewById(R.id.service_status)
        buttonSizeSeekBar = findViewById(R.id.button_size_seekbar)
        buttonSizeValue = findViewById(R.id.button_size_value)
        val settingsButton: Button = findViewById(R.id.accessibility_settings)

        captureSwitch.setOnCheckedChangeListener { _, enabled ->
            if (refreshing) return@setOnCheckedChangeListener
            AppPreferences.setCaptureButtonEnabled(this, enabled)
            if (!enabled) {
                sendBroadcast(InternalActions.intent(this, InternalActions.HIDE_OVERLAY))
            } else if (isScreenshotServiceEnabled()) {
                sendBroadcast(InternalActions.intent(this, InternalActions.SHOW_OVERLAY))
            } else {
                openAccessibilitySettings()
            }
            refreshUi()
        }

        settingsButton.setOnClickListener { openAccessibilitySettings() }
        listOf(
            R.id.corner_top_left to CaptureCorner.TOP_LEFT,
            R.id.corner_top_right to CaptureCorner.TOP_RIGHT,
            R.id.corner_bottom_left to CaptureCorner.BOTTOM_LEFT,
            R.id.corner_bottom_right to CaptureCorner.BOTTOM_RIGHT
        ).forEach { (buttonId, corner) ->
            findViewById<Button>(buttonId).setOnClickListener {
                AppPreferences.setOverlayPositionPreset(this, corner)
                if (AppPreferences.captureButtonEnabled(this) && isScreenshotServiceEnabled()) {
                    sendBroadcast(InternalActions.intent(this, InternalActions.SHOW_OVERLAY))
                }
            }
        }

        buttonSizeSeekBar.max =
            (ButtonSizeGeometry.MAX_VISIBLE_DP - ButtonSizeGeometry.MIN_VISIBLE_DP) / ButtonSizeGeometry.STEP_DP
        buttonSizeSeekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
                val sizeDp = ButtonSizeGeometry.MIN_VISIBLE_DP + progress * ButtonSizeGeometry.STEP_DP
                buttonSizeValue.text = getString(R.string.button_size_value, sizeDp)
                if (!fromUser) return
                AppPreferences.setCaptureButtonSizeDp(this@MainActivity, sizeDp)
                if (AppPreferences.captureButtonEnabled(this@MainActivity)) {
                    sendBroadcast(InternalActions.intent(this@MainActivity, InternalActions.SHOW_OVERLAY))
                }
            }

            override fun onStartTrackingTouch(seekBar: SeekBar) = Unit
            override fun onStopTrackingTouch(seekBar: SeekBar) = Unit
        })

        refreshUi()
    }

    override fun onResume() {
        super.onResume()
        refreshUi()
        if (AppPreferences.captureButtonEnabled(this) && isScreenshotServiceEnabled()) {
            sendBroadcast(InternalActions.intent(this, InternalActions.SHOW_OVERLAY))
        }
    }

    private fun refreshUi() {
        refreshing = true
        try {
            captureSwitch.isChecked = AppPreferences.captureButtonEnabled(this)
        } finally {
            refreshing = false
        }
        serviceStatus.setText(if (isScreenshotServiceEnabled()) R.string.service_enabled else R.string.service_disabled)
        val sizeDp = AppPreferences.captureButtonSizeDp(this)
        buttonSizeSeekBar.progress =
            (sizeDp - ButtonSizeGeometry.MIN_VISIBLE_DP) / ButtonSizeGeometry.STEP_DP
        buttonSizeValue.text = getString(R.string.button_size_value, sizeDp)
    }

    private fun isScreenshotServiceEnabled(): Boolean {
        val expected = ComponentName(this, ScreenshotAccessibilityService::class.java)
        val enabled = Settings.Secure.getString(contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
            ?: return false
        return enabled.split(':').any { ComponentName.unflattenFromString(it) == expected }
    }

    private fun openAccessibilitySettings() {
        runCatching { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
    }
}
