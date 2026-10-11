# AutoNavAlerts settings reference

Every setting in the app: where it is, what it does, its default, and what it affects. Settings are stored on the phone only. They're **not** cloud-backed-up, so a reinstall starts from the defaults (see [Storage and privacy](#storage-and-privacy)).

The phone app has five tabs along the bottom: **Setup · POIs · Cameras · Reports · Settings**. A few settings are also on the car screen, under **More**.

---

## 1. Settings tab

### 1.1 Units
| Control | Options | Default | What it changes |
|---|---|---|---|
| Units | **mph, miles, yards** / **km/h, km, m** | mph, miles, yards | The speed panel and its limit sign, distances in spoken and on-screen alerts, turn-card and ETA distances, the report speed choices on the car screen (20–70 mph or 30–120 km/h), and the distance fields when editing a POI set |

- Camera speed limits keep the unit they were imported in. PocketGPSWorld UK data is in mph. Limits are converted for display and speech, so a 30 mph camera reads "48 km/h" in metric.

### 1.2 Alerts
| Control | Options | Default | What it does |
|---|---|---|---|
| **Voice** | On / Off | On | Speaks POI alerts on the navigation audio channel. Off silences **all** POI speech, including the passing alert. Car-screen cards and notifications still appear. Google's own turn-by-turn voice isn't affected. |
| **Chime before the passing alert** | On / Off | On | Plays a short beep just before the 100 yd passing alert. There's no chime before the 1st and 2nd alerts. |
| **Over-limit warning** | **Slider, −5% to +20%** in 0.1% steps, plus **Reset to −0.1%** | **−0.1%** | When an alert fires and your speed is above the POI's (or the average-speed zone's) limit by more than this, "you are over the limit" is added to the spoken alert. **Negative values warn before the limit**: −0.1% warns just before (above 99.9% of it), −5% warns at 95% of it (28.5 mph in a 30). 0% warns as soon as you're over. Positive values allow a margin: +10% warns above 33 mph in a 30. The text under the slider explains the current value. In an average-speed zone, the warning can repeat at most every 15 s. |

- The over-limit setting applies to **POI and camera limits** (your imported data). The colour of the speed panel follows Google's speed-limit data separately (§1.3).

### 1.3 Speed and location source
| Control | Options | Default | What it does |
|---|---|---|---|
| **Speed and location source** | **Car when available** / Phone only | Car when available | Where your speed and position come from. With **Car when available**, the app reads the **car's own speed and GPS** through Android Auto whenever the car shares them, and falls back to the phone if the car stops reporting (speed: 3 s, GPS: 2 s) or doesn't provide them. **Phone only** ignores the car. |

What car speed is used for:
- **Speed panel**: the car's **speedometer** figure, so it matches your dashboard. Cars usually read a few percent high.
- **Alerts**: the car's **measured** road speed, for "seconds ahead" alert distances and the over-limit warning. It's more accurate than GPS in tunnels, under trees and at low speed.
- **GPS rate** (§1.5): changes as soon as the car's speed changes.
- **Position, from the car's GPS**: used for camera alerts, marker updates and report positions.
  - The car's GPS antenna is usually better placed than a phone in a pocket or door bin.
  - While it's in use, the phone's GPS drops to a **1 Hz backup** to save battery, and takes over immediately if the car goes quiet.
  - The car decides how often it reports, so the adaptive 1–10 Hz rate applies to the phone only.
- **The map itself** (the car marker and route guidance) follows the **phone's** location services, because Google's Navigation SDK can't be fed the car's GPS. Many cars already pass their GPS to the phone through Android Auto.

The car screen's **More → GPS** row shows where the location and speed are coming from ("Location from car · speed from car"). Not every car or head unit shares its speed or GPS. Some aftermarket units don't, and the app needs car API level 3 or higher.

### 1.4 Speed panel
| Control | Options | Default | What it does |
|---|---|---|---|
| **Show speed and limit on the map** | On / Off | On | Shows your current speed, and a speed-limit sign next to it, in the bottom corner of the car map (kept clear of the turn card and the media panel). |

