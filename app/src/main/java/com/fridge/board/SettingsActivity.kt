package com.fridge.board

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.Canvas
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class SettingsActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            FridgeTheme {
                SettingsScreen(onBack = { finish() })
            }
        }
    }

    override fun onResume() {
        super.onResume()
        BoardStore.noteForeground(packageName)
    }
}

@Composable
private fun SettingsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val state by BoardStore.state.collectAsState()
    val apps by produceState(emptyList<Launchable>()) {
        value = withContext(Dispatchers.Default) { Apps.load(context) }
    }
    val config = LocalConfiguration.current
    val side = sideOf(state, config.screenWidthDp, config.screenHeightDp)
    val barPad = (state.barSizeDp + 16).dp
    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.surface)
            .padding(
                start = if (side == BarSide.Left) barPad + 12.dp else 24.dp,
                top = if (side == BarSide.Top) barPad + 8.dp else 16.dp,
                end = if (side == BarSide.Right) barPad + 12.dp else 24.dp,
                bottom = if (side == BarSide.Bottom) barPad + 8.dp else 16.dp,
            ),
    ) {
        if (maxWidth > 840.dp) {
            Row(horizontalArrangement = Arrangement.spacedBy(28.dp), modifier = Modifier.fillMaxSize()) {
                Controls(state, onBack, Modifier.weight(0.9f).fillMaxHeight().verticalScroll(rememberScrollState()))
                AppEditor(
                    state,
                    apps,
                    Modifier.weight(1.2f).fillMaxHeight().verticalScroll(rememberScrollState()),
                )
            }
        } else {
            Column(modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                Controls(state, onBack, Modifier.fillMaxWidth())
                AppEditor(state, apps, Modifier.fillMaxWidth().padding(top = 12.dp))
            }
        }
    }
}

@Composable
private fun Controls(state: BoardState, onBack: () -> Unit, modifier: Modifier) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val wall by BoardStore.wallpaper.collectAsState()
    val custom = remember(wall) { Wallpaper.exists(context) }
    val preview by produceState<android.graphics.Bitmap?>(null, wall) {
        value = withContext(Dispatchers.Default) { Wallpaper.load(context) }
    }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        try {
            context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        } catch (_: SecurityException) {
        }
        val app = context.applicationContext
        scope.launch(Dispatchers.IO) {
            val ok = Wallpaper.import(app, uri)
            if (!ok) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(app, "Couldn't use that photo", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(16.dp)) {
        TextButton(onClick = onBack) { Text("Back") }
        Text(
            "Settings",
            style = MaterialTheme.typography.headlineLarge,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Group("Bar") {
        FieldLabel("Where it sits")
        ChipRow {
            listOf(BarPlace.Fixed to "Fixed side", BarPlace.Short to "Short edge", BarPlace.Long to "Long edge").forEach { (value, label) ->
                FilterChip(
                    selected = state.barPlace == value,
                    onClick = { BoardStore.update { it.copy(barPlace = value) } },
                    label = { Text(label) },
                )
            }
        }
        if (state.barPlace == BarPlace.Fixed) {
            FieldLabel("Side")
            ChipRow {
                BarSide.entries.forEach { value ->
                    FilterChip(
                        selected = state.barSide == value,
                        onClick = { BoardStore.update { it.copy(barSide = value) } },
                        label = { Text(value.name) },
                    )
                }
            }
            Hint("The bar stays on the ${state.barSide.name.lowercase()} in both orientations.")
        } else {
            FieldLabel("When upright")
            ChipRow {
                uprightChoices(state.barPlace).forEach { value ->
                    FilterChip(
                        selected = chosenUpright(state) == value,
                        onClick = { BoardStore.update { it.copy(uprightSide = value) } },
                        label = { Text(value.name) },
                    )
                }
            }
            FieldLabel("When sideways")
            ChipRow {
                sidewaysChoices(state.barPlace).forEach { value ->
                    FilterChip(
                        selected = chosenSideways(state) == value,
                        onClick = { BoardStore.update { it.copy(sidewaysSide = value) } },
                        label = { Text(value.name) },
                    )
                }
            }
            Hint(
                "Upright it sits on the ${chosenUpright(state).name.lowercase()}. " +
                    "Sideways it sits on the ${chosenSideways(state).name.lowercase()}.",
            )
        }
        SliderLine("Icon size", state.barSizeDp, 40, 120) { next ->
            BoardStore.update { it.copy(barSizeDp = next) }
        }
        SliderLine("Transparency", state.barOpacity, 50, 100, suffix = "%") { next ->
            BoardStore.update { it.copy(barOpacity = next) }
        }
        }
        Group("Drawer") {
        Stepper("Columns", state.columns, 1, 8) { value -> BoardStore.update { it.copy(columns = value) } }
        Stepper("Rows", state.rows, 1, 6) { value -> BoardStore.update { it.copy(rows = value) } }
        SliderLine("Favorites icon size", state.favSizeDp, 48, 180) { next ->
            BoardStore.update { it.copy(favSizeDp = next) }
        }
        SliderLine("Rest icon size", state.restSizeDp, 48, 180) { next ->
            BoardStore.update { it.copy(restSizeDp = next) }
        }
        FieldLabel("Icon background")
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Box(
                modifier = Modifier
                    .size(42.dp)
                    .clip(CircleShape)
                    .background(Color(state.plateColor())),
            )
            Slider(
                value = state.iconGrey.toFloat(),
                onValueChange = { value -> BoardStore.update { it.copy(iconGrey = value.toInt()) } },
                valueRange = 0f..255f,
                modifier = Modifier.weight(1f),
            )
        }
        Hint("Charcoal is near the dark end. White is the far end.")
        }
        Group("Background") {
        preview?.let { bitmap ->
            Image(
                bitmap = bitmap.asImageBitmap(),
                contentDescription = "Background preview",
                modifier = Modifier
                    .size(width = 168.dp, height = 105.dp)
                    .clip(MaterialTheme.shapes.extraLarge),
                contentScale = ContentScale.Crop,
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { picker.launch(arrayOf("image/*")) }) { Text("Choose photo") }
            if (custom) {
                TextButton(onClick = { Wallpaper.clear(context) }) { Text("Use built-in") }
            }
        }
        }
    }
}

