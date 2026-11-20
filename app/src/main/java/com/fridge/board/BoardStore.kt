package com.fridge.board

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

enum class Place { Bar, Favorite, Rest, Hidden }

enum class BarPlace { Fixed, Short, Long }

enum class BarSide { Left, Right, Top, Bottom }

data class BoardState(
    val barPlace: BarPlace = BarPlace.Short,
    val barSide: BarSide = BarSide.Right,
    val uprightSide: BarSide = BarSide.Bottom,
    val sidewaysSide: BarSide = BarSide.Right,
    val favoritesAlphabetical: Boolean = false,
    val barSizeDp: Int = 56,
    val barOpacity: Int = 80,
    val favSizeDp: Int = 96,
    val restSizeDp: Int = 96,
    val columns: Int = 3,
    val rows: Int = 2,
    val iconGrey: Int = 44,
    val bar: List<String> = emptyList(),
    val favorites: List<String> = emptyList(),
    val hidden: List<String> = emptyList(),
) {
    fun plateColor(): Int {
        val g = iconGrey.coerceIn(0, 255)
        return (0xFF shl 24) or (g shl 16) or (g shl 8) or g
    }

    fun normalized(): BoardState = copy(
        barSizeDp = barSizeDp.coerceIn(40, 120),
        barOpacity = barOpacity.coerceIn(50, 100),
        favSizeDp = favSizeDp.coerceIn(48, 180),
        restSizeDp = restSizeDp.coerceIn(48, 180),
        columns = columns.coerceIn(1, 8),
        rows = rows.coerceIn(1, 6),
        iconGrey = iconGrey.coerceIn(0, 255),
        bar = bar.distinct(),
        favorites = favorites.distinct().filter { it !in bar },
        hidden = hidden.distinct().filter { it !in bar && it !in favorites },
    )
}

object BoardStore {
    private lateinit var file: File
    private val lock = Any()

    private val _state = MutableStateFlow(BoardState())
    val state: StateFlow<BoardState> = _state

    private val _foreground = MutableStateFlow<String?>(null)
    val foreground: StateFlow<String?> = _foreground

    private val _wallpaper = MutableStateFlow(0)
    val wallpaper: StateFlow<Int> = _wallpaper

    fun bumpWallpaper() {
        _wallpaper.value = _wallpaper.value + 1
    }

    @Volatile
    var wakePending: Boolean = false

    fun init(context: Context) {
        file = File(context.filesDir, "board.json")
        val loaded = if (file.exists()) read() else null
        _state.value = (loaded ?: defaults(context)).normalized()
        if (loaded == null) save(_state.value)
    }

    fun update(block: (BoardState) -> BoardState) {
        val next = synchronized(lock) {
            val value = block(_state.value).normalized()
            save(value)
            value
        }
        _state.value = next
    }

    fun noteForeground(pkg: String?) {
        _foreground.value = pkg
    }

    fun setPlacement(pkg: String, place: Place) {
        update { s ->
            val bar = s.bar.filterNot { it == pkg }
            val fav = s.favorites.filterNot { it == pkg }
            val hidden = s.hidden.filterNot { it == pkg }
            when (place) {
                Place.Bar -> s.copy(bar = bar + pkg, favorites = fav, hidden = hidden)
                Place.Favorite -> s.copy(bar = bar, favorites = fav + pkg, hidden = hidden)
                Place.Hidden -> s.copy(bar = bar, favorites = fav, hidden = hidden + pkg)
                Place.Rest -> s.copy(bar = bar, favorites = fav, hidden = hidden)
            }
        }
    }

    fun move(place: Place, pkg: String, direction: Int) {
        update { s ->
            val cur = when (place) {
                Place.Bar -> s.bar
                Place.Favorite -> s.favorites
                else -> return@update s
            }
            val index = cur.indexOf(pkg)
            val target = index + direction
            if (index < 0 || target !in cur.indices) return@update s
            val next = cur.toMutableList().apply {
                add(target, removeAt(index))
            }
            when (place) {
                Place.Bar -> s.copy(bar = next)
                Place.Favorite -> s.copy(favorites = next)
                else -> s
            }
        }
    }

