# Bambi / ShieldStream agent guide

## Project overview

Bambi is an Android TV streaming client written in Kotlin with Jetpack Compose, Hilt, Retrofit, AndroidX Media3, and Coil. It uses TMDB for catalog metadata, Trakt for synchronization, and user-configured debrid/source providers for playback. The app supports multiple local profiles, per-profile watch state and preferences, external subtitles, and episode release tracking.

- Stable application ID: `com.shieldstream.tv`.
- Debug application ID: `com.shieldstream.tv.debug`; it is a separate installation with separate data.
- Minimum SDK: 21. Target SDK: 35. TV landscape UI.
- UI text uses `tr("Español", "English")`. Keep both languages in user-facing text.
- The project is GPL-3.0. Third-party services and components retain their own terms and licenses.

## Modules

- `app`: `MainActivity`, Hilt application/configuration, navigation, app-level DI, update handling, and episode-notification WorkManager code.
- `core:model`: JVM models and pure model logic. No Android UI dependencies.
- `core:domain`: JVM repository contracts, playback/source rules, episode rules, navigation holder, and coroutine helpers.
- `core:network`: Retrofit APIs and TMDB/Torrentio/Real-Debrid DTOs.
- `core:data`: Android repositories, profile-aware persistence, Trakt, subtitles, updates, and Hilt bindings.
- `core:media`: Media3 playback infrastructure and device capability detection.
- `core:ui`: shared Compose components, TV focus/scrolling behavior, icons, and theme.
- `feature:home`: Home, search, My List, episode center, and top navigation.
- `feature:details`: catalog Details, episode/season selection, source resolution, and playback handoff.
- `feature:player`: playback, audio/subtitle tracks, external subtitles, skip segments, and next episode.
- `feature:settings`: setup, settings, profiles, Trakt linking, and update UI.

Keep domain/model logic free of Android UI dependencies. New repository implementations usually need a `@Binds` binding in `core/data/.../di/DataModule.kt`.

## Important behavior and ownership

### Profiles and preferences

Watch progress, My List, followed shows, dismissed/notified episodes, and playback preferences are profile-aware. Interface language is app-wide and takes effect after restart; audio and subtitle preferences belong to the active profile.

The setup has four numbered steps: catalog, debrid, extra subtitles, then language/audio/subtitle preferences. A language change made in step 4 is deliberately deferred until Finish so it does not restart the setup midway. Do not move these preferences back into Welcome: doing so previously pushed Start/Later off-screen. Welcome tiles use equal weights, not fixed widths that overflow a 1080p TV.

### Navigation and playback

`SelectedContentHolder` carries the selected item, optional episode target, playback queue, and one-shot autoplay state. Continue Watching uses Details as an autoplay handoff so source resolution, Spanish-source policy, and resume-position behavior stay centralized. Do not build a second playback-resolution path in Home.

Continue Watching opens directly on the resumable item. OK resumes playback; long-press offers Details, Remove, and Cancel. One-shot holder flags must be consumed/reset to prevent delayed playback on a later Details screen.

### Episode tracking

Use these shared paths rather than duplicating release/watched logic:

- `EpisodeReleaseRepository`: recent unwatched episodes and upcoming releases.
- `EpisodeTrackingRepository`: followed shows, dismissed episodes, notified episodes, and opt-in notification setting.
- `NewEpisodes`: pure candidate/date/release rules and date windows.

Recent releases use a 30-day window; upcoming releases use the next 30 days. Dismissal is per episode, so a newer episode can produce a new badge. Counts currently inspect the latest aired season, not the show's complete history. The episode center loads only after its tab is first opened; the top-bar count appearing after that first visit is intentional and avoids startup request bursts.

Notifications are opt-in, followed-shows-only, and run through periodic WorkManager checks (12 hours with network). The first run establishes a baseline to avoid notifying for old releases. Preserve watched/dismissed filtering and Trakt synchronization. `@HiltWorker` requires the AndroidX Hilt compiler, worker factory, and `Configuration.Provider` wiring in `App.kt`.

### Persistence and credentials

Most persistence uses profile-specific `SharedPreferences`; there is no Room database for watch state. Tokens and user keys belong in `TokenStore` and the gitignored `local.properties`. Never print, commit, or copy credentials into notes, tests, screenshots, or documentation.

