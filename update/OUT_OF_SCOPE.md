# V2X2MAP Android — explicitly out-of-scope / deferred features

Tracks everything deliberately **not** built as part of implementing
`update/FEATURES.md` / the plan at `.claude/plans/snug-prancing-floyd.md`,
so it isn't silently forgotten or rediscovered from scratch later. Three
categories: user-deferred (a scope decision), discovered-but-never-scoped
(found in the decompiled real app, never added to any phase), and
dropped-after-investigation (looked real, evidence says otherwise).

## Deferred by explicit decision (2026-10-04)

These were cut from the implementation plan on purpose, as their own
future, separately-scoped efforts — not forgotten, just not bundled in:

- **In-app firmware updater.** Per the Play Store changelog/description this
  is a proactive version-check dialog ("Your receiver is running firmware
  %s. This app includes version %s." — Update Now/Later), backed by a
  dedicated `SetupWizardActivity` (confirmed present in the decompiled
  manifest — `org.opentrafficmap.receiver.SetupWizardActivity`, no
  equivalent exists in this codebase). Likely reuses logic similar to the
  web flasher (`docs/flash/`) or `bridge/setup_wizard.py`'s esptool-based
  flow, ported to Android/USB-serial. Real strings confirmed:
  `fw_update_title/body/now/later/unknown`, `flash_firmware_btn`.
- **Receiver → Enable LED toggle (2026-11-07).** Investigated whether this
  could be wired to real firmware via an existing channel. Two candidates,
  both dead ends for the Android app as it stands today:
  - BLE `CFG_UUID` characteristic (`firmware/main/bt_stream.c`'s
    `cfg_access_cb`, the one `CycleSettingsDialog.kt` already uses) is
    **not** a generic config read/write — its 8-byte payload is hardcoded to
    the 4×uint16 `s_cycle_ms[]` array (disc/conn sniff/BLE cycle timing)
    only; no field/opcode for `CONFIG_INDEX_LED_BRIGHTNESS` exists.
  - USB-Serial-JTAG **does** have a generic text config command —
    `handle_cfg_command()` in `firmware/main/usb_stream.c` accepts
    `CFG:key=value\n` lines and writes to any `config_index_t` via
    `cfg_key_to_index()` (used today by `bridge/setup_wizard.py`, not by the
    Android app). Adding `"ledbrightness" → CONFIG_INDEX_LED_BRIGHTNESS` to
    that map would be a one-line firmware change. But `UsbSerialController.kt`
    only ever feeds its byte stream into `FrameReader`'s binary `ITS5`-magic
    framing — it has no code path that writes/reads this line-based text
    protocol at all, so using it means teaching the Android USB transport a
    second, text-based sub-protocol interleaved with the binary frame
    stream, not just flipping a switch.
  Net: no app-reachable channel exists *today* without new work on both
  sides. Not implemented — building the Settings toggle now would be UI
  with no real effect. Revisit as a paired firmware+Android change (likely
  the USB text-config route, since it already exists for the wizard) —
  crosses the Android-only boundary in `STRATEGY.md`, so deliberately left
  for its own workstream rather than bundled into this plan.
- **10-language localization.** This repo has English + German only; the
  real app ships 10 languages per the Play Store changelog (only English +
  Italian resource splits were present on the one device checked). Pure
  `strings.xml` translation effort, no logic — do last, once UI copy stops
  changing, to avoid re-translating strings that get reworded mid-implementation.

## Discovered in the decompiled app, never added to any phase

Found while cross-referencing `update/v2x2map-play-store` (the real app,
pulled via `adb` + inspected with `aapt2`) against the plan — real,
confirmed features with no current phase assigned:

- **Privacy/visibility mode.** Strings `set_mode_hide_me` ("hiding my
  current location"), `set_mode_show_me` ("showing and tracking my current
  location"), `set_mode_online` ("Set to online mode"), `set_mode_offline`
  ("Set to offline mode") — a toggle controlling whether your own position
  is shared, almost certainly tied to the MQTT status topic's online/offline
  last-will (`MqttBridge.kt` already publishes to `its/<NodeID>/status`).
  Exact UI location unconfirmed (Settings? a map control?) — needs a
  decompile pass specifically looking for where these strings are bound
  before scoping a phase for it.
- **Map pre-download: richer than currently planned.** Confirmed modes
  `map_dl_mode_country`/`_state`/`_visible` ("Country"/"State"/"Visible
  area") plus `map_dl_zoom`/`_zoom_hint`, `map_dl_too_big`, `map_dl_warn_big`,
  `map_dl_throttle`, `map_dl_eta` — a region picker + zoom-level picker +
  size warnings + throttled download with ETA. The current
  `downloadVisibleMap()` (`MapFragment.kt`) only does the "Visible area"
  case with no zoom picker or size warnings.
- **BLE cycle-settings dialog redesign.** Decompiled layout
  `dialog_cycle_slider.xml` + strings `cycle_presets_label`,
  `preset_balanced`/`preset_moredata`/`preset_stable`, `cycle_conn_label`,
  `cycle_disc_label`, `cycle_warn_maxdata`, `cycle_end_stable`/`_moredata` —
  sliders and renamed presets ("Balanced"/"More data"/"Stable"), replacing
  this repo's `CycleSettingsDialog.kt` EditText-based fields and
  "Wardriving"/"Stationary"/"Pairing" preset names. Cosmetic/UX upgrade of
  an already-working dialog — low priority.
- **"What's New" dialog.** Strings `whats_new_title` ("New in version %s")
  / `whats_new_body` — shown after an app update. No equivalent anywhere in
  this codebase. Low priority, purely additive.
- **Full per-lane-group intersection panel.** `IntersectionSheet.kt`
  (added in Phase 4) shows the single nearest signal's phase/countdown/
  distance only. The real app's confirmed strings (`intersection_sg`
  "Group %d", `intersection_remaining` "%.0f s", `intersection_groups`)
  imply a panel listing **multiple signal groups per intersection** with
  individual countdowns — needs MAPEM lane/signal-group geometry parsing
  (see next section) to correlate SPATEM signal groups to physical lanes/
  approaches, which isn't implemented.

## Investigated, dropped (evidence says it's probably not real)

- **`.itsg5` capture + in-app Replay.** `FEATURES.md` originally scoped this
  from `.gitignore`'s `*.itsg5` entry and v2x2map.com's "Recording & Replay"
  marketing copy. Searched the entire decompiled string table for
  replay/capture-file/import/load-file wording — nothing. All recording
  strings are PCAP/CSV/KML/debug-log only (`rec_*`, `csv_*`, `kml_*`). Not
  implemented, not currently planned — revisit only if direct evidence of
  an in-app replay UI turns up (e.g. a future decompile of `classes.dex`
  itself, which hasn't been done — everything above came from resources/
  strings/layouts via `aapt2`, not from decompiling the actual Kotlin logic).

## Known gap in method, not just scope

Everything in the "discovered" section above was found via `aapt2 dump` on
resources/layouts/strings — **`classes.dex` itself has never been
decompiled** (would need `jadx` or similar; the one attempt to fetch `jadx`
in-session was interrupted by the user, who provided a resource-only extract
instead). Concretely: `view_radar.xml`, `view_stats.xml`, and
`view_range.xml` each turned out to be a **single custom View class**
(`RadarView`, `StatsPanel`, `RangeView` — all doing their own Canvas
drawing), so `aapt2` can confirm they exist and are mounted via the
`stubRadar`/`stubStats`/`stubRange` `ViewStub`s, but not what they actually
draw or how. Statistics (Phase 5) and Range (Phase 6) are therefore built
as normal composed Material widgets (cards, rows, a small custom chart view
only where genuinely needed) — matching the confirmed feature set and
strings, not a pixel-level match to the real app's custom-drawn panels,
since that source isn't visible. This means:
- Anything whose evidence lives purely in code logic (not a string, layout,
  or resource ID) is invisible to everything found so far — e.g. the exact
  `set_mode_*` toggle's wiring, the precise green-wave speed formula, exact
  High Rate Mode batching thresholds beyond what's already in
  `FEATURES.md`'s string-sourced description.
- If a decompile of `classes.dex` ever happens, it's worth re-checking every
  item in this file against real logic, not just re-confirming strings.

## Also still true from `FEATURES.md` (not re-duplicated here)

See `update/FEATURES.md` for the original, still-valid deferral notes:
Radar/Statistics/Range tab internals not yet built (tracked as plan phases,
not "out of scope"). High Rate Mode, Marker size, and the Frame Recording
bundle (CSV/KML/debug log) are now implemented; Enable LED was investigated
and deferred (see above) — these were all still **in** the
active plan, just not yet reached, so they're not duplicated into this
out-of-scope file.
