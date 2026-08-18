package com.tvchromecast.screenmirroringplus.ui.iptv_fragment

import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.app.AlertDialog
import android.content.Context
import android.graphics.Color
import android.graphics.PorterDuff
import android.media.AudioManager
import android.net.Uri
import android.os.CountDownTimer
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.view.View
import android.view.animation.LinearInterpolator
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.Toast
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
import com.tvchromecast.screenmirroringplus.ui.common.showCastFailureDialog
import com.google.android.gms.cast.CastMediaControlIntent
import com.google.android.gms.cast.Cast
import com.google.android.gms.cast.MediaInfo
import com.google.android.gms.cast.MediaLoadRequestData
import com.google.android.gms.cast.MediaMetadata
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
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONObject

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
    private var audioManager: AudioManager? = null
    private var maxVolume: Int = 0
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

    private var castContext: CastContext? = null
    private var castSession: CastSession? = null
    private var sessionManagerListener: SessionManagerListener<CastSession>? = null
    private val mediaServer by lazy { LocalMediaHttpServer(requireContext().applicationContext) }

    private val receiverMessageCallback = Cast.MessageReceivedCallback { _, _, message ->
        logReceiverMessage(message)
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
        
        // Update player title and favorite status
        binding.playerContainer.tvPlayerTitle.text = channel.name
        binding.playerContainer.btnFavorite.setImageResource(
            if (channel.isFavourite) R.drawable.favourited else R.drawable.favourite
        )
        
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
        } catch (e: Exception) {
            castContext = null
        }
    }

    private fun showCastDialog() {
        val channel = currentUiState.selectedChannel ?: run {
            Toast.makeText(requireContext(), R.string.text_select_channel_first, Toast.LENGTH_SHORT).show()
            return
        }

        val castCtx = castContext ?: run {
            AlertDialog.Builder(requireContext())
                .setTitle(getString(R.string.text_cast_not_available))
                .setMessage(getString(R.string.text_google_play_services_has_not_been_initialized))
                .setPositiveButton(getString(R.string.text_ok), null)
                .show()
            return
        }

        if (castSession?.isConnected == true) {
            AlertDialog.Builder(requireContext())
                .setTitle(getString(R.string.text_casting_to_tv_plain))
                .setMessage(getString(R.string.text_what_would_you_like_to_do))
                .setPositiveButton(getString(R.string.text_disconnect)) { _, _ ->
                    castCtx.sessionManager.endCurrentSession(true)
                }
                .setNeutralButton(getString(R.string.text_restart_from_beginning)) { _, _ ->
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

        androidx.mediarouter.app.MediaRouteChooserDialogFragment().apply {
            routeSelector = selector
        }.show(childFragmentManager, "IPTVCastChooser")
    }

    private fun setupSessionManagerListener() {
        sessionManagerListener = object : SessionManagerListener<CastSession> {
            override fun onSessionStarted(session: CastSession, sessionId: String) {
                castSession = session
                setReceiverDebugCallback(session)
                currentUiState.selectedChannel?.let { loadMediaOnCast(it) }
                player?.pause()
                updateCastIcon(connected = true)
            }

            override fun onSessionResumed(session: CastSession, wasSuspended: Boolean) {
                castSession = session
                setReceiverDebugCallback(session)
                updateCastIcon(connected = true)
            }

            override fun onSessionEnded(session: CastSession, error: Int) {
                removeReceiverDebugCallback(session)
                castSession = null
                player?.play()
                updateCastIcon(connected = false)
            }

            override fun onSessionStartFailed(session: CastSession, error: Int) {
                showCastFailureDialog()
            }

            override fun onSessionSuspended(session: CastSession, reason: Int) = Unit
            override fun onSessionStarting(session: CastSession) = Unit
            override fun onSessionEnding(session: CastSession) = Unit
            override fun onSessionResuming(session: CastSession, sessionId: String) = Unit
            override fun onSessionResumeFailed(session: CastSession, error: Int) = Unit
        }
    }

    private fun loadMediaOnCast(channel: Channel) {
        val remoteClient: RemoteMediaClient = castSession?.remoteMediaClient ?: return
        val contentType = inferCastContentType(channel.url)
        val castUrl = mediaServer.registerRemoteUrl(channel.url, contentType) ?: run {
            Toast.makeText(requireContext(), R.string.text_could_not_prepare_media, Toast.LENGTH_SHORT).show()
            return
        }
        castSession?.let {
            setReceiverDebugCallback(it)
            sendReceiverPing(it)
        }
        Log.d(
            TAG,
            "Loading IPTV on Cast originalUrl=${channel.url} castUrl=$castUrl contentType=$contentType"
        )
        val metadata = MediaMetadata(MediaMetadata.MEDIA_TYPE_MOVIE).apply {
            putString(MediaMetadata.KEY_TITLE, channel.name)
        }

        val streamType = inferCastStreamType(channel.url)

        val mediaInfo = MediaInfo.Builder(castUrl)
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
                    Log.d(
                        TAG,
                        "IPTV cast load result success=${result.status.isSuccess} " +
                            "code=${result.status.statusCode} message=${result.status.statusMessage}"
                    )
                    if (result.status.isSuccess) {
                        player?.pause()
                    } else {
                        showCastFailureDialog()
                    }
                }
            }
    }

    private fun inferCastStreamType(url: String): Int {
        val lower = url.lowercase()
        return when {
            lower.contains(".m3u8") || lower.startsWith("rtmp://") || lower.startsWith("rtsp://") ->
                MediaInfo.STREAM_TYPE_LIVE
            else -> MediaInfo.STREAM_TYPE_BUFFERED
        }
    }

    private fun inferCastContentType(url: String): String {
        val cleanUrl = url.lowercase().substringBefore("#").substringBefore("?")
        return when {
            cleanUrl.endsWith(".m3u8") || cleanUrl.contains(".m3u8/") ->
                "application/x-mpegURL"
            cleanUrl.endsWith(".mpd") || cleanUrl.contains(".mpd/") ->
                "application/dash+xml"
            cleanUrl.endsWith(".webm") || cleanUrl.contains(".webm/") ->
                "video/webm"
            cleanUrl.endsWith(".mp3") || cleanUrl.contains(".mp3/") ->
                "audio/mpeg"
            cleanUrl.endsWith(".m4a") || cleanUrl.contains(".m4a/") ->
                "audio/mp4"
            else -> "video/mp4"
        }
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
        if (currentUiState.selectedCategory != null) {
            viewModel.closeCategory()
        } else if (castSession?.isConnected == true) {
            showDisconnectBeforeExitDialog()
        } else {
            popBackStack()
        }
    }

    private fun showDisconnectBeforeExitDialog() {
        AlertDialog.Builder(requireContext())
            .setTitle(getString(R.string.text_casting_to_tv_plain))
            .setMessage(getString(R.string.text_stop_casting_message))
            .setPositiveButton(getString(R.string.text_disconnect)) { _, _ ->
                castSession?.remoteMediaClient?.stop()
                castContext?.sessionManager?.endCurrentSession(true)
                popBackStack()
            }
            .setNegativeButton(getString(R.string.text_cancel), null)
            .show()
    }

    override fun onResume() {
        super.onResume()
        sessionManagerListener?.let { listener ->
            castContext?.sessionManager?.addSessionManagerListener(listener, CastSession::class.java)
        }
        castSession = castContext?.sessionManager?.currentCastSession
        updateCastIcon(castSession?.isConnected == true)
    }

    override fun onPause() {
        super.onPause()
        sessionManagerListener?.let { listener ->
            castContext?.sessionManager?.removeSessionManagerListener(listener, CastSession::class.java)
        }
        player?.pause()
    }

    override fun onDestroyView() {
        stopRefreshAnimation()
        castSession?.let(::removeReceiverDebugCallback)
        releasePlayer()
        mediaServer.close()
        
        // Cleanup player controls
        uiHandler.removeCallbacksAndMessages(null)
        sleepTimerUpdateHandler.removeCallbacksAndMessages(null)
        sleepTimer?.cancel()
        sleepTimer = null
        
        super.onDestroyView()
    }

    private fun setReceiverDebugCallback(session: CastSession) {
        runCatching {
            session.removeMessageReceivedCallbacks(RECEIVER_NAMESPACE)
            session.setMessageReceivedCallbacks(RECEIVER_NAMESPACE, receiverMessageCallback)
            sendReceiverPing(session)
        }.onFailure {
            Log.e(TAG, "Could not set receiver debug callback", it)
        }
    }

    private fun removeReceiverDebugCallback(session: CastSession) {
        runCatching {
            session.removeMessageReceivedCallbacks(RECEIVER_NAMESPACE)
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

    private fun logReceiverMessage(rawMessage: String) {
        Log.d(TAG, "Receiver message: $rawMessage")
    }

    private fun releasePlayer() {
        binding.playerContainer.playerView.player = null
        player?.release()
        player = null
    }

    // ==================== CUSTOM PLAYER CONTROLS ====================
    
    private fun setupPlayerControls() {
        binding.playerContainer.root.post {
            initVolumeAndBrightness()
        }
        
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
        
        // Favorite button
        binding.playerContainer.btnFavorite.setOnClickListener {
            currentUiState.selectedChannel?.let { channel ->
                viewModel.toggleFavourite(channel, !channel.isFavourite)
            }
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
            // Fullscreen không áp dụng cho bottom player
            Toast.makeText(requireContext(), R.string.text_fullscreen_not_available, Toast.LENGTH_SHORT).show()
            rescheduleHidePlayerControls()
        }
        
        // Sleep Timer
        binding.playerContainer.btnSleepTimer.setOnClickListener {
            showSleepTimerDialog()
            rescheduleHidePlayerControls()
        }
        
        // PiP
        binding.playerContainer.btnPip.setOnClickListener {
            Toast.makeText(requireContext(), R.string.text_pip_not_available, Toast.LENGTH_SHORT).show()
            rescheduleHidePlayerControls()
        }
        
        // Cast
        binding.playerContainer.btnCastPlayer.setOnClickListener {
            showCastDialog()
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
        
        // Volume control
        binding.playerContainer.btnVolume.setOnClickListener {
            val currentVol = audioManager?.getStreamVolume(AudioManager.STREAM_MUSIC) ?: 0
            if (currentVol > 0) {
                audioManager?.setStreamVolume(AudioManager.STREAM_MUSIC, 0, 0)
                binding.playerContainer.volumeSeekbar.progress = 0
                updateVolumeIcon(0)
            } else {
                val halfVol = maxVolume / 2
                audioManager?.setStreamVolume(AudioManager.STREAM_MUSIC, halfVol, 0)
                binding.playerContainer.volumeSeekbar.progress = 50
                updateVolumeIcon(50)
            }
        }
    }
    
    private fun initVolumeAndBrightness() {
        audioManager = requireContext().getSystemService(Context.AUDIO_SERVICE) as AudioManager
        maxVolume = audioManager!!.getStreamMaxVolume(AudioManager.STREAM_MUSIC)

        val currentVolume = audioManager!!.getStreamVolume(AudioManager.STREAM_MUSIC)
        val volumePercent = (currentVolume * 100) / maxVolume
        binding.playerContainer.volumeSeekbar.progress = volumePercent

        val currentBrightness = getCurrentBrightness()
        binding.playerContainer.brightnessSeekbar.progress = currentBrightness

        binding.playerContainer.volumeSeekbar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar, progress: Int, fromUser: Boolean) {
                if (fromUser) {
                    val vol = (progress * maxVolume) / 100
                    audioManager?.setStreamVolume(AudioManager.STREAM_MUSIC, vol, 0)
                    updateVolumeIcon(progress)
                }
            }
            override fun onStartTrackingTouch(sb: SeekBar) {}
            override fun onStopTrackingTouch(sb: SeekBar) {}
        })

        binding.playerContainer.brightnessSeekbar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar, progress: Int, fromUser: Boolean) {
                if (fromUser) setBrightness(progress)
            }
            override fun onStartTrackingTouch(sb: SeekBar) {}
            override fun onStopTrackingTouch(sb: SeekBar) {}
        })
    }

    private fun getCurrentBrightness(): Int {
        val lp = requireActivity().window.attributes
        if (lp.screenBrightness >= 0f) {
            return (lp.screenBrightness * 100).toInt()
        }
        return try {
            val brightness = Settings.System.getInt(
                requireContext().contentResolver,
                Settings.System.SCREEN_BRIGHTNESS
            )
            (brightness * 100) / 255
        } catch (e: Exception) {
            50
        }
    }

    private fun setBrightness(progress: Int) {
        val brightness = progress.coerceAtLeast(5) / 100f
        val lp = requireActivity().window.attributes
        lp.screenBrightness = brightness
        requireActivity().window.attributes = lp
    }

    private fun updateVolumeIcon(volumePercent: Int) {
        binding.playerContainer.btnVolume.setImageResource(
            if (volumePercent == 0) R.drawable.ic_volume_off else R.drawable.ic_volumn
        )
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
    
    private fun updatePlayerLockState() {
        if (isPlayerLocked) {
            binding.playerContainer.topBarControls.visibility = View.GONE
            binding.playerContainer.btnRewind.visibility = View.GONE
            binding.playerContainer.btnPlayPause.visibility = View.GONE
            binding.playerContainer.btnForward.visibility = View.GONE
            binding.playerContainer.bottomControls.visibility = View.GONE
            binding.playerContainer.btnUnlock.visibility = View.VISIBLE
            binding.playerContainer.volumePanel.visibility = View.GONE
            binding.playerContainer.brightnessPanel.visibility = View.GONE
        } else {
            binding.playerContainer.topBarControls.visibility = View.VISIBLE
            binding.playerContainer.btnRewind.visibility = View.VISIBLE
            binding.playerContainer.btnPlayPause.visibility = View.VISIBLE
            binding.playerContainer.btnForward.visibility = View.VISIBLE
            binding.playerContainer.bottomControls.visibility = View.VISIBLE
            binding.playerContainer.btnUnlock.visibility = View.GONE
            binding.playerContainer.volumePanel.visibility = View.VISIBLE
            binding.playerContainer.brightnessPanel.visibility = View.VISIBLE
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
        private const val TAG = "IPTVDebug"
    }
}
