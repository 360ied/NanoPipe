# Technical debt audit (2026-11-05)

Whole-codebase read-only audit for architectural deficits and structural technical debt.

- Status: audit notes (no code changed).
- Date: 2026-11-05.
- Scope: `PipePipeExtractor/`, `PipePipeClient/app/src/main/`, build/infra (`flake.nix`, `PipePipeClient/*.gradle`, `scripts/`), cross-cutting (RxJava/lifecycle/errors/tests), dependencies/fork divergence.
- Method: 6 parallel reviewer lanes (extractor, client UI, client data, concurrency/errors, build/infra, deps/duplication). Sampled largest files + traces, grep for `TODO`/`FIXME`, `subscribe`/`dispose`, `getDefaultSharedPreferences`, `NewPipeDatabase.getInstance`.
- All paths below are relative to the repository root.

## P0 — fix first

### 1. No CI enforces AGENTS.md gates

- Evidence: no `.github/` at root; only `PipePipeExtractor/jitpack.yml` exists. `scripts/check.sh:60-68` defines quick/fast/full gates but nothing runs them automatically.
- Impact: `assembleDebug` / `lintDebug` / `testDebugUnitTest` discipline is manual; worktree/merge rules and `commit.py` format cannot be enforced.
- Direction: minimal CI (`nix develop --command ./scripts/check.sh` on PR, `--full` on `main`) or document "no CI by design".

### 2. `versionCode` frozen — releases indistinguishable to installers

- Evidence: `PipePipeClient/app/build.gradle:24` (`baseVersionCode = 1110`) → `universalVersionCode = 100 * baseVersionCode` (=111000); `AGENTS.md` says bump "only if store-style upgrade ordering ever requires it".
- Impact: every tag ships the same `versionCode`; Play/PackageManager upgrade ordering breaks.
- Direction: derive `versionCode` from tag/commit-count or bump `baseVersionCode` per release; fix `AGENTS.md`.

## Extractor (`PipePipeExtractor/`)

### 3. God-helper `YoutubeParsingHelper` with circular dependency

- Evidence: `extractor/src/main/java/org/schabi/newpipe/extractor/services/youtube/YoutubeParsingHelper.java:1-120` (~2,773 lines) imports `extractors/YoutubeStreamExtractor`; `extractors/YoutubeStreamExtractor.java:60` imports `YoutubeParsingHelper.*` back. ~50 call sites (e.g. `YoutubeStreamInfoItemExtractor.java:321,327,447,486`, `YoutubeSearchExtractor.java:105`, `YoutubeStreamExtractor.java:2144,2158`).
- Why architectural: all InnerTube traversal (text, nav endpoints, thumbnails, date/duration, mix IDs) in one static bag; bidirectional static coupling guarantees merge conflicts.
- Direction: split by concern (`InnertubeClient`, `JsonText`, `Thumbnails`, `NavigationEndpoints`); move `checkPlayabilityStatus` / `isPlayerResponseNotValid` into one owner.

### 4. God-extractor `YoutubeStreamExtractor` (~3,060 lines, public mutable state)

- Evidence: `extractors/YoutubeStreamExtractor.java:76-116` — `public JsonObject playerResponse/nextResponse/webStreamingData`, `public ArrayList<Throwable> errors`, `volatile` fields; responsibilities include player-response fetch (`:2158`), SABR, captions/chapters (`:2956,3025`), HLS/DASH/SABR URL selection.
- Direction: `PlayerResponseStore` (immutable snapshot) + per-client `StreamUrlProvider` + extracted meta-info extractors; make fields private/final.

### 5. Single-service fork keeps multi-service framework tax

- Evidence: `extractor/.../ServiceList.java:38-44` = `singletonList(YouTube)`; `StreamingService.java:200-350` still requires ~15 abstract factories per service; `YoutubeService.java:105-143` branches on `isYoutubeMixId` and throws `RuntimeException("contentFilters is empty. WHY?")`.
- Impact: new-service or new-YouTube-vertical work touches dispatcher + N extractors; search can crash instead of `ExtractionException`.
- Direction: table-driven dispatch (`ContentFilter` / `PlaylistId` → factory) or retire polymorphism; typed exceptions.

### 6. Duplicated `InfoItemExtractor` family

- Evidence: 10+ classes in `extractors/` (`YoutubeStreamInfoItemExtractor.java:179-227,292,321-367,447-704`, plus Shorts/Lockup/Mix/Music/Channel/Playlist variants) re-implement title/URL/thumbnail/uploader/verified/duration logic with different keys (`title` vs `headline`, `lengthText` vs overlay).
- Direction: `AbstractYoutubeInfoItemExtractor` with renderer key-maps; thin subclasses only.

