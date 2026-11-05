# What's New: non-blocking refresh (state-split design)

How the What's New (`FeedFragment`) refresh path works today, why a
refresh blanks the video list, and a proposed redesign that splits
initial load from refresh so the list stays visible and interactive.

- Status: proposal / draft, not implemented (rev. 8, 2026-11-05:
  addresses pedantic review pass 7 — see §9).
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
feed rows are still in the DB and in the adapter. The feed's
`showLoading()` override hides the list (following the upstream loading
pattern — `BaseStateFragment.showLoading()` itself only animates
`emptyStateView`/`loadingProgressBar` and hides the error panel, never
touching the `RecyclerView`), where the `SwipeRefreshLayout` spinner
alone would do.

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
   `throttleLatest(DEFAULT_THROTTLE_TIMEOUT)` (120 ms, `util/Constants.kt:8`;
  use site `FeedViewModel.kt:55`), then maps:
   `Idle/Success → getStreams(groupId, …)` from the DB wrapped as
   `LoadedState`; **any `ProgressEvent` → `ProgressState` with an empty
   list** (`FeedViewModel.kt` `map` block — the DB is not even queried).
   After `Success/Error`, `FeedViewModel` calls
   `FeedEventManager.reset()`, but the trailing `IdleEvent` is posted
   while `ignoreUpstream` is `true` and `events()` filters on exactly
   that flag (`FeedEventManager.kt:18-20` filter, `:22-26` reset) — so the existing subscriber
   never sees it. Consequence: the existing subscriber's cached event stays
   stuck at `SuccessResultEvent`/`ErrorResultEvent` (the processor store
   itself becomes `Idle` — a freshly subscribed VM replays `Idle` via
   `startWithItem` — only the live `combineLatest` cache is stuck), and
   any later emission from the DB flows or the toggle re-runs the `map` against that stale
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
   bypass the gate (`FeedFragment.kt:793-795` toggles and returns before
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
  valid. The hide/show round-trip is jarring and destroys the context
  the "new items" pill (`new_items_loaded_button` +
  `highlightNewItemsAfter()`) depends on. (Note: `listState`
  save/restore covers pause/rotation — `reloadContent()` nulls
  `listState`, so the restore in `handleLoadedState` is a no-op on the
  refresh path itself.)
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
  unrelated tabs. (DB flows are per-`groupId` too, but the `GROUP_ALL`
  tab observes all subscriptions — see §4.2 for the exact scoping
  table.)
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
- **Empty-state click gate sticks.** `showEmptyState()` clears
  `swipeRefreshLayout.isRefreshing` but never clears `isRefreshing`
  (unlike `hideLoading()`/`handleError()`), so after an empty settle
  the click gate stays armed until the next non-empty `LoadedState`.
  Slice 1 fixes this (one line).

## 3. Design goal

Split the conflated "loading" into explicit states:

| State | List | Indicator | Interaction |
|---|---|---|---|
| Initial load (empty adapter, first `Progress`) | hidden | centered bar + text (as today) | n/a |
| Refresh with content | **visible, kept** | swipe spinner (when pull-to-refresh enabled) + determinate `x/y` in header subtitle + spinning `refreshIcon` | fully interactive (clicks + long-press; selection entry/invert allowed) |
| Refresh with content, pull-to-refresh disabled | **visible, kept** | header `x/y` + spinning icon only (no swipe spinner — `swipeRefreshLayout.isEnabled == false`) | fully interactive; header taps still `reloadContent()` |
| Loaded / idle (possibly stale, no load in flight) | visible | header shows `feed_oldest_subscription_update` | interactive |
| Empty (loaded, 0 unfiltered items; covers load-empty and toggle-to-empty when query empty) | `empty_state_view` | as today | n/a |
| Filtered-empty (DB non-empty, query matches 0) | empty visible list via `hideLoading()` (RV visible, 0 rows); `empty_state_view` stays hidden (reserved for truly-empty); `playlist_control` hidden | subtitle `feed_no_filter_results` ("No matches — clear search", TalkBack-mirrored) so blank ≠ stalled | interactive (clear search to return) |
| Selection active across refresh | **visible, kept** (`retainOnly` drops vanished uids) | spinner/header as per refresh-with-content; toolbar coexists | selection + toolbar stay usable; pill/highlight suppressed while `selection.isActive` |
| Cancelled (partial, `cancelled == true`) | **stale list kept** (partial inserts remain, tail flushed) | settles to idle header (no pill, no highlight) | interactive |
| No-op refresh, suppressed (only `(-1,-1)` + processing message seen) | **visible, kept** | no indicator change (no spinner path, subtitle untouched — VM re-emits last `LoadedState`) | interactive |
| Optimistic pending (reload fired, no scoped `Event` yet) | **visible, kept** | swipe spinner (optimistic bit) + header keeps last subtitle until first scoped `Event` | interactive |
| Stuck, 10 s no scoped `Event` | **stale list kept** | spinner cleared, snackbar + `Retry`, header failed-sticky | interactive (header retry / snackbar action) |
| Terminal-timeout, 60 s no terminal | **stale list kept** | spinner cleared, snackbar + `Retry`, header failed-sticky | interactive (header retry / snackbar action) |
| Late-arrival reconcile (`Progress` after 10 s failure) | **visible, kept** | spinner re-lit, stuck-snackbar dismissed, 60 s tracking resumed | interactive |
| Fatal error, empty adapter | error panel (as today) | — | retry button |
| DB-flow death (upstream Room flow errors, parked via `onErrorResumeNext`) | frozen last list (no new states) + logged error | last header state persists | interactive (list usable; recovers on fragment/VM recreate) |
| Membership mutation idle/mid-refresh (group deleted, sub moved/removed, external unsubscribe) | **visible, kept** | silent `LoadedState` re-derive, no spinner/pill | interactive; selection `retainOnly` drops vanished uids |
| Long-press dialog open across refresh/terminal/rotation | dialog stays open (terminal renders underneath); rotation dismisses + re-invokable | underlying list follows refresh rules | interactive (stale snapshot OK; no crash) |
| Fatal error, with content | **stale list kept** | snackbar (`LENGTH_LONG` + `Retry` → `reloadContent()`) + header retry (subtitle `feed_refresh_failed_tap_to_retry`, whole `refresh_root_view` clickable + `refreshIcon` clickable, `contentDescription` set — see §4.3) | interactive |

Notes:

- "Fully interactive" includes entering/inverting selection and opening
  the not-loaded dialog via subtitle long-press during refresh (both
  stay armed — see §4.3).
- Filtered-empty vs truly-empty is distinguished by the *unfiltered*
  list: `loadedState.items.isEmpty()` (unfiltered) → `showEmptyState()`;
  unfiltered non-empty but filtered empty → filtered-empty rule above.
- Toggle-to-empty (show-played off → 0 rows, query empty) takes the
  Empty row (`showEmptyState()`), not filtered-empty.
- `GROUP_ALL` content may update from another group's DB writes without
  that tab's spinner lighting (see §4.2) — content freshness and
  indicator scoping are separate concerns.

## 4. Proposed changes (option C: full state split)

### 4.1 `FeedState`: carry retained items through progress

`ProgressState` must stop meaning "nothing to show". Decision:

- New `RefreshingState(items, currentProgress, maxProgress,
  progressMessage, oldestUpdate, notLoadedCount)` emitted while a load
  is in flight *and* the DB already returned rows; keep bare
  `ProgressState` for the truly-empty initial load. Explicit,
  impossible to misrender, but touches every `when (result)`.
- The `isRefreshing`-on-`LoadedState` alternative is rejected: every
  reader must check the flag; easier to regress into the old behavior.

`ProgressEvent` handling in `FeedViewModel` queries `getStreams()` like
the `Idle/Success` path does today instead of substituting
`arrayListOf()`. Introduced in slice 2 (header progress needs rows +
counts); group-scoping arrives in slice 3a.

### 4.2 `FeedViewModel`

- Always resolve the DB list first, then attach the event: `Idle →
  LoadedState`, `Progress + rows → RefreshingState(rows, progress…)`,
  `Progress + no rows → ProgressState`, `Success → LoadedState(+errors)`,
  `Error + rows → LoadedState(rows)` rendered non-destructively: the VM
  still emits the existing `ErrorState` channel (no new side-channel,
  no new `LiveData` — slice 1 needs none); the fragment branches on
  `itemCount` (see §4.3) and keeps the adapter rows instead of calling
  `showError()`. No separate fatal-error transport exists.
- Scope events by group. Sender side (`FeedLoadManager`, which receives
  the `groupId` in `startLoading`, including the `NotificationWorker`
  path with `GROUP_NOTIFICATION_ENABLED`) attaches the id to every
  posted event (`ProgressEvent`, `SuccessResultEvent`,
  `ErrorResultEvent`; `FeedLoadService.handleError` tags the group it
  was loading). Receiver side filters **upstream** of `combineLatest`
  (so ignored events never enter the combine and cannot suppress
  concurrent DB/toggle emissions — there is no "ignore inside the
  combine" hold problem because non-matching events never become the
  cached event):
  `events().filter { (it.groupId == vm.groupId || it.groupId ==
  GROUP_ALL_ID || (vm.groupId == GROUP_ALL_ID && it.groupId !=
  GROUP_NOTIFICATION_ENABLED)) }` for the indicator path (no `Idle`
  exclusion — every tab's initial paint needs `Idle → LoadedState`).
  Timer-stamp path is a second, narrower tap: the same predicate plus
  `&& it !is IdleEvent`, evaluated at the upstream pass-through
  pre-`throttleLatest` (throttle coalescing must not move `lastProgressAt`).
  `IdleEvent` gains `groupId` too (default `GROUP_ALL_ID`) and is
  explicitly exempt from timer stamping. Foot-gun note: the `GROUP_ALL_ID` default means any
  future poster that forgets to tag fans out to every tab; all new posts
  must set `groupId` explicitly (review gate).
  Receiver truth table:
  - VM(group=A specific): accepts `A` + `GROUP_ALL_ID`; ignores `B`,
    `-2`.
  - VM(group=`GROUP_ALL`): accepts `GROUP_ALL_ID` + any specific group
    (its DB overlaps all groups, so a specific-group load changes its
    data — indicator correctly lights); ignores `-2`.
  - `-2` (`NotificationWorker`): ignored by **all** fragment VMs for
    indicator purposes. DB cross-talk remains and content updates are
    *desired*, but celebration is scoped: a `-2` load that writes B's
    subs recombines the `GROUP_ALL` tab via
    `notLoadedCount(GROUP_ALL)` / `oldestSubscriptionUpdate(GROUP_ALL)`
    / `getStreams(GROUP_ALL)` (which branch on `GROUP_ALL_ID` in
    `FeedDatabaseManager.kt:48,68,189`) into a plain `LoadedState`
    (Idle held → list updates silently). `highlightNewItemsAfter` + pill
    fire **only** when the last scoped event is a non-cancelled
    `Success` with `oldestUpdate` advancement — never on DB-only
    recombines under held `Idle`. "No spurious indicator on unrelated
    tabs" (§7) therefore means no spinner/counts/pill — not frozen
    content. Disjoint specific tabs (`A` viewing `B`-load) see neither
    indicator nor content change (their DB flows are group-filtered).
  - Interaction with `throttleLatest(DEFAULT_THROTTLE_TIMEOUT = 120ms,
    FeedViewModel.kt:55)`: terminal delivery (and hence spinner clear)
    may lag a trailing `ProgressEvent` by one throttle window.
    Acceptance (§7) allows terminal + 500 ms.
- Replace stuck-terminal-event semantics (decision: local replacement,
  no `FeedEventManager` protocol change): after consuming a terminal
  event, the `ViewModel` substitutes `IdleEvent` for the stored event
  and the `reset()` call is removed — its emission is self-suppressed
  anyway (see §2 step 4). Later DB/toggle recombines then render plain
  `LoadedState` instead of replaying stale `itemsErrors` or resurrecting
  `ErrorState`.
- No-op refresh (slice 2 owns this): suppress the hide/show round-trip
  when the load had no outdated subscriptions. Withholding rule: the VM
  emits nothing observable for processing-only `Progress` (`(-1,-1)`)
  while rows exist — no `RefreshingState`, so the fragment never enters
  the spinner path (evaluating "at terminal time" would be too late).
  If the gate below holds at the terminal `Success`, the VM emits a fresh
  DB query wrapped as `LoadedState` (not a cached instance) and the
  fragment takes no spinner path at all. Reset entry:
  `vm.onReloadRequested()` (called by fragment `reloadContent()`; also the
  writer for `refreshRequestedAt` — fragment never touches the fields
  directly). Holder: `ViewModel`
  `seenNonProcessingProgress: Boolean`, set on any `Progress`
  with `max > 0 || current >= 0` (unscoped in slice 2, scoped from 3a),
  reset on `reloadContent()` and on any terminal event. Normative gate:
  treat a load as contentless when the
  only progress seen is `current == -1 &&
  max == -1 && progressMessage ==
  R.string.feed_processing_message` (processing events are always built
  with `(-1, -1)`; `maxProgress` is otherwise a non-empty list size, so
  never gate on `maxProgress == 0`). Slice-2 evaluation is unscoped
  (known limitation: a cross-group `(0, N)` in flight would set the flag
  and reintroduce flicker for the fresh tab; slice 3a scoping removes
  it). Acceptance: fresh-only refresh
  never toggles `RecyclerView` visibility; `loading_progress_bar` stays
  `GONE` when `itemCount > 0`.
- Stream-death guard (slice 3b): the `combineLatest.map` DB query
  (`blockingGet`) runs inside try/catch — on throw the VM emits
  `ErrorState(throwable)` as a *value* and the subscription survives
  (no `onError` termination of `combineDisposable`, which today has no
  error consumer at `FeedViewModel.kt:55-81` and would die permanently).
  Recovery for the `map` path: the next DB/toggle recombine or
  `reloadContent()` proceeds normally. Upstream Room flows
  (`notLoadedCount`, `oldestSubscriptionUpdate`) get
  `.onErrorResumeNext { e -> log; Flowable.never() }` (park, don't crash:
  a dead DB flow freezes that tab's list with a logged error; recovery is
  fragment/VM recreate). Table notes: the `map` throw is the local-death
  mode (fatal path for current adapter state); a parked upstream is the
  frozen-list mode — distinct §3 rows.
- Test seam + exact plumbing deltas (slice 3a): `FeedEventManager.Event`
  gains `groupId: Long = GROUP_ALL_ID` on every subtype
  (`IdleEvent`, `ProgressEvent`, `SuccessResultEvent`, `ErrorResultEvent`);
  `FeedLoadManager.broadcastProgress(groupId, …)` and
  `postProcessFeed(groupId)` thread it (today `postProcessFeed()` takes
  no arg, `FeedLoadManager.kt:289`); `FeedLoadService.handleError`
  (`:220-221`) tags the group it was loading (`intent EXTRA_GROUP_ID`).
  `GROUP_ALL` sender semantics: a load initiated from the ALL tab sends
  `GROUP_ALL_ID` (fan-out by DB overlap, not by re-posting per group).
  `FeedViewModel` retains its `groupId` ctor arg as `private val groupId`
  (today only captured in closures) and gains optional
  `eventsSource: Flowable<Event> = FeedEventManager.events()` +
  scheduler overrides defaulting to production singletons; `Factory`
  (`FeedViewModel.kt:121-135`) passes through only `context, groupId`
  with defaults otherwise. Push channel (slice 3a): the VM also exposes
  `val timerTicks: SharedFlow<Event> = MutableSharedFlow(extraBufferCapacity = 64)`
  emitted at the timer-stamp tap (pre-throttle) for every passing
  `Progress`/`Success`/`Error`; the fragment collects it in
  `viewLifecycleOwner.lifecycleScope` to cancel/relaunch `timerJob`.
  No replay: rotation re-arms purely from the getters + formulas (§4.5).
  Writers (slice 3b — fields/getters live there; 3a owns only the push
  channel + `groupId` plumbing above): `vm.onReloadRequested(now: Long)` (records `refreshRequestedAt`,
  resets `seenNonProcessingProgress`, clears `failedSticky`) and
  `vm.onScopedEvent(e: Event, now: Long)` (stamps `lastProgressAt`, updates
  `latestScopedEvent`, sets `failedSticky` on fatal). All clocks are
  `SystemClock.elapsedRealtime()` (matches the `FeedRefresh` probes; immune
  to wall-clock changes). Service fix in the same slice (load-bearing
  for re-tap): `FeedLoadService` nulls `loadingDisposable` on dispose
  so a re-tap after the terminal but before `stopSelf()` starts a real
  new load instead of dropping into the destroyed service.

### 4.3 `FeedFragment` rendering

- `handleProgressState` / `showLoading`: full-screen hide **only if
  `groupAdapter.itemCount == 0`**. That gate covers both the list hide
  *and* the `refreshRootView` hide: `showLoading()` today hides the
  header (`refreshRootView.animate(false, 0)`) that the `x/y` subtitle
  lives in, so refresh-with-content must keep the header visible.
  Otherwise: keep the adapter AND the header, set
  `swipeRefreshLayout.isRefreshing = true` only when pull-to-refresh is
  enabled (`isPullToRefreshEnabled`, else skip the swipe bit — setting it
  while `swipeRefreshLayout.isEnabled == false` still shows the spinner
  on some versions), and route determinate progress into the
  existing header. Normative header rules during refresh-with-content
  (views: `refresh_root_view` (already `clickable`, ripple
  `selectableItemBackground`), `refresh_text` (`maxLines=2`),
  `refresh_subtitle_text` (`maxLines=1`, `ellipsize=end`), `refreshIcon`
  (already 48 dp `minWidth`/`minHeight`, borderless ripple)):
  - Subtitle truth table (`refresh_subtitle_text`):
    - `max > 0 && current >= 0` → `Refreshing %1$d/%2$d` (new
      `feed_refreshing_progress`).
    - `max > 0 && current == -1` (mixed — not emitted by the current
      manager; defensive) → determinate template with `current`
      displayed as 0, plus `Log.w("FeedRefresh", ...)` with the raw
      values. Counts are the point; message alone
      would hide them.
    - `max <= 0` (processing `(-1,-1)`; `max == 0` is set on the `maxProgress`
    atomic but never *posted* today: `doOnNext { max = it.size }`
    (`FeedLoadManager.kt:113-117`) precedes `filter { isNotEmpty() }`, and
    the `broadcastProgress()` after the filter (`:118-122`) is what never
    fires for empty — so treat `max == 0` defensively, never as a gate)
    → `progressMessage` text when `!= 0`, else subtitle hidden (not
    `∞/∞` — that is centered-initial-load only).
  - Subtitle state machine (4 states): idle (subtitle follows
    `notLoadedCount`-or-gone) → refreshing (truth table above) →
    failed-sticky (retry subtitle; holder: fragment-local `failedSticky`
    in slice 1, promoted to VM `failedSticky: Boolean` with getters in
    slice 3b) → idle. Failed-sticky is a header state,
    not a `FeedState` variant: Idle-held DB-only `LoadedState`s never
    clear it — only the next `reloadContent()` or a scoped non-cancelled
    `Success` (with or without row changes) clears it. Set on a scoped
    fatal `Error` `Event`, on the 10 s no-event trip, or on the 60 s
    terminal trip. Slice-1 behavior (no VM, no timers, no scoping yet):
    the fragment-local flag is set on the fatal `itemCount` branch and on
    any terminal-with-rows settle that keeps the retry header; it is
    transient (no rotation survival — documented limitation, fixed by 3b
    promotion). Rotation re-show predicate (3b+4): plain
    `failedSticky == true` — no `latestScopedEvent` shape check, so 10 s /
    60 s stickies with `Progress`/null latest events re-show too.
    - `refresh_text` (oldest-update line) is unchanged during
      refresh-with-content.
  - When `notLoadedCount > 0` simultaneously applies, the subtitle is
    the refreshing text plus the existing `Not loaded: %d` line
    (`feed_subscription_not_loaded_count`) joined by new
    `feed_refreshing_with_not_loaded` (`%1$s · %2$s`, arg1 = refreshing
    text, arg2 = not-loaded text) so translators own the separator.
    Combined lines may ellipsize at `maxLines=1` — accepted; determinate
    counts stay short. `contentDescription` on `refresh_subtitle_text`
    mirrors the visible subtitle text verbatim (TalkBack pause on `·`
    is acceptable; no extra CD strings). While in the failed state,
    mirror the same retry CD onto `refreshIcon` so an icon-focused reader
    hears the same action; while failed, `refreshIcon.focusable = false`
    (the root is the single action — no double-announcement), restored
    after. While not failed both CDs stay `null` (icon spin is decorative).
  - Fatal-with-content precedence: the retry subtitle
    (`feed_refresh_failed_tap_to_retry`) wins over `Not loaded: n`
    (failure is more urgent; `notLoadedCount` remains reachable via
    subtitle long-press dialog). `refresh_text` keeps the stale
    timestamp (context for "how stale"; do not hide). When a filter is
    active the subtitle is still the retry string; the kept list is
    whichever (filtered/unfiltered) was showing.
  - On scoped terminal settle (a `Success`/`Error` `Event` rendered through
    §4.2 — not an Idle-held DB-only `LoadedState`, which leaves sticky
    untouched), the subtitle reverts to the `notLoadedCount`-or-gone rule
    unless failed-sticky holds (fatal/10 s/60 s set it — see above), and
    `updateRefreshViewState()` runs.
- Single retry contract (fatal-with-content, 10 s stuck, 60 s terminal):
  snackbar `LENGTH_LONG`, text `R.string.feed_load_error` (existing),
  action `R.string.retry` (existing) → `reloadContent()`, anchored to
  `feedBinding.itemsList` (fallback `requireView()`), via
  `ErrorUtil.showSnackbar` so background-tab/no-view cases degrade to a
  notification instead of crashing. Dedupe: `if (snackbar?.isShown !=
  true)` before showing; rotation dismisses the old snackbar with the
  destroyed view and the re-armed timer re-shows only if the failure
  still holds. Scope: only the tab whose fragment called `reloadContent()`
  arms timers/snackbar — a background tab never snackbars on scoped-ignored
  fatals. Header retry is the persistent twin:
  subtitle `Refresh failed — tap to retry` (new
  `feed_refresh_failed_tap_to_retry`); tapping `refresh_root_view` or
  `refreshIcon` calls `reloadContent()` as today. `refresh_root_view`
  gets `contentDescription = @string/feed_retry_desc` (new, e.g.
  "Refresh failed — tap to retry") **only** while in the failed state,
  else `null` (so it is not an a11y action when idle). Both entry
  points fire the same idempotent `reloadContent()` (single-flight
  dedupes) — dual affordance, not dual state machines. Absorbed re-tap
  while refreshing gets no TalkBack announcement (explicit wont-fix:
  spinner + counts already communicate the ongoing load).
- Remove the `!isRefreshing` gates in `listenerStreamItem` with no
  replacement: no modal case is identified, and adapter items are
  immutable snapshots. Subtitle long-press (`showNotLoadedSubscriptions`)
  and header taps stay armed during refresh (a re-tap while in flight
  re-fires into the single-flight drop — see re-tap rule).
- Fatal errors: `handleErrorState`/`handleError` gain an explicit
  `groupAdapter.itemCount == 0` branch (slice 1 owns this — no
  `ViewModel`/`State` shape change, purely a fragment render gate): if
  the adapter is non-empty, do not call `showError()` (panel). Instead
  snackbar per the contract above and leave the stale list + header
  retry: override `handleError()`'s hiding of `refreshRootView` so the
  header stays visible. Keep the panel for the empty-adapter case.
  Fatal-with-content leaves playback adjacency untouched: `handleError()`
  never touches `playlist_control`, so it keeps its last visibility (from
  the last `handleLoadedState`); `getPlayQueue()` returns the kept rows
  (filtered when the kept list was filtered, per the existing filtered-vs-
  original rule); the `autoBackgroundPlaying`/shuffle click path works on
  the kept (stale) rows via `getAdapterPosition`. No re-queue, no hiding —
  stale queue is documented as acceptable. Filtered+fatal: same, with the
  filtered kept list + retry subtitle (precedence above).
- `handleLoadedState` stays the diff/update path; keep
  `selection.retainOnly(…)`, `updateRefreshViewState()`, and
  `highlightNewItemsAfter()` — which becomes *more* correct once the
  list is no longer wiped underneath it. Additionally:
  - After rebuilding `originalItems`, if the search query
    (`editText?.text`) is non-empty, rebuild `filteredItems` from it and
    push the *filtered* list via `groupAdapter.updateAsync` with the
    same `highlightNewItemsAfter` completion callback as the unfiltered
    push (`FeedFragment.kt:842-854`). Do not call `filterItems` here —
    it unconditionally scrolls to top (`:663`), which would override
    the `listState` restore. When the query is empty, keep the existing
    unfiltered push untouched. `selection.retainOnly(…)` keeps applying
    before either push as today.
  - Filtered-empty: unfiltered non-empty but filtered empty →
    `hideLoading()` (RV visible, 0 rows), `playlist_control` hidden
    (nothing to play — differs from today, which keys visibility off the
    unfiltered list in `FeedFragment.kt:847`), `empty_state_view` stays
    hidden, and the subtitle shows `feed_no_filter_results` (new: "No
    matches — clear search", mirrored to `contentDescription`) so the
    blank list is distinguishable from a stalled load. Truly-empty
    (unfiltered empty) → `showEmptyState()` as today.
  - Selection: when `selection.isActive`, still `retainOnly` + re-mark,
    but skip `highlightNewItemsAfter` + pill *and* freeze
    `lastNewItemsCount` (selection highlight owns item visuals; resuming
    the count on exit avoids a stale celebration). `shownKeys` continue
    from the filtered list when `isFilterEnabled` (as `onSelectionAction`
    does today). Highlight otherwise runs on the shown (filtered) items
    with the pill reflecting visible new items.
  - Surface `itemsErrors` whenever the error *set* changes, dropping
    the `oldestUpdate`-changed gate. Error-set key: `subscriptionId +
    "|" + cause::class.qualifiedName`, order-insensitive (`toSet()`
    compare); message/url ignored. Non-`RequestException` throwables key
    as `"raw|" + cause::class.qualifiedName`. Holder: `ViewModel`
    `lastErrorSet: Set<String>` (survives rotation with the VM; cleared
    only by an error-free `Success`). Same key set consecutively → no
    re-fire (avoid nag); different → `handleItemsErrors`.
- `reloadContent()` (slice homes in parentheses): (a) `hideErrorPanel()`
  first (slice 1 — dismisses the empty-adapter panel immediately; also
  clears the parceled `lastPanelError` so `BaseStateFragment.onResume`
  cannot resurrect it after a successful retry); keep firing the service
  + `hideNewItemsLoaded`; (b) set `isRefreshing = true` optimistically plus
  `swipeRefreshLayout.isRefreshing = true` **only when
  `isPullToRefreshEnabled`** (slice 1, fragment fields only — no VM change;
  reconciliation here is by the first rendered state, any kind — temporary
  slice-1 predicate, explicitly replaced by slice 3a with the scoped-`Event`
  predicate below (re-run slice-1 acceptance after the swap) and (c)
  record `refreshRequestedAt` in the `ViewModel` (slice 3b field; until
  then the 10 s rule does not exist — slice 1 has no timers, so nothing
  needs cancelling). First scoped `Progress`/`Success`/`Error` `Event`
  (pre-`combineLatest`, never VM outputs) reconciles the optimistic bit;
  `Loaded`/`Error` `FeedState`s and Idle-held DB-only recombines never do.
  Re-tap while `isRefreshing == true` re-fires the
  service into the single-flight drop with **no** user feedback (no
  hint, no extra snackbar) and does **not** overwrite
  `refreshRequestedAt`/`lastProgressAt` — spinner + counts continue
  under the first load's timers.
- Stuck-spinner / terminal-timeout spec (slice 4 owns the timers;
  timestamps live in the `ViewModel` per §4.5; timers are per-fragment
  for that fragment's own `groupId` — there is no cross-fragment epoch
  to specify: fragment A never arms/cancels on fragment B's request,
  and ignored cross-group events simply never cancel A's jobs):
  - 10 s no-event: if no scoped `Progress`/`Success`/`Error` `Event`
    (upstream filter pass-through, pre-throttle — VM outputs never count:
    `Loaded`/`Error` `FeedState`s and Idle-held DB-only recombines do
    **not** reconcile) arrives within 10 s of `reloadContent()`, clear
    the optimistic spinner and set failed-sticky + snackbar per the retry
    contract (covers the single-flight drop, §2 step 2).
    Ignored cross-group terminals, `-2` events, `IdleEvent`s, and DB-only
    recombines do **not** cancel this timer. Pull gesture stays enabled
    while refreshing (standard `SwipeRefreshLayout` behavior).
    Probes: `Log.d("FeedRefresh", "spinnerStart/…”/“spinnerClear/…" +
    SystemClock.elapsedRealtime())` on every set/clear so the ≤ 500 ms
    acceptance is stopwatch-verifiable.
  - 60 s terminal: once any scoped `ProgressEvent` has arrived, spinner
    lifetime binds to the terminal event instead. If no terminal event
    arrives within 60 s of the last scoped `ProgressEvent`, treat the
    load as failed — clear spinner, snackbar per contract, keep stale
    list (presents as fatal-with-content). Every scoped `ProgressEvent`
    — including indeterminate `(-1,-1)` processing posts — resets
    `lastProgressAt` (they prove liveness; the §4.4 stall warning is
    about UI smoothness between counts, not death: per-channel
    completions on 6-way parallel keep arriving, and
    `feedFetchInterval` is per-service politeness between batches).
    60 s is a heuristic, not a proof: the worst-case gap is unbounded
    in theory, so a false trip settles via the late-terminal rule below
    with no data loss. Job shape: sequential single `timerJob` — the 10 s
    job is replaced by the 60 s job on the first scoped `Progress`
    (cancel + relaunch); each further scoped `Progress` cancels and
    relaunches with remaining `60_000 - (now - lastProgressAt)`; any
    scoped terminal or failure-entry cancels the job. Rotation re-arm:
    10 s phase → `10_000 - (now - refreshRequestedAt)`; 60 s phase →
    `60_000 - (now - lastProgressAt)`; failed-sticky re-show predicate:
    `failedSticky == true` (snackbar re-shown whenever the flag holds,
    whatever the latest event shape — covers 10 s/60 s stickies with
    `Progress`/null latest events; deduped).
  - Late arrival: `Progress` arriving after a 10 s failure re-lights
    the spinner (`isRefreshing = true`), dismisses the stuck-snackbar
    (fragment-held `snackbar?.dismiss()` ref), and resumes 60 s
    tracking. A terminal arriving after a 60 s failure settles normally
    (`hideLoading`/list update/error render, snackbar dismissed);
    pill/highlight follow the normal `cancelled`/selection rules.

### 4.4 Layout (`fragment_feed.xml`)

Minimal: no new views required. `refresh_subtitle_text` carries the
refresh progress per §4.3; `refreshIcon` spins via an `ObjectAnimator`
(`rotation` 0→360, 1000 ms, linear, infinite) started when
refresh-with-content begins and cancelled in `hideLoading()` /
`handleError()` / `showEmptyState()`. Slice 2 edits those three slice-1
methods retroactively to add the spin start/cancel (stated here so the
merge is not a surprise). The centered
`loading_progress_bar/text` is reserved strictly for the empty-adapter
initial load. Future work (not in slices): a thin determinate
`ProgressBar` under `refresh_root_view` for long refreshes.

### 4.5 Concurrency and lifecycle notes

- `FeedLoadService` single-flight drops overlapping group loads;
slice 3a additionally nulls `loadingDisposable` on dispose (treat
`isDisposed == true` as idle) so a re-tap after the terminal but before
`stopSelf()` starts a real new load instead of dropping into the
destroyed service. The fragment tolerates `Progress` never
arriving via the §4.3 10 s rule, and ignores other groups' terminal
events via §4.2 scoping (ignored events do not cancel timers).
- Rotation mid-refresh: `listState` save/restore already exists; with
  the list kept visible there is no restore jump. Timestamps
  (`refreshRequestedAt`, `lastProgressAt`) and `lastErrorSet` /
  `seenNonProcessingProgress` live in the fragment-scoped `ViewModel`
  (survives rotation; fragment fields `oldestSubscriptionUpdate` and
  `isRefreshing` reset on recreation). On `onViewCreated` the fragment
  re-derives the header indicator from the VM-exposed latest scoped
  event + rows (new VM getters: `latestScopedEvent: Event?`,
  `refreshRequestedAt: Long?`, `lastProgressAt: Long?`) replayed via
  `stateLiveData`, and re-arms timers with remaining delay (`10_000 -
  (now - requestedAt)`, floor 0 → fire immediately; single `timerJob:
  Job?` in the fragment tied to `viewLifecycleOwner`, cancelled in
  `onDestroyView`). `isLoading` is deliberately NOT wired: the feed
  path never sets it, `BaseStateFragment.hideLoading()` does not clear
  it, and `wasLoading` is never consumed in the feed path (only
  `VideoDetailFragment` / `BaseListInfoFragment`) — rotation re-derive
  works via `LiveData` replay, not the flag. (Slice 3b owns the VM
  getters so the rotation test passes there, not in Polish.)
- Navigate-away mid-refresh (e.g. not-loaded dialog → channel fragment,
  `showNotLoadedSubscriptions` leaves the load running): timers are
  `viewLifecycleOwner`-bound and cancelled on view destroy; on return
  the fragment re-derives exactly like rotation (same `onViewCreated`
  path). If the fragment instance itself is gone (backstack pop),
  return is a cold DB replay — no spinner, no snackbar, no crash.
- Search query survival: writer is `doAfterTextChanged` (writes VM
  `savedQuery` on every keystroke) plus `onSaveInstanceState` (bundle
  backup); the VM holds `savedQuery: String?` as the source of truth
  across rotation. On recreate, the completion reads `savedQuery` at
  execution time (not the recreated `EditText`, which may lag) inside
  the `updateAsync` completion (same path as §4.3 refresh re-apply —
  never via `filterItems`, so no scroll jump), guarded by a monotonic
  `filterGeneration: Long` (incremented per keystroke and per
  re-apply; the completion applies only if its generation is still
  current, else it no-ops). Without this the `LiveData` replay
  rebuilds the unfiltered list and the filter is silently lost.
- Dialog/toolbar/view adjacency: the three dialogs (`notLoaded` list,
  `feed-not-available` with unsubscribe, and long-press `InfoItemDialog`)
  are tracked in fragment fields (`notLoadedDialog?`,
  `feedNotAvailableDialog?`, `infoItemDialog?` — new fields, slice 1) and
  dismissed in `onDestroyView` (untracked `Builder…show()` today would leak
  the window on view-recreate). A dialog open across a terminal simply
  stays open (terminal renders underneath) and across rotation it is
  dismissed + re-invokable via subtitle long-press / long-press. Async
  `Single`s are in `disposables`, cleared on destroy. `selectionToolbar`
  is released in `onPause` and rebound in `onResume`; a
  `handleLoadedState` completion landing while unbound still runs
  `retainOnly`/highlight (toolbar picks up the state on rebind).
  `updateAsync` completions guard on `isAdded && _feedBinding != null`
  (the query re-apply reads `savedQuery`, never the nulled `editText`).
  Pause-with-selection-plus-refresh then resume re-derives like rotation.
  `onViewCreated` merge order (slice 3b): `latestScopedEvent` wins over a
  lagging throttled `FeedState` (120 ms window) when phases disagree —
  derive `isRefreshing = (latestScopedEvent is Progress && !failedSticky) ||
  (refreshRequestedAt != null && lastProgressAt == null && !failedSticky &&
  now - refreshRequestedAt < 10_000)` — the second clause covers rotation
  inside the optimistic window (no scoped `Event` yet; terminal→`Idle`
  replacement would otherwise read as idle and drop the spinner + 10 s arm)
  after replay, then arm the phase-appropriate timer.
- Adapter writer serialization: three writers touch the adapter —
  `handleLoadedState` (`updateAsync` + completion), `filterItems`
  (sync `update` + scroll-to-top), and the §4.3 query re-apply. Rule:
  `filterItems` keeps its sync update for keystroke latency, but any
  `handleLoadedState` completion re-applies `savedQuery` at execution time
  only if its captured `filterGeneration` is still current (else no-op)
  — the completion runs after its own diff, so a current generation always
  wins over a racing keystroke push; stale diffs are superseded, never merged.
  Test: type during refresh → final list matches the query.
- Process death mid-refresh: the `FeedEventManager` singleton resets to
  `Idle`, the VM and timers are gone. Expectation: cold start replays
  the DB (stale list or empty), idle header, no spinner/snackbar. No
  crash, no hang — no persisted spinner bit to reconcile.
- Cancel (`cancelSignal` from the notification action): slice 3a resets
  `cancelSignal.set(false)` as the first line of `startLoading` (without
  this, a Cancel followed by a re-tap into the same live service instance
  poisons the next load — `takeWhile` is immediately false — because the
  manager is per-service-instance and the flag is never cleared).
  Flag locus: `cancelled` is sampled once at `postProcessFeed()` entry
  (`cancelled = cancelSignal.get()` when building the terminal
  `SuccessResultEvent`) — a cancel landing during `buffer`/`DatabaseConsumer`/
  `postProcessFeed` still suppresses the celebration (flag true); only a
  cancel arriving after the sample is missed (documented race, next load is
  clean). Remainder rule: a `<20` tail flushes via normal Rx
  `buffer`-on-completion through `DatabaseConsumer` (no drop), so "partial
  inserts remain" includes the tail; `oldestUpdate`/pill gating sees the
  flushed rows.
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

1. Fragment-only (A) + fatal strings: conditional hide
   (`itemCount == 0` gates BOTH the list and the `refreshRootView` hide
   in the `showLoading()` override), click-gate removal,
   `showEmptyState()` also clears `isRefreshing`,
   `reloadContent()` calls `hideErrorPanel()` first, fatal-with-content
   (`handleErrorState`/`handleError` `itemCount == 0` branch) snackbar +
   header retry + `feed_refresh_failed_tap_to_retry` +
   `feed_retry_desc` strings. No `ViewModel`/`State` shape change.
   Acceptance: populated list AND header never hide on refresh/fatal;
   empty adapter still panels; retry from the panel dismisses it
   immediately. Dialog-field tracking + `onDestroyView` dismiss for all
   three dialogs (`notLoaded`, `feed-not-available`, `InfoItemDialog`);
   fragment-local `failedSticky` set on the fatal branch (transient).
2. Header progress (B) + progress strings + no-op suppression:
   `RefreshingState` shell (items + counts, no group-scoping yet),
   ViewModel queries DB on `Progress`, route counts into
   `refresh_subtitle_text` per §4.3 truth table + icon spin,
   `feed_refreshing_progress` + `feed_refreshing_with_not_loaded`
   strings, processing-only no-op suppression via VM
   `seenNonProcessingProgress` (unscoped in this slice — documented
   cross-group flicker limitation, fixed by 3a scoping; fresh-only refresh never
   toggles RV visibility; `loading_progress_bar` stays `GONE` when
   `itemCount > 0`). Gates: header `x/y` + icon spin on refresh-with-content,
   no-op suppressed (fresh-only refresh never hides). After this slice,
   re-run slice-1 acceptance (animator retroactively edits slice-1 methods).
3a. Group scoping + terminal replacement + test seam: sender tags
   `groupId` on all events (incl. `NotificationWorker -2` and
   `handleError`); receiver upstream filter + truth table (§4.2);
   local terminal→`Idle` replacement + `reset()` removal; constructor
   seam (`eventsSource`, schedulers, `Factory` passthrough, `private val
   groupId`); service `loadingDisposable` null-on-dispose fix;
   `cancelSignal.set(false)` first line of `startLoading` (+ tail-flush
   rule, §4.5). Gates: disjoint-tabs, `GROUP_ALL`-lights, `-2`-silent,
   ALL→A-lights (new: ALL-tab load lights a specific tab — fan-out by
   DB overlap), post-terminal re-tap starts a real load, stuck-terminal
   replay gone, unit seam injectable.
3b. Cancelled + error-gate + filter + selection + VM getters:
   `cancelled` flag + scoped pill/highlight skip (§4.2); error-gate
   removal with set key (`subscriptionId|causeClass`, order-insensitive,
   VM-held `lastErrorSet`); filter re-apply + filtered-empty
   (`feed_no_filter_results`) + playlist-on-filtered-size rule + query
   persistence across rotation; selection pill-skip + `retainOnly` +
   `lastNewItemsCount` freeze; VM getters (`latestScopedEvent`,
   timestamps, `failedSticky`) for rotation re-derive + stream-death
   try/catch (§4.2). `isLoading` explicitly not wired
   (rejected — `LiveData` replay owns rotation). `playlist_control`
   filtered-size rule owned here. Gates: cancelled-no-pill, error-gate
   removal (incl. same-set-no-nag), filter re-apply + filtered-empty +
   query-across-rotation, type-during-refresh (generation-guarded re-apply wins),
   selection-across-refresh, rotation re-derive (list + header via LiveData +
   getters; timer re-arm verified in slice 4), stream-death UI (map-throw and
   parked-upstream frozen list), dialog-across-terminal/rotation,
   toolbar-null action, membership-mutation silent re-derive.
4. Polish: 10 s no-event + 60 s terminal timers per §4.3/§4.5
   (VM timestamps, sequential single `timerJob` stamped pre-throttle,
   `viewLifecycleOwner`-bound, re-arm formulas + failed-sticky predicate,
   late-arrival reconciliation). Gates: stuck-snackbar (10 s), dead-service
   (60 s, both kill phases), timer re-arm on rotation (both formulas),
   late-arrival re-light + settle, panel-rotate-retry,
   navigate-away, stale-panel-vs-recovered-DB ordering.
   Thin header `ProgressBar` explicitly out of scope (Future work, §4.4).

Per-slice gating: a slice is done when its Gates bullets all pass as
Given/When/Assert — Given (fixture: subs/groups/pull-toggle state),
When (action incl. fault-injection/`am stopservice` where noted),
Assert (observable: RV visibility, `loading_progress_bar` visibility,
`findFirstVisibleItemPosition` Δ, spinner state from `FeedRefresh` log
timestamps, pill presence/absence, snackbar/header copy).

## 7. Test plan (manual)

Falsifiability: "no jump" = `findFirstVisibleItemPosition` Δ ≤ 1 and
`RecyclerView.visibility == VISIBLE` throughout; "no flicker" =
`loading_progress_bar.visibility` stays `GONE` when `itemCount > 0`;
"prompt" = spinner cleared ≤ 500 ms after terminal (one 120 ms throttle
window + main-thread hop).

- Cold start with subscriptions → full-screen initial load, then list.
- Pull-to-refresh with content → list stays (`VISIBLE` throughout),
  stays scrollable and tappable (click + long-press both work);
  swipe spinner + header `Refreshing x/y` + spinning icon; position
  Δ ≤ 1 on completion; new items highlighted + pill appears.
- Pull-to-refresh disabled (`pull_to_refresh_key` off) + header-tap
  refresh → list kept, header `x/y` + icon spin only (no swipe
  spinner); header taps still `reloadContent()`.
- Refresh when all subs fresh → no flicker (RV never hides,
  `loading_progress_bar` stays `GONE`).
- Refresh with 0 subscriptions → empty state, no spinner hang
  (a `SuccessResultEvent` — an `Event` — reconciles the optimistic bit;
  the rendered `LoadedState` with 0 rows then shows empty state).
- Per-channel failure (airplane mode, terminated channel) with content
  → list kept, per-channel dialog/snackbar as today; with empty adapter
  → empty state + per-channel dialog/snackbar. (Non-fatal by design;
  exercises error-gate removal, not the fatal path.)
- Fatal pipeline failure with content → snackbar (`LENGTH_LONG` +
  `Retry`, anchored to the list, deduped) stale list kept, header retry
  (subtitle + whole `refresh_root_view` tappable incl. mirrored icon CD,
  TalkBack announces retry) works; with empty adapter → error panel,
  and retrying from the panel dismisses it immediately
  (`hideErrorPanel()` in `reloadContent()`). Fault-inject in a debug
  build: temporarily insert
  `throw RuntimeException("debug-feed-fatal")` as the first line of
  `postProcessFeed()` body, rebuild, refresh → assert snackbar + kept
  list + header retry; revert before commit. Kill-service test: `adb
  shell am stopservice
  org.schabi.newpipe.debug/org.schabi.newpipe.local.feed.service.FeedLoadService`
  (stops the service in the same process; fragment/VM survive) — not
  "verify by inspection".
- Fatal with `notLoadedCount > 0` → retry subtitle wins; long-press
  subtitle still opens not-loaded dialog. Fatal with active filter →
  kept (filtered) list + retry subtitle.
- Per-channel failure that leaves `oldestUpdate` unchanged → errors
  still surface (gate removal). Same failure twice → no nag (set
  equality); new failure set → fires.
- Rotation mid-refresh → list + header indicator survive (re-derived
  from VM via `stateLiveData` + getters), timers re-armed with remaining
  delay, no duplicate loads (single-flight), no double-snackbar
  (single `timerJob`, `viewLifecycleOwner`-cancelled). Rotation with an
  active search filter → query survives (`savedQuery`) and is re-applied
  in the `updateAsync` completion with no scroll jump.
- Navigate-away mid-refresh (not-loaded dialog → channel) → on return
  same re-derive as rotation; fragment-instance-gone → cold DB replay,
  no spinner/snackbar.
- Rotate while the error panel is visible, then retry from the panel →
  panel dismisses immediately and never re-appears (`hideErrorPanel()` in
  `reloadContent()` clears the parceled `lastPanelError` before `onResume`
  can resurrect it); successful settle shows the list. Stale panel vs
  recovered DB: if the panel is visible and the DB recovers while paused
  (background `-2` write), `onResume` panel resurrection wins over the
  healthy `LiveData` replay — panel persists until retry (documented
  ordering, assert it; no auto-dismiss).
- Long-press `InfoItemDialog` open across refresh completion → dialog stays
  open on the stale snapshot, list updates underneath, no crash. Rotation
  with each of the three dialogs open → dismissed, re-invokable, no leaked
  window. Selection action while `selectionToolbar == null` (paused) with a
  concurrent `handleLoadedState` → `retainOnly` applies, toolbar picks up the
  state on rebind (assert post-resume toolbar state).
- Not-loaded dialog open across a terminal → dialog stays open, list
  renders underneath; unsubscribe from `handleFeedNotAvailable` mid-refresh
  → DB recombine does not refire the same error set (set-equality gate).
- Pause with selection + refresh, then resume → toolbar rebinds with the
  retained selection; no pill fired while `selection.isActive`.
- Type-during-refresh → keystroke pushes (`filterItems` sync) race the
  completion re-apply; final list always matches the query (current
  `filterGeneration` wins).
- Process death mid-refresh → cold start replays DB, idle header, no
  spinner/snackbar, no crash.
- Group tabs: refresh group B while viewing disjoint group A → A shows
  no spinner/counts and no content change. Refresh from the ALL tab while
  viewing A → A lights (fan-out by DB overlap; upstream `GROUP_ALL_ID`
  passes A's filter). Viewing `GROUP_ALL` while B
  refreshes → `GROUP_ALL` spinner/counts light (its data overlaps) and
  content updates silently; pill/highlight fire only on a scoped
  non-cancelled `Success` (never on `-2`/DB-only recombines).
- Toggle "show played items" mid-refresh → instant, no blanking.
  Toggle-to-empty → `empty_state_view` (Empty row).
- Search filter active across refresh completion: query preserved and
  re-applied to new rows, no scroll jump (`listState` restore wins;
  `filterItems` not called). Filtered-to-empty → empty visible list +
  hidden `playlist_control` + `feed_no_filter_results` subtitle (no
  `empty_state_view`), TalkBack reads the subtitle.
- Selection across refresh: enter selection → refresh completes →
  vanished uids dropped (`retainOnly`), toolbar + spinner coexist, no
  pill/highlight while `selection.isActive`, `getPlayQueue` respects
  filtered vs original.
- Cancelled load (notification-shade Cancel while fragment shows
  `Refreshing x/y`) → settles to `LoadedState` without pill/highlight.
  Late cancel (during `buffer`/`DatabaseConsumer`/`postProcessFeed`) → still
  suppressed (flag sampled at `postProcessFeed` entry).
  Cancel then immediate re-tap into the same live service instance → real
  new load (signal reset at `startLoading` entry), spinner re-lights.
- Stream-death: fault-inject `getStreams().blockingGet` throw (debug:
  temporarily throw in `FeedDatabaseManager.getStreams`) → `ErrorState`
  via the stream-death guard (fatal path for current adapter state),
  subscription survives (next recombine renders); assert no frozen
  spinner. Upstream death: fault-inject `notLoadedCount`/`oldestUpdate`
  error → frozen last list + logged error, no crash; recovers on recreate.
- Membership mutation: while viewing A, delete A / move a sub A→B / external
  unsubscribe → silent `LoadedState` re-derive, no spinner/pill, selection
  retains only surviving uids. Refresh B while viewing A → A unchanged.
- Notification-triggered background (`-2`) load while fragment visible
  → no spinner/counts/pill on any tab; overlapping `GROUP_ALL` content
  updates silently via DB flows (desired).
- Same-group refresh re-tapped while in flight → settles on the first
  load's terminal event; no extra snackbar/hint; timestamps not
  overwritten. Cross-group re-tap → other group's terminal ignored per
  §4.2; the new request's 10 s timer applies (scoped events only).
- Fast single-sub refresh (< 120 ms, possibly fully coalesced by
  `throttleLatest` so the spinner never lights) → still pass if the list
  is kept with no flicker and the terminal settles (coalesced-no-spinner
  = pass, not fail); otherwise spinner clears ≤ 500 ms after terminal
  (`FeedRefresh` log timestamps are the stopwatch).
- Service killed before first progress → 10 s path clears optimistic
  spinner with snackbar + retry; killed after progress → 60 s path;
  stale list kept in both. Kill via the `am stopservice` command above;
  assert the timer that applies per arrival state.
- Post-terminal re-tap (after terminal, before `stopSelf()` destroys the
  service) → starts a real new load (service null-on-dispose fix),
  spinner re-lights, timers re-arm. No silent drop.
- Re-tap UX: second pull while refreshing is a no-op (spinner + counts
  continue, no "already refreshing" hint).

## 8. Open questions

Resolved since rev. 4 (all decided normatively above; no load-bearing
questions remain):

- Header copy/composition: decided (§4.3 truth table + 4-state machine,
  join format `%1$s · %2$s` with arg order, fatal precedence,
  `refresh_text` rules, `max == 0` defensive treatment with
  `filter`-guard cite and `FeedRefresh` log tag). Remaining is translator
  review of the five new strings (`feed_refreshing_progress`,
  `feed_refreshing_with_not_loaded`, `feed_refresh_failed_tap_to_retry`,
  `feed_retry_desc`, `feed_no_filter_results`), not the shape.
  Per-channel names stay notification-only.
- Throttle interplay: accepted as terminal + 500 ms (§7 fast-refresh
  test owns it).
- 60 s bound: reframed as heuristic with late-terminal reconciliation
  (§4.3). `feedFetchInterval` is per-service politeness
  (`FeedLoadManager.kt:57` delegates to the service); inter-progress
  gaps in practice are per-channel completions (6-way parallel), so 60 s
  without *any* scoped progress (incl. processing posts) means dead
  service / lost terminal. False trips settle without data loss.
- `FeedLoadManager.retry` always-`false`: out of scope (not touched).
- Testability: slice 3a adds the scheduler / event-source seam
  (§4.2, with `Factory` passthrough, `private val groupId`, and service
  null-on-dispose fix); manual probes are `FeedRefresh` log timestamps +
  layout-inspector steps (§7 preamble). No fallback ambiguity: the seam
  ships with the slice.
- `isLoading`: deliberately not wired (rejected — `LiveData` replay owns
  rotation; `wasLoading` unconsumed in feed path).

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
- Rev. 4 (2026-11-05): addresses pedantic review pass 3 — filter
  re-apply gated on non-empty query with highlight callback preserved
  (no `filterItems` scroll-jump); selection-bypass cite; `isLoading`
  rationale; cross-group timer non-interference.
- Rev. 5 (2026-11-05): addresses pedantic review pass 4 (3-lens fan-out:
  accuracy OK-with-notes, completeness BLOCK, implementability BLOCK) —
  §1 `BaseStateFragment` locus fix; `:793-795` cite fix; `listState`
  refresh-path clarification; §2.1 empty-gate-stuck note; §3 expanded
  to 10 rows (cancelled, disabled-swipe, filtered-empty,
  selection-across-refresh, fatal-with-content) with toggle-to-empty
  and `GROUP_ALL` notes; §4.2 upstream-filter scoping with receiver
  truth table, `-2`/DB cross-talk desired behavior, throttle window,
  seam decision; §4.3 subtitle truth table, fatal precedence,
  single retry/snackbar/a11y contract, optimistic spinner start,
  re-tap rule, full 10 s/60 s timer + rotation + late-arrival spec,
  filtered-empty/playlist rule, selection pill-skip, error-set key;
  thin `ProgressBar` moved to Future work; §4.5 VM timestamp
  persistence + process-death expectation; §6 re-sliced (1, 2, 3a, 3b,
  4) with string and `isLoading` ownership fixed; §7 falsifiable
  criteria + 12 new tests (disabled-swipe, filtered-empty, selection,
  cancelled, `GROUP_ALL`/`-2`, rotation-timer, process-death,
  fatal+notLoaded/filter, fast-refresh, kill-before/after, re-tap);
  §8 all questions decided.
- Rev. 6 (2026-11-05): addresses pedantic review pass 5 (3-lens fan-out:
  accuracy clean, completeness BLOCK, implementability BLOCK) — P0: gate
  `refreshRootView` hide on `itemCount == 0` with the list hide (header
  carries the `x/y`), swipe-bit guarded on `isPullToRefreshEnabled`,
  `reloadContent()` calls `hideErrorPanel()` (panel retry dismisses
  immediately); `:796`/`:817` + `util/Constants.kt:8` cite fixes; §3
  Loaded→idle rename + filtered-empty `feed_no_filter_results` copy;
  §4.2 `Event.groupId`/`broadcastProgress(groupId)`/`postProcessFeed(groupId)`/
  `private val groupId`/`Factory` deltas, `GROUP_ALL` sender semantics,
  service null-on-dispose fix, scoped-celebration rule (pill/highlight
  only on scoped non-cancelled `Success`), no-op `seenNonProcessingProgress`
  holder with slice-2 unscoped limitation; §4.3 4-state subtitle machine,
  `max == 0`/`filter`-guard cite, `FeedRefresh` log tag, icon CD mirror,
  snackbar anchor/dedupe/requesting-tab scope, re-tap no-announcement
  wont-fix, fatal `itemCount` branch owned by slice 1, VM `lastErrorSet`
  + raw-key rule, selection `lastNewItemsCount` freeze, query persistence
  (`savedQuery`), adapter serialization rule; §4.5 `isLoading` rejected
  (`LiveData` replay owns rotation), VM getters + single `timerJob`,
  navigate-away + process-death rules; §6 slice deltas (fatal gate +
  `hideErrorPanel` in 1, history holder in 2, plumbing + null fix in 3a,
  getters/query in 3b); §7 operationalized (debug-throw + `am
  stopservice` steps, coalesced-no-spinner = pass, 5 new tests:
  panel-retry, post-terminal re-tap, navigate-away, rotation-with-filter,
  type-during-refresh) + `FeedRefresh` probes; §8 five strings +
  `isLoading` rejection.
- Rev. 7 (2026-11-05): addresses pedantic review pass 6 (3-lens fan-out:
  accuracy OK-with-notes, completeness BLOCK, implementability BLOCK) —
  P0 contradictions resolved: single reconcile source (scoped `Event`s
  pre-`combineLatest`; VM outputs never reconcile; 0-sub terminal is a
  `SuccessResultEvent`), failed-sticky holder (fragment-local in slice 1,
  VM `failedSticky + failedAt` in 3b; only `reloadContent()` or scoped
  non-cancelled `Success` clears; Idle-held DB states never clear),
  slice homes fixed (slice 1 = fragment-only optimistic bit + panel/branch
  gates, no VM; 3b = VM timestamps/getters; 4 = timers), sequential single
  `timerJob` with pre-throttle stamp + both re-arm formulas + re-show
  predicate, fatal transport = existing `ErrorState` channel + fragment
  `itemCount` branch (no side-channel); no-op slice-2 action (re-emit last
  `LoadedState`, unscoped→scoped wording); `IdleEvent` excluded from timers
  (fans out by design) + tag foot-gun note; rotation writer
  (`doAfterTextChanged`→`savedQuery`, read-at-execution,
  `filterGeneration` guard) + 60 s re-arm; per-slice Gates lists +
  Given/When/Assert probes; slice-2 retroactive animator edit stated;
  `progressMessage != 0`; icon `focusable=false` while failed;
  `playlist_control` owned by 3b; cites corrected (`:797`/`:819`,
  `postProcessFeed :289`, `Factory 121-136`, `FeedEventManager :18-20`/
  `:22-26`, `filter/max` ordering reversal with atomic note, existing-subscriber
  stuck qualification); §3 +5 transient rows (no-op suppressed, optimistic
  pending, stuck 10 s, terminal-timeout 60 s, late-arrival) + tail-flush note;
  fatal playback adjacency (playlist untouched, kept-queue, stale-queue OK);
  stream-death try/catch (3b) + note + test; `cancelSignal` reset + remainder
  flush + cancel-retap test; `lastPanelError` cleared-by-retry + rotate test;
  dialog/toolbar/editText guards + 3 tests; ALL→A acceptance row; §7
  type-during-refresh restored, 0-sub test reworded to `Event`.
- Rev. 8 (2026-11-05): addresses pedantic review pass 7 (3-lens fan-out:
  accuracy clean + 1 pedantic cite, completeness BLOCK, implementability
  BLOCK) — push channel: VM `timerTicks: SharedFlow<Event>`
  (extraBufferCapacity 64, pre-throttle tap, fragment `lifecycleScope`
  collect; no replay, rotation via getters+formulas); no-op withholding
  rule (nothing observable for processing-only `Progress`, fresh-query
  terminal `LoadedState`, `vm.onReloadRequested()` entry); reconcile swap
  owned (slice 1 temp rendered-state predicate → 3a scoped-`Event`, re-gate
  noted); two filters written separately (indicator vs timer-stamp tap),
  `IdleEvent` gains `groupId` default `GROUP_ALL_ID` exempt from stamping;
  sticky: slice-1 transient fragment flag, 3b VM `failedSticky: Boolean`
  (`failedAt` dropped as dead), re-show predicate widened to bare
  `failedSticky == true`; rotation writers (`vm.onReloadRequested(now)`,
  `vm.onScopedEvent(e, now)`), `elapsedRealtime` clocks, merge order
  (`latestScopedEvent` wins) + `isRefreshing` derivation; Gates moved to
  implementing slices (stream-death/type-during-refresh → 3b, no-op → 2,
  timer re-arm → 4) + slice-2 retroactive re-gate note; animator note kept;
  `progressMessage != 0`; icon `focusable=false` while failed; playlist
  owner 3b; `Factory 121-135`; §3 +3 rows (DB-flow frozen, membership
  mutation, dialog-open); fatal playback adjacency (untouched playlist,
  kept-queue, stale-queue OK); stream-death upstream park
  (`onErrorResumeNext` → never) + row + test; `cancelSignal` reset + entry
  sample + late-cancel test; dialog refs + `onDestroyView` dismiss (slice 1,
  all three dialogs); §7 +6 tests (membership, dialog×3/toolbar-null,
  upstream death, late-cancel, stale-panel ordering); §6 Gates per slice +
  Given/When/Assert probes.
