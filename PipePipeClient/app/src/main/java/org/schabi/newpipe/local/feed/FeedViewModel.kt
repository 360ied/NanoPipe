package org.schabi.newpipe.local.feed

import android.content.Context
import android.os.SystemClock
import android.util.Log
import androidx.core.content.edit
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.preference.PreferenceManager
import io.reactivex.rxjava3.android.schedulers.AndroidSchedulers
import io.reactivex.rxjava3.core.Flowable
import io.reactivex.rxjava3.core.Scheduler
import io.reactivex.rxjava3.functions.Function4
import io.reactivex.rxjava3.processors.BehaviorProcessor
import io.reactivex.rxjava3.schedulers.Schedulers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import org.schabi.newpipe.R
import org.schabi.newpipe.database.feed.model.FeedGroupEntity
import org.schabi.newpipe.database.stream.StreamWithState
import org.schabi.newpipe.local.feed.item.StreamItem
import org.schabi.newpipe.local.feed.service.FeedEventManager
import org.schabi.newpipe.local.feed.service.FeedEventManager.Event
import org.schabi.newpipe.local.feed.service.FeedEventManager.Event.ErrorResultEvent
import org.schabi.newpipe.local.feed.service.FeedEventManager.Event.IdleEvent
import org.schabi.newpipe.local.feed.service.FeedEventManager.Event.ProgressEvent
import org.schabi.newpipe.local.feed.service.FeedEventManager.Event.SuccessResultEvent
import org.schabi.newpipe.local.feed.service.FeedLoadManager
import org.schabi.newpipe.local.feed.service.FeedLoadService
import org.schabi.newpipe.util.DEFAULT_THROTTLE_TIMEOUT
import java.time.OffsetDateTime
import java.util.concurrent.TimeUnit

