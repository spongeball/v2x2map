# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## What this is

V2X2MAP is an ESP32-C5-based ITS-G5/V2X (802.11p, 5.9 GHz) sniffer with an Android live-map app and a
Python desktop bridge. It is a fork of `opentrafficmap/its-g5-receiver-firmware` (Codeberg), adapted for
the Waveshare ESP32-C5-WIFI6-KIT, extended with BLE streaming, the Android app, and a Windows installer.

The repo has four independent components that only agree on one thing: the wire protocol.

```
ESP32-C5 firmware (C, ESP-IDF)
     | USB-Serial-JTAG or BLE-GATT, frames prefixed with magic "ITS5"
     v
Android app (Kotlin)  <-- or -->  Python bridge (bridge/its_g5_bridge.py)
     |                                   |
     +----------------- optional MQTT ---+----> cits1.opentrafficmap.org or self-hosted
```

An **iOS app exists but is developed in a private repo** and is deliberately not part of this
repository (see `.gitignore`: `ios/`). Don't expect to find it here.

## Wire protocol (keep firmware, Android, and bridge in sync)

Framed little-endian, no padding — defined independently in `firmware/main/usb_stream.c`/`bt_stream.c`,
`bridge/its_g5_bridge.py`, and `android/.../FrameReader.kt`:

```
magic[4]  = "ITS5"
sec       u32 LE   (epoch seconds, from rx_ctrl)
usec      u32 LE
len       u16 LE
payload   <len> bytes  (raw 802.11 MAC frame)
```

Changing this format means updating all three implementations — there is no shared schema/codegen.

## Firmware (`firmware/`, ESP-IDF, C)

```powershell
. .\esp-idf\export.ps1          # once per shell
cd firmware
idf.py build
idf.py -p COMx -b 921600 flash
idf.py -p COMx monitor          # serial console / REPL
```

- Target chip: ESP32-C5. Two hardware variants carry their own sdkconfig fragments:
  `sdkconfig.proto-enc28j60` and `sdkconfig.proto-w5500` (Ethernet PHY choice via SPI).
- `main.c` boot sequence matters: it opens a timing window after boot (`BOOT_DONE` sent over
  USB-Serial-JTAG) during which the Python/Windows setup wizard can run a 2.4 GHz WiFi `SCAN` —
  once `sniffer_autostart()` calls `phy_11p_set(1,0)` to switch the radio into 802.11p sniffing,
  2.4 GHz scanning stops working until a full chip reset. Don't reorder init without preserving this.
- The device exposes an interactive console (`esp_console`/linenoise) over USB-Serial-JTAG with
  commands registered per module: `sniffer` (cmd_sniffer.c), `pcap` (cmd_pcap.c), `config_set` /
  `config_get` / `config_clear` (config.c), `ota` (ota.c), plus `reboot`. Config keys are the
  `CONFIG_INDEX_*` enum in `config.h` (node ID, MQTT URI, Ethernet/WiFi settings, sniff mode/timing,
  LED brightness, etc.) — add new persisted settings there.
  - `CONFIG_INDEX_SNIFF_MODE` controls whether the radio stays in realtime 802.11p sniffing, cycles
    between 802.11p and WiFi (STA/AP) on a timer (`SNIFF_MS`/`WIFI_MS`), or switches individually —
    this is the mechanism that lets the device both sniff V2X and join a home WiFi for MQTT/OTA.
  - `CONFIG_INDEX_CONN_MODE` picks BLE, WiFi, or both as the transport to the phone/bridge.
- `usb_stream` / `bt_stream` are the two independent transports that frame and send captured packets
  using the protocol above; `mqtt.c` is the device's own optional direct-to-broker path (separate from
  the Android/bridge MQTT forwarding).
- External deps are pulled via the ESP-IDF component registry (`firmware/main/idf_component.yml`):
  `espressif/ethernet_init`, `espressif/mqtt`, `espressif/led_indicator`, `esp-idf-lib/lm75`, `pcap`.
- No test suite — validate by flashing and watching the console/log output (`idf.py monitor`) or the
  Android/bridge log.

## Android app (`android/`, Kotlin)

```powershell
cd android
.\gradlew.bat assembleDebug
adb install -r app\build\outputs\apk\debug\app-debug.apk
```

- Single-module app, package `org.opentrafficmap.receiver`. Min SDK 24, target/compile SDK 34,
  Kotlin, AGP — JVM target 17. No automated tests exist; verify by running on a device (or emulator
  for UI-only changes) with the ESP32-C5 attached via USB-OTG, or against the Python bridge's output.