### 7. Fragile scraping core (regex + hardcoded fingerprints + locale pin)

- Evidence: `YoutubeJavaScriptExtractor.java:35-37` (player-hash regexes); `YoutubeStreamLinkHandlerFactory.java:48` (11-char ID); `YoutubeParsingHelper.java:256-260,542` (`&c=WEB/TVHTML5/...` patterns, `getInitialData` HTML regex); `ClientsConstants.java:1-100` (hardcoded WEB/TV/IOS/ANDROID versions); `YoutubeService.java:150-190` (`SUPPORTED_LANGUAGES = listFrom("zu")`); `Parser.java:42` says "Avoid using regex !!!" yet is the regex hub.
- Direction: `ClientRegistry` with version-probe + fallback + metrics; DOM/JSON over regex; fixture contract tests.

### 8. Global mutable singletons + `trustEveryone()` TLS bypass; error-hygiene gaps

- Evidence: `extractor/.../NewPipe.java:201-231` (allow-all `HostnameVerifier`/`TrustManager` on every `init()`); `NewPipe.java:30-60` + `StreamingService.java:100-130` (prefs/network/PoToken in process-global statics, public mutable `filterTypes`/`tokens`/settings). Errors: `Parser.java:71-88` embeds page content in `RegexException`; repeated `catch (Exception e) → ParsingException` in `YoutubeJavaScriptExtractor.java:79-133`; `catch (Exception ignored)` in `YoutubeDashManifestCreatorsUtils.java:618,644`; `assertPageFetched` throws unchecked `IllegalStateException` (`Extractor.java:60-75`).
- Direction: immutable `ExtractorSession`/`ServiceConfig` to constructors; gate/remove `trustEveryone`; typed hierarchy (`IOException` retryable vs `ParsingException`), no swallowed causes.

## Client UI / navigation (`PipePipeClient/app/src/main/java`)

No navigation graph exists (`res/` has only `layout/`, `menu/`, `values/`, `xml/`); all routing is manual `FragmentManager` transactions. Zero `ViewModel`/`LiveData`/`StateFlow` usages — state lives in fragments/singletons via RxJava.

### 9. `VideoDetailFragment` god-fragment (~3,030 lines)

- Evidence: `fragments/detail/VideoDetailFragment.java:1` (2970+ lines); fields `:157-250`, lifecycle `:255-531` (`onServiceConnected`, `onCreate`…`onDestroyView`, `onBackPressed`, prefs/click/long-press/key handlers); static `LinkedList<StackItem> stack` at `:887`.
- Direction: extract `VideoDetailViewState` + loader, move static stack to nav-scoped holder, split bottom-sheet/mini-player controller to `MainActivity`.

### 10. Static god-navigator + incoherent intent routing

- Evidence: `util/NavigationHelper.java:20,306-516` (same `replace(R.id.fragment_holder,…).addToBackStack(...)` per destination, 2 named tags, rest `null`; player via broadcasts `:362-366,405`); `MainActivity.java:602-688` (`handleIntent`, mini-player logic + receiver); `RouterActivity.java:81` (~850 lines, Rx URL resolution).
- Direction: single `AppNavigator` interface + tested `IntentRouter` shared by both activities.

### 11. Porous player/service boundary

- Evidence: `player/Player.java:230-290,246,875,1192` (~1,750 lines, ~20 controllers, grabs `service.getInstance()` as `Context`, drives notifications); `player/helper/PlayerHolder.java:29-57` static singleton (`player` + `playerService` nullable, racy); `VideoDetailFragment.java:2525-2527` mutates activity views via `findViewById`.
- Direction: `Player` = rendering only; playback/notification in `PlayerService`; replace `PlayerHolder` UI access with binding/scoped session; `MainFocusCoordinator` interface instead of `findViewById`.

### 12. Four parallel list stacks + deprecated pager + binding discipline

- Evidence: adapters `info_list/InfoListAdapter.java:69`, `local/LocalItemListAdapter.java:58`, `playqueue/PlayQueueAdapter.java:51`, `giga/.../MissionAdapter.java:97`; holders `info_list/holder/*` (14) vs `local/holder/*` (16); layouts `list_stream_{item,card,grid,mini}.xml` repeat idioms. Pager: `fragments/detail/TabAdapter.java:15-28` (`BEHAVIOR_SET_USER_VISIBLE_HINT`), `BaseListFragment.java:279-295` (`setUserVisibleHint`). Bindings: some null in `onDestroyView` (`VideoDetailFragment.java:472-474`, `ChannelFragment.java:171`), `BaseStateFragment.java:83-84` never does; `BaseFragment.java:22-44` blind-casts `context` to `AppCompatActivity`.
- Direction: unify on one `ListItemAdapter` keyed by `(kind, ItemViewMode)`; migrate to ViewPager2; standardize nullable `_binding` + `requireActivity()` + narrow activity interface.

