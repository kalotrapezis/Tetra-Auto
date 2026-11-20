# Tetra Auto

**[⬇ Download Tetra Auto 0.2 for the car (APK, 2 MB)](https://github.com/kalotrapezis/Tetra-Auto/releases/download/v0.2.0/TetraAuto-0.2.0.apk)** — needs [Tetra for Android 0.8 Beta 13](https://github.com/kalotrapezis/Tetra-mobile/releases/tag/v0.8.0-beta.13) on the phone.

Tetra Car on your car's own screen, the way Android Auto does it: **the phone is the server, the car a client.**
Tetra on the phone draws Tetra Car (Maps, music, phone, messages, AI Assist) on a private screen the size of the car's,
and sends the picture over Wi‑Fi; the car shows it and sends every touch back. The phone may stay in your pocket with
its screen off. Everything runs on the phone, with its GPS, its apps and its up-to-date code; the car needs only
Android 10 and Wi‑Fi.

## What the car app does

- Shows the phone's picture full-screen (H.264, decoded by the unit's own hardware) and sends touches, Back and the
  steering wheel's voice key to the phone.
- Finds the phone by itself: the phone announces itself on the Wi‑Fi (the phone's hotspot is the usual one).
- **Share** — files both ways, which a head unit's Bluetooth usually cannot do: from the phone's share menu
  (*Send to car*), or Tetra Car's dock › Share on the car. An app sent from the phone (a Tetra Auto update) is offered
  for installing. Files from the car go to *Tetra › From car* on the phone.

## Setting it up

1. Install Tetra Auto on the head unit (the APK from Releases, from a USB stick; after that, updates come over Share).
2. Turn on the phone's hotspot and connect the head unit to it.
3. On the phone: Tetra › Settings › Tetra Car › *Pair with the car*, and scan the code Tetra Auto shows.
4. Tetra Car appears on the car's screen. From then on the phone gets ready as the car's Bluetooth joins
   (Settings › Tetra Car › the car's Bluetooth), or by *Show on the car now*.

Sound goes over Bluetooth as it does now (calls and music).

## Building

```bash
export JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64 ANDROID_HOME=$HOME/Android/Sdk
./gradlew assembleDebug
```

The phone's half is in Tetra for Android (`AutoProjection.kt`, `AutoWire.kt`). `Wire.kt` here and `AutoWire.kt` there
are the same protocol, word for word.

The branch `standalone` keeps an earlier attempt: Tetra Car itself cut down to run on the head unit (maps and music on
the car). Projection replaced it.

## Tetra Auto SE (`se/`)

**[⬇ Download Tetra Auto SE 0.8.0 Beta 27 for the car (APK, arm64)](https://github.com/kalotrapezis/Tetra-Auto/releases/download/se-v0.8.0-beta.27/Tetra-Auto-SE-0.8.0-beta.27-arm64.apk)** — on the head unit itself.

Tetra Auto SE, the "stand alone edition", is Tetra for Android built for a head unit: Tetra Car is its start and home
screen, and it runs on the unit itself. It pairs with the phone over the car link (Tetra's CarWire) for calls, messages,
maps, bookmarks and Share. Until Tetra for Android 0.8 Beta 24 it was Tetra-mobile's `car` module. On 18 Nov 2026 it moved
here, copied as it was at Beta 24 (Tetra-mobile `0c3e70f5`), and Tetra for Android no longer carries it.

```bash
./gradlew :se:assembleRelease            # se/build/outputs/apk/release/se-arm64-v8a-release.apk, se-armeabi-v7a-release.apk
./gradlew :se:assembleDebug -Pemulator   # with an x86_64 APK for the tetra_car_se emulator
```

Same applicationId (`com.kalotrapezis.tetra.auto.se`) and release key (`~/.android/tetra-release.properties`) as before, so
it installs over the car's current one.

## Licence

GPL-3.0, as Tetra.
