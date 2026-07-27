# CLAUDE.md — Hanzi Dictionary

Offline-first Android Chinese-character dictionary + an open data pipeline that builds its
content pack. Personal, single-user project (apply the small-project review lens: simple
code wins; scale/availability/multi-user concerns don't apply). There is **no git remote** —
commits stay local until the dataset-publication decision is made.

Authoritative spec: `docs/hanzi-dictionary-spec-v1.md`. Data provenance/licensing:
`docs/ATTRIBUTIONS.md` + `LICENSES/`.

## Layout

- **`pipeline/`** — Python, **stdlib-only**, its own `.venv`. Fetches source snapshots and
  builds the SQLite content packs. `fetchers/` (one per source) → `pipeline/build.py`
  (assemble `hanzi.db`) → `pipeline/package.py` (split into shippable `core.db` + `ids.db`,
  build FTS5, export JSONL). Schema is `pipeline/pipeline/schema.sql`.
- **`app/`** — Gradle, two modules:
  - **`:core`** — **pure Kotlin, no Android deps, JVM-unit-tested.** Repositories
    (`HanziRepository` over the content pack, `StudyRepository`/`UserRepository` over user
    state), `Fsrs.kt` (hand-written FSRS-6), and **pure state machines** (`WidgetState.kt`,
    `PracticeState.kt`, `PhotoScan.kt`). **Put logic here, not in the UI/provider**, so it can
    be tested without a device. SQLDelight schemas live under `src/main/sqldelight/`.
  - **`:androidApp`** — Jetpack Compose UI (`ui/`), the home-screen widget (`widget/`), and
    thin Android adapters (`photo/`, `audio/`). Depends on `:core`.

## Build / test / run

Always `source ~/android-tooling/env.sh` first (JDK 17 + Gradle + SDK on PATH). This box has
no Android Studio; builds are CLI.

```bash
# From anywhere; -p points gradle at app/
source ~/android-tooling/env.sh
app/gradlew -p app :core:test                       # fast, pure-JVM unit tests — run these
app/gradlew -p app :core:test :androidApp:assembleDebug   # full build + tests
```

- **Unit tests are the safety net.** `:core` tests run against real fixtures with no device
  (`WidgetStateTest`, `PracticeStateTest`, `PhotoScannerTest`, `StudyRepositoryTest`,
  `HanziRepositoryTest` — the last opens the real bundled pack). Add a test for any new
  `:core` transition. Don't boot the emulator for logic checks.
- **On-device install:** the phone is on wireless ADB at an `IP:port` that **rotates every
  session** — the user supplies it. Use the `aconnect` helper, then
  `adb -s <ip:port> install -r app/androidApp/build/outputs/apk/debug/androidApp-debug.apk`.
  A launch + `logcat` crash-scan (grep `FATAL EXCEPTION|AndroidRuntime`) is the standard
  smoke check. **Don't screenshot/drive the UI** unless the user asks — leave visual
  verification to them.

## The data pack (core.db / ids.db)

The app ships a **prebuilt, read-only** content pack as an asset and copies it into the
databases dir on first run (`PackManager`). It is **never migrated** on-device — a new pack
fully replaces the old one.

- Rebuild: source snapshots live in `pipeline/data/snapshots/` (gitignored). Then, with the
  venv active from `pipeline/`: `python -m pipeline.build && python -m pipeline.package`, and
  copy the output over the bundled assets:
  `cp data/build/{core,ids}.db ../app/androidApp/src/main/assets/`.
- **`app/androidApp/src/main/assets/*.db` are GITIGNORED** (rebuild them, don't commit them).
- After changing pack *content or schema*, **bump `BUNDLED_PACK_VERSION` in `PackManager.kt`**
  so installed apps re-copy it. (Currently 10.)
- Adding a **table/column to the pack** means editing **both** `pipeline/schema.sql` **and**
  the app's `Hanzi.sq` (keep them in sync), and adding the table to `core_tables` in
  `package.py`. `SCHEMA_USER_VERSION` stays **1** — the read-only pack has no migrations, so
  the SQLDelight schema version doesn't move when you add `CREATE TABLE` statements.
- `HanziRepository` opens `core.db` and `ids.db` as **two separate SQLDelight drivers, never
  ATTACHed** (ATTACH doesn't survive Android/JDBC connection pools; also keeps the GPL `ids`
  layer physically separate per spec §15). FTS5 needs the **requery** SQLite driver
  (framework SQLite lacks fts5 on many devices) — that's why `PackManager` passes
  `RequerySQLiteOpenHelperFactory`.

## The user DB (user.db) — this one DOES migrate

Writable user state (archive, lookup history, photo finds, study lists, FSRS card state).
Opened with `UserRepository.Schema`; **SQLDelight runs the `.sqm` migrations on open**, so an
installed app upgrades in place. Schema version = number of `.sqm` files + 1.

- **Migrations must be additive and never lose user data** (archive/lists built by hand are
  irreplaceable). Add a numbered `N.sqm` (e.g. `2.sqm`: `ALTER TABLE … ADD COLUMN …`) and
  update the `CREATE TABLE` in `User.sq` for fresh installs. `StudyRepositoryTest`'s
  migration test upgrades a hand-built v1 DB through to the current version — keep it green.

## Conventions & patterns (match these)

- **Gesture grammar, app-wide:** right = affirm (knew it / add / archive), left = negative
  (didn't know / remove), up = skip. Swipes are hand-rolled in `ui/Swipe.kt`
  (`SwipeRow`/`SwipeCard`) on `detectDragGestures` because Material3 here is **1.2.1** (no
  `SwipeToDismissBox`) — **do not "fix" this by bumping the compose BOM**. `SwipeCard` is a
  2D drag (horizontal = graded verbs, vertical-up = skip); every swipe also exposes a
  TalkBack `CustomAccessibilityAction`.
- **Grading is swipe-only, and only two grades.** Left/right/up on the practice card are the
  *whole* grading interface — no grade buttons beside them, and **no Hard/Easy anywhere**
  (`Fsrs.kt` still implements all four ratings; the UI only ever emits `AGAIN` and `GOOD`).
  The widget's two gutters mirror exactly this. Don't reintroduce a tappable grade — a screen
  offering both input systems teaches neither.
- **Every screen but home carries a `HomeButton` in its top-bar `actions`**, last before any
  overflow `⋮`. Browsing here goes character → component → character and gets deep, so Back
  alone isn't enough. It pops to `HOME_ROUTE` rather than navigating (see `goHome` in
  `AppRoot.kt`). It's a top-bar action, not a bottom nav bar, because Practice is budgeted to
  fit one screen without scrolling and a nav bar would spend that budget.
- **The practice card uses structure, not headings.** Its revealed side is three bands split
  by hairlines (`CardRule`) — answer / components / words. Don't add section labels back;
  vertical space on that card is the scarce resource.
- **Comments justify _why_, not _what_.** The codebase is heavily commented in this style;
  match it. Keep it that way.
- **Route args are percent-encoded with `android.net.Uri.encode`, not `URLEncoder`**
  (URLEncoder corrupts `+` in glyph ids like `U+597D`). See `enc`/`dec` in `AppRoot.kt`.
- **After any user-state mutation that changes what's due, poke the widget**
  (`PracticeWidgetUpdater.refresh(context)`) — otherwise a placed widget goes stale.

### Home-screen widget — platform constraints that bite

- **`RemoteViews` gets taps only — no gestures/drag.** The app's swipe verbs become tap
  zones (two grade gutters — Again / Good — plus a middle card that flips then opens).
- **`RemoteViews` inflates only a whitelist of view classes.** A bare `<View>` throws
  `Class not allowed to be inflated` → launcher shows "Couldn't add widget." Use
  `TextView`/`ImageView`/`LinearLayout`/etc. only (a 1dp `TextView` is the divider trick).
- **You cannot trigger the widget from the shell** (`APPWIDGET_UPDATE` is a protected
  broadcast → `SecurityException`). Verify by having the user place it, then read
  `shared_prefs/practice_widget.xml` and `review_log.origin='widget'` via `run-as`.
- All widget card/reveal logic is the pure `WidgetState` state machine in `:core`
  (unit-tested); `PracticeWidgetProvider` only does prefs + RemoteViews + the FSRS write.

### Kotlin gotcha

Cross-module smart-cast: a nullable property on a `:core` data class won't smart-cast in
`:androidApp` after a null check — copy it to a local `val` first
(`val c = prev.card; if (c != null) c.item …`).

## Deck / scheduling model (spec §17)

Scheduling is **FSRS-6** in `core/Fsrs.kt`, hand-ported from published formulas (pinned by a
golden test). **Do not vendor `open-spaced-repetition/FSRS-Kotlin` — it has no LICENSE.**
Deck order (`StudyRepository.buildDeck`): **new cards first** (they need a first look to gain
any signal; capped at `NEW_PER_DAY=15`, which is the safety valve against review debt), then
**due** by ascending predicted recall, then **ahead** (practice-ahead top-up). New-card
*presentation* order is shuffled per session; due/ahead stay recall-sorted (the proven part —
don't shuffle them; tests assert the order). The home-screen widget mirrors this via
`selectWidgetCard`.

## Reviewing this project

Follow the `review` skill (strict nitpicky pass → Implement/Optional/Dismiss triage) with the
small-personal-project lens above. Security findings are never dismissed as theoretical, but
this app ships no secrets and takes no untrusted network input; the one injection surface to
watch is the pipeline (SQLite inserts are parameterized — keep them that way) and any future
CSV/spreadsheet export (guard `=+-@` formula-injection).
