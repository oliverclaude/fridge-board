package com.fridge.board

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.BroadcastReceiver
import android.content.ComponentCallbacks
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.content.res.Configuration
import android.graphics.PixelFormat
import android.graphics.Point
import android.os.Build
import android.os.IBinder
import android.os.SystemClock
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.WindowInsets
import android.view.WindowManager
import android.widget.Toast
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.max
import kotlin.math.min

internal object BarLayout {
    var widthPx: Int = 0
    var heightPx: Int = 0

    fun updatePx(width: Int, height: Int): Boolean {
        if (width <= 0 || height <= 0) return false
        if (widthPx == width && heightPx == height) return false
        widthPx = width
        heightPx = height
        return true
    }
}

class BarService : Service() {
    private val owner = OverlayOwner()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var windows: WindowManager
    private var barView: View? = null
    private var spyView: View? = null
    private var imeHidden = false
    private var imeLog = ""
    private var screenSizedAt = 0L
    private var layoutKey = ""

    private val wakeReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent?) {
            val action = intent?.action ?: return
            if (action == Intent.ACTION_SCREEN_ON || action == Intent.ACTION_USER_PRESENT) {
                Apps.onWake(context)
            }
        }
    }

    private val callbacks = object : ComponentCallbacks {
        override fun onConfigurationChanged(newConfig: Configuration) {
            Log.i(
                "FridgeBoard",
                "config ${newConfig.screenWidthDp}x${newConfig.screenHeightDp}",
            )
            syncScreen()
            relayout(force = true)
        }

        override fun onLowMemory() = Unit
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        owner.create()
        startInForeground()
        windows = getSystemService(WindowManager::class.java)
        syncScreen()
        registerComponentCallbacks(callbacks)
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_USER_PRESENT)
        }
        ContextCompat.registerReceiver(this, wakeReceiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
        if (!addWindows()) {
            Toast.makeText(this, "Allow Fridge board to draw over other apps", Toast.LENGTH_LONG).show()
        }
        scope.launch {
            while (isActive) {
                val pkg = withContext(Dispatchers.Default) { Apps.foregroundPackage(this@BarService) }
                if (!pkg.isNullOrBlank()) BoardStore.noteForeground(pkg)
                syncScreen()
                syncKeyboard()
                delay(250)
            }
        }
        scope.launch {
            BoardStore.state.collect { relayout(force = false) }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    override fun onDestroy() {
        scope.cancel()
        runCatching { unregisterReceiver(wakeReceiver) }
        runCatching { unregisterComponentCallbacks(callbacks) }
        barView?.let { runCatching { windows.removeView(it) } }
        spyView?.let { runCatching { windows.removeView(it) } }
        barView = null
        spyView = null
        owner.destroy()
        super.onDestroy()
    }

    private fun startInForeground() {
        val manager = getSystemService(NotificationManager::class.java)
        val channel = NotificationChannel("bar", "Bar", NotificationManager.IMPORTANCE_LOW)
        channel.setShowBadge(false)
        manager.createNotificationChannel(channel)
        val notification = Notification.Builder(this, "bar")
            .setSmallIcon(R.drawable.ic_stat)
            .setContentTitle("Fridge board")
            .setContentText("The bar stays on screen")
            .setOngoing(true)
            .build()
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(1, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(1, notification)
        }
    }

    private fun addWindows(): Boolean {
        return try {
            val spy = View(this)
            spy.setOnApplyWindowInsetsListener { view, insets ->
                view.post { syncKeyboard() }
                view.onApplyWindowInsets(insets)
            }
            val spyParams = WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT,
            )
            spyParams.softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
            spyParams.alpha = 0f
            if (Build.VERSION.SDK_INT >= 30) {
                // A non-focusable overlay is placed above the keyboard, so it does not
                // receive that inset unless it asks for the raw global insets.
                spyParams.setFitInsetsTypes(
                    WindowInsets.Type.statusBars() or
                        WindowInsets.Type.navigationBars() or
                        WindowInsets.Type.ime(),
                )
                spyParams.setFitInsetsIgnoringVisibility(false)
                runCatching {
                    WindowManager.LayoutParams::class.java
                        .getField("receiveInsetsIgnoringZOrder")
                        .setBoolean(spyParams, true)
                }
            }
            spyParams.gravity = Gravity.TOP or Gravity.START
            windows.addView(spy, spyParams)
            spyView = spy
            spy.post {
                spy.requestApplyInsets()
                syncKeyboard()
            }

            val compose = ComposeView(this).apply {
                setViewTreeLifecycleOwner(owner)
                setViewTreeViewModelStoreOwner(owner)
                setViewTreeSavedStateRegistryOwner(owner)
                setBackgroundColor(android.graphics.Color.TRANSPARENT)
                setContent { FridgeTheme { BarStrip() } }
            }
            val params = barParams()
            layoutKey = params.key()
            windows.addView(compose, params)
            barView = compose
            true
        } catch (error: Exception) {
            Log.e("FridgeBoard", "overlay failed", error)
            false
        }
    }

    private fun relayout(force: Boolean) {
        val view = barView ?: return
        if (!view.isAttachedToWindow && !force) return
        val params = barParams()
        if (imeHidden) {
            params.alpha = 0f
            params.flags = params.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        }
        val key = params.key()
        if (!force && key == layoutKey) return
        layoutKey = key
        runCatching { windows.updateViewLayout(view, params) }
    }

    @Suppress("DEPRECATION")
    private fun syncScreen() {
        val size = Point()
        runCatching { windows.defaultDisplay.getRealSize(size) }
        if (size.x <= 0 || size.y <= 0) {
            val config = resources.configuration
            val density = resources.displayMetrics.density
            if (config.screenWidthDp > 0 && config.screenHeightDp > 0 && density > 0f) {
                size.x = (config.screenWidthDp * density).toInt()
                size.y = (config.screenHeightDp * density).toInt()
            }
        }
        if (BarLayout.updatePx(size.x, size.y)) {
            screenSizedAt = SystemClock.uptimeMillis()
            Log.i("FridgeBoard", "screen ${size.x}x${size.y}")
            relayout(force = true)
        }
    }

    private fun syncKeyboard() {
        val density = resources.displayMetrics.density
        val threshold = (120 * density).toInt()
        var imeBottom = 0
        val spy = spyView
        if (spy != null && Build.VERSION.SDK_INT >= 30) {
            spy.rootWindowInsets?.let { insets ->
                if (insets.isVisible(WindowInsets.Type.ime())) {
                    imeBottom = insets.getInsets(WindowInsets.Type.ime()).bottom
                }
            }
        }
        if (Build.VERSION.SDK_INT >= 30) {
            runCatching { windows.currentWindowMetrics.windowInsets }.getOrNull()?.let { insets ->
                if (insets.isVisible(WindowInsets.Type.ime())) {
                    imeBottom = max(imeBottom, insets.getInsets(WindowInsets.Type.ime()).bottom)
                }
            }
        }
        val fullHeight = BarLayout.heightPx
        val spyHeight = spy?.height ?: 0
        val stable = SystemClock.uptimeMillis() - screenSizedAt > 500
        val shrunk = stable && fullHeight > 0 && spyHeight > 0 && fullHeight - spyHeight > threshold
        val hide = imeBottom > (48 * density).toInt() || shrunk
        val log = "hidden=$hide imeBottom=$imeBottom spyHeight=$spyHeight screen=$fullHeight shrunk=$shrunk"
        if (log != imeLog) {
            imeLog = log
            Log.i("FridgeBoard", "keyboard $log")
        }
        if (hide == imeHidden) return
        imeHidden = hide
        barView?.visibility = if (hide) View.GONE else View.VISIBLE
        relayout(force = true)
    }

    private fun barParams(): WindowManager.LayoutParams {
        val state = BoardStore.state.value
        val density = resources.displayMetrics.density
        val widthPx: Int
        val heightPx: Int
        if (BarLayout.widthPx > 0 && BarLayout.heightPx > 0) {
            widthPx = BarLayout.widthPx
            heightPx = BarLayout.heightPx
        } else if (Build.VERSION.SDK_INT >= 30) {
            val bounds = windows.currentWindowMetrics.bounds
            widthPx = bounds.width()
            heightPx = bounds.height()
        } else {
            widthPx = resources.displayMetrics.widthPixels
            heightPx = resources.displayMetrics.heightPixels
        }
        val edge = systemBars()
        val side = sideOf(state, (widthPx / density).toInt(), (heightPx / density).toInt())
        val icon = state.barSizeDp * density
        val gap = 6f * density
        val pad = 8f * density
        val items = state.bar.size + 1
        val content = (pad * 2f + items * icon + (items - 1).coerceAtLeast(0) * gap + density).toInt()
        val thickness = ((state.barSizeDp + 16) * density).toInt()
        val params = WindowManager.LayoutParams(
            thickness,
            thickness,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
            PixelFormat.TRANSLUCENT,
        )
        params.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        val alongHeight = (heightPx - edge.top - edge.bottom).coerceAtLeast(thickness)
        val alongWidth = (widthPx - edge.left - edge.right).coerceAtLeast(thickness)
        val nudge = (8 * density).toInt()
        when (side) {
            BarSide.Right -> {
                params.width = thickness
                params.height = min(content, alongHeight)
                params.gravity = Gravity.END or Gravity.CENTER_VERTICAL
                params.x = edge.right
                params.y = (edge.top - edge.bottom) / 2
            }
            BarSide.Left -> {
                params.width = thickness
                params.height = min(content, alongHeight)
                params.gravity = Gravity.START or Gravity.CENTER_VERTICAL
                params.x = edge.left
                params.y = (edge.top - edge.bottom) / 2
            }
            BarSide.Bottom -> {
                params.width = min(content, alongWidth)
                params.height = thickness
                params.gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
                params.x = (edge.left - edge.right) / 2
                // The overlay parent already ends where the navigation bar starts
                // (fitTypes includes NAVIGATION_BARS). y is a further inset inside
                // that parent. 0 rests the capsule on the navigation bar, the same
                // zero gap the side bars have from the screen edge.
                params.y = 0
            }
            BarSide.Top -> {
                params.width = min(content, alongWidth)
                params.height = thickness
                params.gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
                params.x = (edge.left - edge.right) / 2
                params.y = max(edge.top, nudge)
            }
        }
        return params
    }

    private fun systemBars(): Edge {
        if (Build.VERSION.SDK_INT >= 30) {
            val insets = windows.currentWindowMetrics.windowInsets.getInsetsIgnoringVisibility(
                android.view.WindowInsets.Type.statusBars() or android.view.WindowInsets.Type.navigationBars(),
            )
            return Edge(insets.left, insets.top, insets.right, insets.bottom)
        }
        return Edge(0, dimenPx("status_bar_height"), 0, dimenPx("navigation_bar_height"))
    }

    private fun dimenPx(name: String): Int {
        val id = resources.getIdentifier(name, "dimen", "android")
        return if (id > 0) resources.getDimensionPixelSize(id) else 0
    }
}

