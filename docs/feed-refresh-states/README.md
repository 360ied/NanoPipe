# What's New: non-blocking refresh (state-split design)

How the What's New (`FeedFragment`) refresh path works today, why a
refresh blanks the video list, and a proposed redesign that splits
initial load from refresh so the list stays visible and interactive.

- Status: proposal / draft, not implemented (rev. 3, 2026-11-05:
  addresses pedantic review passes 1–2 — see §9).
- Scope: `PipePipeClient/app/src/main/java/org/schabi/newpipe/local/feed/`
  (`FeedFragment.kt`, `FeedViewModel.kt`, `FeedState.kt`,
  `FeedDatabaseManager.kt`, `service/FeedEventManager.kt`,
  `service/FeedLoadService.kt`, `service/FeedLoadManager.kt`),
  `PipePipeClient/app/src/main/java/org/schabi/newpipe/fragments/BaseStateFragment.java`,
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

1. `FeedFragment.reloadContent()` (`FeedFragment.kt:1142`) fires
   `FeedLoadService` with `EXTRA_GROUP_ID` and clears `listState`.
   `doInitialLoadLogic()` is overridden to a no-op
   (`FeedFragment.kt:1140`), which neutralizes `BaseStateFragment`'s
   default `startLoading(true)` — so first paint is purely the
   `startWithItem(IdleEvent)` replay in the `ViewModel` driving a DB
   read, with no service load involved.
2. `FeedLoadService` single-flights the load: if `loadingDisposable !=
   null`, a second start intent is silently dropped (`FeedLoadService.kt`
   `onStartCommand`, `:85`). `FeedLoadManager.startLoading(groupId)`
   computes outdated subscriptions (threshold from
   `feed_update_threshold_key`, 6 parallel extractions), posts
   `ProgressEvent(current, max)` per channel via `NotificationConsumer →
   broadcastProgress()`, buffers inserts (`BUFFER_COUNT_BEFORE_INSERT =
   20`), then `postProcessFeed()` posts
   `ProgressEvent(R.string.feed_processing_message)` twice — once in the
   `doOnSubscribe` block (`FeedLoadManager.kt:299`) and once in the
   `Completable` body (`FeedLoadManager.kt:290`) — and finally posts
   `SuccessResultEvent(itemsErrors)`. Per-channel failures never leave
   the manager: they are captured into `Notification`s /
   `feedResultsHolder` and reported via `itemsErrors`.
3. `FeedEventManager` is a global singleton `BehaviorProcessor` with no
   group id on its events (`service/FeedEventManager.kt:9-35`). Every
   `FeedViewModel` (one per open group tab) reacts to every load.
   The only poster of fatal `ErrorResultEvent` is
   `FeedLoadService.handleError` (`FeedLoadService.kt:221`) — i.e. the
   pipeline `Single` itself erroring (outdated-query failure,
   `DatabaseConsumer` transaction throw, `postProcessFeed` failure).
   Network/extraction failures are per-channel and non-fatal by design.
4. `FeedViewModel` combines `FeedEventManager.events()` with the
   show-played toggle, `notLoadedCount(groupId)`, and
   `oldestSubscriptionUpdate(groupId)` (`combineLatest` +
   `throttleLatest(DEFAULT_THROTTLE_TIMEOUT)`), then maps:
   `Idle/Success → getStreams(groupId, …)` from the DB wrapped as
   `LoadedState`; **any `ProgressEvent` → `ProgressState` with an empty
   list** (`FeedViewModel.kt` `map` block — the DB is not even queried).
   After `Success/Error`, `FeedViewModel` calls
   `FeedEventManager.reset()`, but the trailing `IdleEvent` is posted
   while `ignoreUpstream` is `true` and `events()` filters on exactly
   that flag (`FeedEventManager.kt:17-24`) — so the existing subscriber
   never sees it. Consequence: the latest event stays stuck at
   `SuccessResultEvent`/`ErrorResultEvent`, and any later emission from
   the DB flows or the toggle re-runs the `map` against that stale
   terminal event — replaying old `itemsErrors`, or re-emitting
   `ErrorState` (→ error panel) even after the DB has recovered.
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
6. While `isRefreshing == true`, `listenerStreamItem` drops all
   non-selection clicks and all long-presses: selection-mode clicks
   bypass the gate (`FeedFragment.kt:793-794` toggles and returns before
   the `!isRefreshing` check at `:797`), while long-press has no bypass
   (`:819`).

