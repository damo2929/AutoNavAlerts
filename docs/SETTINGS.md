# AutoNavAlerts settings

This document explains every setting in the app: where it is, what it does, and its default.

Settings are stored on the phone only.
They are not backed up to the cloud.
After a reinstall, the app starts from the defaults.
You can keep a settings backup file yourself (section 1.7).

The phone app has four tabs along the bottom: **POIs · Cameras · Reports · Settings**.
The setup steps (Maps key, permissions, navigation terms, Android Auto) are the first sections of **Settings**.
The app opens on Settings until setup is done.
A few settings are also on the car screen, under **More**.

A point of interest (POI) is a place marked on the map, such as a speed camera.
PocketGPSWorld (PGW) is the UK speed camera database you subscribe to yourself.

Technical readers: each section names the DESIGN.md section that specifies it.

## What is in this document

1. [Settings tab](#1-settings-tab)
   - 1.1 Units · 1.2 Alerts · 1.3 Speed and location source · 1.4 Speed panel · 1.4a Speed limits (OpenStreetMap) · 1.4b Routing · 1.5 GPS rate · 1.6 Bench testing · 1.7 Backup · 1.8 Diagnostics · 1.8a Connecting to a car · 1.9 About
2. [Setup](#2-setup)
3. [POIs tab](#3-pois-tab)
4. [Cameras tab](#4-cameras-tab)
5. [Reports tab](#5-reports-tab)
6. [Car screen settings](#6-car-screen-settings)
7. [Storage and privacy](#7-storage-and-privacy)
8. [Defaults at a glance](#8-defaults-at-a-glance)

---

## 1. Settings tab

One scrolling page of cards.
Once setup is complete, its four setup sections collapse to one **Setup** row with a **Show** button.
**Units** and **Speed panel** share one card, called **Car screen**.
**Diagnostics** and **Connecting to a car** are folded behind **Show**. Closed, each is one line.
Each control has at most one short line of help on the phone. The detail is here.

### 1.1 Units

| Control | Options | Default | What it changes |
|---|---|---|---|
| Units | **mph, miles, yards** or **km/h, km, m** | mph, miles, yards | The speed panel and its limit sign. Distances in spoken and on-screen alerts. Distances on the turn card and arrival time. The speed choices for a report on the car screen (20 to 70 mph, or 30 to 120 km/h). The distance fields when you edit a POI set |

Camera speed limits keep the unit they were imported in.
PocketGPSWorld UK data is in mph.
The app converts limits for display and speech, so a 30 mph camera reads "48 km/h" in metric.

### 1.2 Alerts

Technical detail: DESIGN.md §11.1, §12.3, §8.4a.

| Control | Options | Default | What it does |
|---|---|---|---|
| **Voice** | On / Off | On | Speaks POI alerts on the navigation audio channel. Off silences all POI speech, including the passing alert. Cards and notifications still appear. It does not affect the turn-by-turn **directions**: the app speaks those itself, in the same voice, and they are always spoken |
| **Chime before the passing alert** | On / Off | On | Plays a short beep just before the passing alert. There is no chime before the 1st and 2nd alerts |
| **Over-limit warning** | Slider, −5% to +20% in 0.1% steps, plus **Reset to −0.1%** | −0.1% | When an alert fires and your speed is above the camera's limit (or the average speed zone's limit) by more than this, the spoken alert adds "you are over the limit". A negative value warns before the limit: −0.1% warns just below it, −5% warns at 95% of it (28.5 mph in a 30). 0% warns as soon as you are over. A positive value allows a margin: +10% warns above 33 mph in a 30. In an average speed zone the warning repeats at most every 15 seconds |
| **Announce speed limit changes ahead** | On / Off | On | As you approach a change in the road's limit, the new limit is spoken once ("Speed limit changes to thirty ahead"), about 300 m or 12 seconds before it. The map shows the new number on a light grey square where the change starts, until you are under the new limit. The limits come from the OpenStreetMap data on the phone (section 1.4a), so nothing is sent anywhere. While navigating, the change is looked for along the route. In free drive, along the road you are on. It follows the Voice setting and Mute, and never talks over a camera passing alert. Off: no announcement. The panel's limit still changes |
| **Alert lead time** | Slider, 15 to 60 seconds | 15 seconds | How far ahead the **1st alert** fires, in seconds at your current speed. The **2nd alert** fires at half that. The label reads "First alert: N s ahead · second alert at N/2 s", with the distances this gives at 30, 50 and 70 mph underneath. At the default: 242 m / 335 m / 469 m for the 1st, and 142 m / 168 m / 235 m for the 2nd. Minimum distances still apply: the 1st never fires closer than 242 m (265 yd), the 2nd never closer than 142 m (155 yd), and the 2nd is always at least 100 m inside the 1st. The slider sets the seconds for every set in "seconds ahead" mode. Sets in "distance" mode are not affected |

The voice used is the phone's default text-to-speech voice (Android Settings → text-to-speech output).
Changing it there changes both the alerts and the directions.

The over-limit setting applies to camera and POI limits, from your imported data.
The colour of the speed panel follows Google's limit separately (section 1.4).

The lead-time slider changes **when** alerts fire, not **whether** they fire.
A camera facing the other way still never alerts (section 3.3).

### 1.3 Speed and location source

Technical detail: DESIGN.md §10.6, §10.7.

| Control | Options | Default | What it does |
|---|---|---|---|
| **Speed and location source** | **Car when available** / Phone only | Car when available | Where your speed and position come from. With **Car when available**, the app reads the car's own speed and GPS position through Android Auto whenever the car shares them. If the car stops reporting for 3 seconds, the phone takes over at once. The car takes over again after two car reports in a row. **Phone only** ignores the car |

What the car's data is used for:

- **Speed panel**: the car's speedometer figure, so it matches your dashboard. Cars usually read a few percent high.
- **Alerts**: the car's measured road speed, for "seconds ahead" alert distances and the over-limit warning. It is more accurate than GPS in tunnels, under trees and at low speed.
- **Position**: the car's GPS, for camera alerts, marker updates and report positions. A car's GPS aerial is usually better placed than a phone in a pocket.
- The phone's GPS keeps running as a backup, at the rate the phone offers.

*[Image: a car and a phone, with an arrow showing the car's data used first and the phone's as backup]*

The map itself (the car marker and the route) follows the phone's location services.
Google's Navigation SDK cannot be given the car's GPS.
Many cars already pass their GPS to the phone through Android Auto.

The car screen's **More → GPS** row shows where the location and speed are coming from, for example "Location from car · speed from car".
Not every car or head unit shares its speed or GPS.
Some aftermarket units do not. The app needs car API level 3 or higher.

### 1.4 Speed panel

Technical detail: DESIGN.md §8.4.

| Control | Options | Default | What it does |
|---|---|---|---|
| **Show speed and limit on the map** | On / Off | On | Shows your current speed, and a speed limit sign next to it, in the bottom right corner of the car map. It stays clear of the turn card and the media panel |
| **Satellite map in the car** | On / Off | Off | Shows satellite pictures with road names instead of the road map. The same switch is the **Satellite / Map** button at the top right of the car map, and **More → Satellite map** in the car. All three are one setting |

What the panel shows:

- **Your speed**: the car's speedometer reading when the car shares it (section 1.3), otherwise the phone's GPS speed. It updates on every position fix, in your units.
- **The limit**:
  - Google's limit for the road. The Navigation SDK only reveals it while you are over it, so it shows while you are speeding and stays for 60 seconds afterwards.
  - Otherwise, the limit of the camera you are being warned about, marked with a small blue dot.
  - Otherwise, the OpenStreetMap limit (section 1.4a), marked with a grey dot.
  - If none is known, only your speed is shown.
- **Colour**: white normally. Amber at 5% over Google's limit. Red at 10% over. These thresholds match Google Maps and cannot be changed.

### 1.4a Speed limits (OpenStreetMap)

Technical detail: DESIGN.md §8.4a.

OpenStreetMap is a free map made by volunteers.
Its speed limits fill in the panel's limit when Google has not given one and no camera limit applies.

- **Show OpenStreetMap limits**: on by default. Off hides them. The download is kept.
- **Download on Wi-Fi only**: on by default, so the download waits for Wi-Fi. Turn it off to allow mobile data too. It is about 1.1 GB, so it uses a lot of mobile data.
- **Download speed limits for the UK** (later **Update speed limits**): fetches only the roads layer of Geofabrik's extracts for England, Scotland and Wales, plus Northern Ireland from the "Ireland and Northern Ireland" extract (only roads inside Northern Ireland are kept). About 1.1 GB is fetched. It runs in the background with a notification and a Cancel button, and shows its progress here. The result is one file of about 29 MB. The old file is kept until the new one is complete. On the bench it took under 3 minutes.
- **Status line**: the data's date, the number of roads with a limit, and the file size. Geofabrik refreshes daily. Updating every few months picks up new limits.
- **Delete downloaded limits** removes the file.
- **Licence**: © OpenStreetMap contributors (ODbL), extract by Geofabrik. The link opens openstreetmap.org/copyright.

### 1.4b Routing

Technical detail: DESIGN.md §8.3a.

- **Avoid tolls**: on by default. Routes avoid toll roads where there is a reasonable way round. If the only way is a toll road, it is still used.
- **Energy-efficient routes (EV)**: off by default. EV means electric vehicle. When on, the route preview offers two routes, **Fastest** and **Energy-efficient (EV)**, each with its time and distance. You pick one before **Go**. The energy-efficient one is Google's eco-friendly route for an electric car. It uses Google's Routes API on your own Maps key, billed to you like the rest. Your key needs the **Routes API** turned on: **Create a key with my Google account** does that (press it again if you made your key earlier), or turn it on yourself with the **Routes API** link in the guided steps. If it is not available, the fastest route is used and the preview says why.
- **Avoid unpaved roads** is not offered. Google's routing for apps has no such option.
- The same two switches are on the car under **More → Route options**. Changes apply to the next route.

### 1.5 GPS rate

Technical detail: DESIGN.md §10.2.

There is nothing to set, and no card on the phone.
The app asks the phone for one position a second, and accepts any faster fix the phone already delivers.
It never forces the phone faster.
The car's speed and GPS arrive as fast as the car provides them (section 1.3).

The car screen's **More → GPS** row shows the requested rate, the rate actually delivered, and your speed.
The map has its own location feed from the Navigation SDK.

### 1.6 Bench testing (debug builds only)

| Control | Options | Default | What it does |
|---|---|---|---|
| **Simulate speed** | 1×, 2×, 4× | 1× | The speed of the **Simulate** drive started from the car's route preview. 1× is real time. Alerts and the speed panel work during a simulation. See [TESTING.md](../TESTING.md) |

This section is not shown in release builds.

### 1.7 Backup

Technical detail: DESIGN.md §13.4.

| Control | What it does |
|---|---|
| **Save settings backup…** | Opens Android's file picker to save `autonavalerts-settings-YYYY-MM-DD.json` wherever you choose (Downloads, Drive, a USB stick). The file holds the settings on this tab, your Maps API key, your PocketGPSWorld username and password, and each camera set's own settings (show, alerts, sound, icon, spoken text, alert distances), by set name |
| **Restore from backup…** | Pick a backup file. The app checks it is an AutoNavAlerts backup it understands, shows what is in it (the date, whether the key and PGW login are included, how many camera sets match this phone), and asks before changing anything. **Restore** puts back the settings, the key and the login, and the settings of every camera set with the same name |

- **The file is plain text.** Anyone who has it can use your Maps key (billed to you) and your PocketGPSWorld login. Please keep it somewhere private, and do not share or commit it.
- **Not in the file**: camera data and imported POIs (download or import them again), camera reports, recent and favourite places, and your acceptance of Google's navigation terms (accept them again on each install).
- **On a new phone or after a reinstall**: install → Restore (the key and login come back) → accept the navigation terms → Cameras → download → **Restore again**, so the downloaded camera sets get their alert settings. Restoring twice is safe.
- A value the app cannot read (for example a typing mistake in a hand-edited file) is skipped and keeps its current setting. Sets in the file that are not on this phone are skipped and counted in the message. A set's icon is only restored if that icon is on this phone. PGW icons come back with the camera download.

### 1.8 Diagnostics

Technical detail: DESIGN.md §15.5.

The app always keeps two debug logs on the phone, so a drive that goes wrong leaves evidence.
There is nothing to switch on.

| File | What it records |
|---|---|
| `debug-nav` | Routes, the map and navigation: destinations set, route status, guidance start and stop (and who stopped it: the X on the car's card, arrival, or a new Maps key), reroutes, map follow and rebuild decisions, the location feed (a sample every 5 seconds, car and phone handovers, heading and speed sources, gaps), and the speed panel's limit source |
| `alert-debug` | Alerts and camera details: the cameras loaded, each camera entering and leaving the lookahead, why one was **not** alerted (direction, off the road, not ahead, not moving, no heading, muted, quiet), each stage fired with its distance, speed, heading and the camera's bearing, every speech decision (queued, replaced, spoken, dropped, interrupted), and one `PASSED` line per camera as you pass it, saying what was done |

- Lines are collected in memory and written in 15-second blocks. Each block is headed with its time span and a summary of the fixes seen. If the app is killed, up to the last 15 seconds is lost. The block headers show any gap.
- Each file is capped at 1 MB with one rotation (`*.1.log`), so the pair never takes more than about 4 MB.
- Debug builds mirror the lines to logcat. On some phones (OPPO, ColorOS) logcat drops app lines altogether. The files are the record.

| Control | What it does |
|---|---|
| Status line per file | Its size and the time of its oldest entry |
| **Show last 200 lines** | Shows the end of each file on the phone |
| **Save debug-nav… / Save alert-debug…** | Android's file picker, to save `autonavalerts-<name>-YYYY-MM-DD.log` wherever you choose |
| **Share** | The Android share sheet, to send a file by email, chat or Drive |
| **Clear** | Empties both files |
| **Engine now** | Shows, and writes to `alert-debug`, a snapshot of the alert engine: the last fix, the location statistics, the cameras cached, the route state, mutes, the 5 nearest cameras with their distance, bearing and verdict, and the speech queue |
| **Drive recording** (switch, default **Off**) | Records every GPS fix the app receives (phone and car, with speed, bearing, accuracy and which one was used) to a CSV file, so a drive can be replayed on the bench exactly as the phone saw it. About 1 to 8 MB an hour. The newest 3 recordings are kept. It is not in the settings backup and is off again after a reinstall |
| **Save drive recording…** | Saves the latest `drive-YYYYMMDD-HHMM.csv` through the file picker |

**Privacy.** The logs and recordings stay on the phone only, in private app storage, and are excluded from cloud backup.
They contain where you drove and which cameras you passed.
Nothing is sent anywhere unless you press **Save** or **Share**. What you then do with the file is up to you.
Uninstalling the app deletes them.

### 1.8a Connecting to a car

Folded behind **Show**, like Diagnostics.
It holds the steps for getting AutoNavAlerts onto a car's screen, and for debugging on a head unit on a computer, plus **Open Android Auto settings**.

- **On a car**: testers install from Google Play (the link in [INSTALLATION.md](INSTALLATION.md)), open the app on the phone once, then connect. Use a data cable for a wired car, or Bluetooth and Wi-Fi for wireless. Pick the app from the car's app grid.
- **Developer builds** installed with a cable need the Play Store recorded as the installer (`adb install -r -i com.android.vending app-release.apk`), and Android Auto's developer mode (tap Version about 10 times) with **Unknown sources** ticked (⋮ → Developer settings). Such a build is not kept in Customise Launcher. It still appears on the car.
- **If it is missing on the car**: open the app on the phone once, then disconnect and reconnect. Android Auto only notices a newly installed app at the start of a session. Still missing: force stop Android Auto and reconnect. Failing that, connect once to a head unit on a computer, then reconnect the car. For a developer build, also check the installer is recorded: `adb shell dumpsys package uk.damiendye.autonav | grep installerPackageName` must say `com.android.vending`.
- **Debugging on a head unit**:
  1. Android Auto ⋮ → **Start head unit server** (again after each phone restart).
  2. Turn on USB debugging and connect the phone to the computer with a data cable.
  3. Run `adb forward tcp:5277 tcp:5277`, then `desktop-head-unit` from the SDK's `extras/google/auto`.
  4. The app's logs are under Diagnostics.
  5. When you are done, ⋮ → **Stop head unit server** before driving.

### 1.9 About

The app name and version, by Damien Dye.
The **build** line: the git commit the app was built from, with `-dirty` if the tree had uncommitted changes, and "(debug)" for debug builds.
The source repository, github.com/damo2929/AutoNavAlerts (tap to open it).
Then the licence (Apache 2.0), a note that the code is AI-assisted, and the PGW icon credit.
There is nothing to set here.

---

## 2. Setup

Technical detail: DESIGN.md §13, §13.1, §13.2.

These sections come first on the Settings tab, in this order: **Google Maps API key**, **Permissions**, **Navigation terms**, **Android Auto**.
Until the key is saved and the terms are accepted, the app opens on this tab.
Once the key is saved, the required permissions are allowed and the terms accepted, the four sections collapse into one **Setup** row: "✓ Maps key saved, permissions allowed, navigation terms accepted".
**Show** opens them again, for example to remove the key.

An API key is a code that lets the app use Google Maps on your account.

| Item | What it does | Notes |
|---|---|---|
| **Create a key with my Google account** | Makes your own Maps key in your own Google Cloud account, using a Google account on the phone, and saves it. It creates (or reuses) a project called "AutoNavAlerts", links your billing account, turns on the Navigation SDK, the Maps SDK for Android and the Places API (New), and creates a key that only this app can use. You are told what it will create before it starts | Google Maps needs a billing account. If you do not have one, the app says so and opens Google Cloud to add one. Press **Try again** afterwards. Google may charge for use beyond its free allowance. The app keeps only the key, never your Google sign-in. Until Google verifies the app, only Google accounts added as testers can use this button. Others can paste a key instead |
| **Set it up yourself in Google Cloud (step by step)** | Five steps, each with an **Open** button that opens the right Google Cloud page in your browser: create a project, link billing, turn on the three services, create a key restricted to this app (the package name and fingerprint have **Copy** buttons), then copy the key. **Paste key from clipboard** saves it | Works for everyone, even when the button above is not available for your copy of the app. It opens by itself if that button cannot sign in |
| **Google Maps API key** (field, **Save key**, **Remove**) | Stores your own key, encrypted on the phone, and applies it straight away to navigation, the map and search. No rebuild is needed | The key needs the Navigation SDK (Google must approve access) and the Places API (New) turned on, restricted to the package `uk.damiendye.autonav` and your signing certificate. "✓ Your key is saved" shows when one is saved. **Remove** deletes it. There is no built-in or fallback key, so the map, search and navigation stop until a key is added again |
| **Permissions → Allow** | Asks for precise location, notifications (Android 13 and later), the microphone, and car speed | Location is required. Notifications are needed for alerts while another app fills the car screen, and for camera database update messages. The microphone is for spoken report comments. Car speed lets the speed panel show the car's own speedometer figure. Without it, the phone's GPS speed is used |
| **Permissions → Allow all the time** | Asks for background location (Android 10 and later) | Recommended. It keeps camera alerts working while music or another app is on the car screen |
| Contacts status, **Allow contacts** | Shows whether the contacts permission is granted. The button asks for it | Optional, for driving to a contact's address. Search a name on the car screen, or open **Contacts** from the empty Search screen. It lists your contacts with addresses, recently used first, then A to Z. The last row, **Search contacts…**, opens a typed search for long address books. Only the address you pick is sent to Google, to find it on the map |
| **Clear contact locations** | Forgets where contacts' addresses were found | Shown once contacts are allowed. Each address is looked up again the next time you pick it. Locations are kept for 90 days anyway |
| **Navigation terms → Review and accept** | Shows Google's Navigation SDK terms | Required before the car screen can navigate. The button is greyed out, with "Add your Google Maps API key above first", until a key is saved. You only accept once. The app records it ("✓ Accepted on <date>") and never asks again. Uninstalling the app clears it |
| Permission lines (✓ / ✗) | Each permission's current state, refreshed whenever you return to the app | **Allow** only appears if something is missing. If Android stops showing the prompt, **Open app settings** appears instead. When everything is granted: "✓ All permissions allowed." |
| **Android Auto → Open Android Auto settings** | Opens Android Auto's settings | Used to choose this app as your navigation app. Developer builds installed with a cable also turn on developer mode and **Unknown sources** here. A Play install needs neither |

### 2.1 What happens when you press "Create a key with my Google account"

1. A dialog lists what will be made in your Google Cloud account. **Continue** or **Cancel**.
2. Android's own Google screens: pick an account on the phone, then allow "See, edit, configure and delete your Google Cloud data". If you have allowed it before, these are skipped.
3. Progress lines appear under the button, one per step:
   - "Finding or creating your AutoNavAlerts project…": reuses the project it made last time, otherwise creates one named `autonavalerts-xxxxxx`.
   - "Checking billing…": if the project has no billing and you have exactly one open billing account, it is linked. With several, you pick one. With none, you are sent to Google Cloud to add one, then press **Try again**.
   - "Turning on the Navigation SDK, Maps SDK for Android and Places API (New)…"
   - "Creating a key only this app can use…": reuses the key it made before for this build, otherwise creates one restricted to `uk.damiendye.autonav`, this build's fingerprint, and those three services.
4. "✓ Key created in project <id>". The key is saved and used straight away.

*[Image: four steps in a row: project, billing, services on, key]*

**If it stops:**

| Message | What to do |
|---|---|
| "Automatic set-up isn't available for this copy of the app, because it isn't registered for Google sign-in…" | The button is greyed out and the step-by-step guide opens. Please use the guide. The app's registration is a one-time job for the maintainer |
| "Google sign-in was cancelled." | Press the button again when you are ready |
| "Google Maps needs a billing account…" with **Open** | Add a billing account (a card from your Google payments profile can be used), then **Try again**. Cards in Google Wallet alone are not a Cloud billing account. A closed billing account does not count either |
| "Choose the billing account to use." | Pick one. The run continues |
| "The Navigation SDK isn't available to this Google account yet…" with **Open** | Open it in Google Cloud and request access or accept its terms, then **Try again** |
| Any other Google message with **Open** | The button opens the page Google suggests for your project |
| "Google Cloud is taking too long…" | Wait a minute, then **Try again**. Every step is safe to repeat and continues from where it stopped |

### 2.2 The Maps key and the signing certificate

A key made for this app only works for builds signed with the certificate it lists.
Every official build is signed with the same shared key, with the fingerprint (SHA-1) `6C:48:A1:E5:…:9D:2C:0F`.
So one Maps key works for all of them.

If you build the app yourself without that key, your build has a different fingerprint.
Add it to your key's **Android apps** restriction in Google Cloud (the guide's **Copy SHA-1** button shows it), or make a new key with the button.

If something here is not done, the car screen shows **"Finish setup on your phone"** with the reason: no API key, terms not accepted, location permission missing, key not authorised, or no network.

---

## 3. POIs tab

Technical detail: DESIGN.md §5, §7, §11.1, §13.

### 3.1 Set list

This tab lists only the POI files you imported yourself.
Camera sets (PocketGPSWorld and **My reports**) are on the **Cameras** tab (section 4.1).

| Control | What it does |
|---|---|
| **Import file** | Picks a POI file: iGO TXT, KML, GPX, TomTom OV2 or CSV, or a zip containing them. Free and third-party POI files are only ever loaded by hand. Nothing is downloaded automatically except PocketGPSWorld (Cameras tab) |
| Set card | Tap to edit the set (section 3.3). It shows the icon, name, POI count, source and version |
| Switch on each card | Turns the whole set on or off: markers, alerts and speech. The same as the toggle on the car screen under **More → POI layers** |
| **Swipe left** on a card | Reveals **Delete**. A confirmation follows. The card returns to its place if you cancel. You can also delete from the set's editor |

### 3.2 Import dialog

| Field | What it does |
|---|---|
| (no set name to type) | Set names come from what you load. A single file is named after the file. A zip creates one set per POI file inside it, never merged, each named after its file. For example `Croydon_Tramlink_Stop_PGPSW.ov2` becomes "Croydon Tramlink Stop". Each set uses the file's matching `.bmp` or `.png` icon from the zip if there is one. Importing the same zip again updates those sets instead of adding duplicates. The dialog lists the sets it will create. You can rename a set later in its editor |
| **CSV columns** (CSV files only): **Lat, Lon, Name, Speed, Heading, Dir** | Column numbers, where 0 is the first column. Leave a box blank if the file has no such column. **Heading** is the direction of travel the POI applies to, in degrees. **Dir** is the direction type, read as `0/1/2`, `any/one/both` or `a/o/b`. With a heading but no Dir column, the POI applies one way. With neither, it applies in any direction. The separator (`,` `;` tab or `|`) and header row are detected automatically, and the first rows are shown as a preview |
| **Import** | Reads the file. The result says how many POIs were imported and how many rows were skipped, with the first problem shown. The import fails as a whole if more than 20% of the rows are bad |

Importing a file creates a new set. Each new set starts with the defaults in section 3.3.

### 3.3 Editing a set

| Section | Control | Default | What it does |
|---|---|---|---|
| Show and alert | **Show on map** | On | Draws this set's markers on the car map (at zoom 11 or closer, up to 200 markers, nearest first). A camera with a bearing in its data gets a small direction arrow beside its icon, pointing the way the camera faces (one head for one-way cameras, two for both ways, none when the data has no bearing). Cameras facing the other way on your road are drawn dimmed with their arrow, so you can see why they do not alert. They never alert |
| | **Alerts** | On | Whether this set's POIs trigger alerts at all |
| | **Sound** | On | Spoken alerts for this set. While on, the passing alert (1 second ahead, at least 20 yards) is compulsory. Off gives cards and notifications only |
| Icon | (from the download) | PocketGPSWorld sets | Each PocketGPSWorld set uses the icon supplied for its file (for example a Gatso "30" sign). A sync updates it unless you have chosen your own. These icons are © GerryC, 2007 (non-commercial use with credit). The full licence is in **Settings → About → Show icon licence** |
| Icon | **Choose** | A camera icon for PGW, a pin for files | Picks from the built-in icons (camera types, red light, pending site, pin, hazard, school, fuel, EV charger, star) or icons you have added |
| | **From file** | | Adds your own image (scaled to 256 px wide) and uses it for this set |
| **1st alert** | **Distance / Seconds ahead**, minimum distance | Seconds ahead, minimum 265 yd (242 m) | When the first warning fires. "Seconds ahead" uses your current speed each moment, with the seconds from the Alerts slider (section 1.2; 15 seconds by default, which is about 470 m at 70 mph and 335 m at 50 mph), and never fires closer than the minimum. There is no seconds field here. The card says "from the Alerts slider". **Distance** fires at a fixed distance instead. The 1st alert cannot be switched off. Turn off the set's Alerts instead |
| **2nd alert** | **On**, Distance / Seconds ahead, minimum distance | On, seconds ahead, minimum 155 yd (142 m) | A second, closer warning at half the slider's seconds (7.5 seconds by default, about 235 m at 70 mph). The minimum keeps it outside the passing alert. It can be switched off |
| **Passing alert** | none | 1 second ahead at the current speed, at least 20 yd | Fixed and compulsory while Sound is on. It also fires if a GPS jump skips past the 20 yard mark. It is never dropped, and it plays at most 1 second after Google's voice |
| **Spoken text** | **1st alert**, **2nd alert**, **Passing** templates | `{category} ahead, {speed} limit` · `{category}, {distance}` · `{category} now` | What is said and shown. Each field has a live preview and a **Test speak** button |
| | **Save** | | Saves the set. It is disabled while a distance check fails (below) |
| | **Delete set** | | Removes the set and its POIs from the phone, after asking you to confirm |

**Distance rules**, checked as you edit:

- The 2nd alert must be at least 155 yd (142 m), so it stays outside the passing alert.
- The 1st alert must be at least 110 yd (100 m) before the 2nd.
- In seconds-ahead mode the same rules apply while driving. If your speed would bring the stages too close together, the 2nd is held back.
- Once a camera is in range, the 1st alert's distance never shrinks. Braking towards it does not delay the warning.

**Template placeholders**:

| Placeholder | Becomes |
|---|---|
| `{category}` | Its category (for example "Fixed camera", "Red light camera"), or the set name if it has no category |
| `{speed}` | The speed limit in your units |
| `{units}` | "mph" or "km/h" on screen; "miles per hour" or "kilometres per hour" spoken |
| `{distance}` | For example "300 yd" on screen; "300 yards" or "a quarter of a mile" spoken |
| `{direction}` | "ahead", "on your left" or "on your right" |

- If a value is missing, the comma-separated part of the text that uses it is dropped. For example `{category} ahead, {speed} limit` with no limit says "Mobile camera site ahead".
- The over-limit phrase (section 1.2) is added automatically when it applies.

**PocketGPSWorld sets.** A sync creates one set per file in the download (section 4).
With the default TomTom OV2 download that is one set per camera type and speed limit, grouped by type on the Cameras tab.
Pending mobile sites are unverified. Their alerts are on by default. Turn that set off in Camera sets if you would rather not hear them.
Your icon, text and switch choices are kept across updates.

---

## 4. Cameras tab

Technical detail: DESIGN.md §5.2, §5.2a, §6.

| Control | Default | What it does |
|---|---|---|
| **Username / Password → Save and test** | | Saves your own PocketGPSWorld login (encrypted) and tests it straight away. You need an active subscription. Only `www.pocketgpsworld.com` is ever contacted |
| **Forget login** | | Deletes the saved login and session, and stops automatic updates |
| **Download format** | **TomTom OV2** | Which PocketGPSWorld download to use. **TomTom OV2** ("UK speed zoned complete") gives the direction as a compass point, the camera make (Gatso, Truvelo, Monitron, RedSpeed), temporary roadworks cameras, and PocketGPSWorld camera IDs, which are used when you report a known camera. **iGO** ("UK consolidated by type") gives the exact direction in degrees but less detail. Both give the same cameras. Every file in the download becomes its own set, and files are never merged. TomTom OV2 gives one set per type and speed, for example "PGW fixed cameras · 30 mph". iGO gives one set per type. After switching, press **Sync now**: the camera sets are replaced, and icon and alert choices made for the old format's sets are reset |
| Installed / Latest in feed / summary | | The camera database version on the phone, the newest version announced on PocketGPSWorld's update feed with its release notes, when the feed was last checked, and when the last sync ran with its result |
| **Sync now** | | Checks the feed, then downloads and imports only if there is a newer version than the one installed |
| **Force re-download** | | Downloads and imports the current database whatever the version |
| **Update automatically when a new release is announced** | On | Checks PocketGPSWorld's update feed once a day. On a new release, the app logs in, downloads the chosen package (a small zip) and imports it by itself. If no sync has succeeded for 14 days, it syncs anyway. Updates are never swapped in while you are navigating. They wait until guidance ends |
| **Wi-Fi only** | Off | Holds automatic downloads until you are on Wi-Fi. The daily feed check, a tiny request, still runs on mobile data |
| **Open download page** | | Opens PocketGPSWorld's download page in the browser, if you would rather download a file yourself and import it on the POIs tab |

You get a notification when the database updates, when your login fails, or when your subscription has lapsed.
Your PocketGPSWorld data stays in private app storage, is not backed up, and is never shared.

### 4.1 Camera sets

All camera sets are listed here rather than on the POIs tab, grouped by camera type.

| Control | What it does |
|---|---|
| Summary line | The total, for example "19,826 cameras in 33 sets" |
| Group row (for example **PGW fixed cameras**) | One row per camera type: its icon, the cameras and sets in it, and an expand arrow. The per-speed sets ("· 20 mph", "· 30 mph" …) are grouped under their type. Pending mobile sites come last, then **My reports** |
| **Group switch** | Turns every set in the group on or off. It shows on while any set in the group is on |
| Group order | The PocketGPSWorld groups in alphabetical order (average speed cameras, fixed cameras, mobile camera sites, red light cameras), then pending mobile sites, then **My reports**. Inside a group, sets are sorted by speed (20, 30 … 70 mph), then the ones without a number by name |
| Expanded group | Lists its sets, each with its own switch. Tap a set to edit it (section 3.3), or swipe it left to delete it. Deleting a PocketGPSWorld set only removes the phone's copy. The next sync downloads it again unless automatic updates are off |

---

## 5. Reports tab

Technical detail: DESIGN.md §8.5.

Camera reports are captured in the car with one tap.
They are checked and sent only here, on the phone.
The car screen never shows the review or a Send button.

**In the car**: tap **Report** (⚠). The app saves straight away:

- your position: the car's GPS if available, otherwise the phone's
- your direction of travel
- the speed limit: Google's if it is known at that moment (Google only reveals it while you are over it), otherwise the camera being alerted on, otherwise none

Then a tone plays and the car's microphone listens for up to 15 seconds for a spoken comment, for example "mobile van in the lay-by".
It stops after 2 seconds of silence, or after 6 seconds if nothing is said, or when you tap **Done** or **Skip**.
You are back on the map with **Undo** for 5 seconds.

**After the drive**: when you arrive or disconnect from the car, the phone shows "N camera reports to check". Tapping it opens this tab.

| Control | What it does |
|---|---|
| Report card header | The type, when it was made, and its status (draft, queued, sending, sent, failed) |
| **Type** chips | Mobile, Fixed, Average, Red light, Red light + speed, Not there, Wrong limit, Not sure. It is pre-filled from your spoken comment ("van" → Mobile, "specs" → Average, "not there" → Not there). Please check it |
| **Position** and **N/S/E/W 10 m** | The saved spot, with nudges to move it exactly onto the camera. **Show on map** opens it in a map app |
| **Speed limit** | The captured limit and where it came from: from Google, from camera data, unknown, or set by you. Tap 20 to 70 to set it |
| **Direction of travel** | The heading the camera applies to (for example "274° (W)"). Adjust it with **−45° −5° +5° +45° Reverse**, and choose **One way** or **Both ways** |
| **Comment** | Your spoken comment turned into text, which you can edit. The status under it says whether it is still being transcribed, done, or failed |
| **Play recording** | Plays your spoken comment back on the phone |
| **Try transcribing again** | Shown if speech-to-text failed. It needs Android 13 or later and a speech service |
| **Send** | Sends this report to PocketGPSWorld's **Submit Speed Cameras** form with your login |
| **Send all N** | Sends every draft at once, after you have checked them |
| **Save** | Keeps your edits without sending |
| **Discard** | Drops the report and its recording |
| **Browser** | Copies the details to the clipboard and opens PocketGPSWorld's submission page, to submit by hand |

**Never while driving.** Send and Send all are disabled, with the reason shown in red, while:

- the phone is connected to Android Auto
- a car session is open
- or you have moved faster than 5 mph in the last 2 minutes

A send that was started while still driving waits and tries again once you have stopped.
A send that fails for another reason is marked failed. You can send it again, or use **Browser**.

Review edits are applied to the local **My reports** marker straight away:

- **Not there** removes it, and hides the camera you passed.
- Fixed, average and red light types keep it permanently.
- Mobile reports and unchecked reports are removed after 24 hours.

PocketGPSWorld's tips are shown here: do not report warning signs or Traffic Master cameras, and only report mobile cameras when a unit is actually there.

---

## 6. Car screen settings

These are under **More** on the car screen.
Technical detail: DESIGN.md §8, §12.3.

| Item | What it does |
|---|---|
| **Mute alerts for 30 min / Unmute alerts** | Silences all POI speech for 30 minutes. Cards and notifications still show. While muted, the passing alert is silent too, because muting turns sound off |
| **POI layers** | Turns each set on or off, the same as the set switches on the phone's POIs and Cameras tabs |
| **GPS** | Read-only and live. It updates once a second while open, and only when something changed, so it does not flicker while parked. It shows the requested GPS rate and the rate actually delivered, the speed the alert engine is using and the speed on the dial with where each comes from (car or phone), and your heading with its source |
| **Alerts** | Read-only and live: how many cameras are ahead and how many facing away, the last stage fired (which camera, distance, whether it was spoken), the last suppression (which camera and why), and the speech records |
| **Route options** | Avoid tolls, and energy-efficient routes for electric cars (section 1.4b) |
| **Satellite map** | The same switch as on the phone (section 1.4) |
| **Mute 30 min** on an alert card | Silences that one set for 30 minutes |

While navigating, **Recents** leaves the action strip (it is still under More).
Nothing of ours stops the route: the **X** on Google's travel-estimate card at the bottom left of the map ends it.
When you arrive, the route ends by itself and a message says "You have arrived at …".
Searching for somewhere else while navigating shows its route. **Go** switches to it. **Back** returns to the route you were on.
Google's own arrival card, header, recenter button, speedometer and trip progress bar are not shown on the car map. The turn card, our speed panel and our recenter button are the live ones.

**Search** on the car screen, with the box empty, lists recent places and a **Contacts** row (once contacts are allowed, section 2).
Contacts is a list to pick from: your contacts that have an address, recently used first and then A to Z.
Tapping one finds the address and opens the route preview.
The last row, **Search contacts…**, opens the typed search for long address books.
The microphone on the search box takes a spoken destination, and so does Assistant's "navigate to …".
You see "Searching…" and then results or "No results for …", never a silent empty list.

---

## 7. Storage and privacy

- Settings are stored in app-private storage.
- The Maps API key, the PocketGPSWorld login and its session are encrypted with a key held in the Android Keystore.
- The POI database, settings and secrets are excluded from Android cloud backup and device-to-device transfer, as the PocketGPSWorld licence requires. After a reinstall or on a new phone, restore your own settings backup (section 1.7), or enter the key and login again, then sync.
- The settings backup file (section 1.7) is the only thing that takes your key and login out of the app, and only when you save it. It is plain text. Please keep it private.
- The debug logs and drive recordings (section 1.8) are excluded from backup and transfer too. They record where you drove and which cameras you passed, and leave the phone only when you Save or Share them.
- Uninstalling the app deletes everything it stored.

## 8. Defaults at a glance

| Setting | Default |
|---|---|
| Units | mph, miles, yards |
| Voice / Chime | On / On |
| Over-limit warning | −0.1% |
| Alert lead time | First alert 15 seconds (second 7.5 seconds) |
| Announce speed limit changes ahead | On |
| Speed and location source | Car when available |
| Speed panel | On |
| Satellite map in the car | Off |
| Avoid tolls | On |
| Energy-efficient routes (EV) | Off |
| Show OpenStreetMap limits | On |
| Speed limits: download on Wi-Fi only | On |
| GPS rate | Asked for 1 fix a second; faster fixes accepted |
| Simulate speed (debug builds) | 1× |
| Drive recording | Off |
| PocketGPSWorld download format | TomTom OV2 |
| PocketGPSWorld auto-update / Wi-Fi only | On / Off |
| New set: 1st / 2nd / passing alert | Seconds ahead from the slider (minimum 265 yd) / on, half the slider (minimum 155 yd) / 20 yd |
| New set: Show on map / Alerts / Sound | On / On / On |

---

First published: 2026-10-11
Last updated: 2026-10-24
Pictures: not added yet. The lines in square brackets mark where they go.