class FeedViewModel(
    private val applicationContext: Context,
    private val groupId: Long = FeedGroupEntity.GROUP_ALL_ID,
    initialShowPlayedItems: Boolean = true,
    eventsSource: Flowable<Event> = FeedEventManager.events(),
    ioScheduler: Scheduler = Schedulers.io(),
    // Must stay a main-thread scheduler: the subscriber below posts via setValue.
    mainScheduler: Scheduler = AndroidSchedulers.mainThread()
) : ViewModel() {
    private var feedDatabaseManager: FeedDatabaseManager = FeedDatabaseManager(applicationContext)

    private val toggleShowPlayedItems = BehaviorProcessor.create<Boolean>()
    private val toggleShowPlayedItemsFlowable = toggleShowPlayedItems
        .startWithItem(initialShowPlayedItems)
        .distinctUntilChanged()

    private val mutableStateLiveData = MutableLiveData<FeedState>()
    val stateLiveData: LiveData<FeedState> = mutableStateLiveData

    @Volatile
    private var seenNonProcessingProgress = false

    // Slice 3b rotation/timer state (fragment-scoped VM survives rotation; fragment fields reset).
    @Volatile
    var refreshRequestedAt: Long? = null
        private set
    @Volatile
    var lastProgressAt: Long? = null
        private set
    @Volatile
    var latestScopedEvent: Event? = null
        private set
    @Volatile
    var failedSticky: Boolean = false
        private set
    @Volatile
    private var lastErrorSet: Set<String> = emptySet()

    // Slice 3b scoped celebration: set only by a fresh non-cancelled Success render;
    // consumed once by the fragment. DB-only recombines (Idle branch) never set it.
    @Volatile
    private var pendingCelebration = false

    // Non-atomic read-then-clear is safe only by main-thread confinement: the VM
    // writes it on the main subscriber and the fragment reads it on main.
    fun consumeCelebration(): Boolean {
        val pending = pendingCelebration
        pendingCelebration = false
        return pending
    }

    // Slice 3b query persistence: source of truth across rotation (written per keystroke).
    @Volatile
    var savedQuery: String? = null
    @Volatile
    var filterGeneration: Long = 0

    // Slice 3a push channel: timer-stamp tap emits here pre-throttle for every passing
    // Progress/Success/Error (Idle explicitly exempt). Fragment collection lands in slice 4.
    private val _timerTicks = MutableSharedFlow<Event>(extraBufferCapacity = 256)
    val timerTicks: SharedFlow<Event> = _timerTicks

    // Local terminal replacement (slice 3a): the consumed terminal instance. Later recombines
    // with the same cached terminal render as Idle — no stale errors, no panel resurrection.
    // Fresh terminals (unequal) always render. No FeedEventManager.reset() needed.
    @Volatile
    private var consumedTerminal: Event? = null

    private fun passesIndicator(event: Event): Boolean =
        event.groupId == groupId ||
            event.groupId == FeedGroupEntity.GROUP_ALL_ID ||
            (groupId == FeedGroupEntity.GROUP_ALL_ID &&
                event.groupId != FeedLoadManager.GROUP_NOTIFICATION_ENABLED)

    /** Monotonic load id. Terminals stamp the generation they belong to so renders
        can tell a current terminal from a stale replay. Volatile: the map stage
        reads both generations off the main thread (suppressed-error gate). */
    @Volatile
    var loadGeneration = 0L
        private set
    @Volatile
    var terminalGeneration = -1L
        private set

    fun onReloadRequested(now: Long = SystemClock.elapsedRealtime()) {
        refreshRequestedAt = now
        seenNonProcessingProgress = false
        failedSticky = false
        loadGeneration++
        // Fresh load owns fresh terminal/timer/error state: a persistent per-item
        // failure must re-fire on explicit retry instead of staying silent.
        lastErrorSet = emptySet()
        // Fresh load owns fresh terminal/timer state: stale progress markers would
        // defeat the slice-4 rotation re-arm, and a stale celebration would leak
        // into the next load. consumedTerminal is instance-compared, so it needs
        // no reset (a new load posts new instances).
        lastProgressAt = null
        latestScopedEvent = null
        pendingCelebration = false
    }

    /** Slice 4 failure entry for the 10 s / 60 s timer trips, which have no event. */
    fun onNoProgressTimeout() {
        failedSticky = true
    }

    fun onScopedEvent(event: Event, now: Long = SystemClock.elapsedRealtime()) {
        if (event is IdleEvent) return
        latestScopedEvent = event
        if (event is ProgressEvent) lastProgressAt = now
        if (event is ErrorResultEvent) failedSticky = true
        // Only Success clears the sticky — including cancelled ones: cancel is user
        // intent to stop, not a failure, and the fragment renders LoadedState.
        if (event is SuccessResultEvent) failedSticky = false
    }

    private fun errorKey(e: Throwable): String = if (e is FeedLoadService.RequestException) {
        "${e.subscriptionId}|${e.cause?.let { it::class.qualifiedName } ?: "null"}|${e.cause?.message}"
    } else {
        "raw|${e::class.qualifiedName ?: e::class.simpleName ?: e.javaClass.name}|${e.message}"
    }

    // Internal for the fragment's re-render guard: same keying, view-lifetime memory.
    internal fun errorSetKey(errors: List<Throwable>): Set<String> =
        errors.map(::errorKey).toSet()

    private var combineDisposable = Flowable
        .combineLatest(
            eventsSource
                .filter { passesIndicator(it) }
                .doOnNext { e ->
                    // Timer-stamp tap: narrower predicate (indicator + non-Idle), pre-throttle.
                    if (e !is IdleEvent) {
                        if (!_timerTicks.tryEmit(e)) {
                            Log.w(TAG, "timerTicks dropped a tick; 60 s window may trip early")
                        }
                    }
                },
            toggleShowPlayedItemsFlowable,
            feedDatabaseManager.notLoadedCount(groupId)
                // A couple of immediate retries cover transient blips; a wedged query
                // then pins the sentinel until VM recreation (the getStreams
                // queryError path moots it in the truly-wedged case).
                .retry(2)
                .onErrorResumeNext { e: Throwable ->
                    Log.e(TAG, "notLoadedCount stream died, using sentinel", e)
                    Flowable.just(0L)
                },
            feedDatabaseManager.oldestSubscriptionUpdate(groupId)
                // Same bounded-retry-then-sentinel policy as notLoadedCount above.
                .retry(2)
                .onErrorResumeNext { e: Throwable ->
                    Log.e(TAG, "oldestSubscriptionUpdate stream died, using sentinel", e)
                    Flowable.just(emptyList())
                },

            Function4 { t1: FeedEventManager.Event, t2: Boolean,
                t3: Long, t4: List<OffsetDateTime> ->
                return@Function4 CombineResultEventHolder(t1, t2, t3, t4.firstOrNull())
            }
        )
        .throttleLatest(DEFAULT_THROTTLE_TIMEOUT, TimeUnit.MILLISECONDS)
        .subscribeOn(ioScheduler)
        .observeOn(ioScheduler)
        .map { (event, showPlayedItems, notLoadedCount, oldestUpdate) ->
            // Suppressed terminals are DB-recombine replays of an already-handled
            // instance: the first pass ran the scoped side-effects, so re-running
            // them would clear a legitimately tripped sticky or re-stamp state.
            // consumedTerminal is written on main but read here on ioScheduler;
            // a race renders the terminal twice at most, absorbed downstream by
            // the view's surfaced-error-set guard.
            val suppressed = (event is SuccessResultEvent || event is ErrorResultEvent) &&
                event === consumedTerminal
            if (!suppressed) {
                onScopedEvent(event)
            }
            // Fatal errors re-post their cached outcome (panel persists across
            // recombines); successes downgrade to a silent Idle recombine. The
            // re-post is gated on the current generation: after a reload, a
            // recombine still carrying the previous load's terminal must neither
            // re-surface its error nor stamp the new load's generation.
            val effectiveEvent = if (suppressed) {
                if (event is ErrorResultEvent && terminalGeneration == loadGeneration) {
                    event
                } else {
                    IdleEvent()
                }
            } else {
                event
            }
            // Stream-death guard: a throwing DB query emits ErrorState as a value;
            // the subscription survives and the next recombine or reload recovers.
            val (streamItems, queryError) = try {
                Pair(
                    if (effectiveEvent is SuccessResultEvent || effectiveEvent is IdleEvent || effectiveEvent is ProgressEvent)
                        feedDatabaseManager
                            .getStreams(groupId, showPlayedItems)
                            .blockingGet(arrayListOf())
                    else
                        arrayListOf(),
                    null
                )
            } catch (e: Exception) {
                Log.e(TAG, "getStreams threw, freezing tab", e)
                Pair(arrayListOf(), e)
            }

            CombineResultDataHolder(effectiveEvent, streamItems, notLoadedCount, oldestUpdate, queryError)
        }
        .observeOn(mainScheduler)
        .subscribe({ holder ->
            val (event, listFromDB, notLoadedCount, oldestUpdate, queryError) = holder
            if (queryError != null) {
                // Query failures surface transiently (snackbar/panel) like before:
                // only event terminals persist sticky failure across rotation.
                // Note: this render cancels the armed timer (handleErrorState owns
                // the spinner), so a persistently throwing DB mid-load disarms the
                // net while the service still stalls — narrow enough to document,
                // not to widen the sticky contract for.
                mutableStateLiveData.value = FeedState.ErrorState(queryError)
                return@subscribe
            }
            when (event) {
                is ProgressEvent -> {
                    if (event.maxProgress > 0 || event.currentProgress >= 0) {
                        seenNonProcessingProgress = true
                    }
                    val isProcessingOnly = event.currentProgress == -1 &&
                        event.maxProgress == -1 &&
                        event.progressMessage == R.string.feed_processing_message
                    if (listFromDB.isEmpty()) {
                        mutableStateLiveData.value = FeedState.ProgressState(
                                event.currentProgress,
                                event.maxProgress,
                                event.progressMessage
                            )
                    } else if (isProcessingOnly && !seenNonProcessingProgress) {
                        // No-op refresh: emit nothing observable, fragment never enters spinner path.
                        return@subscribe
                    } else {
                        mutableStateLiveData.value = FeedState.RefreshingState(
                                listFromDB.map { e -> StreamItem(e) },
                                event.currentProgress,
                                event.maxProgress,
                                event.progressMessage,
                                oldestUpdate,
                                notLoadedCount
                            )
                    }
                }
                is IdleEvent -> {
                    mutableStateLiveData.value = FeedState.LoadedState(
                            listFromDB.map { e -> StreamItem(e) },
                            oldestUpdate,
                            notLoadedCount
                        )
                }
                is SuccessResultEvent -> {
                    // Fresh DB query already resolved above; no-op gate holds here when only
                    // processing-only progress was seen — still emit fresh LoadedState silently.
                    consumedTerminal = event
                    terminalGeneration = loadGeneration
                    // Error-set gate: same set consecutively → report only the delta;
                    // error-free Success clears the set. Reset per reload in
                    // onReloadRequested so a persistent failure re-fires on retry.
                    val errorSet = errorSetKey(event.itemsErrors)
                    val newKeys = errorSet - lastErrorSet
                    val errorsToReport =
                        event.itemsErrors.filter { errorKey(it) in newKeys }
                    lastErrorSet = errorSet
                    // Celebrate only loads that did real work: a processing-only
                    // Success is the silent no-op path, errors still surface above.
                    if (!event.cancelled && seenNonProcessingProgress) pendingCelebration = true
                    mutableStateLiveData.value = FeedState.LoadedState(
                            listFromDB.map { e -> StreamItem(e) },
                            oldestUpdate,
                            notLoadedCount,
                            errorsToReport
                        )
                    seenNonProcessingProgress = false
                }
                is ErrorResultEvent -> {
                    consumedTerminal = event
                    terminalGeneration = loadGeneration
                    mutableStateLiveData.value = FeedState.ErrorState(event.error)
                    seenNonProcessingProgress = false
                }
            }
        }, { e: Throwable ->
            // Mapping/rendering throws here would otherwise escape as
            // OnErrorMissingException; log and keep the last state instead.
            Log.e(TAG, "feed pipeline failed", e)
        })

    override fun onCleared() {
        super.onCleared()
        combineDisposable.dispose()
    }

    private data class CombineResultEventHolder(
        val t1: FeedEventManager.Event,
        val t2: Boolean,
        val t3: Long,
        val t4: OffsetDateTime?
    )

    private data class CombineResultDataHolder(
        val t1: FeedEventManager.Event,
        val t2: List<StreamWithState>,
        val t3: Long,
        val t4: OffsetDateTime?,
        val t5: Throwable? = null
    )

    fun togglePlayedItems(showPlayedItems: Boolean) {
        toggleShowPlayedItems.onNext(showPlayedItems)
    }

    fun saveShowPlayedItemsToPreferences(showPlayedItems: Boolean) =
        PreferenceManager.getDefaultSharedPreferences(applicationContext).edit {
            this.putBoolean(applicationContext.getString(R.string.feed_show_played_items_key), showPlayedItems)
            this.apply()
        }

    fun getShowPlayedItemsFromPreferences() = getShowPlayedItemsFromPreferences(applicationContext)

    companion object {
        private const val TAG = "FeedViewModel"

        private fun getShowPlayedItemsFromPreferences(context: Context) =
            PreferenceManager.getDefaultSharedPreferences(context)
                .getBoolean(context.getString(R.string.feed_show_played_items_key), true)
    }

    class Factory(
        private val context: Context,
        private val groupId: Long = FeedGroupEntity.GROUP_ALL_ID
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            return FeedViewModel(
                context.applicationContext,
                groupId,
                // Read initial value from preferences
                getShowPlayedItemsFromPreferences(context.applicationContext)
            ) as T
        }
    }
}