Per-subscription (non-fatal) errors avoid the wipe only when they are
surfaced at all: `LoadedState.itemsErrors` goes to `handleItemsErrors()`
→ per-channel dialogs/snackbar, list stays — but that call is gated on
`oldestSubscriptionUpdate` having *changed* since the last `LoadedState`
(`FeedFragment.kt:871-875`), so a refresh whose failures leave the
oldest timestamp untouched reports success with zero user feedback.
Conversely, when both timestamps are null (no subscriptions / fresh DB)
the gate's second arm fires on *every* `LoadedState`, replaying errors
rather than gating them.

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
  but `postProcessFeed()` still posts processing `ProgressEvent`s +
  `SuccessResultEvent`, so the list hides and reappears for zero work.
- **Events are global, view-models are per-group.** A load for group B
  drives `ProgressState` (today: blank) on group A's fragment too.
  Any refresh indicator must be scoped or it will show spuriously on
  unrelated tabs.
- **Stuck terminal events.** The swallowed reset (see §2 step 4) leaves
  the latest event at `Success`/`Error`, replaying stale `itemsErrors`
  or resurrecting the error panel on later unrelated recombines.
- **Fatal-error wipe is rare but total.** Only pipeline failures (DB /
  transaction, via `FeedLoadService.kt:221`) reach the error panel —
  but when they do, a perfectly good stale list is replaced instead of
  kept with a retry affordance.
- **Toggle-during-refresh blanks too.** The `map` keys off the *latest*
  event, so toggling show-played mid-refresh (latest = `ProgressEvent`)
  yields an empty-list `ProgressState`. Non-blocking toggle only holds
  when idle.

## 3. Design goal

Split the conflated "loading" into explicit states:

| State | List | Indicator | Interaction |
|---|---|---|---|
| Initial load (empty adapter, first `Progress`) | hidden | centered bar + text (as today) | n/a |
| Refresh with content | **visible, kept** | swipe spinner + determinate `x/y` in header subtitle | fully interactive |
| Loaded / up to date | visible | header shows `feed_oldest_subscription_update` | interactive |
| Empty (loaded, 0 items) | `empty_state_view` | as today | n/a |
| Fatal error, empty adapter | error panel (as today) | — | retry button |
| Fatal error, with content | **stale list kept** | snackbar + header retry affordance | interactive |

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
  `Error + rows → LoadedState(rows)` plus a separately delivered fatal
  error the fragment renders non-destructively (see 4.3).
