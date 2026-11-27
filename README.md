# dreyev VLink dashboard (Android)

Native Kotlin / Jetpack Compose app that connects to a VLink (ELM327-compatible) OBD-II
Bluetooth adapter and shows the car's data on analog and digital gauges, in portrait
(Drive · Tires & chassis · Vehicle & GPS) and landscape (Drive · Tires & chassis).

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

Requires JDK 17+ and the Android SDK (platform 35).

```
./gradlew :app:assembleDebug                 # APK → app/build/outputs/apk/debug/
./gradlew -PcoreOnly=true :obd-core:test     # data-layer tests, no Android SDK needed
./gradlew :app:recordPaparazziDebug          # render screens to app/src/test/snapshots/images
```

## Known limits

* MFR signals depend on per-car PIDs; only a demo profile (for the simulator) ships today.
  Some newer cars block these requests with a security gateway.
* TPMS sensors transmit every ~30–60 s while driving and rarely when parked; each tire card
  shows how old its reading is.
* The GPS card draws your recent track instead of map tiles (no API key or data needed).