Catalog item metadata (title, artwork, year, rating) is cached in `cacheDir/catalog_items.json` by `FileCatalogItemStore`:

- Entries are per interface language, capped at 800 by fetch time. Episode and airing data are not persisted and stay network-fresh.
- `getItem` checks memory, then disk, then network. `cachedItem` never makes a request.
- Entries older than 24 h are refetched two at a time, 30 s after playback starts (`CatalogCacheRefresher`), so refreshes happen while the user watches.
- On the Nokia cold start, the cache shows all 138 My List items in 2 ms and Home's My list row in 5 ms. Startup requests fell from 257 to 151.

## Performance rules learned on the physical TV box

These are measured project-specific rules, not generic Compose advice. The reference device is the Nokia Streaming Box 8010. Lightweight UI was Off during several measurements, so the normal path must also be efficient.

### Full-screen artwork

- Do not add continuous backdrop zoom or a full-screen crossfade. Removing a 12-second zoom and a 350 ms crossfade reduced a measured path from 133 ms to 48 ms.
- The featured carousel uses static artwork and immediate slide changes. Preserve this unless a physical-device A/B test proves an alternative is faster.
- Lightweight mode skips changing row backdrops. Normal mode retains them. Do not request a new backdrop on every focus movement; the existing delayed hero update avoids that.
- Avoid new full-screen shadows, blur, entrance animations, or simultaneous backdrop compositing without measurement.
- Compose redraws the whole screen on every row scroll: Compose layers never clip HWUI damage. Every full-screen layer is paid on every frame. The Home backdrop plus two full-screen gradient scrims cost ~22 ms of GPU time per frame on the Nokia's Mali-G31. The soft path bakes the scrims into its opaque 320×180 bitmap (`SoftBackdropImage(scrims = …)`): GPU time dropped from 34 ms to 11 ms per frame, and median row-scroll frames from 46 ms to 19 ms. Do not reintroduce full-screen gradient overlays above a backdrop. Details bakes its scrims into a sharp 1280×720 bitmap (`ScrimmedBackdropImage`): GPU time per vertical-scroll frame dropped from 30 ms to 7.6 ms, and median frames from 48 ms to 20 ms.

### Recomposition and focus

- Cache formatted episode/date labels with `remember` at the item level. Caching these labels reduced a measured path from 97 ms to 44 ms. Do not repeatedly construct `SimpleDateFormat` during row recomposition.
- Avoid reading rapidly changing focus state during whole-screen composition. `RowsBrowser` deliberately uses snapshots/listeners for saved focus and delayed hero updates.
- Playback position updates are frequent. Episode-release flows should react to meaningful watched-key/dismissal/candidate changes, not every position update.
- Keep visible poster focus feedback. The current `1.04x` scale is intentional; removing scaling previously gave no speed improvement and made feedback worse.
- Preserve the order of clickable/focus handling before scaling in `PosterCard`; changing it caused vertical list jumps.
- Poster cards draw through `CardImage`, not Coil's `AsyncImage`. While a poster grid scrolls, `AsyncImage` cost ~30% of main-thread time on the Nokia: a coroutine with `snapshotFlow`/`mapLatest` per image, plus a measure-time size handshake. `CardImage` uses a known decode size, hits the memory cache synchronously, and enqueues one request. My List fast-scroll frames went from 44 to 29 ms median (p90 67 to 39 ms), and Home vertical p90 from 45 to 36 ms. Prefer it for new image-heavy lazy rows and grids.
- My List load is not the bottleneck, so pagination would not help. With 138 entries, the first 20 appear in ~10 ms and the full list in ~1.8 s, and the grid is already lazy. The cost is composing each newly visible row of cards.
- Use explicit focus requesters/properties where geometric focus chooses the wrong TV destination. Test Back, held Up/Down, and returning from Details/player.

### Network and concurrency

