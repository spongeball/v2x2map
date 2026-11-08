# Firmware vs. Codeberg upstream — divergence audit (2026-11-07)

Investigation into contributing firmware features back to the original upstream project,
`codeberg.org/opentrafficmap/its-g5-receiver-firmware` (the `firmware/` directory here is a
flat, history-less import of that project — see "No shared git history" below). Nothing in
this repo was changed as part of this investigation; it's a reference doc for a future
dedicated branch/PR effort.

Method: shallow-cloned the Codeberg repo (`git clone --depth 50`) into a scratch dir and diffed
its `main/` against this repo's `firmware/main/`, plus checks via the Codeberg API
(`codeberg.org/api/v1/repos/opentrafficmap/its-g5-receiver-firmware`).

## Fork relationship (GitHub side)

- `spongeball/v2x2map` (this repo, `origin`) is a GitHub fork of `pit711/v2x2map` — confirmed via
  the GitHub API (`fork: true`, `parent: pit711/v2x2map`). Both show the same `pushed_at`
  timestamp and identical `main` HEAD (`6872d24...`) — the fork hasn't diverged in *pushed*
  history at all; all the feature work from recent sessions exists only as uncommitted local
  changes on top of that shared commit.
- `pit711/v2x2map` is the real upstream for the whole monorepo (not itself a fork: `fork: false`).
  It's the one that originally imported the Codeberg firmware into this combined
  firmware+Android+bridge+web-flasher project.

## No shared git history with Codeberg

The entire repo's oldest commit is a single flat `"Initial commit: V2X2MAP firmware, bridge,
Android app, website"` (`222c954`) — firmware, bridge, Android, and website all arrived at once,
with no subtree merge, no submodule link, no preserved ancestry to the Codeberg project. That
means:
- There is no common-ancestor commit to produce a clean 3-way diff against.
- Every diff below is against Codeberg's **current** HEAD (as of 2026-11-07, commit `9c9889a`,
  "Update to ESP-IDF v6.1 (stable)", PR #28), not the actual historical fork point — so it
  necessarily mixes "what this fork changed" with "what upstream changed independently since
  whatever point the import actually happened." Treat every number below as an upper bound on
  real fork-specific work, not an exact count.

## File-by-file divergence (`firmware/main/`)

| File | Status | Notes |
|---|---|---|
| `bt_stream.c` / `.h` | **fork-only, new file** | BLE streaming + the BLE side of the `ITS5` wire protocol for the Android app/bridge. Upstream has **zero** BLE awareness anywhere in `main/` (checked `led.c`/`.h` specifically — no match). |
| `usb_stream.c` / `.h` | **fork-only, new file** | USB-Serial-JTAG `ITS5` framing (binary) + the `CFG:key=value` text config command channel used by the setup wizard. |
| `wifi_manager.c` / `.h` | **fork-only, new file** | STA/AP cycling (`CONFIG_INDEX_SNIFF_MODE`) so the device can sniff 802.11p and still join WiFi for MQTT/OTA. |
| `config.c` / `.h` | **heavily changed** (~480 of ~1044 lines in `config.c`; `config.h` 31 of 57) | Mostly new `CONFIG_INDEX_*` keys backing the three files above (node ID, MQTT URI, WiFi creds/IP, conn/sniff mode, sniff/wifi timing). Biggest single divergence — upstream may also have added its own keys independently since the import, so this isn't purely fork-additive. |
| `led.c` / `.h` | **heavily changed, both-sided** (~400 of 536 lines in `led.c`; `led.h` upstream is 4 lines → local 21) | **Not** a one-directional fork addition — upstream independently added its own feature here: `CONFIG_INDEX_LED_OVERRIDE_COLOR` / "Flugsicherungslampenmodus" (a forced-override LED color mode, likely for outdoor-mount visibility/compliance), which this fork does not have. This fork independently added BLE-aware idle-color states (`s_ble_connected`, `led_status_ble_connected`, the teal/orange/blue-while-sniffing/BLE-connected scheme) that upstream does not have. The shared base — per-sub-LED Ethernet/MQTT/sniffer/C-ITS-activity colors (`LED_ETH`, `LED_SNIFFER`, `LED_MQTT`, `LED_CITS`, the `LED_IRGB` macro, the 100M/10M green/orange Ethernet colors) — is identical in structure on both sides. This file needs feature-level reconciliation, not a patch. |
| `main.c` | moderate (60 lines) | ESP32-C5/Waveshare boot-sequence + sniff-mode-cycling adaptation. |
| `cmd_sniffer.c` / `.h` | small (36 + 9 lines) | |
| `ethernet.c` | small (18 lines) | |
| `events.c` | tiny (8 lines) | |
| `temperature.c` | tiny (9 lines) | |
| `spi.c` | tiny (4 lines) | |
| `ota.c` | tiny (4 lines) | |
| `cmd_pcap.c` | **byte-identical** (0 diff) | |
| `sdcard.c` | **byte-identical** (0 diff) | |
| `spi_eppp.c` / `.h` | **upstream-only, not pulled in** | A newer upstream feature (SPI-over-PPP Ethernet gateway link, e.g. for `esp32-spi-eppp-server`). This fork never imported it — a possible thing to pull *from* upstream later, separate from the "push features up" direction this doc is about. |