What the panel shows:
- **Your speed**: the **car's speedometer reading** when the car shares it (§1.3), otherwise the phone's GPS. It updates on every fix, in your units.
- **The limit**:
  - **Google's limit for the road.** The Navigation SDK only reveals this while you're **over** it, so it shows while you're speeding and stays for 60 s afterwards.
  - Otherwise, the **limit of the camera** you're being alerted about, marked with a **small blue dot** so it isn't mistaken for Google's figure.
  - If neither is known, only your speed is shown.
- **Colour**: white normally, **amber** at 5% over Google's limit, **red** at 10% over. These thresholds match Google Maps and can't be changed.

### 1.5 GPS rate
How often the phone's GPS is read for alerts. The map has its own location feed from the Navigation SDK.

| Control | Options | Default | What it does |
|---|---|---|---|
| **Maximum rate** | max 5 Hz, **max 10 Hz**, max 15 Hz | 10 Hz | The fastest GPS rate, reached at the "full at" speed |
| **Full rate at** | 40, **50**, 60 mph | 50 mph | The speed at which the maximum rate is reached |

How the rate is worked out:
- **Below 10 mph, always 1 Hz**, including when stopped. This 10 mph point is fixed.
- **Between 10 mph and the "full at" speed**, the rate rises in a **straight line** from 1 Hz to the maximum. With the defaults: 20 mph ≈ 3 Hz, 30 mph ≈ 6 Hz, 40 mph ≈ 8 Hz.
- **At and above the "full at" speed**, the maximum rate.
- The rate changes in whole-Hz steps, and only after the new step has held for 2 s, so it doesn't flip back and forth when your speed hovers at a boundary.
- Many phones' GPS can't deliver more than 1–5 Hz. If yours delivers under half the requested rate for 60 s, the app lowers the request to match, which saves power.
- The car screen's **More → GPS** row shows the requested rate, the rate actually delivered, and your speed.

Higher rates make alerts fire closer to the exact distance at speed (at 70 mph, 10 Hz is a fix every 3 m; 1 Hz is every 31 m), but use more battery.

### 1.6 Bench testing (debug builds only)
| Control | Options | Default | What it does |
|---|---|---|---|
| **Simulate speed** | **1×**, 2×, 4× | 1× | Speed of the **Simulate** drive started from the car's route preview. 1× is real time. Alerts, the GPS rate and the speed panel all work during simulation. See [TESTING.md](../TESTING.md). |

This section isn't shown in release builds.

### 1.7 About
Licence (Apache 2.0) and the note that the code is AI-assisted. Nothing to set here.

---

## 2. Setup tab

| Item | What it does | Notes |
|---|---|---|
| **Google Maps API key** (field + **Save key**, **Remove**) | Stores **your own** Maps Platform key, encrypted on the phone, and applies it straight away to navigation, the map and search. No rebuild needed. | The key needs the **Navigation SDK** (Google must approve access) and **Places API (New)** enabled, restricted to the package `io.autonav.alerts` and your signing certificate. "A key is set" shows when one is saved, either yours or one built in at compile time. **Remove** deletes your key and goes back to the built-in one, if there is one. |
| **Permissions → Allow** | Asks for precise location and, on Android 13+, notifications | Location is required. Notifications are needed for alerts while another app fills the car screen, and for camera-database update messages. |
| **Permissions → Allow all the time** | Asks for background location (Android 10+) | Recommended. Keeps camera alerts working while music or another app is on the car screen. |
| Contacts status | Shows whether contacts permission is granted | Optional. It's for navigating to contacts' addresses, which isn't built yet. |
| **Navigation terms → Review and accept** | Shows Google's Navigation SDK terms | **Required** before the car screen can navigate, and done on the phone. **You only accept once:** the app records the acceptance (shown as "✓ Accepted on <date>") and never asks again, even if the SDK's own flag is lost. Uninstalling the app clears it. |
| Permission lines (✓ / ✗) | Each permission's current state, refreshed whenever you return to the app | **Allow** only appears if something is missing. If Android stops showing the prompt, **Open app settings** appears instead. When everything is granted: "✓ All permissions allowed." |
| **Android Auto → Open Android Auto settings** | Opens Android Auto's settings | Used to turn on developer mode and **Unknown sources** (needed because the app is sideloaded), and to choose this as your navigation app |

