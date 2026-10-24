# Installing AutoNavAlerts as a test user

How to get a test build onto your phone so that it shows up on your car's Android Auto screen, set it up, and send back what you find. Follow the steps in order.

> **Where this stands (2026-10-24).** Test builds are installed over USB with adb. That gets the app onto the car screen, but Android Auto treats a sideloaded navigation app as a developer install: Google's own guidance says the "Unknown sources" setting doesn't cover apps built with the Car App Library, and the app never stays in Android Auto's **Customise Launcher** list. The proper route, which is being set up, is a **Google Play internal testing** release: you'll get an invitation link, install from the Play Store like any app, and steps 2, 3 and 5 below go away. Until then, use this guide.

Every setting is explained in [SETTINGS.md](SETTINGS.md). This guide only covers getting from nothing to a working car screen.

---

## 1. What you need

| | Needed | Notes |
|---|---|---|
| Phone | Android 9 or later with **Android Auto** installed and up to date (Play Store) | The app itself runs from Android 8.0. Tested on an OPPO (ColorOS, Android 16) and the Android emulator |
| Car | A car or head unit with Android Auto, wired (a **data** cable, not a charge-only one) or wireless | Tested in an XPENG G6 and an MG4 |
| Computer | Windows, macOS or Linux with **adb** (Android platform-tools) and a USB data cable | adb is a 10 MB download from Google, see step 2. The install **must** be done with adb, not by tapping the APK on the phone (step 3) |
| Google account | A Google account with a **Google Cloud billing account** (a card on your Google payments profile), for your own Maps key | Google Maps use is billed to **you**, within Google's free monthly allowance for normal use. There is no shared key in the app |
| PocketGPSWorld (optional) | Your own PocketGPSWorld subscription login, for UK speed cameras | Without it you can still import your own POI files (iGO, OV2, KML, GPX, CSV) |
| Wi-Fi | For the one-off UK speed-limit download (about 1.1 GB fetched, 29 MB kept) | Optional. Only fills in the speed-limit sign where Google and the cameras give none |

Ask the maintainer to **add your Google account as a tester** for Google sign-in before you start. Until Google has verified the app, only listed accounts can use the one-press key creation in step 4; everyone else uses the step-by-step guide instead, which works but takes a few minutes longer.

## 2. Get the APK and adb

1. Download the latest build from the **Releases** page: <https://github.com/damo2929/AutoNavAlerts/releases>. The file is `AutoNavAlerts-<version>.apk`. The release notes say which commit it was built from.
2. Install adb if you don't have it: download **SDK Platform-Tools** from <https://developer.android.com/tools/releases/platform-tools>, unzip it anywhere, and run the commands below from inside that folder (or add it to your PATH). If you have Android Studio, adb is already in `<Android SDK>/platform-tools`.
3. On the phone, turn on **USB debugging**: Settings → About phone → tap **Build number** 7 times, then Settings → System → **Developer options** → **USB debugging** on. (The path varies by make; search Settings for "Build number" and "USB debugging".)
4. Connect the phone with the cable and run:
   ```bash
   adb devices
   ```
   The phone shows a prompt "Allow USB debugging?": tick **Always allow** and accept. `adb devices` should then list one device marked `device`. If it says `unauthorized`, look at the phone for the prompt; if it lists nothing, try another cable or USB port, and on Windows let the phone's driver install.

## 3. Install the app (with the flag Android Auto needs)

Run this from the folder with the APK:

```bash
adb install -r -i com.android.vending AutoNavAlerts-<version>.apk
```

- **Keep the `-i com.android.vending`.** It records the Play Store as the app's installer, which is what Android Auto looks for. Don't install by tapping the APK in a file manager.
- **`-r`** replaces an existing install and keeps its data, so updates keep your Maps key, logins and settings. All official builds are signed with the same key (SHA-1 starting `6C:48:A1:E5`), so updating never needs an uninstall.
- It ends with `Success`. The app's icon is now on the phone.

