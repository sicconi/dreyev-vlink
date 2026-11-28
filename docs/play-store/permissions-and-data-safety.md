# Permissions, foreground service declaration and Data safety

## Why each permission is needed

Paste these where Play Console asks, and use them in the privacy policy.

| Permission | Why |
|---|---|
| `BLUETOOTH_CONNECT` (Android 12+), `BLUETOOTH` / `BLUETOOTH_ADMIN` (Android 11 and older) | Connect to the OBD-II Bluetooth adapter the user paired. |
| `BLUETOOTH_SCAN` (flagged `neverForLocation`) | Find BLE OBD-II adapters nearby. Scan results are never used to work out location. |
| `ACCESS_FINE_LOCATION`, `ACCESS_COARSE_LOCATION` | Show GPS speed, heading, altitude and the drive trace next to the car's own data. On Android 8–11 the system also requires location for Bluetooth LE scans. Only while the app or its dashboard service is running; the app does not request background location. |
| `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_CONNECTED_DEVICE` | Keep the link to the adapter alive with a visible notification while the screen is off or the user switches apps. |
| `FOREGROUND_SERVICE_LOCATION` | Keep phone GPS readings going during that same user-started session (speed comparison, trace, trip logs). |
| `POST_NOTIFICATIONS` | Show the required "connected to adapter" notification for the foreground service. |
| `WRITE_EXTERNAL_STORAGE` (Android 8–9 only, `maxSdkVersion 28`) | Copy trip and signal-finder logs to Downloads/dreyev-vlink. Android 10+ uses MediaStore and needs no storage permission. |
| `INTERNET` (only once the map tab ships) | Load Google Maps tiles for the map. |

The app does **not** request `ACCESS_BACKGROUND_LOCATION`, contacts, camera, microphone, or broad storage access.

## Foreground service declaration

Play Console › App content › **Foreground service permissions**. Declare both types:

**Connected device**
* Task: "Maintains the Bluetooth connection to the user's OBD-II adapter and reads live car data while the dashboard runs with the screen off or in the background."
* User impact if deferred/interrupted: "Live gauges and trip logs would stop and the adapter would disconnect mid-drive."
* User-initiated: yes, the user taps Connect (or opens the app with auto-reconnect on); a persistent notification shows while it runs and has a Stop action.

**Location**
* Task: "Reads phone GPS speed, heading and position during a drive session the user started, to compare with the car's speed and record the drive trace."
* Same impact/initiation as above.

Play asks for a short video link for each. Record a 30-second screen capture (Demo mode is fine): open app → Connect/Demo → notification appears → go to home screen → gauges keep updating when you come back. Upload as an unlisted YouTube video and paste the link.

## Data safety form

Play defines "collected" as data sent off the device. Data that stays on the phone (settings, logs in Downloads, the VIN) is not collected. Android's own backup to the user's Google account is also exempt.

### Version 1 (no map, no INTERNET permission)

* Does your app collect or share any of the required user data types? **No**
* That's the whole form. Play then shows "No data collected" and "No data shared".

### Once the Google Maps tab ships (INTERNET + Maps SDK)

Google's Maps SDK sends requests to Google that reveal the map area being viewed, and Google's SDK disclosure lists data it handles. Answer:

* Does your app collect or share user data? **Yes**
* Is all data encrypted in transit? **Yes** (Maps SDK uses HTTPS)
* Do you provide a way for users to request deletion? **Yes**: no account and no server-side data; uninstalling removes everything the app stores. (Or "No" if you prefer; there's nothing on a server to delete.)
* Data types:
  * **Location › Approximate location**: Collected, **shared** with Google (Maps SDK). Purpose: **App functionality**. Processed ephemerally: **Yes**. Required (users can't turn it off) unless you add a "map off" setting.
  * **App info and performance › Crash logs / Diagnostics**: only if the Maps SDK version you ship says so in Google's "Maps SDK for Android data disclosure"; check that page when the map lands.
* Precise location: **not** collected or shared (the trace is drawn on the phone; only map tiles are fetched).

Update `docs/privacy-policy.html` at the same time (it already has a Google Maps section).

## Other App content answers

* Ads: **No ads**
* Target audience: **18 and over** (drivers)
* Content rating questionnaire: category **Utility, Productivity, Communication, or Other**; answer **No** to violence, sexuality, language, controlled substances, gambling, user interaction/sharing, location sharing with other users, and digital purchases. Expected rating: Everyone / PEGI 3.
* News app: No. Government app: No. Financial features: None. Health: None.
* COVID-19 contact tracing: No.