If something here isn't done, the car screen shows **"Finish setup on your phone"** with the reason: no API key, terms not accepted, location permission missing, key not authorised, or no network.

---

## 3. POIs tab (POI sets)

### 3.1 Set list
| Control | What it does |
|---|---|
| **Import file** | Picks a POI file: iGO TXT, KML, GPX, TomTom OV2 or CSV, or a **zip** containing one. Free and third-party POI files are **only ever loaded by hand**; nothing is downloaded automatically except PocketGPSWorld (Cameras tab). |
| Set card | Tap to edit the set (§3.3). It shows the icon, name, POI count, source and version. |
| Switch on each card | Turns the whole set **on or off**: markers, alerts and speech. Same as the toggle on the car screen under **More → POI layers**. |
| **Swipe left** on a card | Reveals **Delete**. A confirmation follows ("Delete <set>?"), and the card springs back if you cancel. Deleting a PocketGPSWorld set only removes the phone's copy: the next sync downloads it again unless automatic updates are off. You can also delete from the set's editor (**Delete set**). |

### 3.2 Import dialog
| Field | What it does |
|---|---|
| **Set name** | Name shown in lists, and spoken for `{category}` when a POI has no category. It defaults to the file name. |
| **CSV columns** (CSV files only): **Lat, Lon, Name, Speed, Heading, Dir** | Column numbers, **0 = first column**. Leave a box blank if the file has no such column. **Heading** is the direction of travel the POI applies to, in degrees. **Dir** is the direction type, read as `0/1/2`, `any/one/both` or `a/o/b`. With a heading but no Dir column, the POI applies one way. With neither, it applies in any direction. The delimiter (`,` `;` tab or `|`) and header row are detected automatically, and the first rows are shown as a preview. |
| **Import** | Reads the file. The result says how many POIs were imported and how many rows were skipped, with the first problem shown. **The import fails as a whole if more than 20% of rows are bad.** |

Importing a file creates a **new** set. Each new set starts with the [defaults below](#33-editing-a-set).

### 3.3 Editing a set
| Section | Control | Default | What it does |
|---|---|---|---|
| Show and alert | **Show on map** | On | Draws this set's markers on the car map (at zoom 11 or closer; up to 200 markers, nearest first) |
| | **Alerts** | On, except **PGW pending mobile sites**, which is Off | Whether this set's POIs trigger alerts at all |
| | **Sound** | On | Spoken alerts for this set. **While on, the 100 yd passing alert is compulsory.** Off gives cards and notifications only. |
| Icon | **Choose** | Camera icon for PGW, pin for files | Picks from the built-in icons (camera types, red light, pending site, pin, hazard, school, fuel, EV, star) or icons you've added |
| | **From file** | | Adds your own image (scaled to 256 px) and uses it for this set |
| **1st alert** | **Distance / Seconds ahead** | Seconds ahead: **30 s, minimum 500 yd** | When the first warning fires. "Seconds ahead" scales with speed (30 s at 70 mph ≈ 940 m) but never fires closer than the minimum. The 1st alert can't be switched off (turn off the set's Alerts instead). |
| **2nd alert** | **On**, Distance / Seconds ahead | On, **300 yd** | A second, closer warning. Can be switched off. |
| **Passing alert (100 yd)** | none | Always 100 yd | Fixed and compulsory while Sound is on. It also fires if a GPS jump skips past the 100 yd mark, it's never dropped by the 3 s spacing between alerts, and it plays at most 1 s after Google's voice. |
| **Spoken text** | **1st alert**, **2nd alert**, **Passing** templates | `{category} ahead, {speed} limit` · `{category}, {distance}` · `{category} now` | What's said and shown. Each field has a live preview and a **Test speak** button. |
| | **Save** | | Saves the set. It's disabled while a distance check fails (below). |
| | **Delete set** | | Removes the set and its POIs from the phone, after asking to confirm |

