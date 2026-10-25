# Installing AutoNavAlerts as a tester

This guide takes you from nothing to a working car screen.
Please follow the steps in order.

AutoNavAlerts is on Google Play's **internal testing track**.
You install it from the Play Store like any other app.
It updates itself.
Android Auto treats it as a normal navigation app.
You do not need a computer.

Section 3 is a different route, for developers only.

Every setting is explained in [SETTINGS.md](SETTINGS.md).

## What is in this document

1. [What you need](#1-what-you-need)
2. [Install from Google Play](#2-install-from-google-play)
3. [Developer route: install with a cable](#3-developer-route-install-with-a-cable)
4. [Set the app up on the phone](#4-set-the-app-up-on-the-phone)
5. [Android Auto](#5-android-auto)
6. [Add cameras and speed limits](#6-add-cameras-and-speed-limits)
7. [In the car](#7-in-the-car)
8. [Reporting what you find](#8-reporting-what-you-find)
9. [Quick reference](#9-quick-reference)

## 1. What you need

| | You need | Notes |
|---|---|---|
| Phone | Android 9 or later, with **Android Auto** installed and up to date | The app itself runs from Android 8.0. Tested on an OPPO phone (Android 16) and the Android emulator |
| Car | A car or head unit with Android Auto, wired or wireless | A wired car needs a **data** cable, not a charge-only one. Driven in an XPENG G6 (wireless). Not yet tried in an MG4 |
| Google account | The Google account your phone's Play Store uses, with a **Google Cloud billing account** | Google Maps use is billed to **you**, within Google's free monthly allowance for normal use. There is no shared key in the app. A billing account is a card on your Google payments profile |
| PocketGPSWorld (optional) | Your own PocketGPSWorld subscription login, for UK speed cameras | Without it you can still import your own POI files |
| Wi-Fi (optional) | For the one-off UK speed limit download | About 1.1 GB is fetched. The phone keeps 29 MB. It only fills in the speed limit sign where Google and the cameras give none |

**Before you start, please send the maintainer the Google account address your phone uses.**

It goes on two lists:

- the Play **tester list**. Without it, the install link in step 2 says the app is not available.
- the Google sign-in **tester list**. Without it, the one-press key creation in step 4 is not available. You then use the step-by-step guide instead. It works, but takes a few minutes longer.

## 2. Install from Google Play

1. Wait for the maintainer to confirm your account is added.
2. On the phone, signed in to that account, open this link:

   **<https://play.google.com/apps/internaltest/4701745139667194997>**

3. Press **Become a tester**. This is one tap. You can leave the programme from the same page later.
4. Press the link to the Play Store on that page, then **Install**.
   Until Google has reviewed the store listing, the Play Store shows the app under a temporary name, `uk.damiendye.autonav (unreviewed)`. That is expected.
5. Updates arrive through the Play Store like any app. They usually appear within an hour of the maintainer publishing one.

**The Play Store page says the app is not found?**
Wait a few minutes and try again. A first download can take a little while to become available.
Also check that the Play Store is signed in to the account you sent the maintainer.
If it still fails, clear the Play Store's cache (phone Settings → Apps → Google Play Store → Storage → Clear cache) and try again.

**Had an earlier test build installed with a cable?**
If it was the same package, `uk.damiendye.autonav`, Play updates it in place and your settings survive.
If it was the older package, `io.autonav.alerts`, save a backup first: Settings → Backup → **Save settings backup…**.
Then install from Play, restore the backup in the new app, and uninstall the old one.

## 3. Developer route: install with a cable

Skip this section if you installed from Play.
It is only for developers, and for anyone the maintainer asks to try a build that is not on Play yet.

1. Download the build from the **Releases** page: <https://github.com/damo2929/AutoNavAlerts/releases>. The file is `AutoNavAlerts-<version>.apk`.
   (An APK is an Android app package.)
2. Install adb if you do not have it. adb is the Android Debug Bridge, Google's command-line tool for phones.
   Download **SDK Platform-Tools** from <https://developer.android.com/tools/releases/platform-tools> and unzip it anywhere.
   If you have Android Studio, adb is already in `<Android SDK>/platform-tools`.
3. On the phone, turn on **USB debugging**: Settings → About phone → tap **Build number** 7 times, then Settings → System → **Developer options** → **USB debugging**.
   Connect the cable, run `adb devices`, and accept the prompt on the phone (tick **Always allow**).
4. Install with the flag Android Auto needs:
   ```bash
   adb install -r -i com.android.vending AutoNavAlerts-<version>.apk
   ```
   `-i com.android.vending` records the Play Store as the installer.
   `-r` keeps the data of an existing install.
   All official builds are signed with the same key, so updating never needs an uninstall.
   If you see `INSTALL_FAILED_UPDATE_INCOMPATIBLE`, a build signed with another key is installed: run `adb uninstall uk.damiendye.autonav` (this clears its data), install again, and restore your backup.
5. In Android Auto's settings, tap **Version** about 10 times to turn on developer mode. Then **⋮ → Developer settings → Unknown sources**.
   A build installed this way is never kept in Android Auto's **Customise Launcher** list. That is expected. It still appears on the car.

## 4. Set the app up on the phone

Open **AutoNavAlerts**.
It opens on the **Settings** tab and stays there until setup is complete.
Please work down the page.

### 4.1 Google Maps API key

An API key is a code that lets the app use Google Maps on your account.

Press **Create a key with my Google account**.
The app shows what it will create: one Google Cloud project called "AutoNavAlerts", three services turned on, and one key only this app can use, linked to your billing account.
Press **Continue**, pick your Google account, and allow the Google Cloud permission.
Progress lines appear under the button. It ends with "✓ Key created in project …".

*[Image: a phone, an arrow to a cloud labelled Google Cloud, and a key coming back to the phone]*

- **No billing account?** The app stops with an **Open** button that takes you to Google Cloud to add one. Add it, come back, and press **Try again**.
- **"Automatic set-up isn't available for this copy of the app"** means your account is not on the sign-in tester list yet.
  Either ask the maintainer to add it, or use **Set it up yourself in Google Cloud (step by step)**, which opens just below.
  It has five steps, each with an **Open** button for the right Google Cloud page, and **Paste key from clipboard** at the end.
- **Already have a key?** Paste it in the field and press **Save key**.
  It must have the Navigation SDK, the Maps SDK for Android and the Places API (New) turned on, and be restricted to the package `uk.damiendye.autonav` with the fingerprint the guide shows.

### 4.2 Permissions

Press **Allow**. Accept location (precise), notifications, the microphone and car speed.
Then press **Allow all the time** for background location.
This keeps alerts working while music is on the car screen.

### 4.3 Navigation terms

Press **Review and accept**. These are Google's Navigation SDK terms.
The button is greyed out until a key is saved.
You only do this once.

### 4.4 Contacts (optional)

**Allow contacts** lets you drive to a contact's address from the car.

When all of this is done, the four sections collapse into one line: "✓ Maps key saved, permissions allowed, navigation terms accepted".
The app then opens on the POIs tab from now on.

## 5. Android Auto

With a Play install there is nothing to switch on.

1. Open AutoNavAlerts on the phone once after setup.
2. In Android Auto's settings, open **Customise Launcher**. AutoNavAlerts should be in the list. Tick it if it is not ticked.
3. Please tell the maintainer whether it is there, and whether it is still there after you restart the phone.
   This is the one thing the move to Google Play is meant to fix, and it has not been confirmed on a tester's phone yet.

Please do not turn on developer mode, and do not start the "head unit server", unless the maintainer asks you to help debug with a computer.

## 6. Add cameras and speed limits

**PocketGPSWorld cameras.** On the **Cameras** tab, enter your PocketGPSWorld username and password, press **Save and test**, then **Sync now**.
If nothing downloads, press **Force re-download**.
The default download is the TomTom OV2 "UK speed zoned complete" package.
Every file in it becomes its own set, for example "PGW fixed cameras · 30 mph", grouped by camera type.
From then on the app checks their feed daily and downloads new releases itself.
Your login and the data stay on your phone. They are never shared.

**Your own POI files.** On the **POIs** tab, press **Import file**.
iGO TXT, TomTom OV2, KML, GPX or CSV, zipped or not.
Icons in a zip are picked up automatically.

**Speed limits (optional).** Settings → **Speed limits (OpenStreetMap)** → **Download**.
Wi-Fi only by default.
This gives the speed panel a limit on roads where Google and the cameras do not.

## 7. In the car

1. Open AutoNavAlerts on the phone once after setup, so Android Auto sees a finished app. Then connect to the car as you normally do.
2. On the car screen, open the app grid and tap **AutoNavAlerts**. Android Auto then treats it as your navigation app and reopens it next time.
3. You should see the Google map with your position, a **Satellite** button, **+ / −** and a recenter button down the right-hand edge, and the speed panel at the bottom right.
   Search for a destination and press **Go**.
   The alert banner appears at the bottom of the map when a camera is coming up, and the alert is spoken.

**AutoNavAlerts is not in the car's app grid?**

1. Open AutoNavAlerts on the phone once, disconnect from the car, and reconnect. Android Auto only notices a newly installed app at the start of a session.
2. Still missing: force stop Android Auto on the phone (Settings → Apps → Android Auto → Force stop) and reconnect.
3. A developer build also needs developer mode and **Unknown sources** (section 3, step 5), and the installer flag. `adb shell dumpsys package uk.damiendye.autonav | grep installerPackageName` must say `com.android.vending`.
4. Tell the maintainer which car and head unit you have, how you installed, and whether the app is in Customise Launcher.

**The car screen says "Finish setup on your phone"?**
It names the reason: no key, terms not accepted, location missing, or the key is not authorised. Go back to section 4.

## 8. Reporting what you find

The app keeps two debug logs on the phone all the time, in private storage. It can also record a drive.
They contain where you drove and which cameras you passed, so please share them only with the maintainer.

**Before a test drive.** Settings → **Diagnostics → Show** → **Clear** both logs, and turn **Drive recording** on.
Put the phone where it can see the sky.

**During the drive.** When something is wrong (an alert that did not speak, a marker that vanished, a route that cleared, the car arrow in the wrong place), note the time.
Or tap **Report** on the car screen. Each Report tap leaves a timestamp in the logs that the maintainer can find.

**After the drive.** Settings → Diagnostics → **Share** for each log, and **Save drive recording…**.
Send them with a short description: what happened, roughly when, the car and head unit.
Open an issue at <https://github.com/damo2929/AutoNavAlerts/issues> or send them to the maintainer directly.
Settings → **About** shows the exact build you were running. Please include it.

**Camera reports** you tapped in the car wait on the **Reports** tab.
**Send** really submits them to PocketGPSWorld under your login, so please only send the ones you have checked.

## 9. Quick reference

| What you see | Why | What to do |
|---|---|---|
| The Play link says the app is not available or not found | Your Google account is not on the tester list, or you are signed in to a different account | Send the maintainer the account the phone's Play Store uses. Open the link signed in to it |
| Play Store shows `uk.damiendye.autonav (unreviewed)` | The store listing has not been reviewed yet | Nothing. It is the right app |
| Not in Android Auto's Customise Launcher (Play install) | Not expected | Restart the phone and look again. Tell the maintainer either way |
| Not in Android Auto's Customise Launcher (cable install) | Expected for a developer build | Nothing. It can still appear on the car |
| Not in the car's app grid | The session started before the install. A cable install also needs developer mode and the installer flag | Open the app once, reconnect, then force stop Android Auto and reconnect |
| "Finish setup on your phone" on the car | Key, terms or location missing | Settings tab, section 4 |
| Grey map, no tiles | The key is not working, or there is no data connection | Check the key is saved and the phone has data. A build you made yourself needs its own fingerprint on the key |
| **Create a key** button greyed out | Your account is not a listed sign-in tester | Ask to be added, or use the step-by-step guide |
| `INSTALL_FAILED_UPDATE_INCOMPATIBLE` (cable install) | A build signed with a different key is installed | `adb uninstall uk.damiendye.autonav`, install, restore your backup |
| No speech | The alerts voice is off, the phone's media volume is down, or Android Auto is muted | Settings → Alerts → Voice. Car volume. More → **Unmute alerts** on the car screen |

---

First published: 2026-10-24
Last updated: 2026-10-24
Pictures: not added yet. The lines in square brackets mark where they go.
