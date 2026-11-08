# V2X2MAP Android — feature gap vs. the `update/` screenshots

Source: 12 screenshots in `update/` showing the app's **About** screen reporting
**Version 1.4.1 (build 15)**. The code currently in this repo is
`android/app/build.gradle.kts` → `versionCode 9` / `versionName "0.2.3"` and has
**none** of the tab/Radar/Statistics/Range UI or several of the settings below —
so this is a large, multi-release gap, not a few small tweaks.

This file is an implementation checklist: for each discovered feature it says
what the screenshot shows, whether the current source already has the
underlying logic (just not surfaced this way), and what's net-new. Grounded by
reading `Prefs.kt`, `SettingsActivity.kt`, `MainActivity.kt`, `MarkerLayer.kt`,
`ItsG5Decoder.kt`, `FrameRecorder.kt`, `UsbSerialController.kt`,
`BluetoothController.kt`, and the `activity_main.xml` / `item_station.xml`
layouts as of commit `6872d24`.

Open questions are marked **❓**.

## Where build 15 came from — resolved by external research (2026-10-04)

This repo (`spongeball/v2x2map`) is a fork of
[pit711/v2x2map](https://github.com/pit711/v2x2map), which is itself the
public source for the
[V2X2MAP Play Store app](https://play.google.com/store/apps/details?id=com.v2x2map)
(`com.v2x2map`) built by the same developer (Peter Holzhauser / "Pit711").
Checked directly:

- **Our fork's HEAD (`6872d24`) is byte-identical to upstream `pit711/v2x2map`'s
  HEAD** (same SHA, same timestamp) — this repo is fully caught up with the
  public GitHub source. The gap to v1.4.1 is not something we're behind on
  pulling; upstream itself doesn't have it either.
- **17 public forks** of `pit711/v2x2map` were checked via the GitHub API —
  none has commits past upstream's HEAD. No public newer source tree exists.
- **The Play Store listing confirms this is the same app/developer** and is
  several releases ahead of what's open-sourced: current listing is
  **v1.4.1, updated Jul 17, 2026**, i.e. newer than our HEAD's Sep 23, 2026
  commit date is *older* than... — note the apparent contradiction: our last
  commit is dated 2026-09-23 but is only firmware/web-flasher work
  (`versionName "0.2.3"` in `build.gradle.kts` hasn't moved since May 2026).
  The Android app's version has clearly been developed **and released to Play
  Store independently of the public GitHub repo** since ~May 2026 — i.e. this
  confirms the user's hypothesis: **the 1.x Android source lives in a private
  branch/repo that simply isn't pushed to GitHub**, same pattern already
  acknowledged in this repo for the iOS app (`.gitignore`: `ios/`, commit
  `77f720b` "chore: keep the iOS app source out of the public repo").
- **No newer source tree is recoverable from anywhere public.** There is
  nothing to pull from; this feature list has to be (re)implemented from the
  shipped app's observable behavior (screenshots + store listing), same as
  originally planned.
- The official **v1.4.1 "What's new" changelog** (from the Play Store listing
  itself) corroborates and extends the screenshot analysis — see
  **§0 External confirmation** below before reading the per-screen sections,
  several of which have been corrected based on it.

**❓ Package name — resolved.** The Play Store app's real `applicationId` is
confirmed to be **`com.v2x2map`**, not `org.opentrafficmap.receiver` (which is
still this repo's `android/app/build.gradle.kts` namespace). So the About
screen's `Android/data/com.v2x2map/files/` text was correct, not stale — the
private branch renamed the package at some point after this repo's current
state. **This matters beyond copy:** an `applicationId` change affects
signing continuity, existing installs' upgrade path, and the Play Store
listing identity. If the goal is to eventually publish from this repo's
lineage again, decide early whether to rename `org.opentrafficmap.receiver` →
`com.v2x2map` (matching the shipped app, but a breaking change for anyone who
installed the current repo's build) or keep the current namespace (diverging
permanently from the shipped app's identity). Don't silently pick one while
implementing other sections.

## §0 — External confirmation (Play Store + v2x2map.com, 2026-10-04)

Three independent public sources, fetched directly, corroborate and extend
the screenshot analysis below:

1. **Play Store full app description** (`com.v2x2map` listing, current as of
   fetch):
   > Live OSM map with vehicle markers, heading arrows, and speed labels •
   > **Radar view with concentric range rings and bearing vectors** • Live
   > packet log grouped by station, with message type, **RSSI**, distance,
   > and timestamp • **Max Range Mode**: track and export the furthest
   > received ITS-G5 packet (CSV) • DENM hazard alerts with cause code and
   > distance • **SPATEM traffic-light phase indicator (green wave speed
   > suggestion)** • **MAPEM intersection geometry overlay** • MQTT
   > forwarding • PCAP recording (Wireshark compatible) • Offline map tile
   > pre-download • Works fully offline

2. **Play Store "New in 1.4.1" changelog**:
   > • Completely refreshed, modern user interface
   > • Traffic-light lanes and phases live on the map
   > • Pre-download maps for offline use (**whole states or countries**)
   > • App available in **10 languages**
   > • New **connection sheet** for USB-C and Bluetooth
   > • Built-in **firmware updater** for the receiver
   > • Many stability improvements and bug fixes

3. **[v2x2map.com](https://v2x2map.com)** (official marketing site) feature
   list additionally names: **"Recording & Replay: Frame capture to
   `.itsg5` files for analysis."** This is a feature the screenshots didn't
   show at all — a custom capture format with **replay**, not just PCAP
   record. Notably, `.itsg5` is *already* reserved in this repo's
   `.gitignore` (`# Captured frame logs` / `*.itsg5`) even though
   `FrameRecorder.kt` only writes `.pcap` today — the gitignore entry was
   clearly added in anticipation of this format, confirming it's a real,
   deliberate (if unimplemented-here) feature, not a guess.

These resolve or sharpen several items marked ❓ in the per-screen sections
below; each affected bullet now says "**resolved via §0**" inline.

---

## 1. Top-level navigation — net-new

Screenshots show a 4-tab bottom nav: **Map / Radar / Statistics / Range**.
Current app is a single `MainActivity` + `activity_main.xml` with no tab bar at
all; Map is the only screen. This is the biggest structural change and
everything below (§3–5) depends on it existing first.

- Tabs: `Map` (existing screen), `Radar` (new), `Statistics` (new), `Range` (new).
- Each tab keeps the same top status pills + settings FAB (§2) visible.
- **❓** Fragments-in-one-Activity vs. separate Activities — current codebase
  has no Fragment usage anywhere; introducing `BottomNavigationView` +
  Fragments is a bigger refactor than adding Activities. Pick based on how
  much state (USB/BT connections, MQTT bridges, frame stream) needs to be
  shared live across tabs — Radar and Statistics both need the live frame
  feed, so whatever currently owns that in `MainActivity` needs to become
  shared (ViewModel, Application-scoped singleton, or the existing
  `ReceiverForegroundService`/`SettingsBus` pattern already used for
  cross-screen plumbing).

## 2. Map screen — mostly redesign of existing elements

| Screenshot element | Current source | Gap |
|---|---|---|
| "● USB" / "● MQTT" pill chips, colored dot (green=connected / red=disconnected/retrying), truncated retry text e.g. "USB: retry 129 in 8s…" | `btnConnect`/`btnConnectBt` in `activity_main.xml` are full-width text buttons with color-tinted background (`onUsbState`/`onBtState` in `MainActivity.kt`); retry-countdown strings already exist (`R.string.usb_reconnecting`, `R.string.bt_reconnect`, backoff logic in `UsbSerialController.kt`/`BluetoothController.kt`) | **Redesign only** — swap buttons for compact pill chips, reuse existing state strings. Officially named **"new connection sheet for USB-C and Bluetooth"** in the 1.4.1 changelog (resolved via §0) — treat USB+BT status as one combined sheet/surface, not two independent pills, when designing. **Net-new:** there is no MQTT status pill at all today (`startMqtt`/`stopMqtt` in `MainActivity.kt` has no connection-state callback wired to UI) — need per-broker or aggregate MQTT connect state surfaced. |
| Gear / compass / layers / locate FAB stack, top-right | `fabCompass`, `fabLayers`, `fabLocate` already exist in `activity_main.xml`; Settings is currently a toolbar overflow menu item (`onCreateOptionsMenu`/`onMenuItemClick`) | **Net-new:** promote Settings to a 4th FAB (gear icon) to match the screenshot; drop/keep the toolbar as fallback — ❓ confirm toolbar is being removed entirely or kept for something else. |
| Heading/speed overlay bottom-left ("014° N 62 km/h", or "—° 0 km/h" when stationary) | `speedOverlay` already exists in `activity_main.xml` | Already implemented — verify the "—°" no-heading-when-stationary state matches. |
| Own-position marker: car/arrow icon while moving vs. **yellow walking-person icon** while stationary (screenshot 2, speed=0) | `MarkerLayer.kt` has `ic_marker_arrow.xml`; no pedestrian/walking drawable found anywhere under `res/drawable*/` | **Net-new.** Need a walking-person drawable + logic to switch icon based on speed ≈ 0 (or a dedicated "pedestrian mode"). **❓** confirm this is speed-triggered and not a separate user-toggled mode. |
| Red own-GPS-track polyline across the whole visible area | `KEY_OWN_TRACK` / `Prefs.ownTrackEnabled()` + "Plot own GPS track on map" setting already exist | Already implemented. |
| "Live log" bottom sheet: draggable handle, header "Live log" + "`N pkts · R/min`" + chevron, rows with icon/type/MAC/count/extra-info/time/distance/lock/chevron | `logHeader`, `logStats`, `logCollapseBtn`, `log`, `emptyLog` already exist in `activity_main.xml`; `item_station.xml` layout comment literally says `icon \| ID+summary \| last-seen \| distance \| lock \| chevron` — matches the screenshot columns already | Mostly already implemented. **Verify:** (a) whether it's a true bottom-sheet (drag handle + partially-collapsed default state) vs. the current fixed split (`logSplit`) — screenshot 1 shows a drag handle (small grey bar) which may not exist yet; (b) grouping key — screenshots show 3 separate rows for the *same* MAC (`#48108d78`) split by message type (IVIM/CAM/DENM), confirm current adapter groups by (MAC, type) and not MAC alone; (c) **RSSI** — the Play Store description explicitly lists RSSI as a log column ("message type, RSSI, distance, and timestamp", resolved via §0), and screenshot 2's unknown-frame row shows `-34dBm`, so RSSI needs to be a column for *all* rows, not just unknown ones — check whether `Frame.kt`/`item_station.xml` already carries RSSI end-to-end or only for the fallback "?" case. |
| SPATEM traffic light | Current `spat_light` pref + 3 static red/yellow/green dots (`activity_main.xml` `spatLight`/`lightRed`/`lightYellow`/`lightGreen`) simulate one generic signal | **Net-new, bigger than previously scoped.** Both the Play Store description ("SPATEM traffic-light phase indicator, **green wave speed suggestion**") and v2x2map.com ("Signal Heads: traffic light phases displayed **per direction** with countdown timers") and the 1.4.1 changelog ("**traffic-light lanes and phases live on the map**") agree this is now a full per-lane/per-direction signal head overlay drawn on the map (likely anchored to MAPEM-decoded lane geometry) with a computed speed-to-catch-the-green suggestion — not the current single 3-dot widget. This needs MAPEM lane geometry parsing (see next row) to anchor signal heads correctly. |
| MAPEM intersection geometry | `ItsG5Decoder.kt` decodes MAPEM as a message type/marker but — ❓ confirm — likely doesn't parse lane-level geometry yet | **Net-new.** Play Store: "MAPEM intersection geometry overlay." Needed as the geometric basis for the per-direction signal heads above; scope this one first since SPATEM's new rendering depends on it. |

## 3. Radar tab — net-new

Dark full-screen radar/PPI view: own position at center, concentric range rings
(25 m / 50 m / 100 m shown, scales with selected range), "N" heading label,
segmented range selector **Auto / 250 m / 500 m / 1 km**, "no contacts" empty
state. Live blips for stations in range, positioned by bearing+distance from
own GPS position — no screenshot shows populated contacts, but the Play Store
description confirms **"bearing vectors"** (resolved via §0): contacts are
drawn with a direction indicator (their heading), not just a dot. Blip color
scheme (by message type? by station?) is still **❓ unconfirmed** — infer from
the Map tab's existing type-color scheme (`ItsG5Decoder.MsgType.color`) as the
likely source of truth.

Nothing in current source resembles this (`grep` for Radar/radar across
`android/app/src/main/` is empty).

## 4. Statistics tab — net-new

- 3 stat cards: **Packets** (total), **Rate** (current, presumably pkts/min),
  **Active** (active station count — needs a definition, e.g. stations seen in
  the last N seconds; ❓ confirm threshold).
- "By type" horizontal bar list with counts, currently showing **CAM / DENM /
  SPATEM / MAPEM** (only 4 of the 9 types `ItsG5Decoder.MsgType` already
  supports — IVIM/SREM/SSEM/TLM/RTCMEM exist in the decoder today but weren't
  visible in this card; ❓ confirm whether Statistics lists all 9 types and the
  screenshot session just had zero packets of the other 5, or whether it's
  deliberately capped to the 4 "core" types).
- "Rate" section: time-range tabs **60 s / 1 h / 24 h / 7 d** over a
  sparkline/line chart. The 1 h/24 h/7 d buckets imply packet counts need to be
  **persisted** across the TTL window used elsewhere (`markerTtlMinutes`
  defaults to 60 min and purges markers/paths) — rate history needs its own
  retention separate from map object TTL. ❓ in-memory only (reset on app
  restart) or persisted to disk?

Nothing in current source resembles this.

## 5. Range tab — net-new

- "Furthest packet" distance-from-self measurement: big number (`0.399 km`),
  type + timestamp of the record-holder, "Start measuring" / (implied) "Stop"
  toggle, "No measurement running" status line.
- "Top 5" leaderboard of furthest packets (distance, type chip, timestamp).
- "Filter" section (collapsed in the screenshot — contents unseen, likely a
  message-type filter like the MQTT one in §6).
- "Export as CSV" of the range data.

Needs own-position (GPS) + per-frame `latLon` (already decoded in `Frame.kt`)
to compute distance — the math is straightforward (haversine against current
fix), the new work is the measurement-session state machine, leaderboard, and
CSV export. Officially named **"Max Range Mode"** in the Play Store
description (resolved via §0) — use that name in code/strings, not an
ad-hoc one. Nothing in current source resembles this screen; **however** CSV
export as a concept already exists adjacent to it — see "session log (CSV)" in
the Frame Recording bundle (§6) — ❓ confirm whether Range's CSV is the same
file/format or a distinct one scoped to just the top-N/measurement data.

## 6. Settings screen — several net-new sections, rest already implemented

Checked against `Prefs.kt` / `SettingsActivity.kt` / `activity_settings.xml`.

**Already implemented (just confirm it matches the screenshot's grouping/copy):**
- Keep screen on (`swKeepScreenOn`)
- Force dark mode (`swDarkMode`)
- Centre map automatically while moving (`swFollow`)
- Plot own GPS track on map (`swOwnTrack`)
- Objects and paths visible (minutes) (`edMarkerTtl`, `Prefs.markerTtlMinutes`, default 60, range 1–999999 — matches screenshot text exactly)
- Pre-download maps… (`btnDownloadMap`) — **scope needs to grow.** The current
  implementation (`SettingsBus.downloadVisibleMap()`) downloads only the
  current viewport; the 1.4.1 changelog says pre-download now covers
  **"whole states or countries"** (resolved via §0) — this needs a
  region-picker (not just "download what I'm looking at"), which is a real
  feature addition, not just a copy/label change.
- Geiger-counter mode (`swAudio` / `Prefs.audioFeedback`)
- Cycle… (BLE coexistence settings, `btnCycle` → `CycleSettingsDialog`)
- MQTT: enable toggle, multi-broker list with add/delete (`loadBrokerRows`/`addBrokerRow`/`saveBrokerList`), Node ID field, **"Configure message type filter…"** dialog — this dialog's 10 checkboxes (`? Unknown`, CAM, DENM, MAPEM, SPATEM, IVIM, SREM, SSEM, TLM, RTCMEM) match `showMqttFilterDialog()` / `ItsG5Decoder.MsgType` **exactly**, already fully implemented.
- Licenses / About this app (`btnAbout` → `AboutActivity`)
- Reset (`btnReset`) — screenshot's "Reset map & log" with red/destructive styling; current `R.string.reset` copy should be checked against the exact screenshot wording.

**Net-new:**
- **Units: Auto / Metric (km, km/h) / Imperial (mi, mph)** radio group, with
  "Auto follows your device region" helper text. No unit handling exists in
  `Prefs.kt` at all today — current app presumably hardcodes metric
  everywhere (speed overlay, distance column, Range tab). This needs a new
  pref + a units-formatting helper used consistently across Map overlay,
  log/station rows, Statistics, and Range.
- **Marker size** slider (labeled "Normal" — so likely a 3–5 step
  small/normal/large scale, not a continuous value) — "How large vehicle and
  sign icons are drawn on the map." No `marker_size` pref or sizing logic in
  `MarkerLayer.kt` today (icons are presumably fixed-size).
- **High Rate Mode (≥1000 pkt/s)** toggle — "Optimised for dense deployments:
  batched map updates, sampled log, 60-bucket rate counter. Markers update
  1×/s, log shows 1 in 10 frames." This is a performance-mode affecting
  `MarkerLayer`, `FrameLogAdapter`, and whatever feeds the Statistics rate
  chart (the "60-bucket rate counter" phrase strongly suggests Statistics'
  rate history and this mode share an implementation). Biggest
  engineering-effort item on this list after the three new tabs.
- **Receiver → Enable LED** toggle + **Flash firmware…** link. Firmware
  already has `CONFIG_INDEX_LED_BRIGHTNESS` (`firmware/main/config.h`) so the
  device-side config slot exists — the gap is purely in the Android
  settings UI (and whatever console/BLE command currently used to set it, if
  any — ❓ check whether `config.c`'s `config_set` console command is already
  reachable from the app, e.g. via the same channel `CycleSettingsDialog`
  uses, or whether a new one needs adding). **"Flash firmware…" scope —
  resolved via §0:** the 1.4.1 changelog calls this a **"built-in firmware
  updater for the receiver"** and the Play Store description says "A
  built-in setup wizard flashes the firmware onto the board directly from
  your phone over USB — no PC and no command line required." So it's a full
  **in-app USB flash flow**, not a link out to `docs/flash/` — effectively
  porting the esptool-js web flasher's logic (or `bridge/setup_wizard.py`'s
  esptool-based flow) to Android/USB-serial. This is a substantial standalone
  feature, closer in effort to the Radar/Statistics/Range tabs than to a
  typical settings toggle — scope it as its own workstream.
- **Frame Recording redesign — bigger than the screenshots alone suggested.**
  Current `FrameRecorder.kt` + `SettingsBus` recording toggle is
  **PCAP-only**, manually started/stopped (`btnRecord`/`renderRecordingState`).
  Screenshots show a richer bundle, and v2x2map.com adds a feature the
  screenshots never showed at all:
  - **Auto recording** toggle (start automatically, vs. today's manual-only).
  - Unified recording producing **4 files at once**: raw frames (PCAP,
    already implemented), **session log (CSV)** (new), **map snapshot (KML,
    refreshed every 2 min)** (new), **app debug log** (new).
  - Separate **Export KML** action (presumably exports current map state
    on-demand, independent of an active recording).
  - "No recording active" status line replacing/extending
    `rec_status_idle`/`rec_status_active`.
  - Storage path `Android/data/com.v2x2map/files/` — package name confirmed
    correct, see the resolved **❓ Package name** note near the top of this
    doc.
  - **Net-new, not in any screenshot:** v2x2map.com's feature list says
    **"Recording & Replay: Frame capture to `.itsg5` files for analysis"**
    (§0). This implies a *separate*, app-specific capture format (distinct
    from PCAP) that can be **replayed back through the app** — i.e. feed a
    saved `.itsg5` file through the same `FrameReader`/`ItsG5Decoder`
    pipeline as a live USB/BT stream, for offline analysis or demoing without
    hardware. `.gitignore` already has `*.itsg5` reserved
    (`# Captured frame logs`), confirming this format name is real and
    predates this analysis. **This needs its own UI entry point** (a
    "Replay…" action, file picker, and a playback control surface — likely
    on the Map screen or Settings) that isn't visible in any of the 12
    screenshots — treat this as a known gap in the screenshot coverage, not
    as "not needed."

## 7. About screen — net-new content, mostly documentation not code

The About screen already presumably exists (`AboutActivity.kt`,
`activity_about.xml`) but the screenshots show specific content worth
preserving verbatim when rewriting it, since it's accurate hardware
documentation already validated by the firmware:

- Version string format: `V2X2MAP` / `Version 1.4.1 (build 15)`.
- RGB LED (WS2812) color/pulse legend — boot sequence, idle colors by
  sniffer/BLE state, per-event pulses, troubleshooting for "solid white /
  not responding". This should be kept in sync with `firmware/main/led.c`
  if/when that file's color logic changes — ❓ worth a code comment
  cross-reference in `led.c` pointing at this About text, or vice versa, so
  they don't drift.
- "No frames received although connected?" troubleshooting (channel
  1 vs. 5.9 GHz, RSSI −128 dBm, UART console GPIO 11/12 caveat, DEADBEEF test
  frame on boot) — matches `android/README.md`'s "Known limitations" section
  (`usb_stream_send_test_frame()` in `firmware/main/main.c`) almost exactly;
  this is documentation that already exists in `android/README.md` and should
  be ported into the in-app About screen rather than re-derived.
- "Resetting the board from the app": RTS-pulse soft-reset on the USB/BT
  connection screens — **already implemented** (`UsbSerialController.kt`
  has RTS-reset logic per earlier grep), so this is a documentation gap, not
  a code gap — ❓ confirm the BT side (`BluetoothController.kt`) has an
  equivalent "reset on start" option too, since the About text claims both
  USB *and* BT screens have it.
- Open-source library credits list (OSMdroid, usb-serial-for-android, Eclipse
  Paho, Play Services Location, Material Components, FlexboxLayout, Kotlin
  stdlib/coroutines) — should be cross-checked against actual
  `android/app/build.gradle.kts` dependencies when written, not copied blind.

## 8. Localization — net-new scope, not visible in screenshots

The 1.4.1 changelog states the app is now available in **10 languages**
(resolved via §0); this repo's current i18n is **English + German only**
(README: "Full i18n — English default, German for German-locale devices").
None of the 12 screenshots show a language picker or non-English/German UI,
so the other 8 languages are unconfirmed by name — **❓ ask the developer or
check the Play Store listing's own language list** before committing
translation work, rather than guessing which 8. This is pure `strings.xml`
translation effort (no new logic), but it's a real, substantial scope item
that's easy to miss since it doesn't show up as a UI control.

---

## Suggested implementation order

0. **Decide the `applicationId` question** (top of doc) before writing any
   code that will eventually ship — it's a one-line change now and a
   migration headache later.
1. **Navigation shell** (§1) — nothing else can be built/tested without it.
2. **Units pref** (§6) — small, but Statistics/Range/Map all consume it, so
   get it in before building screens that display numbers.
3. **Map tab redesign** (§2) — smallest net-new surface (status pills, FAB,
   pedestrian icon), reuses existing logic almost entirely. MAPEM lane
   geometry parsing + the new per-direction SPATEM signal heads are the one
   sub-item here with real algorithmic work — budget separately from the rest
   of the Map redesign.
4. **Statistics tab** (§4) — needed before Range, since both likely share
   rate-counting/history infrastructure with High Rate Mode (§6).
5. **Range tab / "Max Range Mode"** (§5) and **Radar tab** (§3) — independent
   of each other, can be parallelized.
6. **High Rate Mode** (§6) — touches Map, log, and Statistics; do last so it
   wraps already-built infrastructure instead of being designed blind.
7. **Frame Recording bundle (CSV/KML/debug log) + `.itsg5` capture format +
   Marker size** (§6) — independent, lower-risk, can slot in anytime.
8. **Replay mode for `.itsg5` captures** (§6) — depends on the capture format
   above existing first; do these two together rather than as separate
   milestones.
9. **In-app firmware updater** (§6) — largest standalone item outside the
   four tabs; treat as its own workstream, likely reusing logic from the web
   flasher (`docs/flash/`) or `bridge/setup_wizard.py`.
10. **About screen content** (§7) — documentation-only, no dependencies.
11. **Localization to 10 languages** (§8) — do last, once UI copy has
    stopped changing, to avoid re-translating strings that get reworded
    mid-implementation.

---

## Sources

- [pit711/v2x2map](https://github.com/pit711/v2x2map) — upstream public repo (confirmed byte-identical HEAD to this fork)
- [pit711/v2x2map forks](https://github.com/pit711/v2x2map/forks) — 17 forks checked via GitHub API, none ahead of upstream HEAD
- [V2X2MAP — Google Play](https://play.google.com/store/apps/details?id=com.v2x2map) — full description + "New in 1.4.1" changelog, applicationId confirmation
- [v2x2map.com](https://v2x2map.com) — official marketing site, feature list including Recording & Replay
- [pit711/v2x2map issue #15](https://github.com/pit711/v2x2map/issues/15) — unrelated DENM decoding report, checked for context, no new info found
