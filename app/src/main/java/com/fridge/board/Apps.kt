package com.fridge.board

import android.app.ActivityOptions
import android.app.KeyguardManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log

data class Launchable(
    val pkg: String,
    val label: String,
)

private const val TAG = "FridgeBoard"

object Apps {
    fun load(context: Context): List<Launchable> {
        val pm = context.packageManager
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        @Suppress("DEPRECATION")
        val resolved = pm.queryIntentActivities(intent, PackageManager.MATCH_ALL)
        return resolved
            .mapNotNull { info ->
                val pkg = info.activityInfo?.packageName ?: return@mapNotNull null
                if (pkg == context.packageName) return@mapNotNull null
                Launchable(pkg, info.loadLabel(pm).toString().ifBlank { pkg })
            }
            .distinctBy { it.pkg }
            .sortedBy { it.label.lowercase() }
    }

    fun rest(state: BoardState, apps: List<Launchable>): List<Launchable> {
        val taken = buildSet {
            addAll(state.bar)
            addAll(state.favorites)
            addAll(state.hidden)
        }
        return apps.filter { it.pkg !in taken }
    }

    fun ordered(packages: List<String>, apps: List<Launchable>, alphabetical: Boolean = false): List<Launchable> {
        val byPkg = apps.associateBy { it.pkg }
        val list = packages.mapNotNull { byPkg[it] }
        return if (alphabetical) list.sortedBy { it.label.lowercase() } else list
    }

    fun foregroundPackage(context: Context): String? {
        val usm = context.getSystemService(UsageStatsManager::class.java) ?: return null
        val end = System.currentTimeMillis()
        val events = try {
            usm.queryEvents(end - 15_000, end)
        } catch (_: Exception) {
            return null
        }
        var last: String? = null
        val event = UsageEvents.Event()
        while (events.hasNextEvent()) {
            events.getNextEvent(event)
            val type = event.eventType
            if (type == UsageEvents.Event.ACTIVITY_RESUMED || type == UsageEvents.Event.MOVE_TO_FOREGROUND) {
                last = event.packageName
            }
        }
        return last
    }

    fun launch(context: Context, pkg: String) {
        if (pkg == context.packageName) {
            openDrawer(context)
            return
        }
        val intent = context.packageManager.getLaunchIntentForPackage(pkg)
        if (intent == null) {
            Log.w(TAG, "no launch intent for $pkg")
            return
        }
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
        try {
            context.startActivity(intent)
            BoardStore.noteForeground(pkg)
        } catch (error: Exception) {
            Log.e(TAG, "startActivity $pkg failed", error)
            fullScreen(context, intent)
        }
    }

    fun openDrawer(context: Context) {
        val intent = Intent(context, DrawerActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        BoardStore.wakePending = false
        try {
            context.startActivity(intent)
            BoardStore.noteForeground(context.packageName)
        } catch (error: Exception) {
            Log.e(TAG, "open drawer failed", error)
        }
    }

    fun openSettings(context: Context) {
        val intent = Intent(context, SettingsActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            context.startActivity(intent)
        } catch (error: Exception) {
            Log.e(TAG, "open settings failed", error)
        }
    }

    fun onWake(context: Context) {
        val keyguard = context.getSystemService(KeyguardManager::class.java)
        if (keyguard?.isKeyguardLocked == true) return
        val first = BoardStore.state.value.bar.firstOrNull()
        if (first == null) {
            openDrawer(context)
            return
        }
        BoardStore.wakePending = true
        val intent = context.packageManager.getLaunchIntentForPackage(first)
        if (intent == null) {
            BoardStore.wakePending = false
            return
        }
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
        try {
            context.startActivity(intent)
            BoardStore.noteForeground(first)
            BoardStore.wakePending = false
        } catch (error: Exception) {
            Log.e(TAG, "wake start $first failed", error)
            fullScreen(context, intent)
        }
    }

    fun consumeWake(context: Context) {
        if (!BoardStore.wakePending) return
        BoardStore.wakePending = false
        val first = BoardStore.state.value.bar.firstOrNull() ?: return
        launch(context, first)
    }

    private fun fullScreen(context: Context, intent: Intent) {
        val nm = context.getSystemService(NotificationManager::class.java) ?: return
        val channel = NotificationChannel("wake", "Wake", NotificationManager.IMPORTANCE_HIGH)
        channel.setSound(null, null)
        nm.createNotificationChannel(channel)
        val pending = PendingIntent.getActivity(
            context,
            7,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val builder = Notification.Builder(context, "wake")
            .setSmallIcon(R.drawable.ic_stat)
            .setContentTitle("Opening the first bar app")
            .setFullScreenIntent(pending, true)
            .setCategory(Notification.CATEGORY_ALARM)
            .setTimeoutAfter(4000)
            .setAutoCancel(true)
        if (Build.VERSION.SDK_INT >= 34) {
            val options = ActivityOptions.makeBasic()
            options.pendingIntentBackgroundActivityStartMode =
                ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED
            builder.setContentIntent(pending)
        }
        nm.notify(7, builder.build())
    }
}

fun sideOf(state: BoardState, widthDp: Int, heightDp: Int): BarSide {
    val upright = widthDp < heightDp
    return when (state.barPlace) {
        BarPlace.Fixed -> state.barSide
        BarPlace.Short -> if (upright) state.uprightSide.verticalOr(BarSide.Bottom) else state.sidewaysSide.horizontalOr(BarSide.Right)
        BarPlace.Long -> if (upright) state.uprightSide.horizontalOr(BarSide.Right) else state.sidewaysSide.verticalOr(BarSide.Bottom)
    }
}

fun uprightChoices(place: BarPlace): List<BarSide> = when (place) {
    BarPlace.Short -> listOf(BarSide.Top, BarSide.Bottom)
    BarPlace.Long -> listOf(BarSide.Left, BarSide.Right)
    BarPlace.Fixed -> emptyList()
}

fun sidewaysChoices(place: BarPlace): List<BarSide> = when (place) {
    BarPlace.Short -> listOf(BarSide.Left, BarSide.Right)
    BarPlace.Long -> listOf(BarSide.Top, BarSide.Bottom)
    BarPlace.Fixed -> emptyList()
}

fun chosenUpright(state: BoardState): BarSide = when (state.barPlace) {
    BarPlace.Short -> state.uprightSide.verticalOr(BarSide.Bottom)
    BarPlace.Long -> state.uprightSide.horizontalOr(BarSide.Right)
    BarPlace.Fixed -> state.barSide
}

fun chosenSideways(state: BoardState): BarSide = when (state.barPlace) {
    BarPlace.Short -> state.sidewaysSide.horizontalOr(BarSide.Right)
    BarPlace.Long -> state.sidewaysSide.verticalOr(BarSide.Bottom)
    BarPlace.Fixed -> state.barSide
}

private fun BarSide.verticalOr(fallback: BarSide): BarSide =
    if (this == BarSide.Top || this == BarSide.Bottom) this else fallback

private fun BarSide.horizontalOr(fallback: BarSide): BarSide =
    if (this == BarSide.Left || this == BarSide.Right) this else fallback
