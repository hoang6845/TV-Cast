package com.tvchromecast.screenmirroringplus.ui.iptv_fragment

import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.app.AlertDialog
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.PorterDuff
import android.net.Uri
import android.os.CountDownTimer
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.view.animation.LinearInterpolator
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.Toast
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.appcompat.widget.PopupMenu
import androidx.core.view.isVisible
import androidx.core.widget.doOnTextChanged
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.SimpleItemAnimator
import com.tvchromecast.screenmirroringplus.R
import com.tvchromecast.screenmirroringplus.cast.CastReceiverIds
import com.tvchromecast.screenmirroringplus.databinding.FragmentIPTVBinding
import com.tvchromecast.screenmirroringplus.databinding.LayoutIptvFilterSheetBinding
import com.tvchromecast.screenmirroringplus.media.LocalMediaHttpServer
import com.tvchromecast.screenmirroringplus.model.entity.Channel
import com.tvchromecast.screenmirroringplus.ui.common.hasRecentReceiverMediaError
import com.tvchromecast.screenmirroringplus.ui.common.resetReceiverMediaErrorUiState
import com.tvchromecast.screenmirroringplus.ui.common.showCastFailureDialog
import com.tvchromecast.screenmirroringplus.ui.common.showReceiverMediaErrorIfAny
import com.google.android.gms.cast.Cast
import com.google.android.gms.cast.CastMediaControlIntent
import com.google.android.gms.cast.MediaError
import com.google.android.gms.cast.MediaInfo
import com.google.android.gms.cast.MediaLoadRequestData
import com.google.android.gms.cast.MediaMetadata
import com.google.android.gms.cast.MediaStatus
import com.google.android.gms.cast.framework.CastContext
import com.google.android.gms.cast.framework.CastSession
import com.google.android.gms.cast.framework.SessionManagerListener
import com.google.android.gms.cast.framework.media.RemoteMediaClient
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import dagger.hilt.android.AndroidEntryPoint
import hoang.dqm.codebase.base.activity.BaseFragment
import hoang.dqm.codebase.base.activity.onBackPressed
import hoang.dqm.codebase.base.activity.popBackStack
import hoang.dqm.codebase.utils.collectLatestFlow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

@AndroidEntryPoint
class IPTVFragment : BaseFragment<FragmentIPTVBinding, IPTVViewModel>() {
    override val viewModelClass: Class<IPTVViewModel>
        get() = IPTVViewModel::class.java

    override fun inflateBinding(
        inflater: android.view.LayoutInflater,
        container: android.view.ViewGroup?
    ): FragmentIPTVBinding {
        return FragmentIPTVBinding.inflate(inflater, container, false)
    }

    private val categoryAdapter by lazy {
        IPTVCategoryAdapter(
            onClick = { category -> viewModel.openCategory(category) },
            onPinClick = { category -> viewModel.toggleCategoryPin(category.name) }
        )
    }

    private val channelAdapter by lazy {
        IPTVChannelAdapter(
            onClick = { channel -> viewModel.selectChannel(channel) },
            onFavouriteClick = { channel, isFavourite ->
                viewModel.toggleFavourite(channel, isFavourite)
            }
        )
    }

    private var currentUiState = IPTVUiState()
    private var player: ExoPlayer? = null
    private var selectedPlayerChannelId: String? = null
    private var refreshAnimator: ObjectAnimator? = null

    // Custom Player Controls
    private val CONTROLS_HIDE_DELAY_MS = 3_500L
    private val SEEK_STEP_MS = 10_000L
    private val PROGRESS_UPDATE_MS = 500L
    private val uiHandler = Handler(Looper.getMainLooper())
    private val hideControlsRunnable = Runnable { hidePlayerControls() }
    private var isSeeking: Boolean = false
    private var sleepTimer: CountDownTimer? = null
    private var sleepTimerEndTime: Long = 0L
    private val sleepTimerUpdateHandler = Handler(Looper.getMainLooper())
    private val sleepTimerUpdateRunnable = object : Runnable {
        override fun run() {
            updateSleepTimerIcon()
            sleepTimerUpdateHandler.postDelayed(this, 1000L)
        }
    }
    private var isPlayerLocked: Boolean = false
    private var isPlayerFullscreen: Boolean = false
    private var originalContentLayoutParams: ConstraintLayout.LayoutParams? = null
    private var originalPlayerLayoutParams: LinearLayout.LayoutParams? = null

    private var castContext: CastContext? = null
    private var castSession: CastSession? = null
    private var sessionManagerListener: SessionManagerListener<CastSession>? = null
    private var observedRemoteClient: RemoteMediaClient? = null
    private val mediaServer by lazy { LocalMediaHttpServer(requireContext().applicationContext) }

    private val receiverMessageCallback = Cast.MessageReceivedCallback { _, namespace, message ->
        logCast("receiver message namespace=$namespace payload=${message.take(LOG_MESSAGE_LIMIT)}")
        showReceiverMediaErrorIfAny(message, TAG)
    }

    private val mediaClientCallback = object : RemoteMediaClient.Callback() {
        override fun onStatusUpdated() {
            handleRemoteMediaStatus(observedRemoteClient?.mediaStatus)
        }

        override fun onMediaError(mediaError: MediaError) {
            logCast("remoteMediaClient onMediaError error=$mediaError")
            handleRemoteMediaStatus(observedRemoteClient?.mediaStatus)
        }
    }

    override fun initView() {
        adjustInsetsForBottomNavigation(binding.topBar)
        adjustInsetsForBottomPadding(binding.rvCategories)
        adjustInsetsForBottomPadding(binding.rvChannels)
        setupRecyclerViews()
        setupCast()
        setupPlayerControls()
    }

    override fun initListener() {
        onBackPressed { handleBackPress() }
        binding.btnBack.setOnClickListener { handleBackPress() }
        binding.btnRefresh.setOnClickListener {
            viewModel.refreshPlaylist()
        }
        binding.btnCast.setOnClickListener {
            showCastDialog()
        }
        binding.btnGenres.setOnClickListener { viewModel.selectTab(IPTVTab.GENRES) }
        binding.btnFavorites.setOnClickListener { viewModel.selectTab(IPTVTab.FAVORITES) }
        binding.btnFilterCombined.setOnClickListener { showCombinedFilterSheet() }
        binding.btnSearch.setOnClickListener { showCategorySearchSheet() }
        binding.etChannelSearch.doOnTextChanged { text, _, _, _ ->
            val query = text?.toString().orEmpty()
            if (query != currentUiState.channelSearchQuery) {
                viewModel.updateChannelSearchQuery(query)
            }
        }
//        binding.btnCastToTv.setOnClickListener { showCastDialog() }
    }

    override fun initData() {
        collectLatestFlow(viewModel.uiState) { state ->
            renderUiState(state)
        }
        collectLatestFlow(viewModel.refreshSaveState) { state ->
            renderRefreshState(state)
        }
    }

    private fun setupRecyclerViews() {
        binding.rvCategories.layoutManager = LinearLayoutManager(requireContext())
        binding.rvCategories.adapter = categoryAdapter

        binding.rvChannels.layoutManager = GridLayoutManager(requireContext(), 3)
        (binding.rvChannels.itemAnimator as? SimpleItemAnimator)?.supportsChangeAnimations = false
        binding.rvChannels.adapter = channelAdapter
    }

