package com.tvchromecast.screenmirroringplus.ui.iptv_fragment

import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.app.AlertDialog
import android.graphics.Color
import android.graphics.PorterDuff
import android.net.Uri
import android.util.Log
import android.view.View
import android.view.animation.LinearInterpolator
import android.widget.Toast
import androidx.appcompat.widget.PopupMenu
import androidx.core.view.isVisible
import androidx.core.widget.doOnTextChanged
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
import org.json.JSONObject

@AndroidEntryPoint
class IPTVFragment : BaseFragment<FragmentIPTVBinding, IPTVViewModel>() {

    private val categoryAdapter by lazy {
        IPTVCategoryAdapter { category -> viewModel.openCategory(category) }
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
        adjustInsetsForBottomMargin(binding.playerContainer)
        setupRecyclerViews()
        setupCast()
    }

    override fun initListener() {
        onBackPressed { handleBackPress() }
        binding.btnBack.setOnClickListener { handleBackPress() }
        binding.btnTopAction.setOnClickListener {
            if (currentUiState.isShowingChannelList()) {
                showCastDialog()
            } else {
                viewModel.refreshPlaylist()
            }
        }
        binding.btnGenres.setOnClickListener { viewModel.selectTab(IPTVTab.GENRES) }
        binding.btnFavorites.setOnClickListener { viewModel.selectTab(IPTVTab.FAVORITES) }
        binding.btnFilterMode.setOnClickListener { showFilterModeMenu() }
        binding.btnFilterValue.setOnClickListener { showFilterSheet() }
        binding.btnSearch.setOnClickListener { showFilterSheet() }
        binding.etChannelSearch.doOnTextChanged { text, _, _, _ ->
            val query = text?.toString().orEmpty()
            if (query != currentUiState.channelSearchQuery) {
                viewModel.updateChannelSearchQuery(query)
            }
        }
        binding.btnCastToTv.setOnClickListener { showCastDialog() }
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
        binding.btnTopAction.setImageResource(
            if (showingChannels) R.drawable.ic_cast_screen_white else R.drawable.ic_reload_white
        )
        binding.btnTopAction.contentDescription = getString(
            if (showingChannels) R.string.text_cast_to_tv else R.string.text_refresh_playlist
        )

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
        binding.btnFilterMode.text = getFilterModeLabel(state.filterMode)
        binding.btnFilterValue.text = state.selectedFilter
            ?: state.filterOptions.firstOrNull { it.value == null }?.label
            ?: getFilterAllLabel(state.filterMode)
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
            binding.playerContainer.isVisible = false
            releasePlayer()
            return
        }

        binding.playerContainer.isVisible = true
        if (selectedPlayerChannelId != channel.id) {
            selectedPlayerChannelId = channel.id
            initPlayer(channel)
        }
    }

    private fun renderRefreshState(state: CategoryRefreshSaveState) {
        when (state) {
            CategoryRefreshSaveState.Idle -> {
                binding.refreshLoadingOverlay.isVisible = false
                stopRefreshAnimation()
            }

            is CategoryRefreshSaveState.Loading -> {
                binding.refreshLoadingOverlay.isVisible = true
                binding.tvRefreshProgress.text = getString(
                    R.string.text_refreshing_playlist_progress,
                    state.progress
                )
                startRefreshAnimation()
            }

            is CategoryRefreshSaveState.Success -> {
                binding.refreshLoadingOverlay.isVisible = false
                stopRefreshAnimation()
                Toast.makeText(
                    requireContext(),
                    getString(R.string.text_playlist_refreshed),
                    Toast.LENGTH_SHORT
                ).show()
                viewModel.acknowledgeRefreshState()
            }

            is CategoryRefreshSaveState.Error -> {
                binding.refreshLoadingOverlay.isVisible = false
                stopRefreshAnimation()
                Toast.makeText(requireContext(), state.message, Toast.LENGTH_SHORT).show()
                viewModel.acknowledgeRefreshState()
            }
        }
    }

    private fun showFilterModeMenu() {
        PopupMenu(requireContext(), binding.btnFilterMode).apply {
            menu.add(0, FILTER_MODE_COUNTRY, 0, getString(R.string.text_by_country))
            menu.add(0, FILTER_MODE_LANGUAGE, 1, getString(R.string.text_by_language))
            setOnMenuItemClickListener { item ->
                when (item.itemId) {
                    FILTER_MODE_COUNTRY -> viewModel.selectFilterMode(IPTVFilterMode.COUNTRY)
                    FILTER_MODE_LANGUAGE -> viewModel.selectFilterMode(IPTVFilterMode.LANGUAGE)
                }
                true
            }
            show()
        }
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
        refreshAnimator = ObjectAnimator.ofFloat(binding.btnTopAction, View.ROTATION, 0f, -360f)
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
        binding.btnTopAction.rotation = 0f
    }

    private fun initPlayer(channel: Channel) {
        releasePlayer()
        binding.playerLoading.isVisible = true
        binding.playerUnavailable.isVisible = false

        player = ExoPlayer.Builder(requireContext()).build().also { exoPlayer ->
            binding.playerView.player = exoPlayer
            exoPlayer.setMediaItem(buildMediaItem(channel.url))
            exoPlayer.prepare()
            exoPlayer.playWhenReady = true
            exoPlayer.addListener(object : Player.Listener {
                override fun onPlaybackStateChanged(playbackState: Int) {
                    when (playbackState) {
                        Player.STATE_BUFFERING -> binding.playerLoading.isVisible = true
                        Player.STATE_READY -> {
                            binding.playerLoading.isVisible = false
                            binding.playerUnavailable.isVisible = false
                        }
                        Player.STATE_ENDED -> binding.playerLoading.isVisible = false
                        else -> Unit
                    }
                }

                override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                    binding.playerLoading.isVisible = false
                    binding.playerUnavailable.isVisible = true
                }
            })
        }
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
        if (!currentUiState.isShowingChannelList()) {
            binding.btnTopAction.setColorFilter(Color.WHITE, PorterDuff.Mode.SRC_IN)
            return
        }
        binding.btnTopAction.setColorFilter(
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
        binding.playerView.player = null
        player?.release()
        player = null
    }

    companion object {
        private const val FILTER_MODE_COUNTRY = 1
        private const val FILTER_MODE_LANGUAGE = 2
        private const val RECEIVER_NAMESPACE = "urn:x-cast:com.example.camera.webrtc"
        private const val TAG = "IPTVDebug"
    }
}
