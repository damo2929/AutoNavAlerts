# Installing AutoNavAlerts as a test user

How to get a test build onto your phone so that it shows up on your car's Android Auto screen, set it up, and send back what you find. Follow the steps in order.

> **Where this stands (2026-10-24).** AutoNavAlerts is on **Google Play's internal testing track**. You install it from the Play Store like any other app, it updates itself, and Android Auto treats it as a normal navigation app. Nothing needs a computer. The adb route in §3 is only for developers and for anyone the maintainer asks to try a build that isn't on Play yet.

Every setting is explained in [SETTINGS.md](SETTINGS.md). This guide only covers getting from nothing to a working car screen.

---

## 1. What you need

| | Needed | Notes |
|---|---|---|
| Phone | Android 9 or later with **Android Auto** installed and up to date (Play Store) | The app itself runs from Android 8.0. Tested on an OPPO (ColorOS, Android 16) and the Android emulator |
| Car | A car or head unit with Android Auto, wired (a **data** cable, not a charge-only one) or wireless | Tested in an XPENG G6 and an MG4 |
| Google account | The Google account your phone's Play Store uses, with a **Google Cloud billing account** (a card on your Google payments profile), for your own Maps key | Google Maps use is billed to **you**, within Google's free monthly allowance for normal use. There is no shared key in the app |
| PocketGPSWorld (optional) | Your own PocketGPSWorld subscription login, for UK speed cameras | Without it you can still import your own POI files (iGO, OV2, KML, GPX, CSV) |
| Wi-Fi | For the one-off UK speed-limit download (about 1.1 GB fetched, 29 MB kept) | Optional. Only fills in the speed-limit sign where Google and the cameras give none |

**Send the maintainer the Google account address your phone uses** before you start. It has to be added to two lists: the Play **tester list** (otherwise the install link in step 2 says the app isn't available) and the Google sign-in tester list (otherwise the one-press key creation in step 4 isn't available and you use the step-by-step guide instead, which works but takes a few minutes longer).

## 2. Install from Google Play

1. Once the maintainer has added your account, open this link **on the phone**, signed in to that account:

   **<https://play.google.com/apps/internaltest/4701745139667194997>**

2. Press **Become a tester** (it's one tap; you can leave the programme from the same page later).
3. Press the link to the Play Store on that page, or search the Play Store for the app, and **Install**. Until Google has reviewed the store listing the Play Store shows it under a temporary name, `uk.damiendye.autonav (unreviewed)`; that's expected.
4. Updates arrive through the Play Store like any app (Play Store → your profile → Manage apps and device → Update), usually within an hour of the maintainer publishing one.

**Had an adb-installed build before?** If it was the same package (`uk.damiendye.autonav`), Play updates it in place and your settings survive. If it was the older `io.autonav.alerts`, take Settings → Backup → **Save settings backup…** in it first, install from Play, restore the backup in the new app, then uninstall the old one.

## 3. Developer route: APK with adb (only if the maintainer asks)

Skip this section if you installed from Play.

1. Download the build from the **Releases** page: <https://github.com/damo2929/AutoNavAlerts/releases>. The file is `AutoNavAlerts-<version>.apk`.
2. Install adb if you don't have it: **SDK Platform-Tools** from <https://developer.android.com/tools/releases/platform-tools>, unzipped anywhere (or `<Android SDK>/platform-tools` if you have Android Studio).
3. On the phone, turn on **USB debugging**: Settings → About phone → tap **Build number** 7 times, then Settings → System → **Developer options** → **USB debugging** on. Connect the cable, run `adb devices`, accept the prompt on the phone (tick **Always allow**).
4. Install with the flag Android Auto needs:
   ```bash
   adb install -r -i com.android.vending AutoNavAlerts-<version>.apk
   ```
   `-i com.android.vending` records the Play Store as the installer; `-r` keeps the data of an existing install. All official builds are signed with the same key (SHA-1 starting `6C:48:A1:E5`), so updating never needs an uninstall. `INSTALL_FAILED_UPDATE_INCOMPATIBLE` means a build signed with another key is installed: `adb uninstall uk.damiendye.autonav` (clears its data), install again, restore your backup.
5. In Android Auto's settings, tap **Version** about 10 times for developer mode, then **⋮ → Developer settings → Unknown sources**. A sideloaded build is never kept in Android Auto's **Customise Launcher** list; that is expected and doesn't stop it appearing on the car.

## 4. Set the app up on the phone

Open **AutoNavAlerts**. It opens on the **Settings** tab and stays there until setup is complete. Work down the page:

1. **Google Maps API key.** Press **Create a key with my Google account**, read what it will create (one Google Cloud project called "AutoNavAlerts", three APIs turned on, one key only this app can use, linked to your billing account), then **Continue**. Pick your Google account and allow the Google Cloud permission. Progress lines appear under the button; it ends with "✓ Key created in project …".
   - **No billing account?** It stops with an **Open** button that takes you to Google Cloud to add one. Add it, come back, press **Try again**.
   - **"Automatic set-up isn't available for this copy of the app"** means your account isn't on the sign-in tester list yet. Either ask the maintainer to add it, or use **Set it up yourself in Google Cloud (step by step)**, which opens just below: five steps, each with an **Open** button for the right Google Cloud page, and **Paste key from clipboard** at the end.
   - Already have a key? Paste it in the field and press **Save key**. It must have the Navigation SDK, Maps SDK for Android and Places API (New) enabled, and be restricted to package `uk.damiendye.autonav` with the SHA-1 the guide shows.
2. **Permissions → Allow.** Accept location (precise), notifications, microphone and car speed. Then **Allow all the time** for background location, so alerts keep working while music is on the car screen.
3. **Navigation terms → Review and accept.** Google's Navigation SDK terms. The button is greyed out until a key is saved. You only do this once.
4. **Contacts** (optional): **Allow contacts** lets you drive to a contact's address from the car.

When all of that is done the four sections collapse into one line, "✓ Maps key saved, permissions allowed, navigation terms accepted", and the app opens on the POIs tab from now on.

## 5. Android Auto

With a Play install there is nothing to switch on. Open AutoNavAlerts on the phone once after setup, then in Android Auto's settings → **Customise Launcher** you should find AutoNavAlerts in the list (tick it if it isn't ticked). Please tell the maintainer whether it is there, and whether it is still there after a phone reboot: that is the one thing the Play route is meant to fix and it hasn't been confirmed on a tester's phone yet.