**Distance rules** (checked as you edit):
- The 2nd alert must be at least **155 yd (142 m)**, so it stays outside the passing alert.
- The 1st alert must be at least **110 yd (100 m)** before the 2nd.
- In seconds-ahead mode the same rules are applied while driving: if your speed would bring the stages too close together, the 2nd is held back.

**Template placeholders**:

| Placeholder | Becomes |
|---|---|
| `{category}` | Its category (e.g. "Fixed camera", "Red light camera"), or the set name if it has no category |
| `{speed}` | The speed limit in your units |
| `{units}` | "mph" / "km/h" on screen, "miles per hour" / "kilometres per hour" spoken |
| `{distance}` | e.g. "300 yd" on screen, "300 yards" or "a quarter of a mile" spoken |
| `{direction}` | "ahead", "on your left" or "on your right" |

- If a value is missing, the comma-separated part of the text that uses it is **dropped**. For example, `{category} ahead, {speed} limit` with no limit says "Mobile camera site ahead".
- The over-limit phrase (§1.2) is added automatically when it applies.

**PocketGPSWorld sets.** Syncing creates two sets:
- **PocketGPSWorld cameras**: categories Fixed, Red light, Average speed, Mobile site, and Red light + speed (type 11, still being confirmed).
- **PGW pending mobile sites (unverified)**: alerts off by default.

Your icon, text and switch choices are **kept across updates**.

---

## 4. Cameras tab (PocketGPSWorld)

| Control | Default | What it does |
|---|---|---|
| **Username / Password → Save and test** | | Saves **your own** PocketGPSWorld login (encrypted) and tests it straight away. You need an active subscription. Only `www.pocketgpsworld.com` is ever contacted. |
| **Forget login** | | Deletes the saved login and session, and stops automatic updates |
| **Download format** | **TomTom OV2** | Which PocketGPSWorld download to use. **TomTom OV2** (UK speed zoned complete) gives direction as a compass point, the camera make (Gatso, Truvelo, Monitron, RedSpeed), temporary roadworks cameras, and PocketGPSWorld camera IDs, which are used when you report a known camera. **iGO** (UK consolidated by type) gives exact direction in degrees but less detail. Both give the same cameras, one set per type. After switching, press **Sync now**: the camera sets are replaced, and icon and alert choices made for the old format's sets are reset. |
| Installed / Latest in feed / summary | | The camera database version on the phone, the newest version announced on PocketGPSWorld's RSS feed with its release notes, and when the feed was last checked and the last sync ran, with its result |
| **Sync now** | | Checks the feed, then downloads and imports **only if** there's a newer version than the one installed |
| **Force re-download** | | Downloads and imports the current database whatever the version |
| **Update automatically when a new release is announced** | **On** | Checks the RSS feed **once a day**. On a new release, the app logs in, downloads the iGO file (about 220 KB) and imports it by itself. If no sync has succeeded for 14 days, it syncs anyway. **Updates are never swapped in while you're navigating**; they wait until guidance ends. |
| **Wi-Fi only** | Off | Holds automatic downloads until you're on Wi-Fi. The daily feed check (a tiny request) still runs on mobile data. |
| **Open download page** | | Opens PocketGPSWorld's download page in the browser, if you'd rather download the iGO file yourself and import it on the POIs tab |

You get a notification when the database updates, when your login fails, or when your subscription has lapsed. Your PocketGPSWorld data stays in private app storage, isn't backed up, and is never shared.

---

## 5. Reports tab
Camera reports are **captured in the car with one tap** and **checked and sent only here, on the phone**. The car screen never shows the review or a Send button.

