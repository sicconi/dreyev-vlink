# Building a Google Play release

The app has two flavors:

| Flavor | Package | What it is |
|---|---|---|
| `play` | `com.dreyev.vlink` | The phone app that goes to Google Play. No Android Auto code. |
| `sideload` | `com.dreyev.vlink.sideload` | "dreyev VLink AA": phone app + Android Auto module. Install by hand, enable Android Auto developer mode + Unknown sources. |

`com.dreyev.vlink` is the final Play package name. Once the first upload is accepted it can never change.

## 1. Create your upload key (once)

Play App Signing keeps the real app-signing key at Google. You only keep an **upload key**. If you lose it, Google can reset it (Play Console › Test and release › App integrity), so it's not fatal, but keep it safe.

```
keytool -genkeypair -v -keystore ~/keys/dreyev-vlink-upload.jks \
  -alias upload -keyalg RSA -keysize 4096 -validity 10000
```

`keytool` ships with any JDK (Android Studio has one under `jbr/bin`). Pick a strong password and store it in a password manager. Back up the `.jks` file somewhere other than your laptop.

You can also do this in Android Studio: **Build › Generate Signed App Bundle or APK › Android App Bundle › Create new…**

## 2. Point the build at the key

Create `keystore.properties` in the repo root (it's git-ignored, never commit it):

```
storeFile=/Users/you/keys/dreyev-vlink-upload.jks
storePassword=…
keyAlias=upload
keyPassword=…
```

Or set the environment variables `VLINK_UPLOAD_STORE_FILE`, `VLINK_UPLOAD_STORE_PASSWORD`, `VLINK_UPLOAD_KEY_ALIAS`, `VLINK_UPLOAD_KEY_PASSWORD` (handy for CI). Without either, release builds come out unsigned and Play rejects them.

## 3. Google Maps key for release builds

The release AAB needs `MAPS_API_KEY` in `local.properties` (or the environment), like debug builds; without it the map shows a "needs a key" placeholder. In Google Cloud Console, restrict the key to **Maps SDK for Android** and to these apps:

* package `com.dreyev.vlink` + the SHA-1 of Google's **app signing key** (Play Console › Test and release › App integrity › App signing). Play re-signs your upload, so the upload key's SHA-1 alone won't work for users who install from Play.
* package `com.dreyev.vlink` + your upload key's SHA-1, and `com.dreyev.vlink.sideload` + the SHA-1 you sign the AA build with, for your own installs.

`keytool -list -v -keystore ~/keys/dreyev-vlink-upload.jks -alias upload` prints the upload key's SHA-1.

## 4. Build the bundle

```
./gradlew :app:bundlePlayRelease
```

Upload `app/build/outputs/bundle/playRelease/app-play-release.aab` in Play Console.

Check it's signed: `jarsigner -verify app/build/outputs/bundle/playRelease/app-play-release.aab` should print `jar verified.`

## 5. Every later update

1. Raise `versionCode` by 1 in `app/build.gradle.kts` (Play refuses a code it has seen). Change `versionName` (shown to users) as you like.
2. `./gradlew :app:bundlePlayRelease` and upload.

## Android Auto build (not for Play)

```
./gradlew :app:assembleSideloadRelease   # signed with your upload key if configured
./gradlew :app:assembleSideloadDebug     # debug key, fine for your own phone
```

The Play app and the AA build have different package names, so both can be installed at once. The older debug build (`com.dreyev.vlink`, debug-signed) must be uninstalled before installing the Play version, because the signatures differ.

## Target SDK

`targetSdk = 36` (Android 16). Google Play has required API 36 for new apps and updates since August 31, 2026. Expect to move to 37 around August 2027.

## Store graphics

* `docs/play-store/graphics/icon-512.png`: Play icon (512×512)
* `docs/play-store/graphics/feature-graphic-1024x500.png`: feature graphic
* `docs/play-store/screenshots/*.png`: phone screenshots, 500×1000 (2:1), rendered from the real UI with simulated data. The Map and Vehicle & GPS tabs aren't included (the renderer can't draw Google Maps); capture those on your phone. Regenerate with
  `VLINK_STORE_SHOTS=1 ./gradlew :app:testPlayDebugUnitTest --tests '*StoreScreenshotTest'`.
  Real screenshots from your phone (Power + Volume down) look sharper and are just as valid.
