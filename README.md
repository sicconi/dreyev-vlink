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

## Hyundai Ioniq 5 (2022–2024)

`app/src/main/assets/profiles/hyundai-ioniq5.json` is picked automatically from the VIN (or choose it in
Settings). It uses community-published PIDs (Esprit1st/Hyundai-Ioniq-5-Torque-Pro-PIDs, built on
JejuSoul/OBD-PIDs-for-HKMC-EVs), not yet verified on a car:

| Signal | Module | Request | Formula |
|---|---|---|---|
| Tire pressure FL/FR/RL/RR | 7A0 (TPMS) | 22C00B | E/5, J/5, O/5, T/5 psi |
| Tire temperature FL/FR/RL/RR | 7A0 | 22C00B | F-50, K-50, P-50, U-50 °C |
| Steering angle | 730 | 22F010 | signed(N·256+M)/10 ° |
| Vehicle speed, outside temp | 7B3 | 220100 | AD km/h, G/2-40 °C |
| Odometer | 7C6 | 22B002 | G·65536+H·256+I km |
| READY, HV current/voltage, motor RPM, 12 V | 7E4 (BMS) | 220101 | bit(AY,2), signed(K·256+L)/10 A, (M·256+N)/10 V, BB/BC, BD/BE, AD/10 |
| State of charge | 7E4 | 220105 | AF/2 % |

On an EV the big gauge's arc shows battery power (kW, green while regenerating), the engine-load gauge
becomes battery SOC, and ENGINE ON/OFF becomes READY/OFF. Not in the published list: brake pressure
and pedal, individual wheel speeds, turn signals, seatbelts and car-sensor acceleration, so those
show N/A (G-force falls back to the phone). If steering reads left when turning right, flip the
sign in the formula.

## Known limits

* MFR signals depend on per-car PIDs; profiles ship for the simulator and the Hyundai Ioniq 5.
  Some newer cars block these requests with a security gateway.
* TPMS sensors transmit every ~30–60 s while driving and rarely when parked; each tire card
  shows how old its reading is.
* The GPS card draws your recent track instead of map tiles (no API key or data needed).