**Had an earlier test build?** Builds before 2026-10-24 used the package `io.autonav.alerts`; from now on it is `uk.damiendye.autonav`, so Android treats the new build as a separate app. In the old app take Settings → Backup → **Save settings backup…**, install the new one, restore the backup there, then uninstall the old one (`adb uninstall io.autonav.alerts`). The key-creation button makes a fresh key for the new package by itself.

**Updating to a new build** is the same command with the new file. Take a settings backup first if you like (Settings → Backup → **Save settings backup…**).

**If you ever see `INSTALL_FAILED_UPDATE_INCOMPATIBLE`**, the installed app was signed with a different key (for example a build you made yourself). Uninstall it first (`adb uninstall uk.damiendye.autonav`), which clears its data, then install again and restore your backup.

## 4. Set the app up on the phone

Open **AutoNavAlerts**. It opens on the **Settings** tab and stays there until setup is complete. Work down the page:

1. **Google Maps API key.** Press **Create a key with my Google account**, read what it will create (one Google Cloud project called "AutoNavAlerts", three APIs turned on, one key only this app can use, linked to your billing account), then **Continue**. Pick your Google account and allow the Google Cloud permission. Progress lines appear under the button; it ends with "✓ Key created in project …".
   - **No billing account?** It stops with an **Open** button that takes you to Google Cloud to add one. Add it, come back, press **Try again**.
   - **"Automatic set-up isn't available for this copy of the app"** means your account isn't on the tester list yet. Either ask the maintainer to add it, or use **Set it up yourself in Google Cloud (step by step)**, which opens just below: five steps, each with an **Open** button for the right Google Cloud page, and **Paste key from clipboard** at the end.
   - Already have a key? Paste it in the field and press **Save key**. It must have the Navigation SDK, Maps SDK for Android and Places API (New) enabled, and be restricted to package `uk.damiendye.autonav` with the SHA-1 the guide shows.
2. **Permissions → Allow.** Accept location (precise), notifications, microphone and car speed. Then **Allow all the time** for background location, so alerts keep working while music is on the car screen.
3. **Navigation terms → Review and accept.** Google's Navigation SDK terms. The button is greyed out until a key is saved. You only do this once.
4. **Contacts** (optional): **Allow contacts** lets you drive to a contact's address from the car.

When all of that is done the four sections collapse into one line, "✓ Maps key saved, permissions allowed, navigation terms accepted", and the app opens on the POIs tab from now on.

## 5. Tell Android Auto to show the app

1. In AutoNavAlerts: Settings → **Connecting to a car → Show → Open Android Auto settings** (or open Android Auto's settings from your phone's Settings).
2. Scroll to the bottom and tap **Version** about 10 times, then **OK**. The **⋮** menu at the top right now has **Developer settings**.
3. **⋮ → Developer settings** → tick **Unknown sources** (near the bottom of the list). Go back.
4. **Don't look for AutoNavAlerts in Customise Launcher.** With a sideloaded build it isn't kept in that list (it may show up for a while after a car session, then vanish after a reboot). That is expected and doesn't stop it appearing on the car. The list will be right once the app comes from Google Play.

Don't turn on anything else under Developer settings, and don't start the "head unit server" unless the maintainer asks you to debug with a computer.

## 6. Add cameras and speed limits

- **PocketGPSWorld cameras:** **Cameras** tab → enter your PocketGPSWorld username and password → **Save and test** → **Sync now**. If nothing downloads, press **Force re-download**. The default download is the TomTom OV2 "UK speed zoned complete" package; every file in it becomes its own set ("PGW fixed cameras · 30 mph" and so on), grouped by camera type. From then on the app checks their feed daily and downloads new releases itself. Your login and the data stay on your phone; they're never shared.
- **Your own POI files:** **POIs** tab → **Import file**. iGO TXT, TomTom OV2, KML, GPX or CSV, zipped or not. Icons in the zip are picked up automatically.
- **Speed limits (optional):** Settings → **Speed limits (OpenStreetMap)** → **Download**. Wi-Fi only by default. This gives the speed panel a limit on roads where Google and the cameras don't.

