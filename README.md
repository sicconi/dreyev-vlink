# dreyev VLink dashboard (Android)

Native Kotlin / Jetpack Compose app that connects to a VLink (ELM327-compatible) OBD-II
Bluetooth adapter and shows the car's data on analog and digital gauges, in portrait
(Drive · Tires & chassis · Vehicle & GPS · Map) and landscape (Drive · Tires & chassis · Map).

## Install the APK

1. Copy `dreyev-vlink-debug.apk` to the phone and open it (allow "Install unknown apps" for
   your file manager when asked). Android 8.0 or newer.
2. Pair the VLink in **Android Settings › Bluetooth** (PIN `1234` or `0000`).
   BLE-only models don't need pairing; use **Scan for BLE adapters** in the app.
3. Open the app, allow Bluetooth and location, tap the gear, pick the adapter.
   It reconnects automatically next time. **Demo mode** runs a built-in simulated car.

## Project layout

| Module | What it is |
|---|---|
| `obd-core/` | Pure Kotlin data layer, no Android code: ELM327 protocol, PID decoders, VIN, poll scheduler, manufacturer-profile engine, unit conversions, simulator. Unit-tested on the JVM. The Android Auto module will reuse it. |
| `app/` | Android app: Bluetooth Classic (SPP) and BLE transports, phone GPS + IMU, DataStore settings, foreground service, trip CSV logger, Compose UI. |

Data flow: `ObdTransport` (SPP / BLE / simulator) → `Elm327` → `ObdPoller` → `VehicleStore`
(`StateFlow<VehicleState>`) ← phone sensors. The UI only reads `VehicleState`.

## Where each value comes from

Every value carries a source tag: **OBD** (standard PID), **MFR** (manufacturer PID, needs a
profile), **PHONE** (GPS / motion sensors), **EST** (calculated). Missing values show **N/A**.

* Standard OBD (works on almost any car): speed 0D, RPM 0C, engine load 04, pedal 49 (throttle 11
  fallback), outside temp 46, run time 1F, odometer A6 (newer cars), VIN 09 02, battery voltage.
* Manufacturer-specific: brake pedal and pressure, steering angle, wheel speeds, TPMS pressure and
  temperature, turn signals, seatbelts, car (ESC) acceleration. These need a profile for your car;
  see `app/src/main/assets/profiles/README.md`. Import one from the phone in Settings, no rebuild.
* Phone: GPS position/speed/heading/altitude, 3-axis acceleration (vertical is phone only).
* Estimated: caliper clamp force = brake pressure × piston area; longitudinal g from speed change.

Units: Imperial + °F by default, switchable to Metric and °C separately, saved across restarts.
All values are stored metric internally.

## Build

Requires JDK 17+ and the Android SDK (platform 36).

Two flavors: `play` (`com.dreyev.vlink`, the Google Play phone app, no Android Auto) and
`sideload` (`com.dreyev.vlink.sideload`, "dreyev VLink AA", adds the Android Auto module from
`app/src/sideload`). Releasing to Play: see [docs/play-store/RELEASE.md](docs/play-store/RELEASE.md).

```
./gradlew :app:assemblePlayDebug             # phone app APK → app/build/outputs/apk/play/debug/
./gradlew :app:assembleSideloadDebug         # phone + Android Auto APK
./gradlew :app:bundlePlayRelease             # signed AAB for Google Play (needs your upload key)
./gradlew -PcoreOnly=true :obd-core:test     # data-layer tests, no Android SDK needed
./gradlew :app:recordPaparazziSideloadDebug  # render phone + car screens to app/src/test/snapshots/images
```

## Petrol/diesel vs electric

On connect the app works out the powertrain: standard PID 51 (fuel type) if the car reports it,
otherwise "no engine-RPM PID" means electric, otherwise it asks for the Hyundai/Kia EV battery
module. With the profile set to **Auto** (default):

* a profile whose VIN prefix matches is used;
* else an **electric** car gets the Ioniq 5 profile and the EV screens (power arc, SOC, READY);
* else (petrol, diesel, hybrid) the original layout with standard OBD-II.