## Client data (`database/`, `local/`, `us.shandian.giga`, caches, settings)

Single Room DB (`database/AppDatabase.kt`, 12 entities) plus a second SQLite store (`FinishedMissionStore.java`, `downloads.db`) + Java-serialized pending missions. Feed: `FeedLoadManager` → `FeedLoadService` / `NotificationWorker` → global `FeedEventManager BehaviorProcessor` → `FeedViewModel`.

### 13. UI talks directly to Room (layering violation)

- Evidence: 15+ sites — `local/feed/FeedFragment.kt:1496,1522`, `local/subscription/SubscriptionListFragment.kt:289`, `local/bookmark/BookmarkFragment.java:99`, `settings/SelectPlaylistFragment.java:89`, `local/playlist/LocalPlaylistFragment.java:148,739`, `local/dialog/PlaylistDialog.java:145`, `util/SparseItemUtil.java:116,147`.
- Direction: thin repositories (`SubscriptionRepository`, `FeedRepository`); lint-ban `NewPipeDatabase.getInstance` in UI.

### 14. Two download persistence stacks + cursor leak + god manager

- Evidence: `FinishedMissionStore.java:14-16` (TODO about unifying); `loadFinishedMissions()` never closes `Cursor`; `DownloadManager.java:38-60` mixes queue/metered/storage/UI-diffing; `DownloadManagerService.java` uses deprecated `getActiveNetworkInfo()` + `Handler` message ints.
- Direction: Room-backed `DownloadRepository` with observable state; `NetworkCapabilities`; typed flows.

### 15. Shared `stream` table, no owner; network I/O inside transactions

- Evidence: `streamDAO` written from subscription/feed/history/playlist managers; `HistoryRecordManager.markAsWatched` does `getStreamInfo(...).blockingGet()` inside `runInTransaction`; `loadStreamStateBatch` loops `blockingFirst()` per item; orphan cleanup from 3 places.
- Direction: sole-writer `StreamRepository`; batched `getStates(ids)`; network out of transactions.

### 16. Feed triple-coordination + cache/settings sprawl

- Evidence: `FeedLoadService.onStartCommand` group-agnostic single-flight drop; `FeedLoadManager` constructed fresh in both Service and `NotificationWorker`; global sticky bus forces `FeedViewModel` compensations (`consumedTerminal`, `terminalGeneration`, `failedSticky`, `blockingGet` in `map`). Caches: `InfoCache` (static 60-LRU, 1h global TTL), `YoutubePlayerResponseCache` (no TTL), `SerializedCache` (max 5, destructive `take()`), `StateSaver` (static + disk serialization). Settings: ~100 `getDefaultSharedPreferences` sites; `PlayerHelper` ~20 static getters; `ServiceHelper.initServices()` mutates static services.
- Direction: one `FeedRefreshUseCase` + per-group non-sticky results; per-type TTL + explicit invalidation events; typed `SettingsRepository`; standardize background work on WorkManager.

## Concurrency / errors / tests

Good baseline: `CompositeDisposable` discipline is the norm (`MissionAdapter.java:131,830`, `BaseListInfoFragment.java:86-194`, `Player.java:455`, `SearchFragment.java:366,425`).

### 17. Leaked / untracked subscriptions

- `SparseItemUtil.java:114,124`, `StreamDialogDefaultEntry.java:156-215` — fire-and-forget `subscribe()` with UI/DB side-effects (`Toast`, dialogs), `onErrorComplete` drops errors, `blockingGet()` inside `map`.
- `TextLinkifier.java:248-249` — per-tap `new CompositeDisposable()` never disposed.
- `AutoQueueController.kt:25-28` — `newScheduledThreadPool(2)` never `shutdown()`, pins `Player`.
- `LocalDomPoTokenProvider.kt:281,313` — unbounded `Thread.start()` for token refresh.
- Direction: return `Disposable` to caller `CompositeDisposable`; reuse bounded executors; add `destroy()/shutdownNow()`.

### 18. Silent failures +ء production logging

