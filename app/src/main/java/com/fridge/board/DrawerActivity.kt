package com.fridge.board

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.min

class DrawerActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ContextCompat.startForegroundService(this, Intent(this, BarService::class.java))
        setContent { FridgeTheme { DrawerScreen() } }
    }

    override fun onResume() {
        super.onResume()
        BoardStore.noteForeground(packageName)
        Apps.consumeWake(this)
    }
}

@Composable
fun DrawerScreen() {
    val context = LocalContext.current
    val state by BoardStore.state.collectAsState()
    val apps by produceState(emptyList<Launchable>()) {
        value = withContext(Dispatchers.Default) { Apps.load(context) }
    }
    val pager = rememberPagerState(pageCount = { 2 })
    val scope = rememberCoroutineScope()
    val wall by BoardStore.wallpaper.collectAsState()
    val wallpaper by produceState<android.graphics.Bitmap?>(null, wall) {
        value = withContext(Dispatchers.Default) { Wallpaper.load(context) }
    }
    val config = LocalConfiguration.current
    val side = sideOf(state, config.screenWidthDp, config.screenHeightDp)
    val barPad = (state.barSizeDp + 16).dp
    val page = pager.currentPage
    val title = if (page == 0) "Favorites" else "Rest"

    Box(modifier = Modifier.fillMaxSize()) {
        wallpaper?.let { bitmap ->
            Image(
                bitmap = bitmap.asImageBitmap(),
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
            )
        }
        val scheme = MaterialTheme.colorScheme
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        listOf(scheme.scrim.copy(alpha = 0.38f), scheme.surface.copy(alpha = 0.55f)),
                    )
                ),
        )
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(
                    start = if (side == BarSide.Left) barPad + 12.dp else 28.dp,
                    top = if (side == BarSide.Top) barPad + 8.dp else 28.dp,
                    end = if (side == BarSide.Right) barPad + 12.dp else 28.dp,
                    bottom = if (side == BarSide.Bottom) barPad + 8.dp else 20.dp,
                ),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = title,
                    color = scheme.onSurface,
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.displaySmall.copy(
                        shadow = Shadow(scheme.scrim, blurRadius = 12f),
                    ),
                )
                Row {
                    repeat(2) { index ->
                        Box(
                            modifier = Modifier
                                .size(48.dp)
                                .clickable { scope.launch { pager.animateScrollToPage(index) } },
                            contentAlignment = Alignment.Center,
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(if (page == index) 10.dp else 8.dp)
                                    .clip(CircleShape)
                                    .background(
                                        if (page == index) scheme.primary else scheme.onSurface.copy(alpha = 0.38f),
                                    ),
                            )
                        }
                    }
                }
            }
            if (!Settings.canDrawOverlays(context)) {
                TextButton(onClick = {
                    context.startActivity(
                        Intent(
                            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                            Uri.parse("package:${context.packageName}"),
                        )
                    )
                }) {
                    Text("Allow the bar to draw over other apps")
                }
            }
            HorizontalPager(state = pager, modifier = Modifier.weight(1f).fillMaxWidth()) { index ->
                val shown = if (index == 0) {
                    Apps.ordered(state.favorites, apps, state.favoritesAlphabetical)
                } else {
                    Apps.rest(state, apps)
                }
                val requested = if (index == 0) state.favSizeDp else state.restSizeDp
                IconPage(shown, state.columns, state.rows, requested, state.plateColor())
            }
        }
    }
}

@Composable
private fun IconPage(
    apps: List<Launchable>,
    columns: Int,
    rows: Int,
    requestedDp: Int,
    plate: Int,
) {
    if (apps.isEmpty()) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(
                "Nothing here yet",
                color = MaterialTheme.colorScheme.onSurface,
                style = MaterialTheme.typography.titleLarge,
            )
        }
        return
    }
    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val iconDp = fittedIcon(requestedDp, columns, rows, maxWidth, maxHeight)
        if (apps.size <= columns * rows) {
            Column(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterVertically),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                apps.chunked(columns).forEach { row ->
                    Row(horizontalArrangement = Arrangement.spacedBy(18.dp)) {
                        row.forEach { app ->
                            AppCell(app, iconDp, plate)
                        }
                    }
                }
            }
        } else {
            LazyVerticalGrid(
                columns = GridCells.Fixed(columns),
                modifier = Modifier.fillMaxSize(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                contentPadding = PaddingValues(8.dp),
            ) {
                items(apps, key = { it.pkg }) { app ->
                    Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                        AppCell(app, iconDp, plate)
                    }
                }
            }
        }
    }
}

private fun fittedIcon(requested: Int, columns: Int, rows: Int, width: Dp, height: Dp): Int {
    val colGap = 18f
    val rowGap = 10f
    val label = 24f
    val iconW = (width.value - colGap * (columns - 1)) / columns
    val iconH = (height.value - rowGap * (rows - 1) - label * rows) / rows
    return min(requested.toFloat(), min(iconW, iconH)).toInt().coerceIn(40, 200)
}

@Composable
private fun AppCell(app: Launchable, sizeDp: Int, plate: Int) {
    val context = LocalContext.current
    val density = LocalDensity.current
    val sizePx = with(density) { sizeDp.dp.roundToPx() }.coerceAtLeast(1)
    val bitmap by produceState<android.graphics.Bitmap?>(null, app.pkg, sizePx, plate) {
        value = withContext(Dispatchers.Default) {
            IconFactory.app(context.packageManager, app.pkg, sizePx, plate)
        }
    }
    Column(
        modifier = Modifier
            .width((sizeDp + 28).dp)
            .clickable { Apps.launch(context, app.pkg) }
            .padding(vertical = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        val image = bitmap
        if (image != null) {
            Image(
                bitmap = image.asImageBitmap(),
                contentDescription = app.label,
                modifier = Modifier.size(sizeDp.dp),
            )
        } else {
            Box(modifier = Modifier.size(sizeDp.dp))
        }
        Text(
            text = app.label,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
            style = MaterialTheme.typography.titleSmall.copy(
                shadow = Shadow(MaterialTheme.colorScheme.scrim, blurRadius = 8f),
            ),
        )
    }
}