**In the car:** tap **Report** (⚠). The app saves straight away:
- your **position**: car GPS if available, otherwise the phone
- your **direction of travel**
- the **speed limit**: Google's if it's known at that moment (Google only reveals it while you're over it), otherwise the camera being alerted, otherwise none

Then a tone plays and the car's microphone listens for up to 15 s for a **spoken comment**, e.g. "mobile van in the lay-by". It stops after 2 s of silence, or tap **Done** / **Skip**. You're back on the map with **Undo** for 5 s.

**After the drive:** when you arrive or disconnect from the car, the phone shows **"N camera reports to check"**. Tapping it opens this tab.

| Control | What it does |
|---|---|
| Report card header | The type, when it was made, and its status (draft, queued, sending, sent, failed) |
| **Type** chips | Mobile, Fixed, Average, Red light, Red light + speed, Not there, Wrong limit, Not sure. It's **pre-filled from your spoken comment** ("van" → Mobile, "specs" → Average, "not there" → Not there…). Check it. |
| **Position** + **N/S/E/W 10 m** | The saved spot, with nudges to move it exactly onto the camera. **Show on map** opens it in a map app. |
| **Speed limit** | The captured limit and where it came from: *from Google*, *from camera data*, *unknown*, or *set by you*. Tap 20–70 to set it. |
| **Direction of travel** | The heading the camera applies to (e.g. "274° (W)"). Adjust it with **−45° −5° +5° +45° Reverse**, and choose **One way** or **Both ways**. |
| **Comment** | Your spoken comment turned into text, which you can edit. The status under it says whether it's still being transcribed, done, or failed. |
| **Play recording** | Plays your spoken comment back on the phone |
| **Try transcribing again** | Shown if speech-to-text failed (it needs Android 13+ and a speech service) |
| **Send** | Sends this report to PocketGPSWorld's **Submit Speed Cameras** form with your login |
| **Send all N** | Sends every draft at once (after you've checked them) |
| **Save** | Keeps your edits without sending |
| **Discard** | Drops the report and its recording |
| **Browser** | Copies the details to the clipboard and opens PocketGPSWorld's submission page, to submit by hand |

**Never while driving.** Send and Send all are **disabled**, with the reason shown in red, while:
- the phone is connected to Android Auto
- a car session is open
- or you've moved faster than 5 mph in the last 2 minutes

A send already queued waits and retries once you've stopped.

Review edits are applied to the local **My reports** marker straight away:
- **Not there** removes it, and hides the camera you'd passed.
- Fixed, average and red-light types keep it permanently.
- Mobile reports and unchecked reports drop off after 24 h.

PocketGPSWorld's tips are shown here: don't report warning signs or Traffic Master cameras, and only report mobile cameras when a unit is actually there.

---

## 6. Car-screen settings (More menu)

| Item | What it does |
|---|---|
| **Mute alerts for 30 min / Unmute alerts** | Silences all POI speech for 30 minutes. Cards and notifications still show. While muted, the passing alert is silent too, because muting turns sound off. |
| **POI layers** | Turns each set on or off, the same as the switch on the phone's POIs tab |
| **GPS** | Read-only: the requested GPS rate, the rate actually delivered, and your current speed |
| **Mute 30 min** on an alert card | Silences that **one set** for 30 minutes |

---

## Storage and privacy
- Settings are stored in app-private storage (DataStore).
- The **Maps API key, PocketGPSWorld login and session are encrypted** with a key held in the Android Keystore.
- The POI database, settings and secrets are **excluded from Android cloud backup and device-to-device transfer**, as the PocketGPSWorld licence requires. After a reinstall or a new phone: enter the key and login again, then sync.
- Uninstalling the app deletes everything it stored.

## Defaults at a glance
| Setting | Default |
|---|---|
| Units | mph, miles, yards |
| Voice / Chime | On / On |
| Over-limit warning | **−0.1%** |
| Speed and location source | Car when available |
| Speed panel | On |
| GPS rate | 1 Hz below 10 mph, rising to 10 Hz at 50 mph |
| Simulate speed (debug builds) | 1× |
| PocketGPSWorld auto-update / Wi-Fi only | On / Off |
| New set: 1st / 2nd / passing alert | 30 s ahead (min 500 yd) / 300 yd / 100 yd |
| New set: Show on map / Alerts / Sound | On / On / On (pending mobile sites: alerts Off) |