- Scope events by group. The sender side (`FeedLoadManager`, which
  receives the `groupId` in `startLoading`, including the
  `NotificationWorker` path with `GROUP_NOTIFICATION_ENABLED`) attaches
  the id to each posted event; the receiver side (the `combineLatest` in
  `FeedViewModel.kt:44-55`) ignores events whose id matches neither its
  own `groupId` nor `GROUP_ALL_ID` semantics (an all-subscriptions load
  refreshes every tab's contents). Events tagged
  `GROUP_NOTIFICATION_ENABLED` (`-2`, the `NotificationWorker` path)
  match no tab and are ignored by all fragment view-models. Without this, background loads and
  other tabs' refreshes light up this tab's indicator.
- Replace stuck-terminal-event semantics (decision: local replacement,
  no `FeedEventManager` protocol change): after consuming a terminal
  event, the `ViewModel` substitutes `IdleEvent` for the stored event and
  the `reset()` call is removed — its emission is self-suppressed anyway
  (see §2 step 4). Later DB/toggle recombines then render plain
  `LoadedState` instead of replaying stale `itemsErrors` or resurrecting
  `ErrorState`.
- No-op refresh: suppress the hide/show round-trip when the load had no
  outdated subscriptions. Normative gate: treat a load as contentless
  when the only progress seen is `current == -1 && max == -1 &&
  progressMessage == R.string.feed_processing_message` (processing
  events are always built with `(-1, -1)`; `maxProgress` is otherwise a
  non-empty list size, so never gate on `maxProgress == 0`).

### 4.3 `FeedFragment` rendering

- `handleProgressState` / `showLoading`: full-screen hide **only if
  `groupAdapter.itemCount == 0`**. Otherwise: keep the adapter,
  `swipeRefreshLayout.isRefreshing = true`, and route determinate
  progress into the existing header. Normative header rules during
  refresh-with-content (see 4.4 for the views):
  - `refresh_subtitle_text` shows `Refreshing %1$d/%2$d` (new
    `feed_refreshing_progress` string) whenever `max > 0`; indeterminate
    phases (`current == -1`) show the `progressMessage` text instead.
    `refresh_text` (the oldest-update line) is unchanged during refresh.
  - When `notLoadedCount > 0` simultaneously applies, the subtitle is
    the refreshing text plus the existing `Not loaded: %d` line joined
    by a new `feed_refreshing_with_not_loaded` format (`%1$s · %2$s`),
    so translators own the separator. Combined lines may ellipsize at
    `maxLines=1` — acceptable, determinate counts stay short.
  - On settle (`LoadedState`/`ErrorState`), the subtitle reverts to the
    `notLoadedCount`-or-gone rule and `updateRefreshViewState()`.
- Remove the `!isRefreshing` gates in `listenerStreamItem` with no
  replacement: no modal case is identified, and adapter items are
  immutable snapshots.
- Fatal errors: if the adapter is non-empty, do not call `showError()`
  (panel). Instead snackbar the error and leave the stale list + a
  header retry: override `handleError()`'s hiding of `refreshRootView`
  so the header stays visible with subtitle `Refresh failed — tap to
  retry` (new string resource; tapping calls `reloadContent()` as
  today). Keep the panel for the empty-adapter case.
- `handleLoadedState` stays the diff/update path; keep
  `selection.retainOnly(…)`, `updateRefreshViewState()`,
  `playlist_control` visibility, and `highlightNewItemsAfter()` — which
  becomes *more* correct once the list is no longer wiped underneath
  it. Additionally: after rebuilding `originalItems`, call
  `filterItems(editText?.text.orEmpty())` instead of pushing the
  unfiltered list (completion currently orphans the query text in the
  action bar, `FeedFragment.kt:842-852`). `filterItems` restores
  `isFilterEnabled` semantics and scrolls to top;
  `selection.retainOnly(…)` keeps applying before the filter as today. And surface `itemsErrors` whenever the
  error set changes, dropping the `oldestUpdate`-changed gate that
  silently swallows failures today.
- `reloadContent()`: keep firing the service + `hideNewItemsLoaded`;
  additionally record `refreshRequestedAt`. Stuck-spinner rule: if **no
  event at all** arrives within 10 s of `reloadContent()`, clear the
  swipe spinner and snackbar with retry (covers the single-flight drop,
  §2 step 2). Once any `ProgressEvent` has arrived, spinner lifetime is
  bound to the terminal event instead — slow many-subscription loads
  must never time out. Bounded instead by a terminal timeout: if no
  terminal event arrives within 60 s of the last `ProgressEvent`, treat
  the load as failed — clear the spinner, snackbar, keep the stale
  list. 60 s is safe because active loads emit per-channel progress far
  more often (6-way parallel); only a dead service or lost terminal
  trips it.

### 4.4 Layout (`fragment_feed.xml`)

Minimal: no new views required. `refresh_subtitle_text` carries the
refresh progress per §4.3; `refreshIcon` spins via an `ObjectAnimator`
(`rotation` 0→360, 1000 ms, linear, infinite) started when
refresh-with-content begins and cancelled in `hideLoading()` /
`handleError()` / `showEmptyState()`. The centered
`loading_progress_bar/text` is reserved strictly for the empty-adapter
initial load. Optional follow-up: a thin determinate `ProgressBar`
under `refresh_root_view` for long refreshes (many subscriptions ×
6-way parallel + per-service `feedFetchInterval` delays, which can
stall progress between counts).

### 4.5 Concurrency and lifecycle notes

- `FeedLoadService` single-flight drops overlapping group loads (and
  `loadingDisposable` is never nulled, so drops continue until the
  service is destroyed); the fragment tolerates `Progress` never
  arriving via the §4.3 10 s rule, and ignores other groups' terminal
  events via §4.2 scoping.
- Rotation mid-refresh: `listState` save/restore already exists; with
  the list kept visible there is no restore jump. Decision: set `isLoading`
  in `handleProgressState` / `hideLoading` / `showEmptyState` /
  `handleError` (it is currently dead in the feed path — only written by
  `startLoading()`, which the feed never calls) so a recreated fragment
  re-derives the header indicator; the fragment-scoped `ViewModel`
  replaying the latest event covers the data.
- Cancel (`cancelSignal` from the notification action) currently flows
  into `postProcessFeed()` and is reported as `Success` (partial).
  Decision: `SuccessResultEvent` gains `cancelled: Boolean = false`;
  the fragment skips the new-items celebration
  (`highlightNewItemsAfter` + pill) when true. Cancelled loads stay
  visible as loads but are never presented as successful refreshes.
- Show-played toggle must stay service-independent (re-query DB, no
  `ProgressEvent` when idle, as today); with §4.2 it additionally stays
  non-blanking mid-refresh because progress now carries rows.

## 5. Alternatives considered

- **A. Minimal (don't hide a populated list):** gate `showLoading()`'s
  hide on `itemCount == 0`, drop click gates, snackbar fatal errors
  when non-empty. ~10 lines, fixes most of the annoyance, but leaves
  determinate progress invisible and the global-event / stuck-terminal
  quirks in place.
- **B. Header-anchored progress only:** A + header `x/y` text and
  spinning icon. A strict subset of §4; a sensible first slice if C
  stalls.
- Rejected: skeleton/shimmer rows — heavy for a local-DB list that
  already has real rows to show; the stale list *is* the placeholder.

## 6. Implementation slices

1. Fragment-only (A): conditional hide, click gates, fatal-error
   snackbar when non-empty. No `ViewModel`/`State` shape change.
2. Header progress (B): route progress counts into
   `refresh_subtitle_text` per §4.3 rules + icon spin per §4.4.
3. State split (C core): `RefreshingState`, ViewModel always queries
   DB, group-scoped events (incl. `-2` ignore), local terminal-event
   replacement + `reset()` removal, `cancelled` flag, error-gate
   removal, filter re-apply on `LoadedState`.
4. Polish: spinner rules (10 s no-event + 60 s terminal), thin header
   progress bar, `isLoading` wiring, header strings.

## 7. Test plan (manual)

- Cold start with subscriptions → full-screen initial load, then list.
- Pull-to-refresh with content → list stays, stays scrollable and
  tappable; swipe spinner + header `Refreshing x/y`; no jump on
  completion; new items highlighted + pill appears.
- Refresh when all subs fresh → no flicker (no hide/show round-trip).
- Refresh with 0 subscriptions → empty state, no spinner hang.
- Per-channel failure (airplane mode, terminated channel) with content
  → list kept, per-channel dialog/snackbar as today; with empty adapter
  → empty state + per-channel dialog/snackbar. (These paths are
  non-fatal by design; they exercise the `itemsErrors` gate removal,
  not the fatal-error path.)
- Fatal pipeline failure with content → snackbar, stale list kept,
  header retry works; with empty adapter → error panel. (Hard to hit
  manually — DB/transaction failure; verify by inspection if needed.)
- Per-channel failure that leaves `oldestUpdate` unchanged → errors
  still surface (gate removal check).
- Rotation mid-refresh → list + header indicator survive, no duplicate
  loads.
- Group tabs: refresh group B while viewing group A → A shows nothing.
- Toggle "show played items" mid-refresh → instant, no blanking.
- Search filter active across refresh completion → query preserved and
  re-applied to new rows.
- Notification-triggered background load while fragment visible →
  no spurious indicator on unrelated tabs.
- Same-group refresh re-tapped while one is in flight → settles on the
  first load's terminal event (events arrived, so the 10 s rule is
  moot). Cross-group re-tap → the other group's terminal is ignored per
  §4.2 and the 10 s timer applies to the new request.
- Service killed mid-refresh → 60 s terminal timeout clears the spinner
  with snackbar + retry; stale list kept.

## 8. Open questions

- Header copy: `Refreshing 12/48` + `· Not loaded: n` composition
  decided (§4.3); remaining is translator review of the new format
  strings, not the shape. Per-channel names stay notification-only.
- Throttle interplay: `throttleLatest` on the combined flow can delay
  the final `LoadedState` behind a trailing `ProgressEvent` — verify the
  spinner clears promptly on fast refreshes.
- `FeedLoadManager.retry` block currently always returns `false`
  (dead code path) — out of scope, but adjacent if touching that file.
- `FeedViewModel` couples to the `FeedEventManager` singleton and
  `AndroidSchedulers.mainThread()`, so the §4.2 mapping is not
  unit-testable as specified. Decide in slice 3: add a scheduler /
  event-source seam, or record the mapping as manually verified.

## 9. Revision history

- Rev. 2 (2026-11-05): addresses pedantic review pass 1 —
  corrected `reloadContent()` reference (`:1142`) and the neutralized
  initial-load path; fixed `ErrorResultEvent` attribution to
  `FeedLoadService`; replaced the disproven double-`LoadedState` claim
  with stuck-terminal-event semantics; fixed the no-op gate
  (`(-1, -1)` + message, not `maxProgress == 0`); noted the double
  processing post and the `itemsErrors` gate; made header composition,
  spinner timeout, group-scoping locus, and rotation re-attach
  normative; corrected toggle/filter test expectations.
- Rev. 3 (2026-11-05): addresses pedantic review pass 2 —
  selection-bypass gate wording; null–null gate arm; `-2` scoping rule;
  60 s terminal timeout; fatal-with-content header visibility and retry
  string; same-group vs cross-group re-tap settle rules; local
  terminal-replacement decision with `reset()` removal; `cancelled`
  flag decision; `filterItems` call-site and header string/truncation
  specifics; `isLoading` wiring decision.