The result is shown on the Vehicle & GPS screen ("Detected: …"). Pick a profile by hand in
Settings to override.

## Hyundai Ioniq 5 (2022–2024)

`app/src/main/assets/profiles/hyundai-ioniq5.json` is picked automatically from the VIN (or choose it in
Settings). Sources: [OBDb/Hyundai-IONIQ-5](https://github.com/OBDb/Hyundai-IONIQ-5) (CC BY-SA 4.0,
decoded from real 2022–2024 car captures), Esprit1st/Hyundai-Ioniq-5-Torque-Pro-PIDs and
meatpiHQ/wican-fw. `Ioniq5ProfileTest` checks the formulas against OBDb's 2022 captures.

| Signal | Module | Request | Formula |
|---|---|---|---|
| Tire pressure FL/FR/RL/RR | 7A0 (BCM/TPMS) | 22C00B | E/5, J/5, O/5, T/5 psi |
| Tire temperature FL/FR/RL/RR | 7A0 | 22C00B | F-50, K-50, P-50, U-50 °C |
| Brake pedal switch | 7A0 | 22B008 | bit(G,4) |
| Steering angle | 730 (chassis) | 22F010 | signed(N·256+M)/10 ° |
| Wheel speeds FR/FL/RR/RL | 730 | 22F010 | (F·256+E)/32, (H·256+G)/32, (J·256+I)/32, (L·256+K)/32 km/h |
| Lateral / longitudinal acceleration | 730 | 22F010 | (P·256+O), (R·256+Q) × 8.354/65535 − 4.177 m/s² |
| Accelerator pedal | 7E2 (VCU) | 22E004 | (J·256+I)/512 % |
| Vehicle speed, outside temp | 7B3 (HVAC) | 220100 | AD km/h, G/2-40 °C |
| Odometer | 7C6 (cluster) | 22B002 | G·65536+H·256+I km, or J·65536+K·256+L mi (US cars fill only the miles field) |
| READY (HV on) | 7E4 (BMS) | 220101 | inverter capacitor voltage AZ·256+BA above 100 V |
| HV current/voltage, motor RPM, 12 V | 7E4 | 220101 | signed(K·256+L)/10 A, (M·256+N)/10 V, BB/BC, BD/BE, AD/10 |
| State of charge | 7E4 | 220105 | AF/2 % |
| Rear motor power | 7E3 (MCU) | 22E001 | signed(W·256+V)/10 N·m × signed(S·256+R) rpm × 2π/60 / 1000 kW |
| Steering angle (fallback) | 7D4 (MDPS) | 220101 | signed(H·256+I)/10 ° |

On an EV the Drive page shows speed and power side by side. The power gauge's outer arc is battery
power (cyan = traction, green = regen); two inner bands show the rear motor (torque × rpm from the
motor controller) and the front motor. Only one motor controller is published, so front motor power
is estimated as battery power minus rear (EST; 0 while the front motor isn't turning, and it includes
heating/AC load). The engine-load gauge becomes battery SOC, and ENGINE ON/OFF becomes READY/OFF. Not in any public list: turn signals,
seat belts and brake line pressure. Those are on the car's internal CAN-FD buses (which is how
comma.ai reads them, from the camera harness) and may not be readable through the OBD gateway at
all. The signal finder below is the way to find out.

## Signal finder (experimental)

Settings → Signal finder pauses the dashboard and uses the adapter directly:

1. **Check profile** sends each profile request once and shows the raw reply, so an N/A gauge can be
   traced to "no reply", a negative response (NRC) or a reply too short for the formula.
2. **Scan ECUs** pings about 25 ECU addresses and reads ~100 DIDs (0100–010F, B001–B010, BC01–BC10,
   C001–C00F, E001–E011, F010–F01F, plus extras) on each ECU that answers.
3. **Watch** re-reads the body/chassis DIDs found while you tag what you are doing (left signal on,
   driver belt buckled, brake pressed…). Changed bytes are listed live and every read goes to a CSV in
   `Android/data/com.dreyev.vlink/files/signal-finder/`. **Share** sends it (and the latest trip log).

## Theme