    private fun renderUiState(state: IPTVUiState) {
        currentUiState = state

        val showingChannels = state.isShowingChannelList()
        binding.tvTitle.text = state.selectedCategory?.name ?: getString(R.string.text_iptv)
        
        // Hiển thị icon cast khi đang xem channels, icon refresh khi đang ở categories
        binding.btnCast.isVisible = showingChannels
        binding.btnRefresh.isVisible = !showingChannels

        renderTabs(state.tab)
        renderFilters(state)
        renderLists(state, showingChannels)
        renderSelectedChannel(state.selectedChannel, showingChannels)
        updateCastIcon(castSession?.isConnected == true)
        applyPlayerFullscreenVisibility()
    }

    private fun renderTabs(tab: IPTVTab) {
        val genresSelected = tab == IPTVTab.GENRES
        binding.btnGenres.setBackgroundResource(
            if (genresSelected) R.drawable.bg_iptv_tab_selected else 0
        )
        binding.btnFavorites.setBackgroundResource(
            if (genresSelected) 0 else R.drawable.bg_iptv_tab_selected
        )
        binding.btnGenres.setTextColor(if (genresSelected) Color.BLACK else Color.WHITE)
        binding.btnFavorites.setTextColor(if (genresSelected) Color.WHITE else Color.BLACK)
    }

    private fun renderFilters(state: IPTVUiState) {
        val filterText = if (state.selectedFilter != null) {
            "${getFilterModeLabel(state.filterMode)}: ${state.selectedFilter}"
        } else {
            getFilterAllLabel(state.filterMode)
        }
        binding.btnFilterCombined.text = filterText
        
        if (binding.etChannelSearch.text?.toString() != state.channelSearchQuery) {
            binding.etChannelSearch.setText(state.channelSearchQuery)
        }
    }

    private fun renderLists(state: IPTVUiState, showingChannels: Boolean) {
        val showingRootTabs = state.selectedCategory == null
        val showingGenreCategories = state.tab == IPTVTab.GENRES && state.selectedCategory == null
        val showingFavoriteSearch = state.tab == IPTVTab.FAVORITES && state.selectedCategory == null

        binding.tabContainer.isVisible = showingRootTabs
        binding.filterContainer.isVisible = showingRootTabs && state.tab == IPTVTab.GENRES
        binding.channelSearchContainer.isVisible = showingFavoriteSearch
        binding.rvCategories.isVisible = showingGenreCategories
        binding.tvEmptyCategories.isVisible = showingGenreCategories && state.categories.isEmpty()
        binding.rvChannels.isVisible = showingChannels
        binding.tvEmptyChannels.isVisible = showingChannels && state.channels.isEmpty()

        categoryAdapter.submitList(state.categories)
        channelAdapter.submitChannels(state.channels, state.selectedChannel?.id)
    }

    private fun renderSelectedChannel(channel: Channel?, showingChannels: Boolean) {
        if (!showingChannels || channel == null) {
            exitPlayerFullscreen(render = false)
            selectedPlayerChannelId = null
            binding.playerContainer.root.isVisible = false
            releasePlayer()
            return
        }

        binding.playerContainer.root.isVisible = true
        if (selectedPlayerChannelId != channel.id) {
            selectedPlayerChannelId = channel.id
            initPlayer(channel)
        }
    }

    private fun renderRefreshState(state: CategoryRefreshSaveState) {
        when (state) {
            CategoryRefreshSaveState.Idle -> {
                stopRefreshAnimation()
                binding.btnRefresh.isEnabled = true
            }

            is CategoryRefreshSaveState.Loading -> {
                startRefreshAnimation()
                binding.btnRefresh.isEnabled = false
            }

            is CategoryRefreshSaveState.Success -> {
                stopRefreshAnimation()
                binding.btnRefresh.isEnabled = true
                Toast.makeText(
                    requireContext(),
                    getString(R.string.text_playlist_refreshed),
                    Toast.LENGTH_SHORT
                ).show()
                viewModel.acknowledgeRefreshState()
            }

            is CategoryRefreshSaveState.Error -> {
                stopRefreshAnimation()
                binding.btnRefresh.isEnabled = true
                Toast.makeText(requireContext(), state.message, Toast.LENGTH_SHORT).show()
                viewModel.acknowledgeRefreshState()
            }
        }
    }

    private fun showCombinedFilterSheet() {
        // Step 1: Show dropdown menu for mode selection
        PopupMenu(requireContext(), binding.btnFilterCombined).apply {
            menu.add(0, 1, 0, getString(R.string.text_by_country))
            menu.add(0, 2, 1, getString(R.string.text_by_language))
            setOnMenuItemClickListener { item ->
                when (item.itemId) {
                    1 -> {
                        viewModel.selectFilterMode(IPTVFilterMode.COUNTRY)
                        // Step 2: Show filter options sheet after mode selected
                        binding.btnFilterCombined.postDelayed({
                            showFilterOptionsSheet()
                        }, 100)
                    }
                    2 -> {
                        viewModel.selectFilterMode(IPTVFilterMode.LANGUAGE)
                        // Step 2: Show filter options sheet after mode selected
                        binding.btnFilterCombined.postDelayed({
                            showFilterOptionsSheet()
                        }, 100)
                    }
                }
                true
            }
            show()
        }
    }

    private fun showFilterOptionsSheet() {
        // Step 2: Show actual filter options (countries or languages)
        val state = currentUiState
        val dialog = BottomSheetDialog(requireContext())
        val sheetBinding = LayoutIptvFilterSheetBinding.inflate(layoutInflater)
        
        val adapter = IPTVFilterOptionAdapter(state.selectedFilter) { option ->
            viewModel.selectFilterOption(option)
            dialog.dismiss()
        }

        sheetBinding.tvSheetTitle.text = getFilterModeLabel(state.filterMode)
        sheetBinding.etFilterSearch.visibility = android.view.View.VISIBLE
        sheetBinding.etFilterSearch.hint = getString(
            if (state.filterMode == IPTVFilterMode.COUNTRY) {
                R.string.text_search_country
            } else {
                R.string.text_search_language
            }
        )
        sheetBinding.rvFilterOptions.layoutManager = LinearLayoutManager(requireContext())
        sheetBinding.rvFilterOptions.adapter = adapter
        adapter.submitList(state.filterOptions)

        sheetBinding.etFilterSearch.doOnTextChanged { text, _, _, _ ->
            adapter.submitList(filterOptions(state.filterOptions, text?.toString().orEmpty()))
        }

        dialog.setContentView(sheetBinding.root)
        
        // Enable soft input mode to adjust the sheet when keyboard appears
        dialog.window?.setSoftInputMode(
            android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
        )
        
        dialog.setOnShowListener {
            val bottomSheet =
                dialog.findViewById<View>(com.google.android.material.R.id.design_bottom_sheet)
            bottomSheet?.let { sheet ->
                // Set background
                sheet.setBackgroundColor(Color.TRANSPARENT)
                
                // Configure behavior
                val behavior = BottomSheetBehavior.from(sheet)
                behavior.skipCollapsed = true
                behavior.state = BottomSheetBehavior.STATE_EXPANDED
                
                // Let the sheet adjust to content/keyboard
                sheet.layoutParams?.height = android.view.ViewGroup.LayoutParams.MATCH_PARENT
            }
        }
        
        dialog.show()
    }