## 7. In the car

1. Open AutoNavAlerts on the phone once after setup (so Android Auto sees a finished app), then connect to the car as you normally do.
2. On the car screen, open the app grid and tap **AutoNavAlerts**. Android Auto then treats it as your navigation app and reopens it next time.
3. You should see the Google map with your position, a **Satellite** pill, **+ / −** and a recenter button down the right-hand edge, and the speed panel bottom right. Search for a destination and press **Go**. The alert banner appears at the bottom of the map when a camera is coming up, and the alerts are spoken.

**If AutoNavAlerts isn't in the car's app grid:**
1. Check the installer: `adb shell dumpsys package uk.damiendye.autonav | grep installerPackageName` must say `com.android.vending`. If not, run the install command from step 3 again.
2. Check Android Auto developer mode is still on and **Unknown sources** is ticked (step 5).
3. Open AutoNavAlerts on the phone once, disconnect from the car and reconnect. Android Auto only picks up a newly installed app at the start of a session.
4. Still missing: force stop Android Auto on the phone (Settings → Apps → Android Auto → Force stop) and reconnect.
5. Tell the maintainer which car and head unit, and whether a Play internal-test install is available yet: that is the supported route.

**If the car screen says "Finish setup on your phone"**, it names the reason: no key, terms not accepted, location missing, or the key isn't authorised. Go back to step 4.

## 8. Reporting what you find

The app keeps two debug logs on the phone all the time, in private storage, and can record a drive. They contain where you drove and which cameras you passed, so share them only with the maintainer.

**Before a test drive:** Settings → **Diagnostics → Show** → **Clear** both logs and turn **Drive recording** on. Put the phone where it can see the sky.

**During the drive:** when something is wrong (an alert that didn't speak, a marker that vanished, a route that cleared, the car arrow in the wrong place), note the time, or tap **Report** on the car screen: each Report tap lands in the logs as a timestamp the maintainer can find.

**After the drive:** Settings → Diagnostics → **Share** for each log and **Save drive recording…**, and send them with a short description (what happened, roughly when, the car and head unit). Open an issue at <https://github.com/damo2929/AutoNavAlerts/issues> or send them to the maintainer directly. Settings → **About** shows the exact build you were running; include it.

**Camera reports** you tapped in the car wait on the **Reports** tab. **Send** really submits them to PocketGPSWorld's camera form under your login, so only send ones you've checked.

## 9. Quick reference

```bash
adb devices                                                   # phone listed as "device"?
adb install -r -i com.android.vending AutoNavAlerts-<version>.apk   # install or update
adb shell dumpsys package uk.damiendye.autonav | grep -E 'versionName|installerPackageName'   # what's installed, and that the installer is com.android.vending
adb uninstall uk.damiendye.autonav                               # only if the signing key changed (clears all data)
```

| Symptom | Cause | Fix |
|---|---|---|
| Not in Android Auto's Customise Launcher | Expected for a sideloaded Car App Library app | Nothing: it can still appear on the car. Fixed for good by the Play internal-test release |
| Not in the car's app grid | Installer not recorded, developer mode off, or the session started before the install | Check the installer and developer mode; open the app once; reconnect; force stop Android Auto and reconnect |
| "Finish setup on your phone" on the car | Key, terms or location missing | Settings tab, step 4 |
| Grey map, no tiles | Key not working, or no data connection | Check the key is saved and the phone has data; a self-built APK needs its own SHA-1 on the key |
| **Create a key** button greyed out | Your account isn't a listed tester | Ask to be added, or use the step-by-step guide |
| `INSTALL_FAILED_UPDATE_INCOMPATIBLE` | Different signing key already installed | `adb uninstall uk.damiendye.autonav`, install, restore backup |
| `adb devices` shows `unauthorized` | USB debugging prompt not accepted | Accept it on the phone (tick Always allow) |
| No speech | Alerts voice off, phone media volume, or Android Auto muted | Settings → Alerts → Voice; car volume; More → **Unmute alerts** on the car screen |
