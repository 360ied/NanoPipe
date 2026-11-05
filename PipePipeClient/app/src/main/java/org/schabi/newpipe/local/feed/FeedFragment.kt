/*
 * Copyright 2019 Mauricio Colli <mauriciocolli@outlook.com>
 * FeedFragment.kt is part of NewPipe
 *
 * License: GPL-3.0+
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program. If not, see <http://www.gnu.org/licenses/>.
 */

package org.schabi.newpipe.local.feed

import android.animation.ObjectAnimator
import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.os.SystemClock
import android.graphics.Typeface
import android.graphics.drawable.LayerDrawable
import android.os.Bundle
import android.os.Parcelable
import android.text.Editable
import android.text.TextWatcher
import android.util.Log
import android.view.*
import android.view.animation.LinearInterpolator
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.EditText
import androidx.activity.OnBackPressedCallback
import androidx.annotation.IdRes
import androidx.annotation.Nullable
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.ActionBar
import androidx.appcompat.content.res.AppCompatResources
import androidx.core.content.edit
import androidx.core.os.bundleOf
import androidx.core.view.isVisible
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.preference.PreferenceManager
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.snackbar.Snackbar
import com.xwray.groupie.GroupieAdapter
import com.xwray.groupie.Item
import com.xwray.groupie.OnAsyncUpdateListener
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import com.xwray.groupie.OnItemClickListener
import com.xwray.groupie.OnItemLongClickListener
import io.reactivex.rxjava3.android.schedulers.AndroidSchedulers
import io.reactivex.rxjava3.core.Single
import io.reactivex.rxjava3.disposables.CompositeDisposable
import io.reactivex.rxjava3.schedulers.Schedulers
import org.schabi.newpipe.NewPipeDatabase
import org.schabi.newpipe.R
import org.schabi.newpipe.database.feed.model.FeedGroupEntity
import org.schabi.newpipe.database.subscription.SubscriptionEntity
import org.schabi.newpipe.databinding.FragmentFeedBinding
import org.schabi.newpipe.databinding.PlaylistControlBinding
import org.schabi.newpipe.fragments.BackPressable
import org.schabi.newpipe.error.ErrorInfo
import org.schabi.newpipe.error.ErrorUtil
import org.schabi.newpipe.error.UserAction
import org.schabi.newpipe.extractor.exceptions.AccountTerminatedException
import org.schabi.newpipe.extractor.exceptions.ContentNotAvailableException
import org.schabi.newpipe.extractor.stream.StreamInfoItem
import org.schabi.newpipe.extractor.utils.Utils.isNullOrEmpty
import org.schabi.newpipe.fragments.BaseStateFragment
import org.schabi.newpipe.info_list.ItemViewMode
import org.schabi.newpipe.info_list.dialog.InfoItemDialog
import org.schabi.newpipe.ktx.animate
import org.schabi.newpipe.ktx.animateHideRecyclerViewAllowingScrolling
import org.schabi.newpipe.ktx.slideUp
import org.schabi.newpipe.local.feed.item.StreamItem
import org.schabi.newpipe.local.feed.service.FeedEventManager
import org.schabi.newpipe.local.feed.service.FeedLoadService
import org.schabi.newpipe.local.subscription.SubscriptionManager
import org.schabi.newpipe.player.PlayerService
import org.schabi.newpipe.player.playqueue.PlayQueue
import org.schabi.newpipe.player.playqueue.SinglePlayQueue
import org.schabi.newpipe.util.DeviceUtils
import org.schabi.newpipe.util.Localization
import org.schabi.newpipe.util.NavigationHelper
import org.schabi.newpipe.util.selection.SelectionController
import org.schabi.newpipe.util.selection.SelectionToolbar
import org.schabi.newpipe.util.selection.StreamSelectionActions
import org.schabi.newpipe.util.NavigationHelper.openFeedChannelsFragment
import org.schabi.newpipe.util.ThemeHelper.getGridSpanCountStreams
import org.schabi.newpipe.util.ThemeHelper.getItemViewMode
import org.schabi.newpipe.util.ThemeHelper.resolveDrawable
import org.schabi.newpipe.util.ThemeHelper.shouldUseGridLayout
import java.time.OffsetDateTime
import java.util.function.Consumer

class FeedFragment : BaseStateFragment<FeedState>(), BackPressable {
    private var _feedBinding: FragmentFeedBinding? = null
    private val feedBinding get() = _feedBinding!!

    private val disposables = CompositeDisposable()

    private lateinit var viewModel: FeedViewModel
    @JvmField var listState: Parcelable? = null

    private var groupId = FeedGroupEntity.GROUP_ALL_ID
    private var groupName = ""
    private var oldestSubscriptionUpdate: OffsetDateTime? = null

    private lateinit var groupAdapter: GroupieAdapter
    @JvmField var showPlayedItems: Boolean = true

    private var onSettingsChangeListener: SharedPreferences.OnSharedPreferenceChangeListener? = null
    private var updateListViewModeOnResume = false
    private var updatePullToRefreshOnResume = false
    private var isRefreshing = false
    private var notLoadedDialog: AlertDialog? = null
    private var feedNotAvailableDialog: AlertDialog? = null
    private var infoItemDialog: InfoItemDialog? = null
    private var snackbar: Snackbar? = null
    private var refreshIconAnimator: ObjectAnimator? = null
    // Slice 4: sequential single timerJob, viewLifecycleOwner-bound, cancelled in onDestroyView.
    private var timerJob: Job? = null

    private var lastNewItemsCount = 0

    private var playlistControlBinding: PlaylistControlBinding? = null

    private val selection = SelectionController<Long>()
    private var selectionToolbar: SelectionToolbar? = null

    private var autoBackgroundPlaying = false
    private var randomBackgroundPlaying = false

    // Search related variables
    private var editText: EditText? = null
    private var searchClear: View? = null
    private var originalItems = mutableListOf<StreamItem>()
    private var filteredItems = mutableListOf<StreamItem>()
    private var isFilterEnabled = false
    private var isPullToRefreshEnabled = true

    // Adapter writer serialization (slice 3b fix): Groupie's updateAsync diffs the passed
    // list on a background thread, so every async push gets an ArrayList snapshot — the
    // live originalItems/filteredItems keep mutating on keystrokes. Sync updates run only
    // when no async diff is in flight; otherwise the keystroke joins the async generation
    // chain (latest wins, stale diffs discarded) instead of clobbering it.
    private var asyncUpdateInflight = 0

    private fun pushAsync(items: List<StreamItem>, listener: OnAsyncUpdateListener?) {
        val snapshot = ArrayList(items)
        asyncUpdateInflight++
        try {
            groupAdapter.updateAsync(snapshot, false) {
                asyncUpdateInflight--
                try {
                    listener?.onUpdateComplete()
                } catch (e: Exception) {
                    Log.e(TAG, "async update listener failed", e)
                }
            }
        } catch (e: Exception) {
            asyncUpdateInflight--
            Log.e(TAG, "pushAsync failed", e)
        }
    }

    private fun pushSyncOrAsync(items: List<StreamItem>) {
        if (asyncUpdateInflight > 0) {
            pushAsync(items, null)
        } else {
            try {
                groupAdapter.update(ArrayList(items))
            } catch (e: Exception) {
                pushAsync(items, null)
            }
        }
    }

    private val textWatcher = object : TextWatcher {
        override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}