    private fun showFilterSheet() {
        val state = currentUiState
        val dialog = BottomSheetDialog(requireContext())
        val sheetBinding = LayoutIptvFilterSheetBinding.inflate(layoutInflater)
        val adapter = IPTVFilterOptionAdapter(state.selectedFilter) { option ->
            viewModel.selectFilterOption(option)
            dialog.dismiss()
        }

        sheetBinding.tvSheetTitle.text = getFilterModeLabel(state.filterMode)
        sheetBinding.etFilterSearch.hint = getString(
            if (state.filterMode == IPTVFilterMode.COUNTRY) {
                R.string.text_search_country
            } else {
                R.string.text_search_language
            }
        )
        sheetBinding.rvFilterOptions.layoutManager = LinearLayoutManager(requireContext())
        sheetBinding.rvFilterOptions.adapter = adapter
        adapter.submitList(state.filterOptions)

        sheetBinding.etFilterSearch.doOnTextChanged { text, _, _, _ ->
            adapter.submitList(filterOptions(state.filterOptions, text?.toString().orEmpty()))
        }

        dialog.setContentView(sheetBinding.root)
        dialog.setOnShowListener {
            val bottomSheet =
                dialog.findViewById<View>(com.google.android.material.R.id.design_bottom_sheet)
            bottomSheet?.setBackgroundColor(Color.TRANSPARENT)
            bottomSheet?.layoutParams?.height = (resources.displayMetrics.heightPixels * 0.86f).toInt()
            bottomSheet?.let { sheet ->
                BottomSheetBehavior.from(sheet).apply {
                    skipCollapsed = true
                    this.state = BottomSheetBehavior.STATE_EXPANDED
                }
            }
        }
        dialog.show()
    }

    private fun showCategorySearchSheet() {
        val state = currentUiState
        val dialog = BottomSheetDialog(requireContext())
        val sheetBinding = LayoutIptvFilterSheetBinding.inflate(layoutInflater)
        
        // Create adapter for categories
        val categories = state.categories
        val categoryOptions = categories.map { category ->
            IPTVFilterOption(
                value = category.name,
                label = category.name,
                channelCount = category.channelCount
            )
        }
        
        val adapter = IPTVFilterOptionAdapter(null) { option ->
            // Find and open the category
            categories.find { it.name == option.value }?.let { category ->
                viewModel.openCategory(category)
                dialog.dismiss()
            }
        }

        sheetBinding.tvSheetTitle.text = getString(R.string.text_search_category)
        sheetBinding.etFilterSearch.hint = getString(R.string.text_search_category_name)
        sheetBinding.rvFilterOptions.layoutManager = LinearLayoutManager(requireContext())
        sheetBinding.rvFilterOptions.adapter = adapter
        adapter.submitList(categoryOptions)

        sheetBinding.etFilterSearch.doOnTextChanged { text, _, _, _ ->
            adapter.submitList(filterOptions(categoryOptions, text?.toString().orEmpty()))
        }

        dialog.setContentView(sheetBinding.root)
        
        // Enable soft input mode to adjust the sheet when keyboard appears
        dialog.window?.setSoftInputMode(
            android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
        )
        
        dialog.setOnShowListener {
            val bottomSheet =
                dialog.findViewById<View>(com.google.android.material.R.id.design_bottom_sheet)
            bottomSheet?.let { sheet ->
                // Set background
                sheet.setBackgroundColor(Color.TRANSPARENT)
                
                // Configure behavior
                val behavior = BottomSheetBehavior.from(sheet)
                behavior.skipCollapsed = true
                behavior.state = BottomSheetBehavior.STATE_EXPANDED
                
                // Let the sheet adjust to content/keyboard
                sheet.layoutParams?.height = android.view.ViewGroup.LayoutParams.MATCH_PARENT
            }
        }
        
        dialog.show()
        
        // Focus and show keyboard after sheet is shown
        sheetBinding.etFilterSearch.postDelayed({
            sheetBinding.etFilterSearch.requestFocus()
            val imm = requireContext().getSystemService(android.content.Context.INPUT_METHOD_SERVICE) 
                as android.view.inputmethod.InputMethodManager
            imm.showSoftInput(sheetBinding.etFilterSearch, android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT)
        }, 200)
    }

    private fun filterOptions(
        options: List<IPTVFilterOption>,
        query: String
    ): List<IPTVFilterOption> {
        val normalizedQuery = query.trim()
        if (normalizedQuery.isBlank()) return options
        return options.filter { it.label.contains(normalizedQuery, ignoreCase = true) }
    }

    private fun getFilterModeLabel(mode: IPTVFilterMode): String {
        return getString(
            when (mode) {
                IPTVFilterMode.COUNTRY -> R.string.text_by_country
                IPTVFilterMode.LANGUAGE -> R.string.text_by_language
            }
        )
    }

    private fun getFilterAllLabel(mode: IPTVFilterMode): String {
        return getString(
            when (mode) {
                IPTVFilterMode.COUNTRY -> R.string.text_all_countries
                IPTVFilterMode.LANGUAGE -> R.string.text_all_language
            }
        )
    }

    private fun startRefreshAnimation() {
        if (refreshAnimator?.isRunning == true) return
        refreshAnimator = ObjectAnimator.ofFloat(binding.btnRefresh, View.ROTATION, 0f, -360f)
            .apply {
                duration = 900L
                repeatCount = ValueAnimator.INFINITE
                interpolator = LinearInterpolator()
                start()
            }
    }

    private fun stopRefreshAnimation() {
        refreshAnimator?.cancel()
        refreshAnimator = null
        binding.btnRefresh.rotation = 0f
    }

    private fun initPlayer(channel: Channel) {
        releasePlayer()
        binding.playerContainer.playerLoadingIndicator.isVisible = true

        player = ExoPlayer.Builder(requireContext()).build().also { exoPlayer ->
            binding.playerContainer.playerView.player = exoPlayer
            exoPlayer.setMediaItem(buildMediaItem(channel.url))
            exoPlayer.prepare()
            exoPlayer.playWhenReady = true
            exoPlayer.addListener(object : Player.Listener {
                override fun onPlaybackStateChanged(playbackState: Int) {
                    when (playbackState) {
                        Player.STATE_BUFFERING -> binding.playerContainer.playerLoadingIndicator.isVisible = true
                        Player.STATE_READY -> {
                            binding.playerContainer.playerLoadingIndicator.isVisible = false
                            binding.playerContainer.tvTotalTime.text = formatTime(exoPlayer.duration)
                            startProgressUpdater()
                        }
                        Player.STATE_ENDED -> binding.playerContainer.playerLoadingIndicator.isVisible = false
                        else -> Unit
                    }
                }

                override fun onIsPlayingChanged(playing: Boolean) {
                    binding.playerContainer.btnPlayPause.setImageResource(
                        if (playing) R.drawable.ic_pause else R.drawable.ic_play
                    )
                }

                override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                    binding.playerContainer.playerLoadingIndicator.isVisible = false
                    Toast.makeText(
                        requireContext(),
                        getString(R.string.text_channel_unavailable),
                        Toast.LENGTH_SHORT
                    ).show()
                }
            })
        }
        
        // Update player title
        binding.playerContainer.tvPlayerTitle.text = channel.name
        