- Everything lives flat under `app/src/main/java/org/opentrafficmap/receiver/` — no feature packages.
  Key modules and how they relate:
  - `UsbSerialController.kt` / `BluetoothController.kt` — transport layer (USB CDC-ACM via
    `usb-serial-for-android`, or BLE-GATT), both feeding the same downstream parser.
  - `FrameReader.kt` — resyncs on the `ITS5` magic and reassembles frames; must stay in lockstep with
    the wire protocol above and with `bridge/its_g5_bridge.py`'s framing logic.
  - `ItsG5Decoder.kt` — finds the LLC/SNAP EtherType (0x8947 = ITS-G5) and extracts the GeoNetworking
    source position. `DenmParser.kt` / `SpatTemParser.kt` decode the higher-level message types.
  - `MarkerLayer.kt` — places/updates OSMDroid map markers per station (keyed by MAC), one marker per
    vehicle updated in place rather than re-added.
  - `GeigerCounter.kt` — audio/haptic feedback per received frame, independent of the map.
  - `MqttBridge.kt` — Paho client, TLS, forwards raw frames to `its/<NodeID>/packet`, with
    last-will/will-retain status; default broker is `cits1.opentrafficmap.org:8883`.
  - `FrameRecorder.kt` — writes `.pcap` (linktype 105 = IEEE 802.11) for Wireshark.
  - `ReceiverForegroundService.kt` keeps the USB/BLE connection and MQTT alive; the map (OSMDroid)
    itself is deliberately *not* kept alive in the background — see `android/README.md` "Known
    limitations" for why.
  - `Prefs.kt` / `SettingsBus.kt` — persisted settings and in-process settings change notifications;
    `SettingsActivity.kt` / `CycleSettingsDialog.kt` are the UI for them.
  - Full i18n (English default, German) — add strings to both `res/values` and `res/values-de`.
- USB VID/PID matching for auto-launch lives in `app/src/main/res/xml/usb_device_filter.xml`
  (Espressif USB-Serial-JTAG 0x303A, plus CP210x/CH340/CH9102/FTDI fallbacks).
- Release signing config is intentionally external to the repo: `app/build.gradle.kts` reads a
  keystore properties file from `V2X2MAP_KEYSTORE_PROPERTIES` or
  `../../.deploy-keys/v2x2map-keystore.properties` (outside this repo), falling back to the debug key
  when absent. Release builds are unminified/unshrunk by design, matching debug bytecode.

## Python bridge (`bridge/`)

```powershell
cd bridge
python its_g5_bridge.py --port COMx --node-id <mac-without-colons>
python its_g5_bridge.py --node-id <id> --broker mqtts://broker1:8883 --broker mqtt://broker2:1883
```

Dashboard served at `http://127.0.0.1:8080` by `dashboard_server.py` / `dashboard.html`. Run
`python its_g5_bridge.py --help` for the full flag list (port autodetect/listing, multiple `--broker`
targets, `--no-mqtt`, `--pcap-out`, `--reset-on-start`, etc.) rather than re-deriving it from source.

- `setup_wizard.py` / `setup_main.py` implement the Windows installer flow (COM port detection →
  firmware flash via `esptool` → Node-ID read-back and config write over the USB config channel) that
  ships as `its-g5-setup.exe`.
- `build_exe.ps1` builds both `its-g5-bridge.exe` (bridge only) and `its-g5-setup.exe` (wizard +
  flash) via PyInstaller, using the `.spec` files in this directory; it first refreshes the bundled
  firmware `.bin`s from `../firmware/build/` if present. `build_deb.py` builds a Debian package.
- No test suite — validate interactively against real or recorded hardware frames.

## Web flasher (`docs/flash/`)

Browser-based flashing via `esptool-js`/`esptool.js`, served as a static page (works from
`https://<pages-url>/flash/` once deployed, since it needs HTTPS + WebSerial). `manifest.json` lists
the firmware parts and flash offsets — **keep it in sync with `firmware/partitions_*.csv` and with the
`.bin`s checked into `docs/flash/firmware/`** whenever the firmware is rebuilt; nothing automates that
sync. Chrome/Edge only (WebSerial); noted in the page as not working on iOS.

## Working across components

Most real changes here touch more than one of firmware / Android / bridge / web-flasher at once:
- A new message type or frame field → firmware (what's captured/forwarded) + Android `ItsG5Decoder.kt`
  or a new `*Parser.kt` + (if the bridge/dashboard needs to show it) `dashboard_server.py`.
- A new persisted device setting → `config.h`/`config.c` (`CONFIG_INDEX_*`) + the setup wizard
  (`bridge/setup_wizard.py`) and/or Android `SettingsActivity.kt`/`Prefs.kt` if user-facing.
- A firmware release → rebuild, then refresh `bridge/firmware/*.bin` (via `build_exe.ps1`) and
  `docs/flash/firmware/*.bin` + `docs/flash/manifest.json` version, and bump the Android
  `versionCode`/`versionName` in `android/app/build.gradle.kts` if the app changed too.
