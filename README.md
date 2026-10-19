# UC2 Control — Android USB OTG app for openUC2

Native Android app that drives an openUC2 ESP32 / ESP32-S3 board over USB OTG.
It covers the hardware control surface of the
[WebSerial demo](https://youseetoo.github.io) — stage jog, illumination, LED
matrix, homing, TMC and CAN setup, and a raw JSON console — without needing a
laptop in the lab.

The **Scan** tab turns phone + camera + XY stage into a low-cost ShitScope:
live view from a USB webcam, the phone's own camera or a Daheng USB3 camera,
fixed exposure and gain, a stage overview you can tap to move, XY(Z) jog, and
tiled 2D scans saved on the phone in the ShitScope folder layout, copied to
the gallery, ready to share to Drive or Dropbox and to stitch on a computer
with ImSwitch.

<!-- Screenshots go here once you have a board to point it at. -->

---

## Install

Grab an APK from the [Releases](../../releases) page, or from the artifacts of
any [Build APK](../../actions/workflows/build.yml) run, and sideload it.

Requirements: Android 5.0 (API 21) or newer, and a phone that supports **USB
host / OTG**.

---

## Build

```bash
./gradlew assembleDebug
```

The APK lands in `app/build/outputs/apk/debug/`. Requires **JDK 17** — newer
JDKs fail with `Unsupported class file major version`.

```bash
export JAVA_HOME=$(/usr/libexec/java_home -v 17)   # macOS
./gradlew assembleRelease                          # unsigned release build
./gradlew test                                     # protocol unit tests
./gradlew lintDebug                                # static analysis
```

### Build in CI

`.github/workflows/build.yml` builds on every push, PR and tag.

| Trigger | Result |
|---|---|
| push / PR | debug + release APK as workflow artifacts, plus test and lint reports |
| `workflow_dispatch` | same, with a debug/release/both choice |
| tag `v*` | a GitHub Release with both APKs attached |

Run it from the command line with the [`gh`](https://cli.github.com) CLI:

```bash
gh workflow run build.yml -f build_type=both
```

Watch it and download the result:

```bash
gh run watch
gh run download --name UC2Control-apk-0.0.0-main+abc1234
```

Cut a release:

```bash
git tag v2.0 && git push origin v2.0
```

Version numbers come from the tag; untagged builds get
`0.0.0-<branch>+<sha>` and use the run number as the version code.

### Signed release builds (optional)

Without a keystore, CI still produces a working *unsigned* release APK, and
debug APKs are always signed with the standard debug key. Signing is only
needed if you want a release APK that upgrades in place.

**Never commit the keystore.** `keystore.properties`, `*.jks` and `*.keystore`
are all git-ignored; reference the key by absolute path instead of copying it
into the repo.

#### Locally

Create `keystore.properties` in the repo root:

```properties
storeFile=/absolute/path/to/your.keystore
storePassword=…
keyAlias=…
keyPassword=…
```

Then `./gradlew assembleRelease` produces `app-release.apk` (signed) instead of
`app-release-unsigned.apk`. Forget the alias? `keytool -list -keystore <file>`
lists it — it will prompt for the store password.

#### Finding the alias

```bash
keytool -list -keystore /absolute/path/to/your.keystore
```

It prompts for the **store** password and prints one line per entry:

```
myandroidkey0, 09.09.2019, PrivateKeyEntry,
```

The first field is the alias — that exact string is `KEY_ALIAS`.

#### In GitHub Actions

Four repository secrets, set with the [`gh`](https://cli.github.com) CLI so the
passwords never land in your shell history:

```bash
base64 -i /absolute/path/to/your.keystore | gh secret set KEYSTORE_BASE64
printf 'myandroidkey0' | gh secret set KEY_ALIAS
gh secret set KEYSTORE_PASSWORD
gh secret set KEY_PASSWORD
```

The last two prompt for the value and read it without echoing. You can also add
them under **Settings ▸ Secrets and variables ▸ Actions** in the browser.

| Secret | Value |
|---|---|
| `KEYSTORE_BASE64` | base64 of the keystore file |
| `KEYSTORE_PASSWORD` | the password that opens the *store* |
| `KEY_ALIAS` | the alias from `keytool -list` |
| `KEY_PASSWORD` | the password of the *key*. Android Studio's "create new keystore" wizard offers to reuse the store password, so these are often — but not always — identical. |

The workflow decodes the key into `$RUNNER_TEMP` (outside the checkout, so it
can never be swept into an artifact upload) and verifies all four values before
Gradle runs: keystore readable, store password correct, alias present, key
password correct. A wrong value fails that step with a one-line message naming
the culprit — and a wrong alias prints the aliases that *are* in the keystore.
AGP's own error for this is a `KeytoolException` in a 40-line Gradle stack
trace with every useful value masked as `***`.

With the secrets absent the whole step is skipped and the build falls through
to unsigned — no failure, no branching to maintain.

---

## Using the app

1. Connect the board to the phone through a USB-C OTG adapter — or directly,
   if the board has USB-C. **The cable must be a data cable**; charge-only
   cables enumerate nothing at all.
2. Android asks which app to open. Pick UC2 Control and allow USB access.
3. Choose a baud rate (**115200** is the firmware default) and tap **Connect**.

Tabs:

| Tab | What is in it |
|---|---|
| **Scan** | Camera live view and exposure, stage overview, XY(Z) jog, tile scan, gallery, share — see [Tile scanning](#tile-scanning) |
| **Stage** | XYZA jog, continuous motion, per-axis stop, big STOP ALL, motor power |
| **Light** | 5 laser/LED PWM channels, LED ring and half-field patterns, 8×8 single-LED grid |
| **Setup** | Homing, TMC driver, CAN address, board queries, connection troubleshooting |
| **Console** | Full serial log, raw JSON entry, share/export |

To change baud while connected, pick a new rate and tap **Apply** — the port is
closed and re-opened, which also resets the board.

---

## When it will not connect

This is the part that used to be painful, so the app now tells you what it
sees instead of failing silently.

**Tap `Diagnostics`.** It lists every attached USB device with VID/PID,
interface classes, permission state, and whether a driver matched. `Share log`
in the Console tab sends the same dump plus the full session log.

Common causes, in the order worth checking:

1. **Nothing listed at all** — the phone is not in host mode. Most phones need
   a real OTG adapter, and every cable must carry data.
2. **Device listed, `driver: none`** — an unrecognised USB-serial chip. Set
   **Setup ▸ Force driver** to CDC-ACM (ESP32-S3 native USB) or CH34x, then
   reconnect. Please also send us the dump so the chip can be added.
3. **Connects, but no output** — wrong baud rate. The board's boot banner is
   the giveaway: garbage characters mean the rate is wrong. Try 115200, then
   921600.
4. **Connects, board silent, ESP32-S3 with native USB** — check that
   **DTR asserted** is on in Setup. Arduino's `USBCDC` only transmits once the
   host raises DTR.
5. **Board seems held in reset** — leave **RTS asserted** on. On boards with
   the classic auto-reset circuit, DTR low with RTS high holds EN low and the
   ESP32 never starts.

### Supported USB-serial chips

CP210x · CH340 / CH341 · FTDI · Prolific · CDC-ACM, including **Espressif
native USB (VID 0x303A)** on ESP32-S2/S3/C3.

`usb-serial-for-android` 3.7.0 has no entry for the Espressif VID at all —
`UsbDrivers.java` adds it, so S3 boards are found deterministically rather than
relying on a descriptor walk.

**CH343 / CH9102F (VID 0x1A86, PID 0x55Dx)** — common on current ESP32-S3
boards — are detected as a *last-resort fallback*: the app tries the CH34x
driver on any otherwise-unmatched WCH device and marks it `[guess]` in
Diagnostics. These parts ship in either CDC or vendor-specific mode and only
some of them speak the CH340 protocol closely enough. If a guess misbehaves,
set **Force driver ▸ CDC-ACM** and reconnect — and please send us the
Diagnostics dump so the id can be mapped properly.

If your board is not detected at all, add its VID/PID to `UsbDrivers.java`
*and* to `res/xml/usb_serial_filter.xml` (which takes **decimal** ids). The file
must not be called `device_filter.xml`: the webcam library reads a resource of
that name to find cameras, and the app's copy would replace it.

---

## Tile scanning

The Scan tab does what ImSwitch's ShitScope does — move, settle, take a frame
exposed after the move, save it, show it on the overview — with a phone in
place of the computer.

### Hardware

```
phone ── USB-C OTG hub ──┬── camera: USB webcam (UVC) or Daheng USB3 camera
                         └── stage: openUC2 ESP32 board, or the PCB planar stage
```

Most phones run a camera and a serial board through one powered OTG hub. With
the phone's own camera (through an eyepiece adapter, say) only the stage
needs USB. A camera alone also works: pick **No stage** to try live view,
storage and sharing without any stage.

### Steps

1. **Connect** the stage in the bar at the top (115200 baud for an openUC2
   board, 9600 for the PCB stage), then pick its protocol under **Stage
   hardware** and check µm/step and speed.
2. Pick the camera, tap **Connect**, allow camera and USB access. The live
   view opens at the camera's largest mode and fills in the image width and
   height.
3. Set **exposure** (ms) and **gain**, tap **Apply** and check the live view.
   Every tile is taken with exactly these values, applied again when the scan
   starts; auto exposure, auto gain and auto white balance are off, and on
   the phone camera focus is locked where autofocus left it. Gain is ISO on
   the phone, dB on Daheng and the raw control value on webcams; values are
   clamped to what the camera supports, and the status line shows what was
   set.
4. Enter the **pixel size at the sample** (µm). Field of view = width ×
   height × pixel size; empty steps default to field of view × (1 − overlap).
5. Jog, or tap the overview, to the middle of the area; set tiles X/Y; the
   outlines on the map show the grid you are about to scan.
6. **Start scan.** Thumbnails land on the map at their commanded positions;
   **Stop** finishes the current tile and keeps everything taken so far.
   Afterwards the tiles are copied to the gallery (untick **Copy the tiles to
   the gallery** to skip that).
7. **Share last scan** zips the folder and opens the share sheet: Drive,
   Dropbox, Nextcloud, mail, Nearby Share.

| Protocol | Speaks | Notes |
|---|---|---|
| UC2 board | UC2-REST JSON, `/motor_act` with a `qid`; a move ends when every axis answered `isDone` | X, Y, Z. Default 0.3125 µm/step |
| ShitScope PCB stage | ASCII `MOVE dx dy speed`, `BUSY`, `STOP`, as ImSwitch's `StepperXYStageManager` | XY only. Every move ends travelling in +X/+Y (overshoot µsteps, 0 = off), so use raster rather than snake. Coils are switched off (`STOP`) after each jog or scan. Defaults from `shitscope_hik.json` |
| No stage | nothing | position only, for trying the app |

A negative µm/step inverts an axis. If the thumbnails on the map do not line
up, the camera sees the stage move the other way: tick **Flip map X/Y**.
Positions are tracked from the moves sent, starting at 0 when the app starts;
moves made from the Stage tab or after a **Stop** are not tracked.

### What is saved

```
Android/data/com.openuc2.control/files/scans/20261018_141500/
    scan.json     pixel size, plan, start position, camera, exposure and gain
    tiles.json    per tile: ix, iy, commanded x/y (µm), stage position, file
    tiles/tile_0000.jpg …     JPEG quality 95, in the orientation of the live view
```

This is ImSwitch's ShitScope scan layout. Copy the folder over USB, or share
the zip. The gallery copy lands in **Pictures/UC2 Scans/<scan>** (Android 10
and newer; older phones keep only the app folder).

### Stitching (on the computer)

Stitching stays off the phone on purpose: tiles from a low-cost stage are
misplaced by up to ~0.4 mm, so they must be registered against each other
first, and that is what ImSwitch's ShitScope pipeline already does well. With
ImSwitch's environment:

```bash
python tools/stitch_scan.py ~/Downloads/20261018_141500.zip --full
```

It converts the tiles to TIFF, registers them, prints the placement errors,
and writes `scan_quality.json`, `stitched.tif` and, with `--full`,
`stitched_full.tif` next to the tiles. The overview on the phone places
tiles at their commanded positions only, like ShitScope's live preview.

### Cameras

| Camera | How | Notes |
|---|---|---|
| USB webcam | any UVC camera, through [UVCAndroid](https://github.com/shiyinghan/UVCAndroid) (libusb + libuvc, no root) | exposure in 100 µs steps, raw gain |
| Phone camera | the back camera, Camera2 | full-resolution still capture per tile, ISO as gain; phones without manual exposure lock auto exposure instead. The live view is drawn from a small stream in the sensor's orientation, the same as the saved tiles, so it does not rotate with the phone |
| Daheng USB3 | Daheng's Galaxy SDK for Android | Android 6+, ARM phones; only in builds with the SDK (below) |

All three implement `Camera.java` (open, preview, fixed exposure and gain,
one fresh frame as JPEG); a HIK camera would be one more class with
Hikrobot's Android SDK.

#### Daheng cameras

Daheng's SDK is proprietary, so it is not in this (public) repository. Copy
it into `app/libs/galaxy/`, which git ignores, and the next build has the
Daheng camera; CI builds without it simply do not offer it.

```bash
SDK=~/Downloads/Galaxy_Android-armhf_U3V_32bits-64bits_1.2.2407.9181
mkdir -p app/libs/galaxy
cp "$SDK/lib/galaxy-android-source/galaxy-android-source.jar" app/libs/galaxy/galaxy-android.jar
cp -R "$SDK/lib/galaxy-android-jniLibs/jniLibs" app/libs/galaxy/
```

SDK 1.2.2407 asks for USB permission with a mutable *implicit*
`PendingIntent`, which Android 14 refuses from apps targeting it.
`DahengCamera` catches that and finishes the SDK's own setup with an explicit
intent, by reflection on that SDK version; if a later SDK changes those
internals, the status line says so.

---

## Command protocol

`UC2Commands.java` builds the JSON the firmware expects. Every builder is
pinned to the WebSerial reference by `UC2CommandsTest`, so a protocol drift
shows up as a failing test rather than a dead board.

| Action | Task |
|---|---|
| Move stepper N steps | `/motor_act` (`position`, `speed`) |
| Continuous motion | `/motor_act` (`isforever`) |
| Stop stepper | `/motor_act` (`isstop`) |
| Motor power | `/motor_act` (`isen`, `isenauto`) |
| Laser / LED PWM | `/laser_act` (`LASERid`, `LASERval` 0–1023) |
| LED matrix | `/ledarr_act` (`action`: `fill` / `off` / `rings` / `halves` / `single`) |
| Home an axis | `/home_act` |
| TMC driver | `/tmc_act` |
| CAN address | `/can_act` |
| Board state | `/state_get`, `/motor_get`, `/modules_get`, `/tmc_get` |

Anything else can be typed straight into the Console tab.

**Stepper ids:** A = 0, X = 1, Y = 2, Z = 3.

> LED commands use the current `action` form. Older firmware that expects
> `LEDArrMode` / `led_array` is covered by `UC2Commands.ledLegacyFill`.

---

## Project layout

```
app/src/main/
├── AndroidManifest.xml
├── java/com/openuc2/control/
│   ├── MainActivity.java       UI wiring, tabs, all click handlers
│   ├── UsbSerialManager.java   connect/read/write/baud, threading, error recovery
│   ├── UsbDrivers.java         probe table, forced drivers, USB diagnostics
│   ├── UC2Commands.java        JSON command builders
│   ├── ConsoleBuffer.java      bounded, colourised console
│   ├── ScanTab.java            Scan tab: wiring, settings, scan start/stop, share
│   ├── TileScan.java           scan grid + the scan loop, ShitScope folder layout
│   ├── StageMapView.java       stage overview: plan, thumbnails, field of view, tap
│   ├── Stage.java              stage interface (+ a position-only stage)
│   ├── Uc2Stage.java           openUC2 board, UC2-REST JSON, waits for isDone
│   ├── PcbStage.java           ShitScope PCB stage, ASCII MOVE/BUSY/STOP
│   ├── SerialLink.java         line-based request/reply over the serial port
│   ├── Camera.java             camera interface + preview painter
│   ├── UvcCamera.java          USB webcams (UVCAndroid)
│   └── PhoneCamera.java        the phone's own camera (Camera2)
└── res/
    ├── layout/                 activity_main + one layout per tab
    ├── values/                 colors (brand palette), themes, strings
    ├── font/                   Space Grotesk, IBM Plex Mono
    ├── xml/usb_serial_filter.xml   USB auto-attach filter (decimal ids!)
    └── xml/file_paths.xml      what the share sheet may read (the scans)

app/src/daheng/java/            DahengCamera.java, built only with the Galaxy SDK
app/libs/galaxy/                Daheng Galaxy SDK (git-ignored, see "Daheng cameras")
app/src/test/                   protocol, grid and stage tests, no device needed
tools/stitch_scan.py            register + stitch a phone scan with ImSwitch
```

---

## Brand

Colours follow `openUC2_brandguide.pdf`:

| | Hex | Role |
|---|---|---|
| Blue | `#023773` | main brand colour — header, primary actions |
| Green | `#85B918` | secondary — "on"/go states, tab indicator |
| Turquoise | `#1F9C7C` | third — continuous motion, secondary actions |
| Light grey | `#FAF9F9` | body background |
| Grey | `#999999` | muted text, "off" states |

The brand font is **Stolzl**, which is licensed and cannot ship in a public
repo. The app bundles **Space Grotesk** — the same substitute the openUC2 web
frontend already falls back to — plus IBM Plex Mono for the console. If you
have a Stolzl licence, drop the TTFs into `res/font/` and repoint
`themes.xml`.

---

## Not included

WebRTC streaming and timelapse from the web demo. On-device stitching (see
[Stitching](#stitching-on-the-computer)). UC2 boards over WiFi (same JSON,
over HTTP): add a `SerialLink`-like transport when the phone's USB port must
stay free for the camera.
