# Tetra Car Stand Alone Edition: the plan

A normal app on the head unit that runs Tetra Car itself: Maps, Media, Phone, Messages, AI Assist. The phone gives only its
hotspot (internet) and signals and commands. No Bluetooth audio, no Android Auto. Tetra Auto (projection) stays as it is.

## Why the first attempt was dropped, and what changes now

The `standalone` branch (8ba214a, 10 Nov) was Tetra for Android copied whole and cut down to minSdk 29: about 30,000 lines,
GeckoView, the full Assistant, Sync. It was dropped because there was no way to get big files onto the car again and again
for testing. Only a download over the internet worked. Tetra Auto's Share now sends files over Wi-Fi quickly, so that
problem is solved.

Two lessons stay:
- **2 GB of RAM, Android 10.** GeckoView alone is about 150 MB of native code and a few hundred MB of RAM. The copy also
  brought everything the phone does. This time the app is written for the unit and stays small.
- **A copy of the whole app falls behind the phone's code.** This time only a few self-contained files are copied, and each
  copy says which Tetra-mobile commit it came from.

## 1. How much of Tetra-mobile is reused

**A second flavour in this repo:** `standalone` beside `projection`, minSdk 29, application id
`com.kalotrapezis.tetra.car`, so it installs beside Tetra Auto. Both flavours share `Wire.kt`, `Link.kt` (finding the phone,
pairing by QR, token) and `Share.kt`.

Lowering Tetra-mobile itself to minSdk 29 is not a good fit. It is one module of 105,000 lines with GeckoView, OpenCV,
ML Kit and TensorFlow, too heavy for 2 GB.

| From Tetra-mobile | How |
|---|---|
| Map style, fonts, sprites, the blue arrow | copied as they are (assets) |
| `MapsRouting.kt` (GraphHopper on the computer-built graph, `MMAP_RO`) and the guidance math in `Maps.kt` (progress, when to say what, spoken distances, bearings) | copied. They are plain Kotlin and already built for API 29 on the `standalone` branch |
| `CarScreen.kt`: dock down a side, clock › recent messages, ☰ launcher grid in rounded squares, Settings (map size, zoom, tilt, arrow position, interface size) | **rewritten** for the car alone, same look and the same choices. Its panes hold the phone's full pages (Browser, PhoneApp, SmsScreen…), which do not fit here |
| `MapsScreen.kt` (1,500 lines) | **rewritten** lean: one MapLibre map, tilted Google Maps-like camera, arrow low, route, turn banner, voice through Android's own TTS |
| Browser (GeckoView) | **replaced:** radio streams in Media3 ExoPlayer (small, plays in the background), YouTube in the system WebView |

"Resolution" in Settings only means something for projection, so it is left out here.

## 2. Phone ↔ car: the phone a small server, the car its client

The same Tetra Auto link (`AutoWire` / `Wire.kt`: one TCP connection over the hotspot, the phone's beacon, the QR pairing, the
token). The car's HELLO says `"mode": "standalone"`, and the phone then starts no picture at all. Three new frames, the same
in both files, word for word:

- **EVENT**, phone → car, JSON: `sms` (from, name, text), `call` (state, name, number), `fix` (the phone's GPS, once a
  second), `go` (navigate to a place: AI Assist on the phone, or a place shared to the car), `play` (a station or a link),
  `say` (a line to speak).
- **ASK**, car → phone, JSON with an `id`: send an SMS, answer, hang up or place a call, recent calls, favourite contacts,
  recent conversations, AI Assist (below).
- **ANSWER**, phone → car: the reply to an ASK `id`.

On the phone this is one new file (`AutoStandalone.kt`) plus one branch in `AutoProjection.kt`'s session. The call and SMS
actions already exist in `CarWire.serve`'s `/car/do` and are reused.

**Call audio, honestly:** Android does not let an app carry a call's voice. The voice of a call reaches the car speakers only
over Bluetooth hands-free, which the unit pairs with the phone by itself (no Android Auto needed). Without it, the call is on
the phone's speaker. Music, directions and AI Assist need no Bluetooth.

