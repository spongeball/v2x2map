**V2X2MAP** is an open-source receiver and live map for **ITS-G5 / V2X** traffic — the 5.9 GHz IEEE 802.11p messages cars and roadside infrastructure send to coordinate.

Plug a $20 ESP32-C5 dev board into your phone, drive somewhere with modern infrastructure, watch the CAMs, DENMs and SPATEMs roll in.

![License: MIT](https://img.shields.io/badge/License-MIT-blue.svg)
[![Ko-fi](https://img.shields.io/badge/Ko--fi-support-ff5e5b?logo=ko-fi&logoColor=white)](https://ko-fi.com/711it)
[![PayPal](https://img.shields.io/badge/PayPal-tip-00457C?logo=paypal&logoColor=white)](https://paypal.me/711IT)

<table>
<tr>
<td><img src="docs/screenshot_01_main.png"         alt="Live map"    width="160"/></td>
<td><img src="docs/screenshot_02_settings_top.png" alt="Settings 1"  width="160"/></td>
<td><img src="docs/screenshot_03_settings_mid.png" alt="Settings 2"  width="160"/></td>
<td><img src="docs/screenshot_04_settings_bot.png" alt="Settings 3"  width="160"/></td>
</tr>
</table>

## Acknowledgements

Big thanks to the team behind [**opentrafficmap/its-g5-receiver-firmware**](https://codeberg.org/opentrafficmap/its-g5-receiver-firmware) on Codeberg — without their foundational work this project would not exist. V2X2MAP is a fork of their firmware adapted for the Waveshare ESP32-C5-WIFI6-KIT devboard, extended with BLE streaming, the Android app, and the Windows installer.

---

## What's different in this fork

This fork ([spongeball/v2x2map](https://github.com/spongeball/v2x2map), forked from
[pit711/v2x2map](https://github.com/pit711/v2x2map)) carries a from-scratch rewrite of the
Android app, closing the feature gap against the published app while keeping the same
firmware/bridge/web-flasher. Full day-by-day detail lives under [`update/`](update/)
(`FEATURES.md`, `STRATEGY.md`, `OUT_OF_SCOPE.md`); the short version:

- **New bottom-nav tabs** — Map, Radar (nearby stations by bearing/distance), Statistics
  (packet/rate/type breakdown + rate history + frame log), Range ("Max Range Mode" + CSV
  export), and a dedicated Settings tab — replacing the single Map screen.
- **SPATEM green-wave assistant** — nearest in-range traffic light's phase, countdown, and a
  live suggested speed to arrive as it turns, with a per-intersection detail sheet.
- **High Rate Mode** — batched map updates and a sampled (1-in-10) frame log for dense
  deployments (≥1000 pkt/s), so the UI stays responsive instead of falling behind.
- **Frame Recording bundle** — PCAP recording now also writes a session CSV, an app debug
  log, and a periodic KML map snapshot; adds auto-recording-on-connect and an on-demand
  "Export KML" action.
- **Theme color** — Default / match-wallpaper (Material You) / custom seed color, via
  Material's `DynamicColors` API, applied consistently across every screen.
- **Settings reorganized** — a proper list-item Settings tab; About, License (this
  project's own MIT text), and Open source libraries are now separate screens instead of
  one bundled page.
- **Marker size setting**, units (metric/imperial/auto), own-GPS-track overlay, and a
  number of theming/contrast bugfixes (frame detail sheet, map FAB icons, radar range tabs)
  that surfaced once the theme color setting made more than one color scheme possible.
- [`update/CODEBERG_UPSTREAM.md`](update/CODEBERG_UPSTREAM.md) tracks a separate, not-yet-
  started effort: contributing the BLE streaming / USB companion-app protocol back to the
  original Codeberg firmware project.

---

## What it is

Modern cars and roadside units (RSUs) broadcast standardised safety messages on the dedicated 5.9 GHz V2X band:

- **CAM** — Cooperative Awareness: "I'm here, going X km/h"
- **DENM** — Decentralised Environmental Notification: "hazard ahead!"
- **SPATEM** — Signal Phase + Timing: traffic-light countdown
- **MAPEM** — intersection geometry

V2X2MAP captures these in promiscuous mode, decodes the GeoNetworking headers locally, and plots each message as a colour-coded marker on an OSM map. No cloud round-trip required — everything runs on the phone.

---

## Hardware

One **Waveshare ESP32-C5-WIFI6-KIT** dev board and any Android phone with USB-OTG or Bluetooth LE.

The board supports 5.9 GHz IEEE 802.11p out of the box; the firmware drives it as a sniffer and forwards captured frames to your phone.

<img src="docs/hardware.jpg" alt="Waveshare ESP32-C5-WIFI6-KIT dev board" width="340"/>

- **Amazon with external Antenna:** [Waveshare ESP32-C5-WROOM-1 dev board](https://amzn.to/4uDpwNa) *
- **Amazon without external Antenna:** [Waveshare ESP32-C5-WROOM-1 dev board](https://amzn.to/43qIJ9h) *
- **AliExpress:** [Waveshare Official Store](https://s.click.aliexpress.com/e/_c3lTQYn5) *



---

## Features

| Feature | Description |
|---|---|
| **Live map** | 5 switchable tile layers: Standard, Dark, Satellite, ÖPNV, Humanitarian |
| **Grouped frame log** | One row per station (MAC); expandable to last 20 frames; shows type icon, speed, distance, 🔒/🔓 secured; also on the Statistics tab |
| **CAM markers** | One marker per vehicle, updated in-place with baked-in heading + speed label; adjustable marker size |
| **SPATEM green-wave assistant** | Nearest in-range traffic light's phase + countdown + a live suggested speed to catch it green |
| **Radar / Statistics / Range tabs** | Nearby stations by bearing+distance; packet/rate/type breakdown + history; "Max Range Mode" with CSV export |
| **High Rate Mode** | Batched map updates + sampled (1-in-10) log at ≥1000 pkt/s so the UI keeps up |
| **Compass mode** | Bearing-up FAB rotates the map to keep your heading at the top |
| **Own GPS track** | Optional blue polyline traces your route |
| **Auto-follow** | Map pans with you; zoom stays exactly as you set it |
| **Geiger-counter mode** | Audio + haptic tick on every frame, distinct beep + buzz on DENM hazard |
| **BLE + USB auto-reconnect** | Exponential-backoff reconnect on cable pull or BT drop — no user interaction |
| **Offline maps** | OSMdroid tile cache up to 600 MB |
| **Recording bundle** | One tap records PCAP (Wireshark-ready) + session CSV + app debug log + periodic KML map snapshot; auto-record on connect; on-demand KML export |
| **Theme color** | Default Material 3, match-wallpaper (Material You), or a custom seed color |
| **Multi-broker MQTT** | One input field per broker, add/remove with + / 🗑; per-type message filter |
| **Full i18n** | English default, German for German-locale devices — all UI, errors and notifications |

---

## Architecture

```
+---------------+     5.9 GHz 802.11p      +------------+
|  Vehicles &   |   CAM / DENM / SPATEM    |  ESP32-C5  |
|  RSUs         |  ----------------------> |  sniffer   |
+---------------+                          +-----+------+
                                                 |
                                  USB-Serial-JTAG | BLE-GATT
                                                 v
                                        +--------+--------+
                                        | Android app /   |
                                        | Python bridge   |
                                        +--------+--------+
                                                 |
                                                 | optional
                                                 v
                                          MQTT (cits1.opentrafficmap.org
                                                 or your own)
```

---

## Install

### Windows — one-click installer (easiest)

1. Download **ITS-G5 Receiver Setup** from the [Releases page](../../releases/latest)
2. Connect the ESP32-C5 via USB
3. Run the EXE and follow three steps:

<table>
<tr>
<td align="center"><strong>Step 1 — Select COM port</strong></td>
<td align="center"><strong>Step 2 — Flash firmware</strong></td>
<td align="center"><strong>Step 3 — Set Node-ID</strong></td>
</tr>
<tr>
<td><img src="docs/installer-1-port.png"   alt="Installer step 1: select COM port"   width="260"/></td>
<td><img src="docs/installer-2-flash.jpg"  alt="Installer step 2: flash firmware"    width="260"/></td>
<td><img src="docs/installer-3-nodeid.png" alt="Installer step 3: set Node-ID"       width="260"/></td>
</tr>
<tr>
<td>The installer detects the board automatically. Pick the right port and click <em>Weiter</em>.</td>
<td>The installer writes bootloader, partition table and application to the C5. Takes 30–60 seconds.</td>
<td>The installer reads the MAC from the chip and pre-fills the Node-ID. Hit <em>Fertig – Bridge starten</em>.</td>
</tr>
</table>

---

### Manual build from source

<details>
<summary>Firmware (ESP-IDF)</summary>

```powershell
# once per shell — activate ESP-IDF toolchain
. .\esp-idf\export.ps1

cd V2X2MAP\firmware
idf.py build
idf.py -p COMx -b 921600 flash
```

</details>

<details>
<summary>Android app</summary>

```powershell
cd V2X2MAP\android
.\gradlew.bat assembleDebug
adb install -r app\build\outputs\apk\debug\app-debug.apk
```

Or open `V2X2MAP/android/` in Android Studio. Min SDK 24 (Android 7.0).

</details>

<details>
<summary>Python bridge + dashboard</summary>

```powershell
cd V2X2MAP\bridge
python its_g5_bridge.py --port COMx --node-id <mac-without-colons>
```

Dashboard at `http://127.0.0.1:8080`. Default MQTT broker: `mqtts://cits1.opentrafficmap.org:8883`.

</details>

---

## Support

V2X2MAP is open-source and free. If it's useful to you, a small tip keeps it going:

- ☕ **Ko-fi:** https://ko-fi.com/711it
- 💸 **PayPal:** https://paypal.me/711IT

---

## Legal

Receiving and forwarding ITS-G5 radio data may be subject to national telecommunications law and data-protection law. The Android app shows a disclaimer on first launch. Use at your own risk.

Code is published under the **MIT License** — see [`LICENSE`](LICENSE).

\* affiliate link (no extra cost for you)
