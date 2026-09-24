package io.github.deniztopcuoglu.cornershot

import android.content.ComponentName
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.RadioGroup
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
        val settingsButton: Button = findViewById(R.id.accessibility_settings)
        val cornerGroup: RadioGroup = findViewById(R.id.corner_group)

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
        cornerGroup.setOnCheckedChangeListener { _, checkedId ->
            val corner = when (checkedId) {
                R.id.corner_top_left -> CaptureCorner.TOP_LEFT
                R.id.corner_top_right -> CaptureCorner.TOP_RIGHT
                R.id.corner_bottom_left -> CaptureCorner.BOTTOM_LEFT
                R.id.corner_bottom_right -> CaptureCorner.BOTTOM_RIGHT
                else -> return@setOnCheckedChangeListener
            }
            AppPreferences.setCaptureCorner(this, corner)
            if (AppPreferences.captureButtonEnabled(this) && isScreenshotServiceEnabled()) {
                sendBroadcast(InternalActions.intent(this, InternalActions.SHOW_OVERLAY))
            }
        }

        cornerGroup.check(when (AppPreferences.captureCorner(this)) {
            CaptureCorner.TOP_LEFT -> R.id.corner_top_left
            CaptureCorner.TOP_RIGHT -> R.id.corner_top_right
            CaptureCorner.BOTTOM_LEFT -> R.id.corner_bottom_left
            CaptureCorner.BOTTOM_RIGHT -> R.id.corner_bottom_right
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
        captureSwitch.isChecked = AppPreferences.captureButtonEnabled(this)
        refreshing = false
        serviceStatus.setText(if (isScreenshotServiceEnabled()) R.string.service_enabled else R.string.service_disabled)
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
