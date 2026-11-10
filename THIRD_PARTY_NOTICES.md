# Third-party notices

## MobileFaceNet face-embedding model

`app/src/main/assets/mobilefacenet.tflite` is sourced from
`hugocornellier/face_detection_tflite`, commit retrieved on 2026-09-20.
The upstream project declares the model Apache License 2.0. The bundled file's
SHA-256 is `be4bc7cfc53f7bc336d0f28b1ab92535f618c913a422b683210750f6b5354854`.

Apache License 2.0: https://www.apache.org/licenses/LICENSE-2.0

## EfficientNet-Lite0 image-classification model

`app/src/main/assets/efficientnet_lite0.tflite` is Google’s MediaPipe
EfficientNet-Lite0 int8 image-classification model, retrieved on 2026-09-20
from `https://storage.googleapis.com/mediapipe-models/image_classifier/efficientnet_lite0/int8/latest/efficientnet_lite0.tflite`.
The model is used on-device alongside ML Kit; its SHA-256 is
`bc2ffe19c1118de0c0c2a9088992da5589722656e0fba81421385300a4a34b16`.
MediaPipe is licensed under Apache License 2.0:
https://github.com/google-ai-edge/mediapipe/blob/master/LICENSE

## MapLibre Native and OpenFreeMap

The in-app photo map uses MapLibre Native Android 13.6.1, licensed under the
BSD 2-Clause License. Map tiles and styles are provided by OpenFreeMap using
OpenMapTiles and OpenStreetMap data. MapLibre displays the required map-data
attribution in the map UI.

MapLibre license: https://github.com/maplibre/maplibre-native/blob/main/LICENSE.md
OpenFreeMap: https://openfreemap.org/

## lib-recur

The Calendar works out repeating events (iCalendar RRULE) with lib-recur 0.17.1
(`org.dmfs:lib-recur`, with `org.dmfs:rfc5545-datetime` and `org.dmfs:jems2`),
licensed under Apache License 2.0: https://github.com/dmfs/lib-recur

## OpenCV

Document page detection uses OpenCV 4.12.0 for Android (`org.opencv:opencv`),
licensed under Apache License 2.0: https://github.com/opencv/opencv/blob/4.x/LICENSE

## Google ML Kit and CameraX

Barcode scanning (`com.google.mlkit:barcode-scanning`), text recognition,
face detection and image labelling run on-device through Google ML Kit, used
under the ML Kit Terms of Service: https://developers.google.com/ml-kit/terms
Camera capture uses AndroidX CameraX, licensed under Apache License 2.0.

## Word's preset shape geometry (LibreOffice)

`app/src/main/resources/shapes/presets.txt` is converted by `tools/shape-presets.py` from LibreOffice's
`share/filter/oox-drawingml-cs-presets` (LibreOffice 26.2), the preset shape definitions of ECMA-376 (Office Open XML)
in LibreOffice's custom-shape notation. LibreOffice is licensed under the Mozilla Public License 2.0, which allows
this file to be combined with Tetra's GPL 3.0 code: https://www.mozilla.org/MPL/2.0/ — source:
https://git.libreoffice.org/core/+/refs/heads/master/oox/source/drawingml/customshapes/

## MuPDF

The PDF applet draws, edits and saves PDFs with MuPDF 1.28.5 (`com.artifex.mupdf:fitz`), © Artifex Software,
licensed under the GNU Affero General Public License 3.0: https://mupdf.com/licensing — Tetra's source is published
here, as that licence asks.

## Tesseract

PDF › Recognize text uses Tesseract through Tesseract4Android 4.8.0, with the Greek and English `tessdata` models
in `app/src/main/assets/tessdata`, all under the Apache License 2.0: https://github.com/adaptech-cz/Tesseract4Android,
https://github.com/tesseract-ocr/tessdata_fast

## GraphHopper

Maps' car, walk and bike routes are worked out on the device with GraphHopper 10.2 (`com.graphhopper:graphhopper-core`),
Apache License 2.0: https://github.com/graphhopper/graphhopper

## ZXing and TensorFlow Lite

QR codes are drawn with ZXing 3.5.3 and on-device models run on TensorFlow Lite 2.16.1 (with the Task Vision
library 0.4.4), all under the Apache License 2.0: https://github.com/zxing/zxing, https://github.com/tensorflow/tensorflow

## Bouncy Castle

The Browser's password sync derives its key from the sync passphrase with Argon2id from the Bouncy Castle Crypto
APIs (`org.bouncycastle:bcprov-jdk18on` 1.80.2), MIT licence: https://www.bouncycastle.org/licence.html

## Noto Sans (map labels)

`app/src/main/assets/maps/fonts` holds Noto Sans glyphs for the map's labels, under the SIL Open Font License 1.1
(`app/src/main/assets/maps/fonts-OFL.txt`).

## GeckoView (Mozilla)

The Browser's engine is GeckoView (`org.mozilla.geckoview:geckoview` 159), Firefox's
engine for Android, by the Mozilla Foundation and its contributors, licensed under the
Mozilla Public License 2.0: https://www.mozilla.org/MPL/2.0/ — its source is at
https://hg.mozilla.org/mozilla-central and https://github.com/mozilla-firefox/firefox.
Tetra uses GeckoView unmodified, as a library from Mozilla's Maven repository. The notices
of the components GeckoView is built from are in its source, `toolkit/content/license.html`.

The Browser installs, on its first start, uBlock Origin (GPL v3, Raymond Hill) and Dark
Reader (MIT, Dark Reader Ltd) from addons.mozilla.org; they are not part of Tetra's package.

## EFF's large word list (Passwords)

Passwords › Create › Words picks from the Electronic Frontier Foundation's large word list
for passphrases (`app/src/main/assets/eff_large_wordlist.txt`, 7,776 words, unchanged), by
the EFF, licensed under Creative Commons Attribution 3.0 (CC BY 3.0):
https://creativecommons.org/licenses/by/3.0/ — from
https://www.eff.org/deeplinks/2016/07/new-wordlists-random-passphrases