        // Show controls initially
        showPlayerControls()
    }

    private fun buildMediaItem(url: String): MediaItem {
        val lower = url.lowercase()
        return when {
            lower.endsWith(".m3u8") || lower.contains(".m3u8?") ->
                MediaItem.Builder()
                    .setUri(url)
                    .setMimeType(MimeTypes.APPLICATION_M3U8)
                    .build()
            lower.endsWith(".mpd") || lower.contains(".mpd?") ->
                MediaItem.Builder()
                    .setUri(url)
                    .setMimeType(MimeTypes.APPLICATION_MPD)
                    .build()
            lower.endsWith(".mp4") || lower.endsWith(".mkv") ||
                lower.endsWith(".avi") || lower.endsWith(".mov") ||
                lower.endsWith(".flv") || lower.endsWith(".wmv") ->
                MediaItem.Builder()
                    .setUri(url)
                    .setMimeType(MimeTypes.VIDEO_MP4)
                    .build()
            lower.startsWith("rtmp://") || lower.startsWith("rtsp://") ->
                MediaItem.fromUri(Uri.parse(url))
            else -> MediaItem.fromUri(Uri.parse(url))
        }
    }

    private fun setupCast() {
        try {
            castContext = CastContext.getSharedInstance(requireContext())
            castContext?.setReceiverApplicationId(CastReceiverIds.CUSTOM_RECEIVER)
            setupSessionManagerListener()
            logCast("setupCast success targetReceiver=${CastReceiverIds.CUSTOM_RECEIVER}")
        } catch (e: Exception) {
            castContext = null
            Log.e(TAG, "setupCast failed", e)
        }
    }

    private fun showCastDialog() {
        val channel = currentUiState.selectedChannel ?: run {
            Toast.makeText(requireContext(), R.string.text_select_channel_first, Toast.LENGTH_SHORT).show()
            logCast("showCastDialog ignored: no selected channel")
            return
        }
        logCast("showCastDialog channel=${channel.logSummary()}")

        val castCtx = castContext ?: run {
            logCast("showCastDialog failed: CastContext is null")
            AlertDialog.Builder(requireContext())
                .setTitle(getString(R.string.text_cast_not_available))
                .setMessage(getString(R.string.text_google_play_services_has_not_been_initialized))
                .setPositiveButton(getString(R.string.text_ok), null)
                .show()
            return
        }

        if (castSession?.isConnected == true) {
            logCast(
                "showCastDialog connected session receiver=${castSession?.receiverIdForLog()} " +
                    "device=${castSession?.castDevice?.friendlyName}"
            )
            AlertDialog.Builder(requireContext())
                .setTitle(getString(R.string.text_casting_to_tv_plain))
                .setMessage(getString(R.string.text_what_would_you_like_to_do))
                .setPositiveButton(getString(R.string.text_disconnect)) { _, _ ->
                    logCast("disconnect requested by user")
                    castCtx.sessionManager.endCurrentSession(true)
                }
                .setNeutralButton(getString(R.string.text_restart_from_beginning)) { _, _ ->
                    logCast("restart cast requested by user channel=${channel.logSummary()}")
                    loadMediaOnCast(channel)
                }
                .setNegativeButton(getString(R.string.text_cancel), null)
                .show()
            return
        }

        val selector = androidx.mediarouter.media.MediaRouteSelector.Builder()
            .addControlCategory(
                CastMediaControlIntent.categoryForCast(
                    CastReceiverIds.CUSTOM_RECEIVER
                )
            )
            .build()

        logCast("showing route chooser targetReceiver=${CastReceiverIds.CUSTOM_RECEIVER}")
        androidx.mediarouter.app.MediaRouteChooserDialogFragment().apply {
            routeSelector = selector
        }.show(childFragmentManager, "IPTVCastChooser")
    }

    private fun setupSessionManagerListener() {
        sessionManagerListener = object : SessionManagerListener<CastSession> {
            override fun onSessionStarted(session: CastSession, sessionId: String) {
                castSession = session
                logCast(
                    "session started id=$sessionId receiver=${session.receiverIdForLog()} " +
                        "device=${session.castDevice?.friendlyName}"
                )
                setReceiverDebugCallback(session)
                observeRemoteMediaClient(session)
                currentUiState.selectedChannel?.let { loadMediaOnCast(it) }
                player?.pause()
                updateCastIcon(connected = true)
            }

            override fun onSessionResumed(session: CastSession, wasSuspended: Boolean) {
                castSession = session
                logCast(
                    "session resumed wasSuspended=$wasSuspended receiver=${session.receiverIdForLog()} " +
                        "device=${session.castDevice?.friendlyName}"
                )
                setReceiverDebugCallback(session)
                observeRemoteMediaClient(session)
                updateCastIcon(connected = true)
            }

            override fun onSessionEnded(session: CastSession, error: Int) {
                logCast("session ended error=$error receiver=${session.receiverIdForLog()}")
                removeReceiverDebugCallback(session)
                stopObservingRemoteMediaClient()
                castSession = null
                player?.play()
                updateCastIcon(connected = false)
            }

            override fun onSessionStartFailed(session: CastSession, error: Int) {
                logCast("session start failed error=$error receiver=${session.receiverIdForLog()}")
                showCastFailureDialog()
            }

            override fun onSessionSuspended(session: CastSession, reason: Int) {
                logCast("session suspended reason=$reason receiver=${session.receiverIdForLog()}")
            }

            override fun onSessionStarting(session: CastSession) {
                logCast("session starting receiver=${session.receiverIdForLog()}")
            }

            override fun onSessionEnding(session: CastSession) {
                logCast("session ending receiver=${session.receiverIdForLog()}")
            }

            override fun onSessionResuming(session: CastSession, sessionId: String) {
                logCast("session resuming id=$sessionId receiver=${session.receiverIdForLog()}")
            }

            override fun onSessionResumeFailed(session: CastSession, error: Int) {
                logCast("session resume failed error=$error receiver=${session.receiverIdForLog()}")
            }
        }
    }

    private fun loadMediaOnCast(channel: Channel) {
        val session = castSession
        val remoteClient: RemoteMediaClient = session?.remoteMediaClient ?: run {
            logCast("loadMediaOnCast aborted: remoteMediaClient is null session=${session?.receiverIdForLog()}")
            return
        }
        resetReceiverMediaErrorUiState()

        val sourceUrl = channel.url.trim()
        if (!sourceUrl.isHttpCastUrl()) {
            logCast("loadMediaOnCast aborted: unsupported url channel=${channel.logSummary()}")
            Toast.makeText(requireContext(), R.string.text_could_not_cast_media, Toast.LENGTH_SHORT).show()
            return
        }

        observeRemoteMediaClient(session)
        logCast(
            "loadMediaOnCast start receiver=${session.receiverIdForLog()} " +
                "device=${session.castDevice?.friendlyName} channel=${channel.logSummary()} " +
                "localPosition=${player?.currentPosition ?: 0L}"
        )

        val expectedChannelId = channel.id
        lifecycleScope.launch {
            val contentType = resolveCastContentType(sourceUrl)
            if (
                view == null ||
                castSession?.isConnected != true ||
                currentUiState.selectedChannel?.id != expectedChannelId
            ) {
                logCast(
                    "loadMediaOnCast cancelled after sniff viewNull=${view == null} " +
                        "connected=${castSession?.isConnected == true} " +
                        "expectedChannelId=$expectedChannelId currentChannelId=${currentUiState.selectedChannel?.id}"
                )
                return@launch
            }

            val useDirectCastUrl = shouldUseDirectCastUrl(sourceUrl, contentType)
            val castUrl = if (useDirectCastUrl) {
                sourceUrl
            } else {
                mediaServer.registerRemoteUrl(sourceUrl, contentType)
            } ?: run {
                logCast("loadMediaOnCast failed: could not register proxy url=$sourceUrl type=$contentType")
                Toast.makeText(requireContext(), R.string.text_could_not_prepare_media, Toast.LENGTH_SHORT).show()
                return@launch
            }

            castSession?.let {
                setReceiverDebugCallback(it)
                sendReceiverPing(it)
            }
            logCast(
                "loadMediaOnCast prepared delivery=${if (useDirectCastUrl) "direct" else "proxy"} " +
                    "contentType=$contentType streamType=${inferCastStreamType(sourceUrl, contentType).streamTypeName()} " +
                    "castUrl=$castUrl sourceUrl=$sourceUrl"
            )

            val metadata = MediaMetadata(MediaMetadata.MEDIA_TYPE_MOVIE).apply {
                putString(MediaMetadata.KEY_TITLE, channel.name)
            }

            val streamType = inferCastStreamType(sourceUrl, contentType)

            val mediaInfo = MediaInfo.Builder(castUrl)
                .setContentUrl(castUrl)
                .setStreamType(streamType)
                .setContentType(contentType)
                .setMetadata(metadata)
                .build()

            val requestBuilder = MediaLoadRequestData.Builder()
                .setMediaInfo(mediaInfo)
                .setAutoplay(true)

            if (streamType != MediaInfo.STREAM_TYPE_LIVE) {
                requestBuilder.setCurrentTime(player?.currentPosition ?: 0L)
            }

            remoteClient.load(requestBuilder.build())
                .setResultCallback { result ->
                    activity?.runOnUiThread {
                        if (view == null) return@runOnUiThread
                        logCast(
                            "loadMediaOnCast result success=${result.status.isSuccess} " +
                                "code=${result.status.statusCode} message=${result.status.statusMessage} " +
                                "delivery=${if (useDirectCastUrl) "direct" else "proxy"} " +
                                "receiver=${castSession?.receiverIdForLog()} mediaStatus=${remoteClient.mediaStatus.summaryForLog()}"
                        )
                        if (result.status.isSuccess) {
                            player?.pause()
                            handleRemoteMediaStatus(remoteClient.mediaStatus)
                        } else {
                            showCastFailureDialogIfNoReceiverError()
                        }
                    }
                }
        }
    }

    private fun showCastFailureDialogIfNoReceiverError() {
        uiHandler.postDelayed({
            if (view == null || hasRecentReceiverMediaError()) {
                logCast(
                    "skip generic cast failure dialog viewNull=${view == null} " +
                        "hasRecentReceiverError=${hasRecentReceiverMediaError()}"
                )
                return@postDelayed
            }
            logCast("show generic cast failure dialog: no receiver-specific error received")
            showCastFailureDialog()
        }, CAST_LOAD_FAILURE_FALLBACK_DELAY_MS)
    }

    private fun inferCastStreamType(url: String, contentType: String): Int {
        val lower = url.lowercase()
        val lowerType = contentType.lowercase()
        return when {
            lower.contains(".m3u8") ||
                lower.contains("m3u8") ||
                lower.contains("/live/") ||
                lowerType.contains("mpegurl") ||
                lowerType == "video/mp2t" ->
                MediaInfo.STREAM_TYPE_LIVE
            else -> MediaInfo.STREAM_TYPE_BUFFERED
        }
    }

    private fun inferCastContentType(url: String): String {
        val lowerUrl = url.lowercase()
        val cleanUrl = lowerUrl.substringBefore("#").substringBefore("?")
        return when {
            cleanUrl.endsWith(".m3u8") || cleanUrl.contains(".m3u8/") || lowerUrl.contains("m3u8") ->
                "application/x-mpegURL"
            cleanUrl.endsWith(".mpd") || cleanUrl.contains(".mpd/") ->
                "application/dash+xml"
            cleanUrl.endsWith(".ts") || cleanUrl.contains(".ts/") ->
                "video/mp2t"
            cleanUrl.endsWith(".webm") || cleanUrl.contains(".webm/") ->
                "video/webm"
            cleanUrl.endsWith(".mp3") || cleanUrl.contains(".mp3/") ->
                "audio/mpeg"
            cleanUrl.endsWith(".m4a") || cleanUrl.contains(".m4a/") ->
                "audio/mp4"
            else -> "video/mp4"
        }
    }

    private suspend fun resolveCastContentType(url: String): String {
        val inferred = inferCastContentType(url)
        if (inferred != DEFAULT_CAST_CONTENT_TYPE) {
            logCast("content type inferred url=$url type=$inferred")
            return inferred
        }

        val sniffed = withContext(Dispatchers.IO) {
            sniffRemoteContentType(url)
        }
        val resolved = sniffed ?: inferred
        logCast("content type resolved url=$url inferred=$inferred sniffed=$sniffed resolved=$resolved")
        return resolved
    }

    private fun sniffRemoteContentType(url: String): String? {
        return sniffRemoteContentType(url, "HEAD")
            ?: sniffRemoteContentType(url, "GET")
    }

    private fun sniffRemoteContentType(url: String, method: String): String? {
        val connection = runCatching {
            (URL(url).openConnection() as HttpURLConnection).apply {
                instanceFollowRedirects = true
                connectTimeout = CAST_SNIFF_TIMEOUT_MS
                readTimeout = CAST_SNIFF_TIMEOUT_MS
                requestMethod = method
                setRequestProperty("User-Agent", CAST_REMOTE_USER_AGENT)
                setRequestProperty("Accept", "*/*")
                setRequestProperty("Accept-Encoding", "identity")
                if (method == "GET") {
                    setRequestProperty("Range", "bytes=0-0")
                }
            }
        }.onFailure {
            Log.w(TAG, "sniff open failed method=$method url=$url", it)
        }.getOrNull() ?: return null

        return try {
            val responseCode = connection.responseCode
            val remoteType = connection.contentType
                ?.substringBefore(";")
                ?.trim()
                ?.takeIf { it.isNotBlank() }
            val normalized = if (responseCode in 200..399 && remoteType != null) {
                normalizeCastContentType(remoteType, url)
            } else {
                null
            }
            logCast(
                "sniff method=$method code=$responseCode type=$remoteType normalized=$normalized " +
                    "length=${connection.getHeaderField("Content-Length")} url=$url"
            )
            normalized
        } catch (e: Exception) {
            Log.w(TAG, "sniff failed method=$method url=$url", e)
            null
        } finally {
            connection.disconnect()
        }
    }

    private fun normalizeCastContentType(contentType: String, url: String): String {
        val lowerType = contentType.lowercase()
        return when {
            lowerType.contains("mpegurl") -> "application/x-mpegURL"
            lowerType.contains("dash+xml") -> "application/dash+xml"
            lowerType == "video/mp2t" -> "video/mp2t"
            lowerType == "video/mp4" || lowerType == "application/mp4" -> "video/mp4"
            lowerType == "video/webm" -> "video/webm"
            lowerType == "audio/mpeg" -> "audio/mpeg"
            lowerType == "audio/mp4" -> "audio/mp4"
            lowerType == "application/octet-stream" -> inferCastContentType(url)
            else -> contentType
        }
    }

    private fun shouldUseDirectCastUrl(url: String, contentType: String): Boolean {
        val uri = runCatching { Uri.parse(url) }.getOrNull() ?: return false
        if (!uri.scheme.equals("https", ignoreCase = true)) return false

        val lowerType = contentType.lowercase()
        if (lowerType.contains("mpegurl") || lowerType.contains("dash+xml")) return false

        val path = uri.path.orEmpty().lowercase()
        return lowerType in DIRECT_CAST_CONTENT_TYPES &&
            DIRECT_CAST_EXTENSIONS.any { path.endsWith(it) }
    }

    private fun String.isHttpCastUrl(): Boolean {
        return startsWith("http://", ignoreCase = true) ||
            startsWith("https://", ignoreCase = true)
    }

    private fun observeRemoteMediaClient(session: CastSession) {
        val remoteClient = session.remoteMediaClient ?: run {
            logCast("observeRemoteMediaClient skipped: remote client null")
            return
        }
        if (observedRemoteClient === remoteClient) return

        stopObservingRemoteMediaClient()
        observedRemoteClient = remoteClient
        remoteClient.registerCallback(mediaClientCallback)
        logCast("observing remote media client status=${remoteClient.mediaStatus.summaryForLog()}")
        handleRemoteMediaStatus(remoteClient.mediaStatus)
    }

    private fun stopObservingRemoteMediaClient() {
        observedRemoteClient?.unregisterCallback(mediaClientCallback)
        observedRemoteClient = null
    }

    private fun handleRemoteMediaStatus(status: MediaStatus?) {
        if (status == null) {
            logCast("remote media status=null")
            return
        }

        logCast("remote media status ${status.summaryForLog()}")
        if (status.playerState == MediaStatus.PLAYER_STATE_IDLE &&
            status.idleReason == MediaStatus.IDLE_REASON_ERROR
        ) {
            showCastFailureDialogIfNoReceiverError()
        }
    }

    private fun setReceiverDebugCallback(session: CastSession) {
        runCatching {
            session.removeMessageReceivedCallbacks(RECEIVER_NAMESPACE)
            session.setMessageReceivedCallbacks(RECEIVER_NAMESPACE, receiverMessageCallback)
            sendReceiverPing(session)
            logCast("receiver debug callback attached namespace=$RECEIVER_NAMESPACE")
        }.onFailure {
            Log.e(TAG, "Could not set receiver debug callback", it)
        }
    }

    private fun removeReceiverDebugCallback(session: CastSession) {
        runCatching {
            session.removeMessageReceivedCallbacks(RECEIVER_NAMESPACE)
            logCast("receiver debug callback removed namespace=$RECEIVER_NAMESPACE")
        }
    }

    private fun sendReceiverPing(session: CastSession) {
        runCatching {
            session.sendMessage(
                RECEIVER_NAMESPACE,
                JSONObject().put("type", "PING").toString()
            )
        }.onFailure {
            Log.e(TAG, "Could not ping receiver", it)
        }
    }

    private fun Channel.logSummary(): String {
        return "id=$id name=${name.take(LOG_MESSAGE_LIMIT)} url=$url"
    }

    private fun CastSession.receiverIdForLog(): String {
        return applicationMetadata?.applicationId ?: "unknown"
    }

    private fun MediaStatus?.summaryForLog(): String {
        if (this == null) return "null"
        return "playerState=${playerState.mediaPlayerStateName()} " +
            "idleReason=${idleReason.mediaIdleReasonName()} " +
            "contentType=${mediaInfo?.contentType} " +
            "contentId=${mediaInfo?.contentId} " +
            "streamType=${mediaInfo?.streamType?.streamTypeName()}"
    }

    private fun Int.mediaPlayerStateName(): String {
        return when (this) {
            MediaStatus.PLAYER_STATE_UNKNOWN -> "UNKNOWN"
            MediaStatus.PLAYER_STATE_IDLE -> "IDLE"
            MediaStatus.PLAYER_STATE_PLAYING -> "PLAYING"
            MediaStatus.PLAYER_STATE_PAUSED -> "PAUSED"
            MediaStatus.PLAYER_STATE_BUFFERING -> "BUFFERING"
            else -> toString()
        }
    }

    private fun Int.mediaIdleReasonName(): String {
        return when (this) {
            MediaStatus.IDLE_REASON_NONE -> "NONE"
            MediaStatus.IDLE_REASON_FINISHED -> "FINISHED"
            MediaStatus.IDLE_REASON_CANCELED -> "CANCELED"
            MediaStatus.IDLE_REASON_INTERRUPTED -> "INTERRUPTED"
            MediaStatus.IDLE_REASON_ERROR -> "ERROR"
            else -> toString()
        }
    }

    private fun Int.streamTypeName(): String {
        return when (this) {
            MediaInfo.STREAM_TYPE_NONE -> "NONE"
            MediaInfo.STREAM_TYPE_BUFFERED -> "BUFFERED"
            MediaInfo.STREAM_TYPE_LIVE -> "LIVE"
            else -> toString()
        }
    }

    private fun logCast(message: String) {
        Log.d(TAG, "IPTVCast: $message")
    }

    private fun updateCastIcon(connected: Boolean) {
        // Chỉ cập nhật màu của icon cast, không ảnh hưởng đến icon refresh
        binding.btnCast.setColorFilter(
            if (connected) Color.parseColor("#C49A45") else Color.WHITE,
            PorterDuff.Mode.SRC_IN
        )
    }

    private fun IPTVUiState.isShowingChannelList(): Boolean {
        return tab == IPTVTab.FAVORITES || selectedCategory != null
    }

    private fun handleBackPress() {
        when {
            isPlayerFullscreen -> exitPlayerFullscreen()
            currentUiState.selectedCategory != null -> viewModel.closeCategory()
            else -> {
                // Không tự động ngắt kết nối, chỉ quay lại màn trước
                popBackStack()
            }
        }
    }

    override fun onResume() {
        super.onResume()
        castContext?.setReceiverApplicationId(CastReceiverIds.CUSTOM_RECEIVER)
        sessionManagerListener?.let { listener ->
            castContext?.sessionManager?.addSessionManagerListener(listener, CastSession::class.java)
        }
        castSession = castContext?.sessionManager?.currentCastSession
        castSession?.let { session ->
            logCast(
                "onResume current session connected=${session.isConnected} " +
                    "receiver=${session.receiverIdForLog()} device=${session.castDevice?.friendlyName}"
            )
            if (session.isConnected) {
                setReceiverDebugCallback(session)
                observeRemoteMediaClient(session)
            }
        } ?: logCast("onResume no current cast session")
        updateCastIcon(castSession?.isConnected == true)
    }

    override fun onPause() {
        super.onPause()
        exitPlayerFullscreen(render = false)
        sessionManagerListener?.let { listener ->
            castContext?.sessionManager?.removeSessionManagerListener(listener, CastSession::class.java)
        }
        player?.pause()
    }

    override fun onDestroyView() {
        exitPlayerFullscreen(render = false)
        stopRefreshAnimation()
        castSession?.let(::removeReceiverDebugCallback)
        stopObservingRemoteMediaClient()
        releasePlayer()
        mediaServer.close()
        
        // Cleanup player controls
        uiHandler.removeCallbacksAndMessages(null)
        sleepTimerUpdateHandler.removeCallbacksAndMessages(null)
        sleepTimer?.cancel()
        sleepTimer = null
        
        super.onDestroyView()
    }

    private fun releasePlayer() {
        binding.playerContainer.playerView.player = null
        player?.release()
        player = null
    }

    // ==================== CUSTOM PLAYER CONTROLS ====================
    
    private fun setupPlayerControls() {
        binding.playerContainer.playerView.setOnClickListener {
            togglePlayerControls()
        }
        
        binding.playerContainer.controlsOverlay.setOnClickListener {
            togglePlayerControls()
        }
        
        // Back button
        binding.playerContainer.btnBackPlayer.setOnClickListener {
            viewModel.clearSelectedChannel()
        }
        
        // Play/Pause
        binding.playerContainer.btnPlayPause.setOnClickListener {
            player?.let { exo ->
                if (exo.isPlaying) exo.pause() else exo.play()
            }
            rescheduleHidePlayerControls()
        }
        
        // Rewind
        binding.playerContainer.btnRewind.setOnClickListener {
            player?.let { exo ->
                val newPos = maxOf(0L, exo.currentPosition - SEEK_STEP_MS)
                exo.seekTo(newPos)
                showSeekFeedback("-10s")
            }
            rescheduleHidePlayerControls()
        }
        
        // Forward
        binding.playerContainer.btnForward.setOnClickListener {
            player?.let { exo ->
                val newPos = minOf(exo.duration, exo.currentPosition + SEEK_STEP_MS)
                exo.seekTo(newPos)
                showSeekFeedback("+10s")
            }
            rescheduleHidePlayerControls()
        }
        
        // Seekbar
        binding.playerContainer.seekbarTime.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar, progress: Int, fromUser: Boolean) {
                if (fromUser) {
                    val duration = player?.duration ?: 0L
                    if (duration > 0) {
                        val pos = (duration * progress) / 1000L
                        binding.playerContainer.tvCurrentTime.text = formatTime(pos)
                    }
                }
            }

            override fun onStartTrackingTouch(sb: SeekBar) {
                isSeeking = true
                uiHandler.removeCallbacks(hideControlsRunnable)
            }

            override fun onStopTrackingTouch(sb: SeekBar) {
                isSeeking = false
                val duration = player?.duration ?: 0L
                if (duration > 0) {
                    val pos = (duration * sb.progress) / 1000L
                    player?.seekTo(pos)
                }
                rescheduleHidePlayerControls()
            }
        })
        
        // Fullscreen
        binding.playerContainer.btnFullscreen.setOnClickListener {
            togglePlayerFullscreen()
            rescheduleHidePlayerControls()
        }
        
        // Sleep Timer
        binding.playerContainer.btnSleepTimer.setOnClickListener {
            showSleepTimerDialog()
            rescheduleHidePlayerControls()
        }

        // Lock
        binding.playerContainer.btnLock.setOnClickListener {
            isPlayerLocked = !isPlayerLocked
            updatePlayerLockState()
        }
        
        binding.playerContainer.btnUnlock.setOnClickListener {
            isPlayerLocked = !isPlayerLocked
            updatePlayerLockState()
        }
    }
    
    private fun togglePlayerControls() {
        if (binding.playerContainer.controlsOverlay.isVisible) hidePlayerControls()
        else showPlayerControls()
    }

    private fun showPlayerControls() {
        binding.playerContainer.controlsOverlay.visibility = View.VISIBLE
        binding.playerContainer.controlsOverlay.animate().alpha(1f).setDuration(200).start()
        scheduleHidePlayerControls()
    }

    private fun hidePlayerControls() {
        if (!isAdded || view == null || isDetached) return
        binding.playerContainer.controlsOverlay.animate()
            .alpha(0f)
            .setDuration(300)
            .withEndAction {
                if (isAdded && view != null && !isDetached) {
                    binding.playerContainer.controlsOverlay.visibility = View.GONE
                }
            }
            .start()
    }

    private fun scheduleHidePlayerControls() {
        uiHandler.removeCallbacks(hideControlsRunnable)
        uiHandler.postDelayed(hideControlsRunnable, CONTROLS_HIDE_DELAY_MS)
    }

    private fun rescheduleHidePlayerControls() {
        uiHandler.removeCallbacks(hideControlsRunnable)
        uiHandler.postDelayed(hideControlsRunnable, CONTROLS_HIDE_DELAY_MS)
    }

    private fun togglePlayerFullscreen() {
        if (isPlayerFullscreen) {
            exitPlayerFullscreen()
        } else {
            enterPlayerFullscreen()
        }
    }

    private fun enterPlayerFullscreen() {
        if (currentUiState.selectedChannel == null || isPlayerFullscreen) return

        isPlayerFullscreen = true
        captureInlinePlayerLayoutParams()
        applyPlayerFullscreenLayout()
        applyPlayerFullscreenVisibility()
        updatePlayerFullscreenIcon()
        hideSystemBars()
        requireActivity().requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        showPlayerControls()
    }

    private fun exitPlayerFullscreen(render: Boolean = true) {
        if (!isPlayerFullscreen) return

        isPlayerFullscreen = false
        updatePlayerFullscreenIcon()
        restoreInlinePlayerLayout()
        showSystemBars()
        requireActivity().requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT

        if (render) {
            renderUiState(currentUiState)
        } else {
            applyInlinePlayerVisibility(currentUiState)
        }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        binding.root.post {
            if (isPlayerFullscreen) {
                applyPlayerFullscreenLayout()
                applyPlayerFullscreenVisibility()
                updatePlayerFullscreenIcon()
                hideSystemBars()
            } else {
                restoreInlinePlayerLayout()
                applyInlinePlayerVisibility(currentUiState)
                updatePlayerFullscreenIcon()
                showSystemBars()
            }
        }
    }

    private fun captureInlinePlayerLayoutParams() {
        originalContentLayoutParams =
            ConstraintLayout.LayoutParams(binding.contentContainer.layoutParams as ConstraintLayout.LayoutParams)
        originalPlayerLayoutParams =
            LinearLayout.LayoutParams(binding.playerContainer.root.layoutParams as LinearLayout.LayoutParams)
    }

    private fun restoreInlinePlayerLayout() {
        originalContentLayoutParams?.let {
            binding.contentContainer.layoutParams = ConstraintLayout.LayoutParams(it)
        }
        originalPlayerLayoutParams?.let {
            binding.playerContainer.root.layoutParams = LinearLayout.LayoutParams(it)
        }
        originalContentLayoutParams = null
        originalPlayerLayoutParams = null
    }

    private fun applyPlayerFullscreenLayout() {
        val contentParams =
            ConstraintLayout.LayoutParams(binding.contentContainer.layoutParams as ConstraintLayout.LayoutParams)
        contentParams.topToTop = ConstraintLayout.LayoutParams.PARENT_ID
        contentParams.topToBottom = ConstraintLayout.LayoutParams.UNSET
        binding.contentContainer.layoutParams = contentParams

        val playerParams =
            LinearLayout.LayoutParams(binding.playerContainer.root.layoutParams as LinearLayout.LayoutParams)
        playerParams.width = ViewGroup.LayoutParams.MATCH_PARENT
        playerParams.height = 0
        playerParams.weight = 1f
        playerParams.setMargins(0, 0, 0, 0)
        binding.playerContainer.root.layoutParams = playerParams
    }

    private fun applyInlinePlayerVisibility(state: IPTVUiState) {
        val showingChannels = state.isShowingChannelList()
        val showingRootTabs = state.selectedCategory == null
        val showingGenreCategories = state.tab == IPTVTab.GENRES && state.selectedCategory == null
        val showingFavoriteSearch = state.tab == IPTVTab.FAVORITES && state.selectedCategory == null

        binding.topBar.isVisible = true
        binding.tabContainer.isVisible = showingRootTabs
        binding.filterContainer.isVisible = showingRootTabs && state.tab == IPTVTab.GENRES
        binding.channelSearchContainer.isVisible = showingFavoriteSearch
        binding.listContainer.isVisible = true
        binding.rvCategories.isVisible = showingGenreCategories
        binding.tvEmptyCategories.isVisible = showingGenreCategories && state.categories.isEmpty()
        binding.rvChannels.isVisible = showingChannels
        binding.tvEmptyChannels.isVisible = showingChannels && state.channels.isEmpty()
        binding.playerContainer.root.isVisible = showingChannels && state.selectedChannel != null
    }

    private fun applyPlayerFullscreenVisibility() {
        if (!isPlayerFullscreen) return

        binding.topBar.isVisible = false
        binding.tabContainer.isVisible = false
        binding.filterContainer.isVisible = false
        binding.channelSearchContainer.isVisible = false
        binding.listContainer.isVisible = false
        binding.playerContainer.root.isVisible = true
    }

    private fun updatePlayerFullscreenIcon() {
        binding.playerContainer.btnFullscreen.setImageResource(
            if (isPlayerFullscreen) R.drawable.ic_fullscreen_exit else R.drawable.ic_fullscreen
        )
        binding.playerContainer.btnFullscreen.contentDescription = getString(
            if (isPlayerFullscreen) R.string.text_exit_fullscreen else R.string.text_fullscreen
        )
    }

    private fun hideSystemBars() {
        val window = requireActivity().window
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowCompat.getInsetsController(window, window.decorView).apply {
            systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            hide(WindowInsetsCompat.Type.systemBars())
        }
    }

    private fun showSystemBars() {
        val window = requireActivity().window
        WindowCompat.setDecorFitsSystemWindows(window, true)
        WindowCompat.getInsetsController(window, window.decorView)
            .show(WindowInsetsCompat.Type.systemBars())
        binding.root.requestApplyInsets()
    }
    
    private fun updatePlayerLockState() {
        if (isPlayerLocked) {
            binding.playerContainer.topBarControls.visibility = View.GONE
            binding.playerContainer.btnRewind.visibility = View.GONE
            binding.playerContainer.btnPlayPause.visibility = View.GONE
            binding.playerContainer.btnForward.visibility = View.GONE
            binding.playerContainer.bottomControls.visibility = View.GONE
            binding.playerContainer.btnUnlock.visibility = View.VISIBLE
        } else {
            binding.playerContainer.topBarControls.visibility = View.VISIBLE
            binding.playerContainer.btnRewind.visibility = View.VISIBLE
            binding.playerContainer.btnPlayPause.visibility = View.VISIBLE
            binding.playerContainer.btnForward.visibility = View.VISIBLE
            binding.playerContainer.bottomControls.visibility = View.VISIBLE
            binding.playerContainer.btnUnlock.visibility = View.GONE
        }
    }
    
    private fun startProgressUpdater() {
        lifecycleScope.launch {
            while (true) {
                delay(PROGRESS_UPDATE_MS)
                player?.let { exo ->
                    if (!isSeeking && exo.duration > 0) {
                        val pos = exo.currentPosition
                        val dur = exo.duration
                        val prog = ((pos * 1000L) / dur).toInt()
                        binding.playerContainer.seekbarTime.progress = prog
                        binding.playerContainer.tvCurrentTime.text = formatTime(pos)
                        binding.playerContainer.tvTotalTime.text = formatTime(dur)
                    }
                }
            }
        }
    }

    private fun showSeekFeedback(text: String) {
        binding.playerContainer.tvSeekFeedback.text = text
        binding.playerContainer.tvSeekFeedback.visibility = View.VISIBLE
        binding.playerContainer.tvSeekFeedback.alpha = 1f
        uiHandler.postDelayed({
            binding.playerContainer.tvSeekFeedback.animate()
                .alpha(0f)
                .setDuration(500)
                .withEndAction { binding.playerContainer.tvSeekFeedback.visibility = View.GONE }
                .start()
        }, 700)
    }

    private fun formatTime(ms: Long): String {
        if (ms <= 0) return "00:00"
        val totalSeconds = ms / 1000
        val hours = totalSeconds / 3600
        val minutes = (totalSeconds % 3600) / 60
        val seconds = totalSeconds % 60
        return if (hours > 0)
            "%02d:%02d:%02d".format(hours, minutes, seconds)
        else
            "%02d:%02d".format(minutes, seconds)
    }
    
    private fun showSleepTimerDialog() {
        val options = arrayOf(
            getString(R.string.text_off),
            getString(R.string.text_10_minutes),
            getString(R.string.text_30_minutes),
            getString(R.string.text_60_minutes)
        )
        
        AlertDialog.Builder(requireContext())
            .setTitle(getString(R.string.text_sleep_timer))
            .setItems(options) { _, which ->
                cancelSleepTimer()
                when (which) {
                    1 -> startSleepTimer(10 * 60L)
                    2 -> startSleepTimer(30 * 60L)
                    3 -> startSleepTimer(60 * 60L)
                }
            }
            .show()
    }

    private fun startSleepTimer(durationSeconds: Long) {
        sleepTimerEndTime = System.currentTimeMillis() + durationSeconds * 1000L

        sleepTimer = object : CountDownTimer(durationSeconds * 1000L, 1000L) {
            override fun onTick(ms: Long) {}
            override fun onFinish() {
                player?.pause()
                sleepTimerEndTime = 0L
                sleepTimerUpdateHandler.removeCallbacks(sleepTimerUpdateRunnable)
                binding.playerContainer.btnSleepTimer.clearColorFilter()
                Toast.makeText(
                    requireContext(), 
                    getString(R.string.text_end_timer_pause_playback), 
                    Toast.LENGTH_LONG
                ).show()
            }
        }.start()

        binding.playerContainer.btnSleepTimer.setColorFilter(
            Color.parseColor("#FFC107"), PorterDuff.Mode.SRC_IN
        )
        sleepTimerUpdateHandler.post(sleepTimerUpdateRunnable)

        Toast.makeText(
            requireContext(), 
            formatCountdown(durationSeconds), 
            Toast.LENGTH_SHORT
        ).show()
    }

    private fun cancelSleepTimer() {
        sleepTimer?.cancel()
        sleepTimer = null
        sleepTimerEndTime = 0L
        sleepTimerUpdateHandler.removeCallbacks(sleepTimerUpdateRunnable)
        binding.playerContainer.btnSleepTimer.clearColorFilter()
    }

    private fun updateSleepTimerIcon() {
        val remaining = getRemainingSeconds()
        if (remaining <= 0) {
            sleepTimerUpdateHandler.removeCallbacks(sleepTimerUpdateRunnable)
            return
        }
        binding.playerContainer.btnSleepTimer.imageAlpha = 
            if (remaining in 1..60 && (remaining % 2L) == 0L) 160 else 255
    }

    private fun getRemainingSeconds(): Long {
        if (sleepTimerEndTime == 0L) return 0L
        return maxOf(0L, (sleepTimerEndTime - System.currentTimeMillis()) / 1000L)
    }

    private fun formatCountdown(totalSeconds: Long): String {
        val h = totalSeconds / 3600
        val m = (totalSeconds % 3600) / 60
        val s = totalSeconds % 60
        return if (h > 0) "%d hr %02d min".format(h, m)
        else if (m > 0) "%d min %02d sec".format(m, s)
        else "%d sec".format(s)
    }

    companion object {
        private const val RECEIVER_NAMESPACE = "urn:x-cast:com.example.camera.webrtc"
        private const val CAST_LOAD_FAILURE_FALLBACK_DELAY_MS = 1_200L
        private const val CAST_SNIFF_TIMEOUT_MS = 3_500
        private const val CAST_REMOTE_USER_AGENT =
            "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 Chrome/120 Mobile Safari/537.36"
        private const val DEFAULT_CAST_CONTENT_TYPE = "video/mp4"
        private const val TAG = "IPTVCast"
        private const val LOG_MESSAGE_LIMIT = 900
        private val DIRECT_CAST_CONTENT_TYPES = setOf(
            "video/mp4",
            "video/webm",
            "audio/mpeg",
            "audio/mp4"
        )
        private val DIRECT_CAST_EXTENSIONS = setOf(
            ".mp4",
            ".m4v",
            ".webm",
            ".mp3",
            ".m4a",
            ".aac"
        )
    }
}