@Composable
private fun AppEditor(state: BoardState, apps: List<Launchable>, modifier: Modifier) {
    val bar = Apps.ordered(state.bar, apps)
    val fav = Apps.ordered(state.favorites, apps)
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Group("Bar order") {
            if (bar.isEmpty()) Hint("No apps on the bar.")
            bar.forEach { app ->
                OrderRow(app.label, canUp = app.pkg != state.bar.firstOrNull(), canDown = app.pkg != state.bar.lastOrNull()) { dir ->
                    BoardStore.move(Place.Bar, app.pkg, dir)
                }
            }
        }
        Group("Favorites order") {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                Text(
                    "Alphabetical",
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.bodyLarge,
                )
                Switch(
                    checked = state.favoritesAlphabetical,
                    onCheckedChange = { on -> BoardStore.update { it.copy(favoritesAlphabetical = on) } },
                )
            }
            if (fav.isEmpty()) {
                Hint("No favorites yet.")
            } else if (state.favoritesAlphabetical) {
                Hint("Favorites are listed from A to Z.")
            } else {
                fav.forEach { app ->
                    OrderRow(
                        app.label,
                        canUp = app.pkg != state.favorites.firstOrNull(),
                        canDown = app.pkg != state.favorites.lastOrNull(),
                    ) { dir ->
                        BoardStore.move(Place.Favorite, app.pkg, dir)
                    }
                }
            }
        }
        Group("Where each app shows") {
            apps.forEach { app ->
                val place = BoardStore.placement(app.pkg, state)
                Column(modifier = Modifier.fillMaxWidth()) {
                    Text(
                        app.label,
                        color = MaterialTheme.colorScheme.onSurface,
                        style = MaterialTheme.typography.bodyLarge,
                    )
                    ChipRow {
                        Place.entries.forEach { option ->
                            FilterChip(
                                selected = place == option,
                                onClick = { BoardStore.setPlacement(app.pkg, option) },
                                label = { Text(option.name) },
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun OrderRow(label: String, canUp: Boolean, canDown: Boolean, onMove: (Int) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Text(
            label,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.bodyLarge,
        )
        ChevronButton(up = true, enabled = canUp) { onMove(-1) }
        ChevronButton(up = false, enabled = canDown) { onMove(1) }
    }
}

@Composable
private fun ChevronButton(up: Boolean, enabled: Boolean, onClick: () -> Unit) {
    FilledTonalIconButton(onClick = onClick, enabled = enabled) {
        val color = LocalContentColor.current
        Canvas(modifier = Modifier.size(24.dp)) {
            val mid = size.width / 2f
            val yTip = if (up) size.height * 0.36f else size.height * 0.64f
            val yBase = if (up) size.height * 0.64f else size.height * 0.36f
            drawLine(color, Offset(size.width * 0.22f, yBase), Offset(mid, yTip), strokeWidth = 2.dp.toPx(), cap = StrokeCap.Round)
            drawLine(color, Offset(size.width * 0.78f, yBase), Offset(mid, yTip), strokeWidth = 2.dp.toPx(), cap = StrokeCap.Round)
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ChipRow(content: @Composable () -> Unit) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        content()
    }
}

@Composable
private fun Group(title: String, content: @Composable () -> Unit) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainer,
        shape = MaterialTheme.shapes.extraLarge,
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(title, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
            content()
        }
    }
}

@Composable
private fun FieldLabel(text: String) {
    Text(text, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface)
}

@Composable
private fun Hint(text: String) {
    Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun SliderLine(
    label: String,
    value: Int,
    min: Int,
    max: Int,
    suffix: String = " dp",
    onChange: (Int) -> Unit,
) {
    Text(
        "$label  $value$suffix",
        style = MaterialTheme.typography.bodyLarge,
        color = MaterialTheme.colorScheme.onSurface,
    )
    Slider(
        value = value.toFloat(),
        onValueChange = { onChange(it.toInt()) },
        valueRange = min.toFloat()..max.toFloat(),
    )
}

@Composable
private fun Stepper(label: String, value: Int, min: Int, max: Int, onChange: (Int) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Text(
            label,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.bodyLarge,
        )
        FilledTonalIconButton(onClick = { if (value > min) onChange(value - 1) }, enabled = value > min) {
            Text("−", style = MaterialTheme.typography.titleLarge)
        }
        Text(
            value.toString(),
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.padding(horizontal = 12.dp),
            style = MaterialTheme.typography.titleLarge,
        )
        FilledTonalIconButton(onClick = { if (value < max) onChange(value + 1) }, enabled = value < max) {
            Text("+", style = MaterialTheme.typography.titleLarge)
        }
    }
}

