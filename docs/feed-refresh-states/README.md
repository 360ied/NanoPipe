# What's New: non-blocking refresh (state-split design)

How the What's New (`FeedFragment`) refresh path works today, why a
refresh blanks the video list, and a proposed redesign that splits
initial load from refresh so the list stays visible and interactive.

- Status: proposal / draft, not implemented.
- Scope: `PipePipeClient/app/src/main/java/org/schabi/newpipe/local/feed/`
  (`FeedFragment.kt`, `FeedViewModel.kt`, `FeedState.kt`,
  `FeedDatabaseManager.kt`, `service/FeedEventManager.kt`,
  `service/FeedLoadService.kt`, `service/FeedLoadManager.kt`),
  `.../fragments/BaseStateFragment.java`,
  `PipePipeClient/app/src/main/res/layout/fragment_feed.xml`.
- All paths below are relative to the repository root.
- This is a hard fork: no need to keep the design mergeable upstream.

## 1. Background

On the What's New page, pulling to refresh or tapping the refresh icon
hides the whole video list behind a centered progress bar + text
(`∞/∞` or `12/48`), and list clicks/long-presses are ignored until the
load finishes. There is no data reason for this: the previously loaded
feed rows are still in the DB and in the adapter. It is inherited
upstream NewPipe behavior — the generic "loading = hide content, show
spinner" default from `BaseStateFragment` applied to refresh too, where
the `SwipeRefreshLayout` spinner alone would do.

## 2. Current pipeline (as-is)

End to end, a refresh does this:

1. `FeedFragment.reloadContent()` (`FeedFragment.kt:761`) fires
   `FeedLoadService` with `EXTRA_GROUP_ID` and clears `listState`.
   (`doInitialLoadLogic()` is a no-op; initial load is driven by the
   `IdleEvent → LoadedState` path below, plus `startLoading(true)` from
   `BaseStateFragment.onViewCreated`.)
2. `FeedLoadService` single-flights the load: if `loadingDisposable !=
   null`, a second start intent is silently dropped (`FeedLoadService.kt`
   `onStartCommand`). `FeedLoadManager.startLoading(groupId)` computes
   outdated subscriptions (threshold from `feed_update_threshold_key`,
   6 parallel extractions), posts `ProgressEvent(current, max)` per
   channel via `NotificationConsumer → broadcastProgress()`, buffers
   inserts (`BUFFER_COUNT_BEFORE_INSERT = 20`), then `postProcessFeed()`
   posts `ProgressEvent(R.string.feed_processing_message)` and finally
   `SuccessResultEvent(itemsErrors)` (or `ErrorResultEvent` on fatal
   failure).
3. `FeedEventManager` is a global singleton `BehaviorProcessor` with no
   group id on its events (`service/FeedEventManager.kt`). Every
   `FeedViewModel` (one per open group tab) reacts to every load.
4. `FeedViewModel` combines `FeedEventManager.events()` with the
   show-played toggle, `notLoadedCount(groupId)`, and
   `oldestSubscriptionUpdate(groupId)` (`combineLatest` +
   `throttleLatest(DEFAULT_THROTTLE_TIMEOUT)`), then maps:
   `Idle/Success → getStreams(groupId, …)` from the DB wrapped as
   `LoadedState`; **any `ProgressEvent` → `ProgressState` with an empty
   list** (`FeedViewModel.kt` `map` block — the DB is not even queried).
   After `Success/Error`, `FeedEventManager.reset()` posts a trailing
   `IdleEvent`, i.e. every successful refresh emits `LoadedState`
   twice (once from `SuccessResultEvent`, once from the reset `Idle`).
5. `FeedFragment.handleResult()` renders: `ProgressState →
   handleProgressState() → showLoading()` hides the `RecyclerView`
   (`animateHideRecyclerViewAllowingScrolling()` — alpha to 0, still
   touch-capturing, see `ktx/View.kt:341`), shows the centered
   `loading_progress_bar/text`, sets `swipeRefreshLayout.isRefreshing`
   and `isRefreshing = true`. `LoadedState` rebuilds `originalItems`,
   `updateAsync`s the adapter, restores `listState`, and calls
   `hideLoading()` (or `showEmptyState()` when empty). `ErrorState` with
   a non-null error calls `showError()` → full error panel (which also
   hides the list via `handleError()`).
6. While `isRefreshing == true`, `listenerStreamItem` drops all clicks
   and long-presses (`FeedFragment.kt` click/long-click listeners).

Per-subscription (non-fatal) errors already avoid the wipe:
`LoadedState.itemsErrors` goes to `handleItemsErrors()` → per-channel
dialogs/snackbar, list stays. Only the fatal `ErrorResultEvent` path
blanks the screen.