Settings → Appearance: **System** (default, follows the phone's dark mode), **Dark** or **Light**.
Saved across restarts. The Android Auto screen always uses the dark palette.

## Android Auto

The `sideload` build adds a car screen to Android Auto. It draws the gauges on a full-screen surface
(`app/src/sideload/java/com/dreyev/vlink/auto/`), with four pages picked from the icons on the right:
**Drive** (speed, power or RPM, pedals, G-force, steering, SOC or load, turn signals, belts),
**Tires** (pressure, temperature, reading age, brake and acceleration) and **Vehicle** (VIN,
odometer, battery or engine, GPS trail and compass) and **Map** (a Google map with the trace,
heading-up while moving, with speed and power chips). The map page renders a `MapView` into a
private virtual display whose output is the car surface; if that fails, or no Maps key was built
in, it draws the offline trace instead. It shares the phone app's adapter link, so
both screens show the same live data.

To get full graphics the app registers as a *navigation* car app. Google only accepts real
navigation apps in that category on the Play Store, so this build is meant to be sideloaded:

1. Install the APK, open the phone app once, grant Bluetooth and location, and connect to the VLink
   adapter (it reconnects automatically after that).
2. In Android Auto settings on the phone (Settings → Connected devices → Android Auto), tap
   **Version** about 10 times and accept to turn on developer mode.
3. In the ⋮ menu choose **Developer settings** and turn on **Unknown sources**.
4. Connect the phone to the car. "dreyev VLink" appears in the Android Auto launcher.

Car screens are checked with Paparazzi (`carDrive`, `carTires`, `carVehicle`, `carDriveIoniq5`,
`carVehicleIoniq5`, `carMap`) at 1280×720.

## Google Maps key

The Map tab, the map in the Vehicle & GPS card and the Android Auto map page use the Google Maps
SDK for Android. The key is read at build time from `MAPS_API_KEY` in `local.properties`
(git-ignored) or from the `MAPS_API_KEY` environment variable. **Never commit it.** Without a key
the app builds and runs normally and shows the offline breadcrumb trace instead of a map.

Create and restrict the key (about 10 minutes):

1. Open <https://console.cloud.google.com/>, sign in, and create a project (e.g. "dreyev VLink").
2. **Billing**: link a billing account to the project (Billing → Link a billing account). Google
   requires one for any Maps key. Map loads in the Android Maps SDK (Mobile Native Dynamic Maps)
   are free with no monthly cap, so this app should cost nothing; add a budget alert (Billing →
   Budgets & alerts, e.g. $1) for peace of mind.
3. **Enable the API**: APIs & Services → Library → search "Maps SDK for Android" → Enable.
   Nothing else is needed.
4. **Create the key**: APIs & Services → Credentials → Create credentials → API key.
5. **Restrict it** (Edit API key):
   * Application restrictions → **Android apps** → Add an item for each build you install:
     package `com.dreyev.vlink.sideload` (Android Auto build) and `com.dreyev.vlink` (phone /
     Play build), each with the SHA-1 of the key that signs that APK. Get it with
     `keytool -list -v -keystore <keystore> -alias <alias>` (debug builds:
     `~/.android/debug.keystore`, alias `androiddebugkey`, password `android`). For the Play
     build, also add the **App signing key** SHA-1 from Play Console → Setup → App signing.
   * API restrictions → **Restrict key** → tick only **Maps SDK for Android**. Save.
6. Put the key in `local.properties` next to `sdk.dir`:
   ```
   MAPS_API_KEY=AIza...
   ```
   and rebuild. Restrictions can take a few minutes to apply.

A key built into an APK can be read by anyone who has the APK; the package + SHA-1 and API
restrictions are what stop others from using it. If the map stays grey with a "Google" logo,
the restriction doesn't match the installed APK: check Logcat for `Authorization failure` and
the SHA-1 it prints.

## Known limits

* MFR signals depend on per-car PIDs; profiles ship for the simulator and the Hyundai Ioniq 5.
  Some newer cars block these requests with a security gateway.
* TPMS sensors transmit every ~30–60 s while driving and rarely when parked; each tire card
  shows how old its reading is.
* The GPS card draws your recent track instead of map tiles (no API key or data needed).
