package com.alglucomon.android

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.alglucomon.android.data.AppSettings
import com.alglucomon.android.data.BrightnessMode
import com.alglucomon.android.data.GlucoseStore
import com.alglucomon.android.data.SettingsRepository
import com.alglucomon.android.data.luxToBrightness
import com.alglucomon.android.overlay.OverlayService
import com.alglucomon.android.ui.DisplayScreen
import com.alglucomon.android.ui.SettingsScreen

class MainActivity : ComponentActivity(), SensorEventListener {

    companion object {
        const val EXTRA_OPEN_SETTINGS = "open_settings"

        fun createIntent(context: Context, openSettings: Boolean = false): Intent {
            return Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
                putExtra(EXTRA_OPEN_SETTINGS, openSettings)
            }
        }
    }

    private lateinit var sensorManager: SensorManager
    private var lightSensor: Sensor? = null
    private var smoothedLux: Float? = null

    private var showSettings by mutableStateOf(false)
    private var currentLux by mutableStateOf<Float?>(null)
    private var effectiveBrightness by mutableFloatStateOf(0.7f)
    private var canDrawOverlays by mutableStateOf(false)
    private var batteryIgnored by mutableStateOf(false)
    private var pendingStartOverlay = false

    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) {
        if (pendingStartOverlay && Settings.canDrawOverlays(this)) {
            pendingStartOverlay = false
            enableAndStartOverlay()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)

        sensorManager = getSystemService(Context.SENSOR_SERVICE) as SensorManager
        lightSensor = sensorManager.getDefaultSensor(Sensor.TYPE_LIGHT)

        val initial = SettingsRepository.settings.value
        showSettings = !initial.isSignedIn || intent.getBooleanExtra(EXTRA_OPEN_SETTINGS, false)
        refreshSystemStates()

        if (initial.overlayEnabled && Settings.canDrawOverlays(this)) {
            OverlayService.start(this)
        }

        setContent {
            val settings by SettingsRepository.settings.collectAsState()
            val glucoseState by GlucoseStore.state.collectAsState()

            LaunchedEffect(
                showSettings,
                settings.keepScreenOn,
                settings.brightnessMode,
                settings.manualBrightness,
                currentLux,
            ) {
                applyWindowBehavior(settings)
            }

            if (showSettings) {
                SettingsScreen(
                    settings = settings,
                    canDrawOverlays = canDrawOverlays,
                    isBatteryOptimizationIgnored = batteryIgnored,
                    currentLux = currentLux,
                    onBackToDisplay = { showSettings = false },
                    onToggleOverlay = { enable -> handleToggleOverlay(enable) },
                    onRequestOverlayPermission = { requestOverlayPermission() },
                    onRequestBatteryWhitelist = { requestBatteryWhitelist() },
                )
            } else {
                DisplayScreen(
                    settings = settings,
                    glucoseState = glucoseState,
                    currentLux = currentLux,
                    effectiveBrightness = effectiveBrightness,
                    onOpenSettings = { showSettings = true },
                    onSwitchToOverlay = { switchToFloatingOverlay() },
                    onToggleBrightnessMode = {
                        val nextMode = if (settings.brightnessMode == BrightnessMode.AUTO) {
                            BrightnessMode.MANUAL
                        } else {
                            BrightnessMode.AUTO
                        }
                        SettingsRepository.update { it.copy(brightnessMode = nextMode) }
                    },
                    onRefreshNow = { GlucoseStore.refreshNow() },
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (intent.getBooleanExtra(EXTRA_OPEN_SETTINGS, false)) {
            showSettings = true
        }
    }

    override fun onResume() {
        super.onResume()
        refreshSystemStates()
        lightSensor?.let {
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_NORMAL)
        }
        if (pendingStartOverlay && Settings.canDrawOverlays(this)) {
            pendingStartOverlay = false
            enableAndStartOverlay()
        }
    }

    override fun onPause() {
        super.onPause()
        sensorManager.unregisterListener(this)
    }

    override fun onSensorChanged(event: SensorEvent?) {
        if (event?.sensor?.type != Sensor.TYPE_LIGHT) return
        val rawLux = event.values.firstOrNull() ?: return
        val prev = smoothedLux
        val next = if (prev == null) rawLux else prev * 0.78f + rawLux * 0.22f
        smoothedLux = next
        currentLux = next
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

    private fun refreshSystemStates() {
        canDrawOverlays = Settings.canDrawOverlays(this)
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        batteryIgnored = pm.isIgnoringBatteryOptimizations(packageName)
    }

    private fun applyWindowBehavior(settings: AppSettings) {
        val controller = WindowInsetsControllerCompat(window, window.decorView)
        if (showSettings) {
            controller.show(WindowInsetsCompat.Type.systemBars())
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            val lp = window.attributes
            if (lp.screenBrightness != WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE) {
                lp.screenBrightness = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
                window.attributes = lp
            }
            return
        }

        controller.systemBarsBehavior =
            WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        controller.hide(WindowInsetsCompat.Type.systemBars())

        if (settings.keepScreenOn) {
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } else {
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }

        val target = when (settings.brightnessMode) {
            BrightnessMode.MANUAL -> settings.manualBrightness.coerceIn(0.03f, 1.0f)
            BrightnessMode.AUTO -> {
                val lux = currentLux
                if (lux != null) luxToBrightness(lux) else settings.manualBrightness.coerceIn(0.03f, 1.0f)
            }
        }
        effectiveBrightness = target
        val lp = window.attributes
        if (kotlin.math.abs(lp.screenBrightness - target) > 0.015f) {
            lp.screenBrightness = target
            window.attributes = lp
        }
    }

    private fun switchToFloatingOverlay() {
        if (!Settings.canDrawOverlays(this)) {
            pendingStartOverlay = true
            requestOverlayPermission()
            return
        }
        if (ensureNotificationPermission()) {
            enableAndStartOverlay()
            moveTaskToBack(true)
        } else {
            pendingStartOverlay = true
        }
    }

    private fun handleToggleOverlay(enable: Boolean) {
        if (!enable) {
            SettingsRepository.update { it.copy(overlayEnabled = false) }
            OverlayService.stop(this)
            return
        }
        if (!Settings.canDrawOverlays(this)) {
            pendingStartOverlay = true
            requestOverlayPermission()
            return
        }
        if (ensureNotificationPermission()) {
            enableAndStartOverlay()
        } else {
            pendingStartOverlay = true
        }
    }

    private fun enableAndStartOverlay() {
        SettingsRepository.update { it.copy(overlayEnabled = true) }
        OverlayService.start(this)
    }

    private fun ensureNotificationPermission(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return true
        val granted = ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.POST_NOTIFICATIONS,
        ) == PackageManager.PERMISSION_GRANTED
        if (!granted) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        return granted
    }

    private fun requestOverlayPermission() {
        val intent = Intent(
            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
            Uri.parse("package:$packageName"),
        )
        startActivity(intent)
    }

    private fun requestBatteryWhitelist() {
        val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
            data = Uri.parse("package:$packageName")
        }
        startActivity(intent)
    }
}