    fun placement(pkg: String, state: BoardState = _state.value): Place = when (pkg) {
        in state.bar -> Place.Bar
        in state.favorites -> Place.Favorite
        in state.hidden -> Place.Hidden
        else -> Place.Rest
    }

    private fun defaults(context: Context): BoardState {
        fun keep(packages: List<String>) = packages.filter { installed(context, it) }
        return BoardState(
            bar = keep(
                listOf(
                    "com.google.android.calendar",
                    "com.google.android.keep",
                    "com.google.android.deskclock",
                )
            ),
            favorites = keep(
                listOf(
                    "com.android.chrome",
                    "com.google.android.apps.photos",
                    "com.google.android.apps.nbu.files",
                    "com.google.android.calculator",
                    "com.google.android.contacts",
                    "com.google.android.apps.maps",
                )
            ),
        )
    }

    private fun installed(context: Context, pkg: String): Boolean = try {
        context.packageManager.getPackageInfo(pkg, 0)
        true
    } catch (_: Exception) {
        false
    }

    private fun read(): BoardState? = try {
        val json = JSONObject(file.readText())
        BoardState(
            barPlace = barPlaceOf(json),
            barSide = sideOf(json.optString("barSide"), BarSide.Right),
            uprightSide = sideOf(json.optString("uprightSide"), BarSide.Bottom),
            sidewaysSide = sideOf(json.optString("sidewaysSide"), BarSide.Right),
            favoritesAlphabetical = json.optBoolean("favoritesAlphabetical", false),
            barSizeDp = json.optInt("barSizeDp", 56),
            barOpacity = json.optInt("barOpacity", 80),
            favSizeDp = json.optInt("favSizeDp", 96),
            restSizeDp = json.optInt("restSizeDp", 96),
            columns = json.optInt("columns", 3),
            rows = json.optInt("rows", 2),
            iconGrey = json.optInt("iconGrey", 44),
            bar = json.optJSONArray("bar").toStrings(),
            favorites = json.optJSONArray("favorites").toStrings(),
            hidden = json.optJSONArray("hidden").toStrings(),
        )
    } catch (_: Exception) {
        null
    }

    private fun save(state: BoardState) {
        val json = JSONObject()
            .put("barPlace", state.barPlace.name.lowercase())
            .put("barSide", state.barSide.name.lowercase())
            .put("uprightSide", state.uprightSide.name.lowercase())
            .put("sidewaysSide", state.sidewaysSide.name.lowercase())
            .put("favoritesAlphabetical", state.favoritesAlphabetical)
            .put("barSizeDp", state.barSizeDp)
            .put("barOpacity", state.barOpacity)
            .put("favSizeDp", state.favSizeDp)
            .put("restSizeDp", state.restSizeDp)
            .put("columns", state.columns)
            .put("rows", state.rows)
            .put("iconGrey", state.iconGrey)
            .put("bar", JSONArray(state.bar))
            .put("favorites", JSONArray(state.favorites))
            .put("hidden", JSONArray(state.hidden))
        file.parentFile?.mkdirs()
        val tmp = File(file.parentFile, "board.json.tmp")
        tmp.writeText(json.toString())
        if (!tmp.renameTo(file)) {
            file.writeText(json.toString())
            tmp.delete()
        }
    }
}

private fun sideOf(name: String, fallback: BarSide): BarSide = when (name) {
    "left" -> BarSide.Left
    "right" -> BarSide.Right
    "top" -> BarSide.Top
    "bottom" -> BarSide.Bottom
    else -> fallback
}

private fun barPlaceOf(json: JSONObject): BarPlace = when (json.optString("barPlace")) {
    "fixed" -> BarPlace.Fixed
    "long" -> BarPlace.Long
    "short" -> BarPlace.Short
    else -> when (json.optString("barSide")) {
        "left", "right", "top", "bottom" -> BarPlace.Fixed
        else -> BarPlace.Short
    }
}

private fun JSONArray?.toStrings(): List<String> {
    if (this == null) return emptyList()
    return buildList {
        for (i in 0 until length()) {
            val value = optString(i)
            if (value.isNotEmpty()) add(value)
        }
    }
}
