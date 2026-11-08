# V2X2MAP Android — how we're building the feature gap in `FEATURES.md`

Decision record for *how* to implement the features catalogued in
`update/FEATURES.md` (the v1.4.1 Play Store feature set, reimplemented from
screenshots + store listing since no newer source tree exists anywhere
public — see `FEATURES.md`'s "Where build 15 came from" section).

## Decision: build in place, in the existing fork and the existing `android/app` module

Three options were considered. Researched 2026-10-04.

| Option | Verdict |
|---|---|
| **Keep this fork, add on top of `android/app`** | **Chosen.** |
| Create a new `android-v2/` (or similar) folder in this repo, build fresh, swap later | Rejected |
| Create an entirely new, separate repository | Rejected |

### Why not a new repository

- This repo is a multi-component monorepo (firmware + android + bridge +
  web-flasher) that is still actively synced with upstream
  [`pit711/v2x2map`](https://github.com/pit711/v2x2map) for firmware and
  web-flasher fixes (commits into September 2026). `CLAUDE.md` documents real
  cross-component coupling — a new message type or `CONFIG_INDEX_*` setting
  needs firmware, Android, and sometimes the bridge updated together in one
  commit. Splitting Android into its own repo severs that and makes those
  changes two-PR, two-repo affairs forever after.
- No distribution or signing reason forces a split: this fork has **zero
  published GitHub releases** for Android (`gh`/API check, 2026-10-04) and no
  Play Store listing of its own — there is no existing install base, no
  signing key continuity, and no Play Store identity clash with upstream's
  `com.v2x2map` to avoid (this fork's `applicationId` is already the
  different `org.opentrafficmap.receiver` — see the open applicationId
  decision in `FEATURES.md`).
- A new repo earns its cost when a component needs an independent release
  cadence, issue tracker, or contributor base. None of that applies here.

### Why not a parallel `android-v2/` folder in this repo

- Doubles manifest/build-config/signing maintenance while giving up the
  benefits of either real modularization (shared code, independent builds)
  or real independence (separate repo). It's the worst of both.
- The pattern that *does* justify a hard parallel-build-then-swap is a
  framework-level rewrite with one unavoidable cutover (e.g., Views →
  Jetpack Compose, or a persistence-layer migration that can't run
  incrementally). Nothing in `FEATURES.md` requires that — it's new screens
  (Radar/Statistics/Range), redesigned settings, and a richer recording
  format, all addable to the existing View-based `:app` module without a
  framework change.

### Why in-place, and why no Gradle multi-module split (yet)

- [Strangler Fig pattern](https://microservices.io/post/refactoring/2023/06/21/strangler-fig-application-pattern-incremental-modernization-to-services.md.html):
  build the new thing alongside the old, migrate screen by screen, never a
  big-bang cutover. Directly fits — we're reimplementing a feature set
  discovered by *observation* (screenshots, store listing), which the
  pattern's own literature calls out as "a process of discovery," same as
  here. Its stated caveat: **below a certain size, running two systems side
  by side costs more than it saves.**
- That caveat is the deciding factor against heavier modularization: `android/`
  is one flat `:app` Gradle module, ~20 Kotlin files, no CI pipeline, single
  maintainer. [Android's own modularization guidance](https://developer.android.com/topic/modularization/patterns)
  recommends feature modules in one repo over separate repos — but that
  guidance targets teams fighting slow monolith builds and multi-person merge
  contention. Neither applies at this scale. Introducing `:feature:radar`,
  `:feature:statistics`, etc. now would be process overhead with no one
  waiting on parallel builds.
- **Right-sized version of Strangler Fig for this project:** one feature
  branch per `FEATURES.md` section, merged to `main` via normal PRs in the
  order `FEATURES.md` already proposes, each merge leaving the app buildable
  and runnable. Revisit Gradle modularization only if/when the codebase or
  team actually grows enough to need it — not preemptively.

### Still open — decide before or during implementation, not silently

- **`applicationId`**: keep `org.opentrafficmap.receiver`, or rename to match
  the shipped app's `com.v2x2map`? No technical blocker either way (no
  existing installs to break), but it's a real identity decision — see
  `FEATURES.md`'s "Package name — resolved" section for the trade-off.
- **Scope/phasing** of the `FEATURES.md` list — full parity in one push, or
  staged milestones (e.g., tabs+settings first, in-app firmware updater and
  10-language localization as later, separately-scoped efforts)? Being
  tracked in `update/PLAN.md`.

## Sources

- [Strangler Fig application pattern — microservices.io](https://microservices.io/post/refactoring/2023/06/21/strangler-fig-application-pattern-incremental-modernization-to-services.md.html)
- [Android — Common modularization patterns](https://developer.android.com/topic/modularization/patterns)
- GitHub API checks, 2026-10-04: `spongeball/v2x2map` releases (0 found),
  `pit711/v2x2map` releases (7 found, last Android APK bundled was v0.2.3)