        override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
            filterItems(s.toString())
        }

        override fun afterTextChanged(s: Editable?) {}
    }

    init {
        setHasOptionsMenu(true)
    }


    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        groupId = arguments?.getLong(KEY_GROUP_ID, FeedGroupEntity.GROUP_ALL_ID)
            ?: FeedGroupEntity.GROUP_ALL_ID
        groupName = arguments?.getString(KEY_GROUP_NAME) ?: ""

        val prefs = PreferenceManager.getDefaultSharedPreferences(requireContext())
        autoBackgroundPlaying = false
        randomBackgroundPlaying = prefs.getBoolean(getString(R.string.random_music_play_mode_key), false)

        onSettingsChangeListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            when (key) {
                getString(R.string.list_view_mode_key) -> {
                    updateListViewModeOnResume = true
                }
                getString(R.string.grid_layout_enabled_key) -> {
                    updateListViewModeOnResume = true
                }
                getString(R.string.grid_columns_key) -> {
                    updateListViewModeOnResume = true
                }
                getString(R.string.grid_columns_landscape_key) -> {
                    updateListViewModeOnResume = true
                }
                getString(R.string.pull_to_refresh_key) -> {
                    updatePullToRefreshOnResume = true
                }
            }
        }
        PreferenceManager.getDefaultSharedPreferences(activity)
            .registerOnSharedPreferenceChangeListener(onSettingsChangeListener)
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
        return inflater.inflate(R.layout.fragment_feed, container, false)
    }

    override fun onViewCreated(rootView: View, savedInstanceState: Bundle?) {
        // super.onViewCreated() calls initListeners() which require the binding to be initialized
        _feedBinding = FragmentFeedBinding.bind(rootView)
        playlistControlBinding = PlaylistControlBinding.bind(feedBinding.playlistControl.root)
        feedBinding.playlistControl.root.configure(getString(R.string.list_control_page_feed))
        super.onViewCreated(rootView, savedInstanceState)
        updatePullToRefreshState()

        requireActivity().onBackPressedDispatcher.addCallback(viewLifecycleOwner, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (activity?.supportActionBar?.customView != null) {
                    destroyCustomViewInActionBar()
                } else {
                    // Let the system handle the back press if search isn't active
                    isEnabled = false
                    requireActivity().onBackPressedDispatcher.onBackPressed()
                }
            }
        })

        val factory = FeedViewModel.Factory(requireContext(), groupId)
        viewModel = ViewModelProvider(this, factory).get(FeedViewModel::class.java)
        showPlayedItems = viewModel.getShowPlayedItemsFromPreferences()
        // Bundle backup for process death; rotation keeps the VM instance itself.
        savedInstanceState?.getString("feedQuery")?.let { viewModel.savedQuery = it }
        viewModel.stateLiveData.observe(viewLifecycleOwner) { it?.let(::handleResult) }

        // Slice 4: collect the pre-throttle timer ticks to cancel/relaunch timerJob.
        // Retry on unexpected errors so one bad tick can't park the timers silently.
        viewLifecycleOwner.lifecycleScope.launch {
            while (isActive) {
                try {
                    viewModel.timerTicks.collect { event -> onScopedTimerTick(event) }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.w(TAG, "timerTicks collect ended, retrying", e)
                    try {
                        delay(1_000)
                    } catch (ce: CancellationException) {
                        throw ce
                    }
                }
            }
        }

        // Slice 4 rotation re-derive: re-arm the phase-appropriate timer with the
        // remaining delay, or re-show the sticky snackbar. First creation is a no-op
        // (refreshRequestedAt is null until the first reloadContent).
        try {
            if (::viewModel.isInitialized && isAdded) {
                val now = SystemClock.elapsedRealtime()
                if (viewModel.failedSticky) {
                    try {
                        showRetrySnackbar()
                    } catch (e: Exception) {
                        Log.w(TAG, "sticky re-show has no view", e)
                    }
                    try {
                        updateRefreshViewState()
                    } catch (e: Exception) {
                    }
                } else {
                    val requestedAt = viewModel.refreshRequestedAt
                    val lastProgressAt = viewModel.lastProgressAt
                    val latest = viewModel.latestScopedEvent
                    // Throttle-lag guard: latest already Progress but not yet stamped —
                    // the 60 s leg owns this window, not an immediate 10 s trip.
                    if (requestedAt != null && lastProgressAt == null &&
                        latest !is FeedEventManager.Event.ProgressEvent
                    ) {
                        val remaining = TIMEOUT_NO_EVENT_MS - (now - requestedAt)
                        if (remaining <= 0) {
                            onRefreshTimeout("timeout10s-rearm-elapsed")
                        } else {
                            launchTimeout(remaining) { onRefreshTimeout("timeout10s-rearm") }
                        }
                    } else if (latest is FeedEventManager.Event.ProgressEvent) {
                        val lastAt = lastProgressAt ?: requestedAt ?: now
                        val remaining = TIMEOUT_NO_PROGRESS_MS - (now - lastAt)
                        if (remaining <= 0) {
                            onRefreshTimeout("timeout60s-rearm-elapsed")
                        } else {
                            launchTimeout(remaining) { onRefreshTimeout("timeout60s-rearm") }
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "timer re-arm failed", e)
        }

        groupAdapter = GroupieAdapter().apply {
            setOnItemClickListener(listenerStreamItem)
            setOnItemLongClickListener(listenerStreamItem)
        }

        feedBinding.itemsList.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrollStateChanged(recyclerView: RecyclerView, newState: Int) {
                // Check if we scrolled to the top
                if (newState == RecyclerView.SCROLL_STATE_IDLE &&
                    !recyclerView.canScrollVertically(-1)
                ) {

                    if (tryGetNewItemsLoadedButton()?.isVisible == true) {
                        hideNewItemsLoaded(true)
                    }
                }
            }
        })

        feedBinding.itemsList.adapter = groupAdapter
        setupListViewMode()

        selection.setListener { controller ->
            selectionToolbar?.update(controller)
            applySelectionToItems()
        }
    }

    override fun onPause() {
        super.onPause()
        listState = feedBinding.itemsList.layoutManager?.onSaveInstanceState()
        selectionToolbar?.release()
        selectionToolbar = null
    }

    override fun onResume() {
        super.onResume()
        updateRelativeTimeViews()
        selectionToolbar = SelectionToolbar.bind(
            activity, R.menu.menu_stream_selection, selection, ::onSelectionAction
        )

        if (updateListViewModeOnResume) {
            updateListViewModeOnResume = false

            setupListViewMode()
            if (viewModel.stateLiveData.value != null) {
                handleResult(viewModel.stateLiveData.value!!)
            }
        }

        if (updatePullToRefreshOnResume) {
            updatePullToRefreshOnResume = false
            updatePullToRefreshState()
        }
    }

    private fun setupListViewMode() {
        // does everything needed to setup the layouts for grid or list modes
        groupAdapter.spanCount = if (shouldUseGridLayout(context)) getGridSpanCountStreams(context) else 1
        feedBinding.itemsList.layoutManager = GridLayoutManager(requireContext(), groupAdapter.spanCount).apply {
            spanSizeLookup = groupAdapter.spanSizeLookup
        }
    }

    override fun initListeners() {
        super.initListeners()
        feedBinding.refreshRootView.setOnClickListener { reloadContent() }
        feedBinding.refreshIcon.setOnClickListener { reloadContent() }
        feedBinding.refreshSubtitleText.setOnLongClickListener {
            showNotLoadedSubscriptions()
            true
        }
        feedBinding.swipeRefreshLayout.setOnRefreshListener { reloadContent() }
        feedBinding.newItemsLoadedButton.setOnClickListener {
            hideNewItemsLoaded(true)
            feedBinding.itemsList.scrollToPosition(0)
        }
        setupPlaylistControlListeners()
        updateSwipeRefreshListener()
    }

    private fun updatePullToRefreshState() {
        val prefs = PreferenceManager.getDefaultSharedPreferences(requireContext())
        isPullToRefreshEnabled = prefs.getBoolean(
            getString(R.string.pull_to_refresh_key),
            true
        )

        // Update the SwipeRefreshLayout state
        updateSwipeRefreshListener()

        // Optionally disable the visual refresh indicator if disabled
        if (!isPullToRefreshEnabled) {
            feedBinding.swipeRefreshLayout.isEnabled = false
        } else {
            feedBinding.swipeRefreshLayout.isEnabled = true
        }
    }

    private fun updateSwipeRefreshListener() {
        // Remove existing listener first
        feedBinding.swipeRefreshLayout.setOnRefreshListener(null)

        // Only add listener if pull-to-refresh is enabled
        if (isPullToRefreshEnabled) {
            feedBinding.swipeRefreshLayout.setOnRefreshListener { reloadContent() }
        }
    }

    private fun setupPlaylistControlListeners() {
        playlistControlBinding?.let { binding ->
            binding.playlistCtrlPlayAllButton.setOnClickListener {
                val playQueue = getPlayQueue()
                if (playQueue.streams.isNotEmpty()) {
                    NavigationHelper.playOnMainPlayer(activity, playQueue)
                }
            }

            binding.playlistCtrlPlayPopupButton.setOnClickListener {
                val playQueue = getPlayQueue()
                if (playQueue.streams.isNotEmpty()) {
                    NavigationHelper.playOnPopupPlayer(activity, playQueue, false)
                }
            }

            binding.playlistCtrlPlayBgButton.setOnClickListener {
                val playQueue = getPlayQueue()
                if (playQueue.streams.isNotEmpty()) {
                    val prefs = PreferenceManager.getDefaultSharedPreferences(requireContext())
                    if (prefs.getBoolean(getString(R.string.random_music_play_mode_key), false)) {
                        NavigationHelper.playOnBackgroundPlayerShuffled(activity, playQueue, false)
                    } else {
                        NavigationHelper.playOnBackgroundPlayer(activity, playQueue, false)
                    }
                }
            }

            // Long click listeners for enqueue functionality
            binding.playlistCtrlPlayPopupButton.setOnLongClickListener {
                val playQueue = getPlayQueue()
                if (playQueue.streams.isNotEmpty()) {
                    NavigationHelper.enqueueOnPlayer(activity, playQueue, PlayerService.PlayerType.POPUP)
                }
                true
            }

            binding.playlistCtrlPlayBgButton.setOnLongClickListener {
                val playQueue = getPlayQueue()
                if (playQueue.streams.isNotEmpty()) {
                    NavigationHelper.enqueueOnPlayer(activity, playQueue, PlayerService.PlayerType.AUDIO)
                }
                true
            }
        }
    }

    // /////////////////////////////////////////////////////////////////////////
    // Multi-selection
    // /////////////////////////////////////////////////////////////////////////

    /** Marks the items according to the selection and refreshes their highlight only. */
    private fun applySelectionToItems() {
        for (item in originalItems) {
            item.isItemSelected = selection.isSelected(item.streamWithState.stream.uid)
        }
        groupAdapter.notifyItemRangeChanged(0, groupAdapter.itemCount, StreamItem.UPDATE_SELECTION)
    }

    private fun onSelectionAction(item: MenuItem): Boolean {
        val shownKeys = (if (isFilterEnabled) filteredItems else originalItems)
            .map { it.streamWithState.stream.uid }
        when (item.itemId) {
            R.id.action_selection_select_all -> selection.selectAll(shownKeys)
            R.id.action_selection_invert -> selection.invert(shownKeys)
            else -> {
                // in the order they are shown, including ones hidden by the search filter
                val streams = originalItems
                    .filter { selection.isSelected(it.streamWithState.stream.uid) }
                    .map { it.streamWithState.stream }
                val handled = StreamSelectionActions.handle(this, disposables, item, streams)
                if (handled) {
                    selection.exit()
                }
                return handled
            }
        }
        return true
    }

    // Add method to create PlayQueue from feed items
    private fun getPlayQueue(startIndex: Int = 0): PlayQueue {
        val streamInfoItems = mutableListOf<StreamInfoItem>()
        val itemsToUse = if (isFilterEnabled) filteredItems else originalItems

        // Extract StreamInfoItems from the current items
        for (item in itemsToUse) {
            streamInfoItems.add(item.streamWithState.stream.toStreamInfoItem())
        }

        return if (streamInfoItems.isNotEmpty()) {
            SinglePlayQueue(streamInfoItems, startIndex.coerceAtMost(streamInfoItems.size - 1))
        } else {
            SinglePlayQueue(emptyList(), 0)
        }
    }

    // /////////////////////////////////////////////////////////////////////////
    // Menu
    // /////////////////////////////////////////////////////////////////////////

    private fun Menu.isItemVisible(@IdRes itemId: Int): Boolean =
        findItem(itemId)?.let { it.isVisible } ?: false

    override fun onCreateOptionsMenu(menu: Menu, inflater: MenuInflater) {
        super.onCreateOptionsMenu(menu, inflater)

        activity.supportActionBar?.setDisplayShowTitleEnabled(true)
        if (groupName == ""){
            activity.supportActionBar?.setTitle(R.string.fragment_feed_title)
        } else {
            activity.supportActionBar?.title = groupName
        }

//        activity.supportActionBar?.subtitle = groupName

        inflater.inflate(R.menu.menu_feed_fragment, menu)
        inflater.inflate(R.menu.menu_multi_select, menu)
        updateTogglePlayedItemsButton(menu.findItem(R.id.menu_item_feed_toggle_played_items))
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == R.id.action_multi_select) {
            selection.enter()
            return true
        } else if (item.itemId == R.id.menu_item_feed_toggle_played_items) {
            showPlayedItems = !item.isChecked
            updateTogglePlayedItemsButton(item)
            viewModel.togglePlayedItems(showPlayedItems)
            viewModel.saveShowPlayedItemsToPreferences(showPlayedItems)
        } else if (item.itemId == R.id.menu_item_feed_channel_list) {
            openFeedChannelsFragment(fm, groupId, groupName)
        } else if (item.itemId == R.id.action_search_feed) {
            setupSearchInActionBar()
            return true
        }

        return super.onOptionsItemSelected(item)
    }

    override fun onBackPressed(): Boolean {
        if (editText != null && activity?.supportActionBar?.customView != null) {
            destroyCustomViewInActionBar()
            return true
        }
        return false
    }

    override fun onDestroyOptionsMenu() {
        super.onDestroyOptionsMenu()
        activity?.supportActionBar?.subtitle = null
    }

    override fun onDestroy() {
        disposables.dispose()
        if (onSettingsChangeListener != null) {
            PreferenceManager.getDefaultSharedPreferences(activity)
                .unregisterOnSharedPreferenceChangeListener(onSettingsChangeListener)
            onSettingsChangeListener = null
        }

        super.onDestroy()
        activity?.supportActionBar?.subtitle = null
    }

    override fun onDestroyView() {

        if (activity.supportActionBar?.customView != null) {
            destroyCustomViewInActionBar()
        }
        // Ensure that all animations are canceled
        tryGetNewItemsLoadedButton()?.clearAnimation()

        selection.setListener(null)
        cancelTimer()
        stopRefreshIconSpin()
        notLoadedDialog?.dismiss()
        notLoadedDialog = null
        feedNotAvailableDialog?.dismiss()
        feedNotAvailableDialog = null
        infoItemDialog?.dismiss()
        infoItemDialog = null
        snackbar?.dismiss()
        snackbar = null
        feedBinding.itemsList.adapter = null
        _feedBinding = null
        playlistControlBinding?.let { binding ->
            binding.playlistCtrlPlayAllButton.setOnClickListener(null)
            binding.playlistCtrlPlayPopupButton.setOnClickListener(null)
            binding.playlistCtrlPlayBgButton.setOnClickListener(null)
            binding.playlistCtrlPlayPopupButton.setOnLongClickListener(null)
            binding.playlistCtrlPlayBgButton.setOnLongClickListener(null)
        }
        playlistControlBinding = null

        originalItems.clear()
        filteredItems.clear()
        editText = null
        searchClear = null

        super.onDestroyView()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        listState?.let { outState.putParcelable("listState", it) }
        outState.putBoolean("showPlayedItems", showPlayedItems)
        try {
            if (::viewModel.isInitialized) {
                viewModel.savedQuery?.let { outState.putString("feedQuery", it) }
            } else {
                editText?.text?.toString()?.takeIf { it.isNotEmpty() }?.let {
                    outState.putString("feedQuery", it)
                }
            }
        } catch (e: Exception) {
        }
        outState.putBundle("selection", Bundle().also { selection.saveState(it) })
    }

    override fun onRestoreInstanceState(savedInstanceState: Bundle) {
        super.onRestoreInstanceState(savedInstanceState)
        listState = savedInstanceState.getParcelable("listState")
        showPlayedItems = savedInstanceState.getBoolean("showPlayedItems", true)
        selection.restoreState(savedInstanceState.getBundle("selection"))
    }

    private fun updateTogglePlayedItemsButton(menuItem: MenuItem) {
        menuItem.isChecked = showPlayedItems
        menuItem.icon = AppCompatResources.getDrawable(
            requireContext(),
            if (showPlayedItems) R.drawable.ic_visibility_on else R.drawable.ic_visibility_off
        )
    }

    /*
     Search
     */

    private fun setupSearchInActionBar() {
        val actionBar = activity.supportActionBar ?: return
        val customView = layoutInflater.inflate(R.layout.feed_search_toolbar, null, false)

        // Hide the title and let the search bar take the full width
        actionBar.setDisplayShowTitleEnabled(false)
        actionBar.setCustomView(
            customView,
            ActionBar.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        )
        actionBar.setDisplayShowCustomEnabled(true)

        editText = activity.findViewById(R.id.toolbar_search_edit_text_feed)
        editText?.requestFocus()

        val imm = activity.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        imm.showSoftInput(editText, InputMethodManager.SHOW_IMPLICIT)

        // Hide the search menu item
        try {
            activity.findViewById<View>(R.id.action_search_feed)?.visibility = View.GONE
        } catch (e: Exception) {
            // ignore
        }

        searchClear = customView.findViewById(R.id.toolbar_search_clear_feed)
        searchClear?.setOnClickListener {
            if (editText?.text?.isEmpty() == true) {
                editText?.clearFocus()
                destroyCustomViewInActionBar()
            } else {
                editText?.setText("")
            }
        }

        try {
            editText?.removeTextChangedListener(textWatcher)
        } catch (e: Exception) {
            // ignore
        }
        try {
            val restored = if (::viewModel.isInitialized) viewModel.savedQuery else null
            if (!restored.isNullOrEmpty()) editText?.setText(restored)
        } catch (e: Exception) {
            // ignore
        }
        editText?.addTextChangedListener(textWatcher)

        setupTouchListeners(feedBinding.root)
    }

    private fun setupTouchListeners(view: View) {
        // Skip if this is our EditText
        if (view == editText) {
            return
        }

        // For other views, set a touch listener to clear focus
        if (view !is EditText) {
            view.setOnTouchListener { _, event ->
                if (event.action == MotionEvent.ACTION_DOWN && editText?.hasFocus() == true) {
                    editText?.clearFocus()
                    val imm = activity.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
                    imm.hideSoftInputFromWindow(editText?.windowToken, 0)
                }
                false
            }
        }

        // If it's a ViewGroup, apply to all children
        if (view is ViewGroup) {
            for (i in 0 until view.childCount) {
                setupTouchListeners(view.getChildAt(i))
            }
        }

        // Add specific listener for the RecyclerView
        if (view.id == R.id.items_list) {
            val recyclerView = view as RecyclerView
            recyclerView.addOnItemTouchListener(object : RecyclerView.OnItemTouchListener {
                override fun onInterceptTouchEvent(rv: RecyclerView, e: MotionEvent): Boolean {
                    if (e.action == MotionEvent.ACTION_DOWN && editText?.hasFocus() == true) {
                        editText?.clearFocus()
                        val imm = activity.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
                        imm.hideSoftInputFromWindow(editText?.windowToken, 0)
                    }
                    return false
                }

                override fun onTouchEvent(rv: RecyclerView, e: MotionEvent) {}
                override fun onRequestDisallowInterceptTouchEvent(disallowIntercept: Boolean) {}
            })
        }
    }

    private fun rebuildFilteredList(query: String?) {
        if (!query.isNullOrEmpty()) {
            isFilterEnabled = true
            filteredItems.clear()
            for (item in originalItems) {
                val stream = item.streamWithState.stream
                if (stream.title.contains(query, ignoreCase = true) ||
                    stream.uploader.contains(query, ignoreCase = true)) {
                    filteredItems.add(item)
                }
            }
        } else {
            isFilterEnabled = false
            filteredItems.clear()
            filteredItems.addAll(originalItems)
        }
    }

    private fun filterItems(text: String) {
        try {
            if (::viewModel.isInitialized) {
                viewModel.savedQuery = text.ifEmpty { null }
                viewModel.filterGeneration++
            }
        } catch (e: Exception) {
        }
        rebuildFilteredList(text.ifEmpty { null })

        // Use synchronous update to avoid race conditions during rapid filtering.
        // Joins the async chain while a refresh diff is in flight (see pushSyncOrAsync).
        try {
            pushSyncOrAsync(if (isFilterEnabled) filteredItems else originalItems)
        } catch (e: Exception) {
            // Fallback to async if needed
            pushAsync(if (isFilterEnabled) filteredItems else originalItems, null)
        }

        // Always scroll to top when filter changes
        val itemsList = _feedBinding?.itemsList
        itemsList?.post {
            try {
                itemsList.layoutManager?.scrollToPosition(0)
            } catch (e: Exception) {
            }
        }
    }

    private fun clearFilter() {
        try {
            if (::viewModel.isInitialized) {
                viewModel.savedQuery = null
                viewModel.filterGeneration++
            }
        } catch (e: Exception) {
        }
        isFilterEnabled = false
        filteredItems.clear()
        // Cancel any ongoing diff operations before starting a new one
        try {
            // Update synchronously to avoid race conditions
            pushSyncOrAsync(originalItems)
        } catch (e: Exception) {
            // Fallback to async with proper synchronization
            pushAsync(originalItems, null)
        }
        // Always scroll to top when clearing filter
        val itemsList = _feedBinding?.itemsList
        itemsList?.post {
            try {
                itemsList.layoutManager?.scrollToPosition(0)
            } catch (e: Exception) {
            }
        }
    }

    private fun destroyCustomViewInActionBar() {
        val actionBar = activity.supportActionBar ?: return

        // Remove text watcher first to prevent additional filtering during cleanup
        try {
            editText?.removeTextChangedListener(textWatcher)
        } catch (e: Exception) {
            // ignore
        }

        actionBar.setCustomView(null)
        actionBar.setDisplayShowCustomEnabled(false)
        actionBar.setDisplayShowTitleEnabled(true)

        val searchLocal = activity.findViewById<View>(R.id.action_search_feed)
        searchLocal?.visibility = View.VISIBLE

        // Clear filter after removing listeners
        clearFilter()

        val imm = activity.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        imm.hideSoftInputFromWindow(activity.window.decorView.windowToken, 0)

        editText = null
        searchClear = null
    }


    // //////////////////////////////////////////////////////////////////////////
    // Handling
    // //////////////////////////////////////////////////////////////////////////

    private var lastSurfacedErrorSet: Set<String>? = null

    // Rows hidden behind a no-match filter still count as content: fullscreen
    // first-start UI only when truly nothing is loaded and no filter hides it.
    private fun hasVisibleOrHiddenContent(): Boolean {
        return try {
            (::groupAdapter.isInitialized && groupAdapter.itemCount > 0) ||
                originalItems.isNotEmpty()
        } catch (e: Exception) {
            false
        }
    }

    override fun showLoading() {
        super.showLoading()
        val hasContent = hasVisibleOrHiddenContent()
        if (!hasContent) {
            feedBinding.itemsList.animateHideRecyclerViewAllowingScrolling()
            feedBinding.refreshRootView.animate(false, 0)
            feedBinding.loadingProgressText.animate(true, 200)
        } else {
            // Refresh-with-content: keep list + header, hide centered indicator.
            feedBinding.loadingProgressBar.animate(false, 0)
            feedBinding.loadingProgressText.animate(false, 0)
        }
        // Setting the swipe spinner while disabled still shows it on some versions.
        if (isPullToRefreshEnabled) {
            feedBinding.swipeRefreshLayout.isRefreshing = true
        }
        isRefreshing = true
        Log.d("FeedRefresh", "spinnerStart/showLoading@" + SystemClock.elapsedRealtime())
    }

    override fun hideLoading() {
        super.hideLoading()
        stopRefreshIconSpin()
        Log.d("FeedRefresh", "spinnerClear/hideLoading@" + SystemClock.elapsedRealtime())
        feedBinding.itemsList.animate(true, 0)
        feedBinding.refreshRootView.animate(true, 200)
        feedBinding.loadingProgressText.animate(false, 0)
        feedBinding.swipeRefreshLayout.isRefreshing = false
        isRefreshing = false
    }

    override fun showEmptyState() {
        super.showEmptyState()
        stopRefreshIconSpin()
        Log.d("FeedRefresh", "spinnerClear/showEmptyState@" + SystemClock.elapsedRealtime())
        feedBinding.itemsList.animateHideRecyclerViewAllowingScrolling()
        feedBinding.refreshRootView.animate(true, 200)
        feedBinding.loadingProgressText.animate(false, 0)
        feedBinding.swipeRefreshLayout.isRefreshing = false
        isRefreshing = false
        playlistControlBinding?.root?.isVisible = false
    }

    override fun handleResult(result: FeedState) {
        when (result) {
            is FeedState.ProgressState -> handleProgressState(result)
            is FeedState.RefreshingState -> handleRefreshingState(result)
            is FeedState.LoadedState -> handleLoadedState(result)
            is FeedState.ErrorState -> if (handleErrorState(result)) return
        }

        updateRefreshViewState()
    }

    override fun handleError() {
        super.handleError()
        stopRefreshIconSpin()
        Log.d("FeedRefresh", "spinnerClear/handleError@" + SystemClock.elapsedRealtime())
        val hasContent = hasVisibleOrHiddenContent()
        if (!hasContent) {
            feedBinding.itemsList.animateHideRecyclerViewAllowingScrolling()
            feedBinding.refreshRootView.animate(false, 0)
        }
        feedBinding.loadingProgressText.animate(false, 0)
        feedBinding.swipeRefreshLayout.isRefreshing = false
        isRefreshing = false
    }

    private fun handleRefreshingState(state: FeedState.RefreshingState) {
        if (::groupAdapter.isInitialized && groupAdapter.itemCount == 0 &&
            state.items.isNotEmpty()
        ) {
            // Rotation re-derive: repopulate kept rows from the replayed state.
            // No celebration here — the terminal has not arrived yet.
            val itemVersion = try {
                when (getItemViewMode(requireContext())) {
                    ItemViewMode.GRID -> StreamItem.ItemVersion.GRID
                    ItemViewMode.CARD -> StreamItem.ItemVersion.CARD
                    else -> StreamItem.ItemVersion.NORMAL
                }
            } catch (e: Exception) {
                StreamItem.ItemVersion.NORMAL
            }
            state.items.forEach { it.itemVersion = itemVersion }
            originalItems.clear()
            originalItems.addAll(state.items)
            selection.retainOnly(state.items.map { it.streamWithState.stream.uid })
            state.items.forEach {
                it.isItemSelected = selection.isSelected(it.streamWithState.stream.uid)
            }
            val query = try {
                viewModel.savedQuery?.takeIf { it.isNotEmpty() }
            } catch (e: Exception) {
                null
            }
            rebuildFilteredList(query)
            val shown = if (isFilterEnabled) filteredItems else originalItems
            pushAsync(shown, null)
            listState?.run {
                try {
                    feedBinding.itemsList.layoutManager?.onRestoreInstanceState(listState)
                } catch (e: Exception) {
                }
                listState = null
            }
            playlistControlBinding?.root?.isVisible = shown.isNotEmpty()
        }
        showLoading()
        updateRefreshingHeader(state)
        startRefreshIconSpin()
    }

    private fun updateRefreshingHeader(state: FeedState.RefreshingState) {
        val refreshingText: String? = when {
            state.maxProgress > 0 && state.currentProgress >= 0 ->
                getString(
                    R.string.feed_refreshing_progress,
                    state.currentProgress,
                    state.maxProgress
                )
            state.maxProgress > 0 && state.currentProgress == -1 -> {
                Log.w(
                    "FeedRefresh",
                    "mixed progress current=-1 max=${state.maxProgress}"
                )
                getString(R.string.feed_refreshing_progress, 0, state.maxProgress)
            }
            state.progressMessage != 0 -> getString(state.progressMessage)
            else -> null
        }
        val combinedText: String? = if (
            refreshingText != null && state.notLoadedCount > 0
        ) {
            getString(
                R.string.feed_refreshing_with_not_loaded,
                refreshingText,
                getString(
                    R.string.feed_subscription_not_loaded_count,
                    state.notLoadedCount
                )
            )
        } else {
            refreshingText
        }
        if (combinedText != null) {
            feedBinding.refreshSubtitleText.isVisible = true
            feedBinding.refreshSubtitleText.text = combinedText
            feedBinding.refreshSubtitleText.contentDescription = combinedText
        } else {
            feedBinding.refreshSubtitleText.isVisible = false
            feedBinding.refreshSubtitleText.text = ""
            feedBinding.refreshSubtitleText.contentDescription = null
        }
    }

    private fun startRefreshIconSpin() {
        try {
            val view = _feedBinding?.refreshIcon ?: return
            if (refreshIconAnimator?.isRunning == true) return
            refreshIconAnimator?.cancel()
            refreshIconAnimator = ObjectAnimator.ofFloat(view, "rotation", 0f, 360f).apply {
                duration = 1000
                interpolator = LinearInterpolator()
                repeatCount = ObjectAnimator.INFINITE
                start()
            }
        } catch (e: Exception) {
            Log.w("FeedRefresh", "spin start failed", e)
        }
    }

    private fun stopRefreshIconSpin() {
        try {
            refreshIconAnimator?.cancel()
        } catch (e: Exception) {
        }
        refreshIconAnimator = null
        try {
            _feedBinding?.refreshIcon?.rotation = 0f
        } catch (e: Exception) {
        }
    }

    private fun handleProgressState(progressState: FeedState.ProgressState) {
        showLoading()

        val isIndeterminate = progressState.currentProgress == -1 &&
            progressState.maxProgress == -1

        feedBinding.loadingProgressText.text = if (!isIndeterminate) {
            val current = if (progressState.maxProgress > 0 && progressState.currentProgress == -1) {
                0
            } else {
                progressState.currentProgress
            }
            "$current/${progressState.maxProgress}"
        } else if (progressState.progressMessage > 0) {
            getString(progressState.progressMessage)
        } else {
            "∞/∞"
        }

        feedBinding.loadingProgressBar.isIndeterminate = isIndeterminate ||
            (progressState.maxProgress > 0 && progressState.currentProgress == 0)
        // Never write negatives: setMax(-1) corrupts the bar even when indeterminate.
        if (progressState.currentProgress >= 0) {
            feedBinding.loadingProgressBar.progress = progressState.currentProgress
        }
        if (progressState.maxProgress >= 0) {
            feedBinding.loadingProgressBar.max = progressState.maxProgress
        }
    }

    private fun showInfoItemDialog(item: StreamInfoItem) {
        val context = context
        val activity: Activity? = getActivity()
        if (context == null || context.resources == null || activity == null) return

        infoItemDialog?.dismiss()
        infoItemDialog = InfoItemDialog.Builder(activity, context, this, item).create()
        infoItemDialog?.show()
    }

    private val listenerStreamItem = object : OnItemClickListener, OnItemLongClickListener {
        override fun onItemClick(item: Item<*>, view: View) {
            if (item is StreamItem && selection.isActive) {
                selection.toggle(item.streamWithState.stream.uid)
                return
            }
            if (item is StreamItem) {
                val stream = item.streamWithState.stream

                if (autoBackgroundPlaying) {
                    // Find the index of the clicked item
                    val clickedIndex = groupAdapter.getAdapterPosition(item)
                    val playQueue = getPlayQueue(clickedIndex)

                    if (randomBackgroundPlaying) {
                        playQueue.shuffle()
                    }
                    NavigationHelper.playOnBackgroundPlayer(activity, playQueue, false)
                } else {
                    NavigationHelper.openVideoDetailFragment(
                        requireContext(), fm,
                        stream.serviceId, stream.url, stream.title, null, false
                    )
                }
            }
        }

        override fun onItemLongClick(item: Item<*>, view: View): Boolean {
            if (item is StreamItem) {
                showInfoItemDialog(item.streamWithState.stream.toStreamInfoItem())
                return true
            }
            return false
        }
    }

    @SuppressLint("StringFormatMatches")
    private fun handleLoadedState(loadedState: FeedState.LoadedState) {
        // Terminal render owns the spinner from here: a live timeout would post a
        // bogus retry snackbar if the tick path ever misses it.
        cancelTimer()
        snackbar?.dismiss()
        // Scoped celebration (slice 3b): only a fresh non-cancelled Success sets the
        // pending flag; DB-only recombines never do. Selection skips + freezes the count.
        val celebrate = try {
            viewModel.consumeCelebration() && !selection.isActive
        } catch (e: Exception) {
            !selection.isActive
        }
        val itemVersion = when (getItemViewMode(requireContext())) {
            ItemViewMode.GRID -> StreamItem.ItemVersion.GRID
            ItemViewMode.CARD -> StreamItem.ItemVersion.CARD
            else -> StreamItem.ItemVersion.NORMAL
        }
        loadedState.items.forEach { it.itemVersion = itemVersion }

        // the feed items are recreated on every update, so drop selected ones that disappeared
        // and mark the new instances
        selection.retainOnly(loadedState.items.map { it.streamWithState.stream.uid })
        loadedState.items.forEach { it.isItemSelected = selection.isSelected(it.streamWithState.stream.uid) }

        // Store original items for filtering; re-apply the saved query without scroll jump
        // (never via filterItems, which scrolls to top).
        originalItems.clear()
        originalItems.addAll(loadedState.items)
        val query = try {
            viewModel.savedQuery?.takeIf { it.isNotEmpty() }
        } catch (e: Exception) {
            null
        } ?: editText?.text?.toString()?.takeIf { it.isNotEmpty() }
        rebuildFilteredList(query)
        val shownItems = if (isFilterEnabled) filteredItems else originalItems
        val unfilteredEmpty = loadedState.items.isEmpty()
        val filteredEmpty = !unfilteredEmpty && shownItems.isEmpty()

        // Playlist follows the shown (filtered when filtering) size.
        playlistControlBinding?.root?.isVisible = shownItems.isNotEmpty()

        // This need to be saved in a variable as the update occurs async
        val oldOldestSubscriptionUpdate = oldestSubscriptionUpdate
        val applyGen = try {
            viewModel.filterGeneration
        } catch (e: Exception) {
            -1L
        }

        if (filteredEmpty) {
            // Filtered-empty: visible empty list, hidden playlist, no celebration.
            pushAsync(shownItems, null)
        } else {
            pushAsync(shownItems) {
                if (!celebrate) return@pushAsync
                val stillCurrent = try {
                    viewModel.filterGeneration == applyGen
                } catch (e: Exception) {
                    true
                }
                if (!stillCurrent) return@pushAsync
                oldOldestSubscriptionUpdate?.run {
                    highlightNewItemsAfter(oldOldestSubscriptionUpdate)
                }
            }
        }
        listState?.run {
            feedBinding.itemsList.layoutManager?.onRestoreInstanceState(listState)
            listState = null
        }

        if (filteredEmpty) {
            feedBinding.refreshSubtitleText.isVisible = true
            feedBinding.refreshSubtitleText.text = getString(R.string.feed_no_filter_results)
            feedBinding.refreshSubtitleText.contentDescription =
                getString(R.string.feed_no_filter_results)
        } else {
            val feedsNotLoaded = loadedState.notLoadedCount > 0
            feedBinding.refreshSubtitleText.isVisible = feedsNotLoaded
            if (feedsNotLoaded) {
                feedBinding.refreshSubtitleText.text = getString(
                    R.string.feed_subscription_not_loaded_count,
                    loadedState.notLoadedCount
                )
            }
        }

        // Re-render guard (pass 2): the VM dedupes across terminals, but re-rendering
        // the same LoadedState (view-mode toggle, rotation replay) bypasses it.
        // Surface only when the set changed since this view last surfaced it.
        val errorSet = try {
            viewModel.errorSetKey(loadedState.itemsErrors)
        } catch (e: Exception) {
            null
        }
        if (errorSet != null && errorSet != lastSurfacedErrorSet) {
            lastSurfacedErrorSet = errorSet
            handleItemsErrors(loadedState.itemsErrors)
        }
        oldestSubscriptionUpdate = loadedState.oldestUpdate

        if (unfilteredEmpty) {
            showEmptyState()
        } else {
            // Filtered-empty also settles here: visible RV with 0 rows, empty view hidden.
            hideLoading()
        }
    }

    private fun handleErrorState(errorState: FeedState.ErrorState): Boolean {
        cancelTimer()
        // Defensive null branch: the VM never posts ErrorState(null) today.
        return if (errorState.error == null) {
            hideLoading()
            false
        } else if (!hasVisibleOrHiddenContent()) {
            showError(ErrorInfo(errorState.error, UserAction.REQUESTED_FEED, "Loading feed"))
            true
        } else {
            // Fatal-with-content: keep stale list + header, offer snackbar + header retry.
            // failedSticky lives in the VM (set on the scoped Error pass-through).
            hideLoading()
            updateRefreshViewState()
            showFatalSnackbar(errorState.error)
            true
        }
    }

    private fun showRetrySnackbar() {
        if (snackbar?.isShown == true) return
        val root: View = _feedBinding?.itemsList ?: requireView()
        snackbar = Snackbar.make(
            root,
            getString(R.string.feed_load_error),
            Snackbar.LENGTH_LONG
        ).setAction(getString(R.string.retry)) { reloadContent() }
        snackbar?.show()
    }

    private fun showFatalSnackbar(error: Throwable) {
        if (snackbar?.isShown == true) return
        try {
            showRetrySnackbar()
        } catch (e: Exception) {
            try {
                ErrorUtil.showSnackbar(
                    this,
                    ErrorInfo(error, UserAction.REQUESTED_FEED, "Loading feed")
                )
            } catch (e: Exception) {
            }
        }
    }

    // Slice 4 timers: sequential single timerJob. Ticks arrive pre-throttle via the VM
    // push channel; terminal ticks cancel, Progress ticks (re)launch the 60 s leg.
    private fun cancelTimer() {
        try {
            timerJob?.cancel()
        } catch (e: Exception) {
        }
        timerJob = null
    }

    private fun launchTimeout(delayMs: Long, onTrip: () -> Unit) {
        cancelTimer()
        val scope = try {
            viewLifecycleOwner.lifecycleScope
        } catch (e: Exception) {
            return
        }
        timerJob = scope.launch {
            try {
                delay(delayMs)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                return@launch
            }
            if (!isAdded) return@launch
            onTrip()
        }
    }

    private fun onRefreshTimeout(reason: String) {
        timerJob = null
        if (!isAdded || _feedBinding == null) return
        // Narrow terminal race: a terminal queued behind this task already settled
        // the load — don't resurrect a failure on top of fresh content.
        val awaiting = try {
            val latest = viewModel.latestScopedEvent
            latest is FeedEventManager.Event.ProgressEvent ||
                (viewModel.refreshRequestedAt != null && viewModel.lastProgressAt == null)
        } catch (e: Exception) {
            true
        }
        if (!awaiting) return
        try {
            viewModel.onNoProgressTimeout()
        } catch (e: Exception) {
        }
        if (!hasVisibleOrHiddenContent()) {
            // No-content timeout mirrors the fatal path: error container, no
            // infinite centered spinner, header retry + snackbar below.
            handleError()
        } else {
            isRefreshing = false
            try {
                feedBinding.swipeRefreshLayout.isRefreshing = false
            } catch (e: Exception) {
            }
            stopRefreshIconSpin()
        }
        Log.d("FeedRefresh", "spinnerClear/" + reason + "@" + SystemClock.elapsedRealtime())
        try {
            updateRefreshViewState()
        } catch (e: Exception) {
        }
        try {
            showRetrySnackbar()
        } catch (e: Exception) {
            Log.w(TAG, "stuck snackbar has no view", e)
        }
    }

    private fun onScopedTimerTick(event: FeedEventManager.Event) {
        if (!isAdded || _feedBinding == null) return
        cancelTimer()
        if (event is FeedEventManager.Event.ProgressEvent) {
            val sticky = try {
                viewModel.failedSticky
            } catch (e: Exception) {
                false
            }
            if (sticky) {
                // Late arrival after a failure: re-light, dismiss the stuck snackbar,
                // resume 60 s tracking. Sticky clears on the terminal, not here.
                isRefreshing = true
                startRefreshIconSpin()
                if (isPullToRefreshEnabled) {
                    try {
                        feedBinding.swipeRefreshLayout.isRefreshing = true
                    } catch (e: Exception) {
                    }
                }
                Log.d(
                    "FeedRefresh",
                    "spinnerStart/lateArrival@" + SystemClock.elapsedRealtime()
                )
                try {
                    snackbar?.dismiss()
                } catch (e: Exception) {
                }
            }
            // Full 60 s from the tick ~= the remaining-delay formula: the VM stamps
            // lastProgressAt post-throttle in map(), so a tick runs ~throttle + one
            // scheduler hop ahead of the stamp.
            launchTimeout(TIMEOUT_NO_PROGRESS_MS) { onRefreshTimeout("timeout60s") }
        }
        // Terminal ticks: the render path owns the spinner; nothing to arm.
    }

    private fun handleItemsErrors(errors: List<Throwable>) {
        errors.forEachIndexed { i, t ->
            if (t is FeedLoadService.RequestException &&
                t.cause is ContentNotAvailableException
            ) {
                Single.fromCallable {
                    NewPipeDatabase.getInstance(requireContext()).subscriptionDAO()
                        .getSubscription(t.subscriptionId)
                }.subscribeOn(Schedulers.io())
                    .observeOn(AndroidSchedulers.mainThread())
                    .subscribe(
                        { subscriptionEntity ->
                            handleFeedNotAvailable(
                                subscriptionEntity,
                                t.cause,
                                errors.subList(i + 1, errors.size)
                            )
                        },
                        { throwable -> Log.e(TAG, "Unable to process", throwable) }
                    )
                return // this will be called on the remaining errors by handleFeedNotAvailable()
            }
        }

        if (errors.isNotEmpty()) {
            ErrorUtil.showSnackbar(this, ErrorInfo(errors, UserAction.REQUESTED_FEED, ""))
        }
    }

    private fun showNotLoadedSubscriptions() {
        disposables.add(
            Single.fromCallable {
                val feedDao = NewPipeDatabase.getInstance(requireContext()).feedDAO()
                when (groupId) {
                    FeedGroupEntity.GROUP_ALL_ID -> feedDao.getNotLoadedSubscriptions()
                    else -> feedDao.getNotLoadedSubscriptionsForGroup(groupId)
                }
            }
                .subscribeOn(Schedulers.io())
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(
                    { subscriptions ->
                        if (subscriptions.isEmpty()) {
                            return@subscribe
                        }

                        notLoadedDialog?.dismiss()
                        notLoadedDialog = AlertDialog.Builder(requireContext())
                            .setTitle(
                                getString(
                                    R.string.feed_subscription_not_loaded_count,
                                    subscriptions.size
                                )
                            )
                            .setItems(
                                subscriptions.map { it.name ?: it.url.orEmpty() }.toTypedArray()
                            ) { _, index ->
                                val subscription = subscriptions[index]
                                subscription.url?.let { url ->
                                    NavigationHelper.openChannelFragment(
                                        fm,
                                        subscription.serviceId,
                                        url,
                                        subscription.name ?: url
                                    )
                                }
                            }
                            .setNegativeButton(R.string.cancel, null)
                            .create()
                        notLoadedDialog?.show()
                    },
                    { throwable -> Log.e(TAG, "Unable to load failed subscriptions", throwable) }
                )
        )
    }

    private fun handleFeedNotAvailable(
        subscriptionEntity: SubscriptionEntity,
        @Nullable cause: Throwable?,
        nextItemsErrors: List<Throwable>
    ) {
        val sharedPreferences = PreferenceManager.getDefaultSharedPreferences(requireContext())
        val isFastFeedModeEnabled = sharedPreferences.getBoolean(
            getString(R.string.feed_use_dedicated_fetch_method_key), false
        )

        val builder = AlertDialog.Builder(requireContext())
            .setTitle(R.string.feed_load_error)
            .setPositiveButton(
                R.string.unsubscribe
            ) { _, _ ->
                SubscriptionManager(requireContext()).deleteSubscription(
                    subscriptionEntity.serviceId, subscriptionEntity.url
                ).subscribe()
                handleItemsErrors(nextItemsErrors)
            }
            .setNegativeButton(R.string.cancel) { _, _ -> }

        var message = getString(R.string.feed_load_error_account_info, subscriptionEntity.name)
        if (cause is AccountTerminatedException) {
            message += "\n" + getString(R.string.feed_load_error_terminated_new)
        } else if (cause is ContentNotAvailableException) {
            if (isFastFeedModeEnabled) {
                message += "\n" + getString(R.string.feed_load_error_fast_unknown)
                builder.setNeutralButton(R.string.feed_use_dedicated_fetch_method_disable_button) { _, _ ->
                    sharedPreferences.edit {
                        putBoolean(getString(R.string.feed_use_dedicated_fetch_method_key), false)
                    }
                }
            } else if (!isNullOrEmpty(cause.message)) {
                message += "\n" + cause.message
            }
        }
        builder.setMessage(message).create().let { dialog ->
            feedNotAvailableDialog?.dismiss()
            feedNotAvailableDialog = dialog
            dialog.show()
        }
    }

    private fun updateRelativeTimeViews() {
        updateRefreshViewState()
        groupAdapter.notifyItemRangeChanged(
            0, groupAdapter.itemCount,
            StreamItem.UPDATE_RELATIVE_TIME
        )
    }

    private fun updateRefreshViewState() {
        feedBinding.refreshText.text = getString(
            R.string.feed_oldest_subscription_update,
            oldestSubscriptionUpdate?.let { Localization.relativeTime(it) } ?: "\u2014"
        )
        val sticky = try {
            ::viewModel.isInitialized && viewModel.failedSticky
        } catch (e: Exception) {
            false
        }
        if (sticky) {
            feedBinding.refreshSubtitleText.isVisible = true
            feedBinding.refreshSubtitleText.text =
                getString(R.string.feed_refresh_failed_tap_to_retry)
            feedBinding.refreshSubtitleText.contentDescription =
                getString(R.string.feed_refresh_failed_tap_to_retry)
            feedBinding.refreshRootView.contentDescription =
                getString(R.string.feed_retry_desc)
            feedBinding.refreshIcon.contentDescription =
                getString(R.string.feed_retry_desc)
            feedBinding.refreshIcon.isFocusable = false
        } else {
            feedBinding.refreshRootView.contentDescription = null
            feedBinding.refreshIcon.contentDescription = null
            feedBinding.refreshIcon.isFocusable = true
            if (feedBinding.refreshSubtitleText.isVisible) {
                feedBinding.refreshSubtitleText.contentDescription =
                    feedBinding.refreshSubtitleText.text
            } else {
                feedBinding.refreshSubtitleText.contentDescription = null
            }
        }
    }

    /**
     * Highlights all items that are after the specified time
     */
    private fun highlightNewItemsAfter(updateTime: OffsetDateTime) {
        var highlightCount = 0

        var doCheck = true

        for (i in 0 until groupAdapter.itemCount) {
            val item = groupAdapter.getItem(i) as StreamItem

            var typeface = Typeface.DEFAULT
            var backgroundSupplier = { ctx: Context ->
                resolveDrawable(ctx, android.R.attr.selectableItemBackground)
            }
            if (doCheck) {
                // If the uploadDate is null or true we should highlight the item
                if (item.streamWithState.stream.uploadDate?.isAfter(updateTime) != false) {
                    highlightCount++

                    typeface = Typeface.DEFAULT_BOLD
                    backgroundSupplier = { ctx: Context ->
                        // Merge the drawables together. Otherwise we would lose the "select" effect
                        LayerDrawable(
                            arrayOf(
                                resolveDrawable(ctx, R.attr.dashed_border),
                                resolveDrawable(ctx, android.R.attr.selectableItemBackground)
                            )
                        )
                    }
                } else {
                    // Decreases execution time due to the order of the items (newest always on top)
                    // Once a item is is before the updateTime we can skip all following items
                    doCheck = false
                }
            }

            // The highlighter has to be always set
            // When it's only set on items that are highlighted it will highlight all items
            // due to the fact that itemRoot is getting recycled
            item.execBindEnd = Consumer { viewBinding ->
                val context = viewBinding.itemRoot.context
                viewBinding.itemRoot.background = backgroundSupplier.invoke(context)
                viewBinding.itemVideoTitleView.typeface = typeface
            }
        }

        // Force updates all items so that the highlighting is correct
        // If this isn't done visible items that are already highlighted will stay in a highlighted
        // state until the user scrolls them out of the visible area which causes a update/bind-call
        groupAdapter.notifyItemRangeChanged(
            0,
            minOf(groupAdapter.itemCount, maxOf(highlightCount, lastNewItemsCount))
        )

        if (highlightCount > 0) {
            showNewItemsLoaded()
        }

        lastNewItemsCount = highlightCount
    }

    private fun showNewItemsLoaded() {
        tryGetNewItemsLoadedButton()?.clearAnimation()
        tryGetNewItemsLoadedButton()
            ?.slideUp(
                250L,
                delay = 100,
                execOnEnd = {
                    // Disabled animations would result in immediately hiding the button
                    // after it showed up
                    try {
                        if (DeviceUtils.hasAnimationsAnimatorDurationEnabled(context)) {
                            // Hide the new items-"popup" after 10s
                            hideNewItemsLoaded(true, 10000)
                        }
                    } catch (e: Exception) {
                        e.printStackTrace()
                    }

                }
            )
    }

    private fun hideNewItemsLoaded(animate: Boolean, delay: Long = 0) {
        tryGetNewItemsLoadedButton()?.clearAnimation()
        if (animate) {
            tryGetNewItemsLoadedButton()?.animate(
                false,
                200,
                delay = delay,
                execOnEnd = {
                    // Make the layout invisible so that the onScroll toTop method
                    // only does necessary work
                    tryGetNewItemsLoadedButton()?.isVisible = false
                }
            )
        } else {
            tryGetNewItemsLoadedButton()?.isVisible = false
        }
    }

    /**
     * The view/button can be disposed/set to null under certain circumstances.
     * E.g. when the animation is still in progress but the view got destroyed.
     * This method is a helper for such states and can be used in affected code blocks.
     */
    private fun tryGetNewItemsLoadedButton(): Button? {
        return _feedBinding?.newItemsLoadedButton
    }

    // /////////////////////////////////////////////////////////////////////////
    // Load Service Handling
    // /////////////////////////////////////////////////////////////////////////

    override fun doInitialLoadLogic() {}

    override fun reloadContent() {
        // Re-tap while a load is in flight re-fires into the single-flight drop with
        // no feedback: timestamps and timers continue under the first load.
        val duplicate = isRefreshing
        if (!duplicate) {
            try {
                if (::viewModel.isInitialized) {
                    viewModel.onReloadRequested(SystemClock.elapsedRealtime())
                }
            } catch (e: Exception) {
            }
        }
        if (_feedBinding != null && !duplicate) {
            try {
                hideErrorPanel()
            } catch (e: Exception) {
            }
            snackbar?.dismiss()
            isRefreshing = true
            if (isPullToRefreshEnabled) {
                try {
                    feedBinding.swipeRefreshLayout.isRefreshing = true
                } catch (e: Exception) {
                }
            }
            Log.d("FeedRefresh", "spinnerStart/reload@" + SystemClock.elapsedRealtime())
            try {
                updateRefreshViewState()
            } catch (e: Exception) {
            }
            // Arm the 10 s no-event leg; the first scoped Progress swaps it for 60 s.
            launchTimeout(TIMEOUT_NO_EVENT_MS) { onRefreshTimeout("timeout10s") }
        }
        hideNewItemsLoaded(false)

        getActivity()?.startService(
            Intent(requireContext(), FeedLoadService::class.java).apply {
                putExtra(FeedLoadService.EXTRA_GROUP_ID, groupId)
            }
        )
        // Duplicate taps must not destroy the pending scroll restore: the eventual
        // terminal belongs to the first load.
        if (!duplicate) {
            listState = null
        }
    }

    companion object {
        const val KEY_GROUP_ID = "ARG_GROUP_ID"
        const val KEY_GROUP_NAME = "ARG_GROUP_NAME"
        private const val TIMEOUT_NO_EVENT_MS = 10_000L
        private const val TIMEOUT_NO_PROGRESS_MS = 60_000L

        @JvmStatic
        fun newInstance(groupId: Long = FeedGroupEntity.GROUP_ALL_ID, groupName: String? = null): FeedFragment {
            val feedFragment = FeedFragment()
            feedFragment.arguments = bundleOf(KEY_GROUP_ID to groupId, KEY_GROUP_NAME to groupName)
            return feedFragment
        }
    }
}
