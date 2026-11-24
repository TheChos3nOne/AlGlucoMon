package com.alglucomon.android.overlay

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.os.Build
import android.os.IBinder
import android.provider.Settings
import android.view.Gravity
import android.view.MotionEvent
import android.view.ViewConfiguration
import android.view.WindowManager
import android.widget.FrameLayout
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.core.graphics.drawable.IconCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import com.alglucomon.android.MainActivity
import com.alglucomon.android.R
import com.alglucomon.android.data.AppSettings
import com.alglucomon.android.data.GlucoseStore
import com.alglucomon.android.data.GlucoseUiState
import com.alglucomon.android.data.GraphStyle
import com.alglucomon.android.data.SettingsRepository
import com.alglucomon.android.data.formatAge
import com.alglucomon.android.data.formatDelta
import com.alglucomon.android.data.formatGlucose
import com.alglucomon.android.data.getGlucoseStatus
import com.alglucomon.android.data.statusColor
import com.alglucomon.android.ui.OverlayCardContent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import kotlin.math.hypot
import kotlin.math.roundToInt

class OverlayService : Service(), LifecycleOwner, SavedStateRegistryOwner {

    companion object {
        private const val CHANNEL_ID = "alglucomon_overlay"
        private const val NOTIFICATION_ID = 7001
        private const val ACTION_TOGGLE_LOCK = "com.alglucomon.android.ACTION_TOGGLE_LOCK"
        private const val ACTION_HIDE_OVERLAY = "com.alglucomon.android.ACTION_HIDE_OVERLAY"

        @Volatile
        var isRunning: Boolean = false
            private set

        fun start(context: Context) {
            if (!Settings.canDrawOverlays(context)) return
            val intent = Intent(context, OverlayService::class.java)
            ContextCompat.startForegroundService(context, intent)
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, OverlayService::class.java))
        }
    }

    private val lifecycleRegistry = LifecycleRegistry(this)
    private val savedStateController = SavedStateRegistryController.create(this)
    override val lifecycle: Lifecycle get() = lifecycleRegistry
    override val savedStateRegistry: SavedStateRegistry get() = savedStateController.savedStateRegistry

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var windowManager: WindowManager
    private lateinit var notificationManager: NotificationManager
    private lateinit var windowParams: WindowManager.LayoutParams
    private var rootContainer: DraggableContainer? = null
    private var inspectMode by mutableStateOf(false)

    override fun onCreate() {
        super.onCreate()
        isRunning = true
        SettingsRepository.init(this)
        GlucoseStore.start()

        savedStateController.performRestore(null)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_CREATE)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_START)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_RESUME)

        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        ensureNotificationChannel()

        val initialSettings = SettingsRepository.settings.value
        val initialNotif = buildNotification(initialSettings, GlucoseStore.state.value)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                NOTIFICATION_ID,
                initialNotif,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
            )
        } else {
            startForeground(NOTIFICATION_ID, initialNotif)
        }

        attachOverlayView(initialSettings)

        serviceScope.launch {
            combine(SettingsRepository.settings, GlucoseStore.state) { s, g -> s to g }
                .collect { (settings, glucose) ->
                    updateLayoutFromSettings(settings)
                    if (settings.clickThrough) inspectMode = false
                    notificationManager.notify(NOTIFICATION_ID, buildNotification(settings, glucose))
                }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_TOGGLE_LOCK -> {
                SettingsRepository.update {
                    if (!it.isSignedIn) it else it.copy(clickThrough = !it.clickThrough)
                }
            }
            ACTION_HIDE_OVERLAY -> {
                SettingsRepository.update { it.copy(overlayEnabled = false) }
                stopSelf()
                return START_NOT_STICKY
            }
        }
        return START_STICKY
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        updateLayoutFromSettings(SettingsRepository.settings.value)
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        isRunning = false
        serviceScope.cancel()
        rootContainer?.let {
            try {
                windowManager.removeView(it)
            } catch (_: Exception) {
            }
        }
        rootContainer = null
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_PAUSE)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_STOP)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_DESTROY)
        super.onDestroy()
    }

    private fun dpToPx(dp: Int): Int = (dp * resources.displayMetrics.density).roundToInt()

    private fun isLandscape(): Boolean =
        resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE

    private fun attachOverlayView(settings: AppSettings) {
        val (initX, initY) = resolvedPosition(settings)
        val flags = baseFlags(settings.clickThrough && settings.isSignedIn)
        windowParams = WindowManager.LayoutParams(
            dpToPx(settings.overlaySize.widthDp),
            dpToPx(settings.overlaySize.heightDp),
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            flags,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = initX
            y = initY
        }

        val container = DraggableContainer(this).apply {
            setViewTreeLifecycleOwner(this@OverlayService)
            setViewTreeSavedStateRegistryOwner(this@OverlayService)
            val composeView = ComposeView(this@OverlayService).apply {
                setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
                setContent {
                    val s by SettingsRepository.settings.collectAsState()
                    val g by GlucoseStore.state.collectAsState()
                    OverlayCardContent(
                        settings = s,
                        glucoseState = g,
                        inspectMode = inspectMode,
                        onExitInspect = { inspectMode = false },
                        onOpenApp = { openMainApp(openSettings = !s.isSignedIn) },
                    )
                }
            }
            addView(composeView)
        }

        rootContainer = container
        windowManager.addView(container, windowParams)
    }

    private fun resolvedPosition(settings: AppSettings): Pair<Int, Int> {
        val landscape = isLandscape()
        val savedX = if (landscape) settings.overlayLandscapeX else settings.overlayPortraitX
        val savedY = if (landscape) settings.overlayLandscapeY else settings.overlayPortraitY
        val screenW = resources.displayMetrics.widthPixels
        val screenH = resources.displayMetrics.heightPixels
        val cardW = dpToPx(settings.overlaySize.widthDp)
        val cardH = dpToPx(settings.overlaySize.heightDp)

        if (savedX >= 0 && savedY >= 0) {
            return savedX.coerceIn(0, (screenW - cardW).coerceAtLeast(0)) to
                savedY.coerceIn(0, (screenH - cardH).coerceAtLeast(0))
        }
        val margin = dpToPx(18)
        return (screenW - cardW - margin).coerceAtLeast(0) to dpToPx(48)
    }

    private fun baseFlags(clickThrough: Boolean): Int {
        var flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
        if (clickThrough) {
            flags = flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        }
        return flags
    }

    private fun updateLayoutFromSettings(settings: AppSettings) {
        val container = rootContainer ?: return
        val w = dpToPx(settings.overlaySize.widthDp)
        val h = dpToPx(settings.overlaySize.heightDp)
        val flags = baseFlags(settings.clickThrough && settings.isSignedIn)
        val (rx, ry) = resolvedPosition(settings)

        var changed = false
        if (windowParams.width != w || windowParams.height != h) {
            windowParams.width = w
            windowParams.height = h
            changed = true
        }
        if (windowParams.flags != flags) {
            windowParams.flags = flags
            changed = true
        }
        if (windowParams.x != rx || windowParams.y != ry) {
            windowParams.x = rx
            windowParams.y = ry
            changed = true
        }
        if (changed) {
            try {
                windowManager.updateViewLayout(container, windowParams)
            } catch (_: Exception) {
            }
        }
    }

    private fun saveDraggedPosition(x: Int, y: Int) {
        val landscape = isLandscape()
        SettingsRepository.update {
            if (landscape) it.copy(overlayLandscapeX = x, overlayLandscapeY = y)
            else it.copy(overlayPortraitX = x, overlayPortraitY = y)
        }
    }

    private fun openMainApp(openSettings: Boolean) {
        startActivity(MainActivity.createIntent(this, openSettings = openSettings))
    }

    private fun ensureNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "AlGlucoMon Overlay",
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = "Displays live Dexcom G7 readings and quick overlay controls"
            setShowBadge(false)
        }
        notificationManager.createNotificationChannel(channel)
    }

    private fun buildNotification(settings: AppSettings, glucose: GlucoseUiState): Notification {
        val latest = glucose.readings.lastOrNull()
        val prev = if (glucose.readings.size >= 2) glucose.readings[glucose.readings.size - 2] else null

        val title = if (latest != null) {
            val valStr = formatGlucose(latest.value, settings.unit)
            val deltaStr = prev?.let { " (${formatDelta(latest.value - it.value, settings.unit)})" } ?: ""
            val (ageLabel, _) = formatAge(latest.timestamp, System.currentTimeMillis())
            "$valStr ${settings.unit.id}$deltaStr • $ageLabel"
        } else if (!settings.isSignedIn) {
            "Sign in to Dexcom"
        } else {
            glucose.errorMsg ?: "Waiting for readings"
        }

        val subtitle = if (settings.clickThrough && settings.isSignedIn) {
            "Overlay locked (click-through active)"
        } else {
            "Tap to open Display • Drag overlay to move"
        }

        val openDisplayPi = PendingIntent.getActivity(
            this,
            1,
            MainActivity.createIntent(this, openSettings = !settings.isSignedIn),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val lockPi = PendingIntent.getService(
            this,
            2,
            Intent(this, OverlayService::class.java).setAction(ACTION_TOGGLE_LOCK),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val hidePi = PendingIntent.getService(
            this,
            3,
            Intent(this, OverlayService::class.java).setAction(ACTION_HIDE_OVERLAY),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(subtitle)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(openDisplayPi)
            .addAction(
                0,
                if (settings.clickThrough) "Unlock" else "Lock",
                lockPi,
            )
            .addAction(0, "Display", openDisplayPi)
            .addAction(0, "Hide", hidePi)

        if (latest != null) {
            val color = statusColor(getGlucoseStatus(latest.value, settings)).toArgb()
            val icon = createGlucoseStatusIcon(formatGlucose(latest.value, settings.unit), color)
            builder.setSmallIcon(icon)
        } else {
            builder.setSmallIcon(R.drawable.ic_stat_pulse)
        }

        return builder.build()
    }

    private fun createGlucoseStatusIcon(label: String, colorArgb: Int): IconCompat {
        val size = 64
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = colorArgb
            textAlign = Paint.Align.CENTER
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            textSize = when {
                label.length <= 2 -> 42f
                label.length == 3 -> 32f
                else -> 26f
            }
        }
        val y = size / 2f - (paint.descent() + paint.ascent()) / 2f
        canvas.drawText(label, size / 2f, y, paint)
        return IconCompat.createWithBitmap(bmp)
    }

    private inner class DraggableContainer(context: Context) : FrameLayout(context) {
        private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop.toFloat()
        private var downRawX = 0f
        private var downRawY = 0f
        private var startX = 0
        private var startY = 0
        private var dragging = false

        override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
            val s = SettingsRepository.settings.value
            if (inspectMode || (s.clickThrough && s.isSignedIn)) {
                return false
            }
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downRawX = ev.rawX
                    downRawY = ev.rawY
                    startX = windowParams.x
                    startY = windowParams.y
                    dragging = false
                    return true
                }
            }
            return super.onInterceptTouchEvent(ev)
        }

        override fun onTouchEvent(ev: MotionEvent): Boolean {
            val s = SettingsRepository.settings.value
            if (inspectMode || (s.clickThrough && s.isSignedIn)) {
                return super.onTouchEvent(ev)
            }
            when (ev.actionMasked) {
                MotionEvent.ACTION_MOVE -> {
                    val dx = ev.rawX - downRawX
                    val dy = ev.rawY - downRawY
                    if (!dragging && hypot(dx, dy) > touchSlop) {
                        dragging = true
                    }
                    if (dragging) {
                        val screenW = resources.displayMetrics.widthPixels
                        val screenH = resources.displayMetrics.heightPixels
                        windowParams.x = (startX + dx.roundToInt())
                            .coerceIn(0, (screenW - windowParams.width).coerceAtLeast(0))
                        windowParams.y = (startY + dy.roundToInt())
                            .coerceIn(0, (screenH - windowParams.height).coerceAtLeast(0))
                        windowManager.updateViewLayout(this, windowParams)
                    }
                    return true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    if (dragging) {
                        saveDraggedPosition(windowParams.x, windowParams.y)
                    } else if (ev.actionMasked == MotionEvent.ACTION_UP) {
                        val canInspect = s.isSignedIn &&
                            s.showBackgroundGraph &&
                            s.graphStyle == GraphStyle.DOTS &&
                            GlucoseStore.state.value.readings.size >= 2
                        if (canInspect) {
                            inspectMode = true
                        } else {
                            openMainApp(openSettings = !s.isSignedIn)
                        }
                    }
                    dragging = false
                    return true
                }
            }
            return super.onTouchEvent(ev)
        }
    }
}