**Notifications from other apps:** Tetra-mobile has no notification listener yet. Version 1 carries SMS and calls; other
apps' notifications come later (they need a permission on the phone).

## 3. Offline maps onto the car

**Over Share (Wi-Fi), not a download.** The maps are built on the computer and have no URL, and a download would spend the
phone's mobile data.
- Phone: Settings › Tetra Car › *Send maps to the car* sends each map's `.pmtiles`, `.json` and its `-gh` graph folder.
  Files keep their path (`Maps/greece-gh/…`) and land in the car's Maps folder.
- Greece is 485 MB of tiles plus 744 MB of graph, 1.2 GB in all: a few minutes over the hotspot. The car checks free space
  first and resends only files that changed (size and date).
- For testing from the computer: `adb push` works too.

## 4. GPS: the car's, the phone's, or both

Settings › Maps › Location: **Best of both** (default), Car, Phone.

Best of both, as suggested: of the fixes less than 2 s old, take the one with the best accuracy. On a tie, take the car's
fix, which has no Wi-Fi delay. A phone fix is moved forward by speed × its age, so it does not trail behind at 100 km/h.
If one source has nothing fresh, use the other.

**Honestly:** two GNSS receivers see the same satellites. Averaging them does not give a noticeably better position, and it
can make the arrow jump when they disagree. What "both" really gives:
- **A quick start.** The phone has assisted GPS and finds itself in seconds. A cheap unit's cold start can take minutes.
- **Cover when one drops out.** The unit's antenna behind a metal dash, the phone in a pocket or a bag.
- **Whichever is better.** If the unit has an external roof antenna, its fix wins on accuracy by itself.

## 5. AI Assist, and keeping keys safe

**Recommended: the car holds no key at all.** The car records you (its own mic) and sends the audio over the link (ASK
`assist`). The phone's AI Assist does the work: its keys in the Android Keystore, its chosen services (Mistral, OpenAI,
Groq, ElevenLabs) and its 38 tools. It sends back the answer text plus any command (`go`, `play`, `sms`). The car shows the
TV and speaks the answer through its own speakers, with the phone's voice when one is chosen (audio bytes) or Android TTS.

Without the phone the car has no internet anyway, so a key on the car would add little. The keys stay where they are today:
on the phone only, never in a repo, never on the car. If you ever want the car to have its own key, it is typed on the car
and kept Keystore-encrypted. It never comes over the link and never goes into the repo.

## 6. 2 GB / Android 10: what is written for the unit

- No GeckoView. YouTube uses the unit's system WebView. If that WebView turns out too old, YouTube is the one thing that
  fails; radio does not depend on it.
- One activity and one map. A pane that is not shown is dropped, except the map and the music, which keep running.
- The graph is memory-mapped read-only (`MMAP_RO`, as on the phone), so it takes no heap.
- **To check on the unit:** whether its Android is 32-bit (`ro.product.cpu.abilist`). A 32-bit system may not map 1.2 GB of
  map and graph at once. Then the fix is a smaller map (a region, or the graph without Bulgaria).
- The APK is arm-only (arm64 and armv7) for release, plus x86_64 for the emulator.

## 7. Build order and how each part is tested

1. The flavour and the link's standalone mode, plus a **fake phone**: a small script on the computer that speaks the wire.
   The emulator reaches the computer at 10.0.2.2, its gateway, which `Link` already tries.
2. The Tetra Car screen: dock, clock, ☰ grid, Settings.
3. Maps with the car's GPS, the phone's fixes and Best of both (the emulator's GPS plus fixes from the fake phone).
4. Media: a radio stream through ExoPlayer (its own sound).
5. An SMS EVENT from the fake phone, shown and read aloud. A "go" EVENT that starts a route.
6. The phone's side in Tetra-mobile (`AutoStandalone.kt`), then the same tests with the real phone.
7. Send maps to the car; offline routing and voice directions.
8. Phone and Messages pages; AI Assist through the phone; YouTube in WebView.

Steps 1–5 meet "done" on the emulator. Steps 6–8 follow, then the real car.