- Use `mapConcurrent(concurrency = 4)` for batches of show/season requests. Do not launch 40–50 unrestricted TMDB calls.
- `CatalogRepositoryImpl` caches airing and season data for six hours and uses per-key mutexes to deduplicate in-flight requests. Preserve both behaviors.
- Do not eagerly load all episode statuses at app startup merely to populate a badge.
- Full-season completion checks are expensive and should remain user-triggered and bounded. `isComplete` first rules out shows whose latest aired episode is unwatched. For a 68-show My List, Hide completed went from 118 season requests (2.8 s) to none (75 ms).
- `getItem` for a show goes through `getShowAiring`: both need the same TMDB details request. Fetched separately, Home and My List downloaded each show twice at startup (124 → 101 show requests for ~97 shows).
- My List emits its first 20 items early only under the Recent sort, where they are exactly the top of the final list. Other sorts wait for the full list so it does not reshuffle. Home's My list row shows its first 12 items in ~1.3 s instead of waiting ~3.4 s for all 50.
- Image loads and full-screen composition are different costs; avoid new image requests unless they solve a measured problem.

### Changes that did not help in testing

Do not repeat these as presumed optimizations without new evidence:

- `w500` instead of `w780` hero images: no frame-time gain and extra image requests.
- Nested prefetch reduced from two cards to one: effectively unchanged. Keep two.
- Removing poster scaling: no improvement and worse visual feedback.
- A longer hero debounce: no improvement in the tested path.
- Removing the redundant full-screen black fills (window, MainScreen, RowsBrowser): ≤0.5 ms GPU; the opaque backdrop already hides them.
- An offscreen layer for the backdrop instead of baking the scrims: bimodal, with p90 GPU still 35 ms.
- `nestedPrefetchItemCount` 7 instead of 2, or disabling overscroll: unchanged.

Memory was not the observed bottleneck. Prioritize UI-thread work, draw submission, frame deadlines, and input latency.

## Compose/TV layout pitfalls

- Design for D-pad navigation and 1920x1080 output on the Nokia, not touch or phone layouts.
- Use `verticalScroll` for preference forms that can exceed the available height. Use equal-weight choices when every option must have the same width.
- Checkmark text changes intrinsic button width; use fixed/equal-weight containers for aligned options.
- Some Shield rendering paths lose clipping on scaled layers. Existing code often draws a shaped background directly instead of relying on clip; preserve that pattern where noted in comments.
- `onBackKey` handles Compose TV focus-group behavior; do not replace it casually with a generic back listener.
- Row keys and virtual indexes preserve focus in looping rows and after item removal. Do not replace them with unstable positional keys.

## Build and tests

`gradlew` may not be executable in this checkout; invoke it through `bash`.

```bash
ANDROID_HOME="$HOME/Library/Android/sdk" bash ./gradlew test :app:assembleRelease
```

`app/src/main/baseline-prof.txt` precompiles the UI libraries (Compose, Coil, coroutines, collection, navigation, lifecycle) as well as app code. Their bundled profiles miss the row-scroll paths. Without these rules, ~38% of main-thread time while scrolling on the Nokia was interpreted, and median main-thread time per row-scroll frame was 10.5 ms instead of 8 ms. Measure UI-thread changes after `cmd package compile -m speed-profile -f`. Measurements taken right after a reinstall without it are pessimistic. Launch the app once (about 10 s) before compiling: ProfileInstaller writes the profile on first launch, and compiling earlier leaves `dumpsys package dexopt` at `status=verify`, with about 3x the UI-thread time. Restart the app after compiling.

Compose follows the system animator duration scale. At 0 (Developer options), focus-driven scrolling snaps in one frame instead of animating. The Nokia had all three scales at 0; `animator_duration_scale` is now 1.0, and Details vertical scrolling animates at a median 16.7 ms frame interval with 7% janky frames. Check `settings get global animator_duration_scale` before judging scroll smoothness.

Unit tests exist mainly in `core:model`, `core:domain`, `core:data`, and `core:media`. Add focused tests there for pure rules, persistence mappings, date filtering, and source/playback policy. There is no comprehensive Compose/UI or end-to-end suite. Compilation and unit tests do not validate D-pad focus, direct playback, notification delivery, or setup layout; verify those on a device.

Before changing code, inspect `git status` and relevant diffs. Multiple agents may edit this tree; do not revert unrelated work. Keep one measurable change per test build when evaluating performance.