- `PlayerHistoryController.kt:29,45,87`, `VideoDetailFragment.java:1533` — `onErrorComplete().subscribe()` with no logging (history loss invisible).
- `printStackTrace()` / `System.err` in shipped code: `DownloaderImpl.java:147,552`, `RouterActivity.java:202`, `VideoDetailFragment.java:2094,2679+`, `SearchFragment.java:525,1044`, `PlayerErrorHandler.kt:138,322`, `SparseItemUtil.java:148-149`.
- `catch (Exception ignored)` in `StateSaver.java:281`, `StoredDirectoryHelper.java:197`, `DownloadMission.java:585,590`, `SearchFragment.java:1011`; dead `if(false){ new Thread… }` in `ErrorActivity.java:179`.
- Direction: `Log.e(TAG,…)` / `ErrorUtil` paths; at minimum `Log.w` on ignored; delete dead block.

### 19. Near-zero hermetic tests

- `app/src/test`: 4 files (`NewPipeSettingsSeedTest`, `CaptionLanguageFilterTest`, `CodecSupportDetectorTest`, `VideoStreamSelectorTest`) vs 500+ main files. `PipePipeExtractor/*/src`: `main/` only. `androidTest`: 4 device/network files (SABR smoke/benchmark/dumper).
- Direction: JVM tests for pure logic (`DebounceSaver`, feed error mapping, `StreamProcessor`) + dispose/lifecycle tests; hermetic extractor fixtures.

## Build / dependencies

### 20. Gate holes + versioning/filename contradictions + `aapt2` fragility

- `scripts/check.sh` skips the extractor; `PipePipeExtractor/build.gradle:14-17` disables `test`/`check`; `app/build.gradle:149-152` sets `lint { checkReleaseBuilds false; abortOnError false }`.
- `AGENTS.md` claims APK filename "never carries `-debug`/`-dirty`" but `app/build.gradle:33-46,201-208` interpolates raw `git describe --dirty` into `NanoPipe_${gitDescribeRaw}-${variant}.apk`.
- `flake.nix` `aapt2FromMavenOverride` uses build-tools 37 while comment says AGP 9.2 defaults to 36; any user `GRADLE_OPTS` clobbers it; undocumented AGP property.
- Signing files `PipePipeClient/release.keystore` + `keystore.properties` live on disk (root `.gitignore` covers them, `PipePipeClient/.gitignore` does not).
- `plans/` vs `docs/` confusion (`.gitignore` `/plans/` "never commit" yet `plans/release-v*.md` on disk); `docs/ffmpeg-removal/README.md` describes deleted `.github/workflows/build-ffmpeg.yml`.
- Direction: extend `--full` to extractor, make `lintDebug` meaningful or stop claiming it; strip `-dirty` from filename or fix docs; guard resolved `aapt2`; duplicate ignore + env-only secrets; clarify `plans/` lifecycle + banner stale workflow refs.

### 21. EOL media/UI stack + fork divergence + util sprawl

- ExoPlayer `2.18.7` (EOL Dec 2023, upstream on Media3) + archived `groupie:2.10.1`, `markwon:4.6.2`, `filepicker:4.2.1` (2019), `stetho:1.6.0`, `picasso:2.8` — `app/build.gradle:206-321`, `proguard-rules.pro:23`. (`app/build.gradle` + `extractor/build.gradle` at least both pin `jsoup:1.22.2`, `okhttp:5.4.0`.)
- Vendored extractor fork: `settings.gradle:32` `includeBuild`, `ServiceList` YouTube-only, no sync doc; `nanojson` pinned by JitPack hash in 3 places.
- Dead code: `CookieUtils.java:29-70` Netscape-export (zero callers), `SerializedUtils.clone` (serialization round-trip), `TimeUtils` vs PrettyTime vs `timeago-parser`, `translator.py` targets 9 locales absent from `res/` + missing `config` import + hardcoded model.
- Schema/ProGuard drift: missing `900.json` export (`schemas/…/AppDatabase/` has `2,3,4,5,6,901,902.json`), stale `android.arch` dontwarns, `packaging.resources.excludes` hardcodes protobuf 4.35.1 assumptions.
- Direction: Media3/SAF migration plan; `docs/extractor-fork.md` (upstream pin, YouTube-only decision, merge procedure) + tagged nanojson; delete dead export or ship it with tests; version catalog (`libs.versions.toml`); commit/document missing schema.

## Suggested priority

1. Extractor split (#3, #4, #7) — total-playback breakage surface.
2. `VideoDetailFragment` + navigator + player boundary (#9–#11) — blocks testability.
3. Repository layering + download/feed consolidation (#13–#16).
4. CI + `versionCode` + gate holes (#1, #2, #20) — cheap, unlocks enforcement.
5. Media3/fork-sync plan (#21) before the next YouTube breakage forces an emergency port.