Don't turn on developer mode, and don't start the "head unit server", unless the maintainer asks you to debug with a computer.

## 6. Add cameras and speed limits

- **PocketGPSWorld cameras:** **Cameras** tab → enter your PocketGPSWorld username and password → **Save and test** → **Sync now**. If nothing downloads, press **Force re-download**. The default download is the TomTom OV2 "UK speed zoned complete" package; every file in it becomes its own set ("PGW fixed cameras · 30 mph" and so on), grouped by camera type. From then on the app checks their feed daily and downloads new releases itself. Your login and the data stay on your phone; they're never shared.
- **Your own POI files:** **POIs** tab → **Import file**. iGO TXT, TomTom OV2, KML, GPX or CSV, zipped or not. Icons in the zip are picked up automatically.
- **Speed limits (optional):** Settings → **Speed limits (OpenStreetMap)** → **Download**. Wi-Fi only by default. This gives the speed panel a limit on roads where Google and the cameras don't.

## 7. In the car

1. Open AutoNavAlerts on the phone once after setup (so Android Auto sees a finished app), then connect to the car as you normally do.
2. On the car screen, open the app grid and tap **AutoNavAlerts**. Android Auto then treats it as your navigation app and reopens it next time.
3. You should see the Google map with your position, a **Satellite** pill, **+ / −** and a recenter button down the right-hand edge, and the speed panel bottom right. Search for a destination and press **Go**. The alert banner appears at the bottom of the map when a camera is coming up, and the alerts are spoken.

**If AutoNavAlerts isn't in the car's app grid:**
1. Open AutoNavAlerts on the phone once, disconnect from the car and reconnect. Android Auto only picks up a newly installed app at the start of a session.
2. Still missing: force stop Android Auto on the phone (Settings → Apps → Android Auto → Force stop) and reconnect.
3. An adb-installed build also needs developer mode and **Unknown sources** (§3 step 5) and the installer flag: `adb shell dumpsys package uk.damiendye.autonav | grep installerPackageName` must say `com.android.vending`.
4. Tell the maintainer which car and head unit, how you installed, and whether the app is in Customise Launcher.

**If the car screen says "Finish setup on your phone"**, it names the reason: no key, terms not accepted, location missing, or the key isn't authorised. Go back to step 4.

## 8. Reporting what you find

The app keeps two debug logs on the phone all the time, in private storage, and can record a drive. They contain where you drove and which cameras you passed, so share them only with the maintainer.

**Before a test drive:** Settings → **Diagnostics → Show** → **Clear** both logs and turn **Drive recording** on. Put the phone where it can see the sky.

**During the drive:** when something is wrong (an alert that didn't speak, a marker that vanished, a route that cleared, the car arrow in the wrong place), note the time, or tap **Report** on the car screen: each Report tap lands in the logs as a timestamp the maintainer can find.

**After the drive:** Settings → Diagnostics → **Share** for each log and **Save drive recording…**, and send them with a short description (what happened, roughly when, the car and head unit). Open an issue at <https://github.com/damo2929/AutoNavAlerts/issues> or send them to the maintainer directly. Settings → **About** shows the exact build you were running; include it.

**Camera reports** you tapped in the car wait on the **Reports** tab. **Send** really submits them to PocketGPSWorld's camera form under your login, so only send ones you've checked.

## 9. Quick reference

| Symptom | Cause | Fix |
|---|---|---|
| The Play link says the app isn't available, or "not found" | Your Google account isn't on the tester list, or you're signed in to a different account | Send the maintainer the account the phone's Play Store uses; open the link in a browser signed in to it |
| Play Store shows `uk.damiendye.autonav (unreviewed)` | The store listing hasn't been reviewed yet | Nothing; it's the right app |
| Not in Android Auto's Customise Launcher (Play install) | Not expected | Reboot the phone and look again; tell the maintainer either way |
| Not in Android Auto's Customise Launcher (adb install) | Expected for a sideloaded Car App Library app | Nothing: it can still appear on the car |
| Not in the car's app grid | The session started before the install; for adb installs also developer mode / installer flag | Open the app once; reconnect; force stop Android Auto and reconnect |
| "Finish setup on your phone" on the car | Key, terms or location missing | Settings tab, step 4 |
| Grey map, no tiles | Key not working, or no data connection | Check the key is saved and the phone has data; a self-built APK needs its own SHA-1 on the key |
| **Create a key** button greyed out | Your account isn't a listed sign-in tester | Ask to be added, or use the step-by-step guide |
| `INSTALL_FAILED_UPDATE_INCOMPATIBLE` (adb) | Different signing key already installed | `adb uninstall uk.damiendye.autonav`, install, restore backup |
| No speech | Alerts voice off, phone media volume, or Android Auto muted | Settings → Alerts → Voice; car volume; More → **Unmute alerts** on the car screen |
