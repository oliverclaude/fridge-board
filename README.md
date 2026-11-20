# Fridge board

A launcher for a tablet that stays on the fridge. The home screen is the app drawer. A short bar stays on top of other apps, so the family's calendar, notes, and clock stay one tap away.

Package name: `com.fridge.board`.

## The drawer

The home screen has two pages: Favorites, then everything else. Each launchable app is in exactly one place: the bar, Favorites, the rest of the drawer, or hidden. A newly installed app lands in the rest of the drawer. Fridge board does not list itself.

Favorites and the rest of the drawer share one grid (3 columns by 2 rows unless you change it). Each page has its own icon size. That size is a maximum: the icons shrink until the grid fits beside the bar. Extra apps scroll inside the page. Names sit under the icons.

## The bar

The bar is a capsule of icons plus a settings gear, drawn over other apps by a foreground service. It is only as long as those icons, and it sits in the middle of the edge you pick. It scrolls only when the icons no longer fit between the status bar and the navigation bar. It hides while the keyboard is open.

You can pin it to the left, right, top, or bottom. You can also follow an edge by length, the short edge or the long edge, and pick which side of that edge to use when the tablet is upright and when it is sideways. The first-run placement follows the short edge: the bottom when upright, the right when sideways.

Tapping an icon opens that app, or brings it back if it is already open. Tapping the app that is in front returns to the drawer, and its bar icon becomes a home mark.

When the screen wakes or unlocks, the first app on the bar opens. If that app is already open, it resumes on the page the family left. If the bar is empty, Favorites is shown. A lock screen waits until unlock.

## Settings

- Move each app between the bar, Favorites, the rest of the drawer, and hidden. Reorder the bar and Favorites. Favorites can be shown A to Z instead; the order you set is kept for when you turn that off.
- Choose the bar edge.
- Set the bar icon size and how solid the capsule is.
- Set the drawer columns, rows, and icon size for each page.
- Set how dark the icon plates are.
- Pick a photo for the drawer, or go back to the built-in nature photo.

The look is a dark Material 3 theme, leaf on charcoal, over that photo.

## Build

You need JDK 17 and Android SDK platform 35. The Gradle wrapper downloads Gradle 8.11.1.

```sh
export JAVA_HOME=/path/to/jdk-17
export ANDROID_HOME=/path/to/sdk
echo "sdk.dir=$ANDROID_HOME" > local.properties
./gradlew assembleDebug
```

`local.properties` stays on your machine. The debug APK is written to `app/build/outputs/apk/debug/app-debug.apk`.

The app targets SDK 35 and runs on Android 8.0 and newer.

## Install

Install the APK on the tablet, then set Fridge board as the Home app under Settings, Apps, Default apps, Home app. Grant "Display over other apps". On HyperOS, also allow the background pop-up if it asks.

Installing an update clears the Home role. Choose Fridge board as the Home app again after each install. The system launcher stays installed, and switching Home back to it leaves Fridge board.

This is an ordinary home app. It is not a device-owner lock, and it does not use an accessibility service. On HyperOS, gesture navigation belongs to the system launcher, so the tablet uses the 3-button bar while Fridge board is the Home app.

On some HyperOS builds, install the APK from the tablet's Files app. `adb install` can be refused there.