private data class Edge(val left: Int, val top: Int, val right: Int, val bottom: Int)

private fun WindowManager.LayoutParams.key(): String = "$width:$height:$gravity:$x:$y:$alpha:$flags"

@Composable
private fun BarStrip() {
    val state by BoardStore.state.collectAsState()
    val front by BoardStore.foreground.collectAsState()
    val density = LocalDensity.current
    val sizePx = with(density) { state.barSizeDp.dp.roundToPx() }.coerceAtLeast(1)
    val plate = state.plateColor()
    val scrim = MaterialTheme.colorScheme.surface.copy(alpha = state.barOpacity / 100f)
    val apps = state.bar
    val shape = RoundedCornerShape(percent = 50)
    // Follow the window itself. A short wide window is the bottom bar; a tall
    // narrow window is the side bar. A vertical list inside the short window
    // collapses the icons to nothing.
    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        if (maxHeight > maxWidth) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .clip(shape)
                    .background(scrim)
                    .padding(8.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                if (apps.isNotEmpty()) {
                    Column(
                        modifier = Modifier.weight(1f).verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        apps.forEach { pkg ->
                            BarIcon(pkg, pkg == front, sizePx, state.barSizeDp, plate)
                        }
                    }
                }
                Gear(state.barSizeDp)
            }
        } else {
            Row(
                modifier = Modifier
                    .fillMaxSize()
                    .clip(shape)
                    .background(scrim)
                    .padding(8.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (apps.isNotEmpty()) {
                    Row(
                        modifier = Modifier.weight(1f).horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        apps.forEach { pkg ->
                            BarIcon(pkg, pkg == front, sizePx, state.barSizeDp, plate)
                        }
                    }
                }
                Gear(state.barSizeDp)
            }
        }
    }
}