### 2.1 Defects / quirks the redesign should fix

- **Refresh blanks a healthy list.** `ProgressState` carries no items
  and `showLoading()` hides the adapter even though the rows are still
  valid. Scroll position is saved/restored via `listState`, but the
  hide/show round-trip is jarring and destroys the context the "new
  items" pill (`new_items_loaded_button` +
  `highlightNewItemsAfter()`) depends on.
- **Interaction is blocked for no reason.** `isRefreshing` gates clicks.
  Adapter items are immutable snapshots; opening a stale row mid-refresh
  is harmless.
- **No-op refreshes flicker.** When every subscription is within the
  update threshold, `outdatedSubscriptions` is empty and filtered out —
  but `postProcessFeed()` still posts `ProgressEvent(processing)` +
  `SuccessResultEvent`, so the list hides and reappears for zero work.
- **Events are global, view-models are per-group.** A load for group B
  drives `ProgressState` (today: blank) on group A's fragment too.
  Any refresh indicator must be scoped or it will show spuriously on
  unrelated tabs.
- **Double `LoadedState` per refresh** (Success + reset Idle) causes a
  redundant `groupAdapter.updateAsync` diff each cycle.
- **Error wipes stale content.** A failed refresh replaces a perfectly
  good list with the error panel instead of a snackbar + retry affordance
  in the header.

## 3. Design goal

Split the conflated "loading" into explicit states:

| State | List | Indicator | Interaction |
|---|---|---|---|
| Initial load (empty adapter, first `Progress`) | hidden | centered bar + text (as today) | n/a |
| Refresh with content | **visible, kept** | swipe spinner + determinate `x/y` in header subtitle | fully interactive |
| Loaded / up to date | visible | header shows `feed_oldest_subscription_update` | interactive |
| Empty (loaded, 0 items) | `empty_state_view` | as today | n/a |
| Error, empty adapter | error panel (as today) | — | retry button |
| Error, with content | **stale list kept** | snackbar + header retry affordance | interactive |

## 4. Proposed changes (option C: full state split)

### 4.1 `FeedState`: carry retained items through progress

`ProgressState` must stop meaning "nothing to show". Two shapes work:

- **Preferred:** new `RefreshingState(items, currentProgress,
  maxProgress, progressMessage, oldestUpdate, notLoadedCount)` emitted
  while a load is in flight *and* the DB already returned rows; keep
  bare `ProgressState` for the truly-empty initial load. Explicit,
  impossible to misrender, but touches every `when (result)`.
- **Smaller:** add `isRefreshing: Boolean = false` + nullable progress
  fields to `LoadedState`. One fewer branch, but every reader must check
  the flag; easier to regress into the old behavior.

Either way `ProgressEvent` handling in `FeedViewModel` must query
`getStreams()` like the `Idle/Success` path does today instead of
substituting `arrayListOf()`.

### 4.2 `FeedViewModel`

- Always resolve the DB list first, then attach the event: `Idle →
  LoadedState`, `Progress + rows → RefreshingState(rows, progress…)`,
  `Progress + no rows → ProgressState`, `Success → LoadedState(+errors)`,
  `Error + rows → LoadedState(rows, fatalError?)` or an
  `ErrorState` the fragment renders non-destructively (see 4.3).
- Scope events by group: add `groupId` to `FeedEventManager.Event`
  (or filter `ProgressEvent`s against the ViewModel's `groupId` in
  `startLoading`). Without this, background `NotificationWorker` loads
  (`GROUP_NOTIFICATION_ENABLED`) and other tabs' refreshes will light up
  this tab's indicator.
- Consider suppressing the trailing `IdleEvent` after `reset()` when the
  emitted rows are unchanged (kills the double-diff), and debouncing the
  no-op-refresh `ProgressEvent(processing)` when `maxProgress == 0`.

### 4.3 `FeedFragment` rendering

- `handleProgressState` / `showLoading`: full-screen hide **only if
  `groupAdapter.itemCount == 0`**. Otherwise: keep the adapter,
  `swipeRefreshLayout.isRefreshing = true`, and route determinate
  progress (`current/max`, `progressMessage`) into the existing header —
  `refresh_subtitle_text` (today: `Not loaded: %d`) — plus a spinning
  `refreshIcon`. Do not touch `originalItems`/`filteredItems` on the
  progress path, so the in-progress search filter keeps working.
- Remove the `!isRefreshing` gates in `listenerStreamItem`; keep modal
  behavior only for the integer-overflow-style cases that genuinely need
  it (none identified).