Top-level (`firmware/`): local lacks `sdkconfig.proto-spi-eppp` (exists upstream, pairs with
`spi_eppp.c`) and upstream's own `README.md`/vendored `esp-idf` submodule reference (this repo's
`esp-idf/` lives at the monorepo root instead, one level up from `firmware/`). Partition files
(`partitions_4M.csv`, `partitions_8M.csv`) and the enc28j60/w5500 `sdkconfig.proto-*` naming are
identical to upstream — inherited, not fork-invented.

## Firmware version — already an upstream feature, not a fork invention

Checked specifically because of the earlier "can I see firmware version in the app" question:
Codeberg's `mqtt.cpp` already has the **same** `publish_node_info()` mechanism this fork's
`mqtt.c` has — `esp_app_get_description()->version` published as `"ver"` in a JSON object
(alongside `"emac"` MAC and a hardware-variant field) to an `info` MQTT topic. It was inherited
(ported C++→C with this fork's other MQTT work), not invented here. Implication for any future
"show firmware version in the Android app" work: subscribing to that existing `info` topic (when
MQTT is configured) is the more upstream-aligned route, rather than adding a new BLE/USB
characteristic that only this fork would have.

## Licensing — a real gap, not resolved here

- This repo's `LICENSE` is MIT, "Copyright (c) 2026 Peter Holzhauser and contributors."
- The Codeberg repo has **no license at all** — confirmed via `GET /api/v1/repos/.../` (`license:
  None`) and by listing the repo root contents directly (no `LICENSE`/`LICENCE`/`COPYING` file of
  any name). This is worth raising with the upstream maintainer before submitting substantial
  code, rather than assuming MIT compatibility.

## Contribution process — they have a live, working one

Confirmed via the Codeberg API: `has_pull_requests: true`, `has_issues: true`, `open_pr_counter: 5`
(as of 2026-11-07), not archived, 48 stars / 12 forks. The visible commit log already shows
several merged PRs by number (`#28`, `#25`, `#24`, `#19`, `#15`) — a real, active Gitea-style
fork-and-PR workflow, not a dead or maintainer-only project. No `CONTRIBUTING.md` exists, so their
specific preferences (PR size, whether they want Android/BLE-companion support upstream at all)
are unknown — worth an issue first on anything beyond a small, obviously-useful patch.

## Recommended plan for the future branch

1. Create a Codeberg account, **Fork** `opentrafficmap/its-g5-receiver-firmware` there, clone that
   fork. This is a manual port, not a `git merge`/`rebase` — there's no shared history (see above).
2. Open with the **zero-conflict pieces** as a first, focused PR: `bt_stream.c/.h`,
   `usb_stream.c/.h`, `wifi_manager.c/.h` — net-new files, nothing upstream already has to
   conflict with — plus the specific `config.c`/`config.h` keys they depend on (not the whole
   diverged file).
3. Open an issue first asking whether BLE/Android-companion support is something upstream even
   wants, before investing in that PR — there's no contributing guide to infer this from.
4. Treat `led.c` and the rest of `config.c` as separate, harder follow-up work: both sides moved
   independently, so it's feature reconciliation (decide whether to also port upstream's LED
   override-color feature into this fork, and whether upstream wants this fork's BLE-aware idle
   colors), not a copy-paste patch.
5. Raise the missing-license question with the maintainer before/alongside the first PR.
6. Lower priority, opposite direction: consider pulling `spi_eppp.c/.h` (+ its sdkconfig variant)
   from upstream into this fork, since it was added there after whatever point this fork's copy
   was taken and this fork never picked it up.