@Composable
private fun BarIcon(pkg: String, home: Boolean, sizePx: Int, sizeDp: Int, plate: Int) {
    val context = LocalContext.current
    val bitmap by produceState<android.graphics.Bitmap?>(null, pkg, home, sizePx, plate) {
        value = withContext(Dispatchers.Default) {
            if (home && pkg != context.packageName) {
                IconFactory.home(sizePx, plate)
            } else {
                IconFactory.app(context.packageManager, pkg, sizePx, plate)
            }
        }
    }
    val image = bitmap
    Box(
        modifier = Modifier
            .size(sizeDp.dp)
            .clickable {
                if (home) Apps.openDrawer(context) else Apps.launch(context, pkg)
            },
        contentAlignment = Alignment.Center,
    ) {
        if (image != null) {
            androidx.compose.foundation.Image(
                bitmap = image.asImageBitmap(),
                contentDescription = pkg,
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}

@Composable
private fun Gear(sizeDp: Int) {
    val context = LocalContext.current
    Box(
        modifier = Modifier
            .size(sizeDp.dp)
            .clickable { Apps.openSettings(context) },
        contentAlignment = Alignment.Center,
    ) {
        val color = MaterialTheme.colorScheme.onSurface
        Canvas(modifier = Modifier.size(sizeDp.dp * 0.55f)) {
            val s = size.minDimension
            drawCircle(color, radius = s * 0.18f, style = Stroke(width = s * 0.08f))
            repeat(8) { index ->
                rotate(index * 45f) {
                    drawLine(
                        color,
                        start = androidx.compose.ui.geometry.Offset(s / 2f, s * 0.05f),
                        end = androidx.compose.ui.geometry.Offset(s / 2f, s * 0.24f),
                        strokeWidth = s * 0.08f,
                        cap = StrokeCap.Round,
                    )
                }
            }
        }
    }
}