- `handleErrorState` / `handleError`: if the adapter is non-empty, do not
  call `showError()` (panel). Instead snackbar the fatal error and leave
  the stale list + a header retry (`refreshRootView` click already calls
  `reloadContent()`). Keep the panel for the empty-adapter case.
- `handleLoadedState` stays the diff/update path; keep
  `selection.retainOnly(…)`, `updateRefreshViewState()`,
  `notLoadedCount` subtitle (compose with progress text during refresh:
  e.g. `Refreshing 12/48…` replaces, rather than appends to, the
  not-loaded line), `playlist_control` visibility, and
  `highlightNewItemsAfter()` — which becomes *more* correct once the
  list is no longer wiped underneath it.
- `reloadContent()`: keep firing the service + `hideNewItemsLoaded`;
  additionally record `refreshRequestedAt` so a no-op service drop
  (single-flight, §2 step 2) can clear a stuck swipe spinner via timeout.

### 4.4 Layout (`fragment_feed.xml`)

Minimal: no new views required. Reuse `refresh_subtitle_text` for
determinate progress and rotate `refreshIcon` during refresh. Optional
follow-up: a thin determinate `ProgressBar` under `refresh_root_view`
for long refreshes (many subscriptions × 6-way parallel), keeping the
centered bar strictly for initial load.

### 4.5 Concurrency and lifecycle notes

- `FeedLoadService` single-flight drops overlapping group loads; the
  fragment must tolerate `Progress` never arriving (spinner timeout) and
  `Success` arriving for another group's load (ignore via 4.2 scoping).
- Rotation mid-refresh: `listState` save/restore already exists; with the
  list kept visible there is no restore jump. `wasLoading`/`isLoading` in
  `BaseStateFragment` should align with the new `RefreshingState` so a
  recreated fragment re-attaches the header indicator from the
  `BehaviorProcessor` replay.
- Show-played toggle path already re-queries the DB without a
  `ProgressEvent` (non-blocking today) — the redesign must preserve that
  property; do not funnel it through the service path.

## 5. Alternatives considered

- **A. Minimal (don't hide a populated list):** gate `showLoading()`'s
  hide on `itemCount == 0`, drop click gates, snackbar errors
  when non-empty. ~10 lines, fixes most of the annoyance, but leaves
  determinate progress invisible and the global-event / double-emit
  quirks in place.
- **B. Header-anchored progress only:** A + header `x/y` text and
  spinning icon. A strict subset of §4; a sensible first slice if C
  stalls.
- Rejected: skeleton/shimmer rows — heavy for a local-DB list that
  already has real rows to show; the stale list *is* the placeholder.

## 6. Implementation slices

1. Fragment-only (A): conditional hide, click gates, error-with-content
   snackbar. No `ViewModel`/`State` shape change.
2. Header progress (B): route `ProgressState` counts into
   `refresh_subtitle_text` + icon spin.
3. State split (C core): `RefreshingState`, ViewModel always queries DB,
   group-scoped events, drop double-emit.
4. Polish: spinner timeout for dropped single-flight starts, thin header
   progress bar, rotation re-attach.

## 7. Test plan (manual)

- Cold start with subscriptions → full-screen initial load, then list.
- Pull-to-refresh with content → list stays, stays scrollable and
  tappable; swipe spinner + header `x/y`; no jump on completion; new
  items highlighted + pill appears.
- Refresh when all subs fresh → no flicker (no hide/show round-trip).
- Refresh with 0 subscriptions → empty state, no spinner hang.
- Airplane-mode refresh with content → snackbar, stale list kept,
  header retry works; with empty adapter → error panel.
- Per-channel failure (e.g. terminated channel) → existing
  per-item dialog/snackbar path unchanged.
- Rotation mid-refresh → list + indicator survive, no duplicate loads.
- Group tabs: refresh group B while viewing group A → A shows nothing.
- Toggle "show played items" and search filter during refresh →
  instant, non-blocking, filter preserved across `LoadedState`.
- Notification-triggered background load while fragment visible →
  no spurious full-screen load.

## 8. Open questions

- Should the header show per-channel names during refresh (the service
  notification already does via `updateDescription`), or is `x/y`
  enough? Per-channel text churns fast at 6-way parallelism.
- Throttle interplay: `throttleLatest` on the combined flow can delay
  the final `LoadedState` behind a trailing `ProgressEvent` — verify the
  spinner clears promptly on fast refreshes.
- `FeedLoadManager.retry` block currently always returns `false`
  (dead code path) — out of scope, but adjacent if touching that file.
