package com.tvchromecast.screenmirroringplus.ui.cast_media

import android.Manifest
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.Toast
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import androidx.core.view.updatePadding
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.google.android.gms.cast.MediaInfo
import com.google.android.gms.cast.MediaLoadRequestData
import com.google.android.gms.cast.MediaMetadata
import com.google.android.gms.cast.MediaStatus
import com.google.android.gms.cast.framework.CastButtonFactory
import com.google.android.gms.cast.framework.CastContext
import com.google.android.gms.cast.framework.CastSession
import com.google.android.gms.cast.framework.SessionManagerListener
import com.google.android.gms.cast.framework.media.RemoteMediaClient
import com.google.android.gms.common.images.WebImage
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.tvchromecast.screenmirroringplus.R
import com.tvchromecast.screenmirroringplus.cast.CastReceiverIds
import com.tvchromecast.screenmirroringplus.databinding.FragmentCastMediaBinding
import com.tvchromecast.screenmirroringplus.media.LocalMediaHttpServer
import com.tvchromecast.screenmirroringplus.ui.common.hasRecentReceiverMediaError
import com.tvchromecast.screenmirroringplus.ui.common.showCastFailureDialog
import hoang.dqm.codebase.base.activity.BaseFragment
import hoang.dqm.codebase.base.activity.onBackPressed
import hoang.dqm.codebase.base.activity.popBackStack
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import hoang.dqm.codebase.R as CodeBaseR

class CastMediaFragment : BaseFragment<FragmentCastMediaBinding, CastMediaViewModel>() {
    override val viewModelClass: Class<CastMediaViewModel>
        get() = CastMediaViewModel::class.java

    override fun inflateBinding(
        inflater: android.view.LayoutInflater,
        container: android.view.ViewGroup?
    ): FragmentCastMediaBinding {
        return FragmentCastMediaBinding.inflate(inflater, container, false)
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private val mediaServer by lazy { LocalMediaHttpServer.shared(requireContext()) }
    private val photoAdapter by lazy { PhotoThumbAdapter(::selectPhoto) }
    private val mode: String by lazy {
        arguments?.getString(ARG_MODE, MODE_PHOTO) ?: MODE_PHOTO
    }

    private var castContext: CastContext? = null
    private var pendingCast = false
    private var reconnectingForMediaReceiver = false
    private var isCasting = false
    private var selectedPhotoIndex = 0
    private var photos = emptyList<Uri>()
    private var videoUri: Uri? = null
    private var player: ExoPlayer? = null
    private var toolbarBaseHeight = 0
    private var bottomButtonBaseMargin = 0
    private var observedRemoteClient: RemoteMediaClient? = null

    private val mediaClientCallback = object : RemoteMediaClient.Callback() {
        override fun onStatusUpdated() {
            handleRemoteMediaStatus(observedRemoteClient?.mediaStatus)
        }

        override fun onMetadataUpdated() {
            handleRemoteMediaStatus(observedRemoteClient?.mediaStatus)
        }
    }

    private val videoProgressRunnable = object : Runnable {
        override fun run() {
            updateVideoTime()
            mainHandler.postDelayed(this, VIDEO_PROGRESS_INTERVAL_MS)
        }
    }

    private val photoPicker = registerForActivityResult(
        ActivityResultContracts.PickMultipleVisualMedia(MAX_PHOTOS)
    ) { uris ->
        if (uris.isEmpty()) {
            if (photos.isEmpty()) {
                updateControls()
            }
        } else {
            if (photos.isEmpty()) {
                setPhotos(uris)
            } else {
                addPhotos(uris)
            }
        }
    }

    private val videoPicker = registerForActivityResult(
        ActivityResultContracts.PickVisualMedia()
    ) { uri ->
        if (uri == null) {
            updateControls()
        } else {
            setVideo(uri)
        }
    }

    private val mediaPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            openPicker()
        } else {
            Toast.makeText(
                requireContext(),
                R.string.text_media_pick_error_message,
                Toast.LENGTH_SHORT
            ).show()
        }
    }

    private val castSessionListener = object : SessionManagerListener<CastSession> {
        override fun onSessionStarting(session: CastSession) {
            updateCastStatus(CastConnectionState.Connecting)
        }

        override fun onSessionStarted(session: CastSession, sessionId: String) {
            updateCastStatus(CastConnectionState.Connected)
            observeRemoteMediaClient(session)
            if (pendingCast) {
                pendingCast = false
                castSelectedMedia()
            }
        }

        override fun onSessionStartFailed(session: CastSession, error: Int) {
            pendingCast = false
            reconnectingForMediaReceiver = false
            updateCastStatus(CastConnectionState.Error)
            updateControls()
        }

        override fun onSessionEnding(session: CastSession) {
            updateCastStatus(CastConnectionState.Connecting)
        }

        override fun onSessionEnded(session: CastSession, error: Int) {
            stopObservingRemoteMediaClient()
            if (reconnectingForMediaReceiver) {
                reconnectingForMediaReceiver = false
            } else {
                pendingCast = false
            }
            isCasting = false
            updateCastStatus(CastConnectionState.Disconnected)
            updateControls()
        }

        override fun onSessionResuming(session: CastSession, sessionId: String) {
            updateCastStatus(CastConnectionState.Connecting)
        }

        override fun onSessionResumed(session: CastSession, wasSuspended: Boolean) {
            updateCastStatus(CastConnectionState.Connected)
            observeRemoteMediaClient(session)
            updateControls()
        }

        override fun onSessionResumeFailed(session: CastSession, error: Int) {
            reconnectingForMediaReceiver = false
            updateCastStatus(CastConnectionState.Error)
            updateControls()
        }

        override fun onSessionSuspended(session: CastSession, reason: Int) {
            stopObservingRemoteMediaClient()
            isCasting = false
            updateCastStatus(CastConnectionState.Disconnected)
            updateControls()
        }
    }

    override fun initView() {
        adjustInsetsForBottomMargin(binding.barSetting)
        applySystemInsets()
        setupCastButton()
        setupModeUi()
        setupPhotoList()
        updateControls()
    }

    override fun initListener() {
        binding.btnBack.setOnClickListener { handleBackPressed() }
        binding.btnStartCasting.setOnClickListener { handleCastButton() }
//        binding.photoPreview.setOnClickListener { openPicker() }
        binding.videoPlayer.setOnClickListener { openPicker() }
        binding.emptyMediaContainer.setOnClickListener { requestMediaPermissionAndOpen() }
        binding.btnAddPhotos.setOnClickListener { openPicker() }
        binding.btnPreviousPhoto.setOnClickListener { navigateToPreviousPhoto() }
        binding.btnNextPhoto.setOnClickListener { navigateToNextPhoto() }
        onBackPressed(Runnable { handleBackPressed() })
    }

    override fun initData() {
        requestMediaPermissionAndOpen()
    }

    override fun onStart() {
        super.onStart()
        castContext?.sessionManager?.addSessionManagerListener(
            castSessionListener,
            CastSession::class.java
        )
        currentCastSession()?.let(::observeRemoteMediaClient)
        updateCastStatusFromSession()
    }

    override fun onStop() {
        stopObservingRemoteMediaClient()
        castContext?.sessionManager?.removeSessionManagerListener(
            castSessionListener,
            CastSession::class.java
        )
        super.onStop()
    }

    override fun onPause() {
        mainHandler.removeCallbacks(videoProgressRunnable)
        player?.pause()
        super.onPause()
    }

    override fun onResume() {
        super.onResume()
        if (mode == MODE_VIDEO && videoUri != null) {
            startVideoProgressTicker()
        }
    }

    override fun onDestroyView() {
        mainHandler.removeCallbacksAndMessages(null)
        player?.release()
        player = null
        super.onDestroyView()
    }

    private fun applySystemInsets() {
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { _, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())

            if (toolbarBaseHeight == 0) {
                toolbarBaseHeight = binding.toolbar.layoutParams.height
            }
            binding.toolbar.layoutParams = binding.toolbar.layoutParams.apply {
                height = toolbarBaseHeight + systemBars.top
            }
            binding.toolbar.updatePadding(top = systemBars.top)

            insets
        }
    }

    private fun setupCastButton() {
        runCatching {
            castContext = CastContext.getSharedInstance(requireContext())
            castContext?.setReceiverApplicationId(CastReceiverIds.MEDIA_RECEIVER)
            CastButtonFactory.setUpMediaRouteButton(requireContext(), binding.btnTopCast)
            updateCastStatusFromSession()
        }.onFailure {
            binding.btnTopCast.isEnabled = false
            binding.btnTopCast.alpha = 0.45f
            updateCastStatus(CastConnectionState.Error)
        }
    }

    private fun setupModeUi() {
        val isPhotoMode = mode == MODE_PHOTO
        binding.title.text = getString(
            if (isPhotoMode) R.string.text_cast_photos else R.string.text_cast_video
        )
        binding.photoPreview.isVisible = isPhotoMode
        binding.photoList.isVisible = isPhotoMode
        binding.videoPlayer.isVisible = !isPhotoMode
        binding.videoTimeRow.isVisible = false
        binding.emptyMediaTitle.text = getString(
            if (isPhotoMode) R.string.text_select_photos_to_cast else R.string.text_select_video_to_cast
        )
    }

    private fun setupPhotoList() {
        binding.photoList.adapter = photoAdapter
        binding.photoList.layoutManager = LinearLayoutManager(
            requireContext(),
            LinearLayoutManager.HORIZONTAL,
            false
        )
    }

    private fun requestMediaPermissionAndOpen() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val permission = if (mode == MODE_PHOTO) {
                Manifest.permission.READ_MEDIA_IMAGES
            } else {
                Manifest.permission.READ_MEDIA_VIDEO
            }

            when {
                ContextCompat.checkSelfPermission(
                    requireContext(),
                    permission
                ) == PackageManager.PERMISSION_GRANTED -> {
                    openPicker()
                }

                shouldShowRequestPermissionRationale(permission) -> {
                    Toast.makeText(
                        requireContext(),
                        R.string.text_media_pick_error_message,
                        Toast.LENGTH_LONG
                    ).show()
                    mediaPermissionLauncher.launch(permission)
                }

                else -> {
                    mediaPermissionLauncher.launch(permission)
                }
            }
        } else {
            openPicker()
        }
    }

    private fun navigateToPreviousPhoto() {
        if (photos.isEmpty()) return
        selectedPhotoIndex = if (selectedPhotoIndex > 0) {
            selectedPhotoIndex - 1
        } else {
            photos.size - 1
        }
        selectPhoto(selectedPhotoIndex)
        updateNavigationButtons()
    }

    private fun navigateToNextPhoto() {
        if (photos.isEmpty()) return
        selectedPhotoIndex = if (selectedPhotoIndex < photos.size - 1) {
            selectedPhotoIndex + 1
        } else {
            0
        }
        selectPhoto(selectedPhotoIndex)
        updateNavigationButtons()
    }

    private fun updateNavigationButtons() {
        val hasPhotos = photos.isNotEmpty()
        val canNavigate = photos.size > 1

        // Chỉ hiện nút previous/next khi có ảnh
        binding.btnPreviousPhoto.isVisible = hasPhotos
        binding.btnNextPhoto.isVisible = hasPhotos
        binding.btnPreviousPhoto.isEnabled = canNavigate
        binding.btnNextPhoto.isEnabled = canNavigate
        binding.btnPreviousPhoto.alpha = if (canNavigate) 1f else 0.3f
        binding.btnNextPhoto.alpha = if (canNavigate) 1f else 0.3f
        binding.btnAddPhotos.isVisible = hasPhotos
    }

    private fun openPicker() {
        if (mode == MODE_PHOTO) {
            photoPicker.launch(
                PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
            )
        } else {
            videoPicker.launch(
                PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.VideoOnly)
            )
        }
    }

    private fun setPhotos(uris: List<Uri>) {
        photos = uris
        selectedPhotoIndex = 0
        Glide.with(binding.photoPreview)
            .load(uris.first())
            .dontTransform()
            .into(binding.photoPreview)
        photoAdapter.submit(uris, selectedPhotoIndex)

        // Scroll về đầu danh sách
        binding.photoList.scrollToPosition(0)

        if (isCasting) {
            castSelectedMedia()
        }
        updateNavigationButtons()
        updateControls()
    }

    private fun addPhotos(newUris: List<Uri>) {
        val allPhotos = photos.toMutableList()
        newUris.forEach { uri ->
            if (!allPhotos.contains(uri) && allPhotos.size < MAX_PHOTOS) {
                allPhotos.add(uri)
            }
        }
        photos = allPhotos
        photoAdapter.submit(photos, selectedPhotoIndex)

        // Đảm bảo ảnh đang chọn vẫn hiển thị trên màn hình
        binding.photoList.post {
            binding.photoList.smoothScrollToPosition(selectedPhotoIndex)
        }

        updateNavigationButtons()
        updateControls()

        if (newUris.isNotEmpty()) {
            Toast.makeText(
                requireContext(),
                "Added ${newUris.size} photo(s)",
                Toast.LENGTH_SHORT
            ).show()
        }
    }

    private fun selectPhoto(position: Int) {
        val uri = photos.getOrNull(position) ?: return
        selectedPhotoIndex = position
        Glide.with(binding.photoPreview)
            .load(uri)
            .dontTransform()
            .into(binding.photoPreview)
        photoAdapter.submit(photos, selectedPhotoIndex)

        // Scroll RecyclerView tới ảnh đang chọn để không bị mất khỏi màn hình
        binding.photoList.smoothScrollToPosition(position)

        if (isCasting) {
            castSelectedMedia()
        }
        updateNavigationButtons()
    }

    private fun setVideo(uri: Uri) {
        videoUri = uri
        if (player == null) {
            player = ExoPlayer.Builder(requireContext()).build()
            binding.videoPlayer.player = player
        }
        player?.setMediaItem(MediaItem.fromUri(uri))
        player?.prepare()
        player?.playWhenReady = false
        startVideoProgressTicker()
        if (isCasting) {
            castSelectedMedia()
        }
        updateControls()
    }

    private fun startVideoProgressTicker() {
        mainHandler.removeCallbacks(videoProgressRunnable)
        updateVideoTime()
        mainHandler.postDelayed(videoProgressRunnable, VIDEO_PROGRESS_INTERVAL_MS)
    }

    private fun updateVideoTime() {
        val currentPlayer = player ?: return
        binding.textVideoPosition.text = currentPlayer.currentPosition.formatDuration()
        binding.textVideoDuration.text = currentPlayer.duration
            .takeIf { it != C.TIME_UNSET && it > 0 }
            ?.formatDuration()
            ?: "00:00"
    }

    private fun Long.formatDuration(): String {
        val totalSeconds = (this / 1000).coerceAtLeast(0)
        val minutes = totalSeconds / 60
        val seconds = totalSeconds % 60
        return "%02d:%02d".format(minutes, seconds)
    }

    private fun handleCastButton() {
        if (currentSelection() == null) {
            openPicker()
            return
        }

        if (isCasting) {
            showStopCastingDialog()
        } else {
            castSelectedMedia()
        }
    }

    private fun castSelectedMedia() {
        val selected = currentSelection()
        if (selected == null) {
            openPicker()
            return
        }

        val session = currentCastSession()
        if (session?.isConnected != true) {
            pendingCast = true
            Toast.makeText(requireContext(), R.string.text_select_tv_to_cast, Toast.LENGTH_SHORT)
                .show()
            binding.btnTopCast.performClick()
            mainHandler.postDelayed({
                if (_binding != null &&
                    view != null &&
                    pendingCast &&
                    currentCastSession()?.isConnected != true
                ) {
                    pendingCast = false
                    updateCastStatusFromSession()
                    showCastFailureDialog()
                }
            }, CAST_SELECTION_TIMEOUT_MS)
            updateControls()
            return
        }

        val remoteClient = session.remoteMediaClient
        if (remoteClient == null) {
            reconnectWithMediaReceiver()
            return
        }
        observeRemoteMediaClient(session)

        binding.preparingOverlay.isVisible = true
        isCasting = true
        updateControls()

        if (!selected.isPhoto) {
            prepareAndLoadVideo(remoteClient, selected)
            return
        }

        val castUrl = mediaServer.register(
            selected.uri,
            selected.mimeType,
            selected.title
        )
        if (castUrl == null) {
            binding.preparingOverlay.isVisible = false
            isCasting = false
            updateControls()
            Toast.makeText(
                requireContext(),
                R.string.text_could_not_prepare_media,
                Toast.LENGTH_SHORT
            ).show()
            return
        }

        loadCastMedia(remoteClient, selected, castUrl)
    }

    private fun prepareAndLoadVideo(
        remoteClient: RemoteMediaClient,
        selected: SelectedMedia
    ) {
        viewLifecycleOwner.lifecycleScope.launch {
            val castUrl = withContext(Dispatchers.IO) {
                mediaServer.registerCached(
                    selected.uri,
                    selected.mimeType,
                    selected.title
                )
            }

            if (_binding == null || view == null) return@launch

            val current = currentSelection()
            if (current?.uri != selected.uri) {
                binding.preparingOverlay.isVisible = false
                isCasting = false
                updateControls()
                return@launch
            }

            if (castUrl == null) {
                binding.preparingOverlay.isVisible = false
                isCasting = false
                updateControls()
                Toast.makeText(
                    requireContext(),
                    R.string.text_could_not_prepare_media,
                    Toast.LENGTH_SHORT
                ).show()
                return@launch
            }

            loadCastMedia(remoteClient, selected, castUrl)
        }
    }

    private fun loadCastMedia(
        remoteClient: RemoteMediaClient,
        selected: SelectedMedia,
        castUrl: String
    ) {
        Log.d(
            TAG,
            "Loading cast media url=$castUrl mime=${selected.mimeType} isPhoto=${selected.isPhoto}"
        )

        val metadata = MediaMetadata(
            if (selected.isPhoto) MediaMetadata.MEDIA_TYPE_PHOTO else MediaMetadata.MEDIA_TYPE_MOVIE
        ).apply {
            putString(MediaMetadata.KEY_TITLE, selected.title)
            if (selected.isPhoto) {
                addImage(WebImage(castUrl.toUri()))
            }
        }

        val mediaInfo = MediaInfo.Builder(castUrl)
            .setContentUrl(castUrl)
            .setStreamType(
                if (selected.isPhoto) MediaInfo.STREAM_TYPE_NONE else MediaInfo.STREAM_TYPE_BUFFERED
            )
            .setContentType(selected.mimeType)
            .setMetadata(metadata)
            .build()

        remoteClient
            .load(
                MediaLoadRequestData.Builder()
                    .setMediaInfo(mediaInfo)
                    .setAutoplay(!selected.isPhoto)
                    .build()
            )
            ?.setResultCallback { result ->
                mainHandler.post {
                    if (_binding == null || view == null) return@post

                    binding.preparingOverlay.isVisible = false
                    isCasting = result.status.isSuccess
                    Log.d(
                        TAG,
                        "Cast media load result success=${result.status.isSuccess} " +
                                "code=${result.status.statusCode} message=${result.status.statusMessage}"
                    )
                    if (result.status.isSuccess && !selected.isPhoto) {
                        remoteClient.play()
                    } else if (!result.status.isSuccess) {
                        showLoadFailureToastIfNoReceiverError(R.string.text_could_not_cast_media)
                    }
                    updateControls()
                }
            }
    }

    private fun observeRemoteMediaClient(session: CastSession) {
        val remoteClient = session.remoteMediaClient ?: return
        if (observedRemoteClient === remoteClient) return

        stopObservingRemoteMediaClient()
        observedRemoteClient = remoteClient
        remoteClient.registerCallback(mediaClientCallback)
        handleRemoteMediaStatus(remoteClient.mediaStatus)
    }

    private fun stopObservingRemoteMediaClient() {
        observedRemoteClient?.unregisterCallback(mediaClientCallback)
        observedRemoteClient = null
    }

    private fun handleRemoteMediaStatus(status: MediaStatus?) {
        if (_binding == null || view == null || status == null) return

        when (status.playerState) {
            MediaStatus.PLAYER_STATE_PLAYING,
            MediaStatus.PLAYER_STATE_BUFFERING,
            MediaStatus.PLAYER_STATE_PAUSED -> {
                if (!isCasting) {
                    isCasting = true
                    updateControls()
                }
                binding.preparingOverlay.isVisible = false
            }

            MediaStatus.PLAYER_STATE_IDLE -> {
                if (status.idleReason == MediaStatus.IDLE_REASON_ERROR) {
                    handleCastPlaybackError(status)
                }
            }
        }
    }

    private fun handleCastPlaybackError(status: MediaStatus) {
        Log.w(
            TAG,
            "Cast receiver stopped with error: idleReason=${status.idleReason} " +
                    "contentType=${status.mediaInfo?.contentType} " +
                    "contentId=${status.mediaInfo?.contentId}"
        )
        binding.preparingOverlay.isVisible = false
        isCasting = false
        updateControls()
        showLoadFailureToastIfNoReceiverError(R.string.text_could_not_cast_media)
    }

    private fun reconnectWithMediaReceiver() {
        Log.w(TAG, "Connected Cast session has no RemoteMediaClient; reconnecting media receiver")
        pendingCast = true
        reconnectingForMediaReceiver = true
        isCasting = false
        binding.preparingOverlay.isVisible = false
        updateControls()

        Toast.makeText(requireContext(), R.string.text_select_tv_to_cast, Toast.LENGTH_SHORT).show()
        castContext?.sessionManager?.endCurrentSession(true)
        mainHandler.postDelayed({
            if (_binding == null ||
                view == null ||
                !pendingCast ||
                currentCastSession()?.isConnected == true
            ) {
                return@postDelayed
            }
            binding.btnTopCast.performClick()
        }, MEDIA_RECEIVER_RECONNECT_DELAY_MS)
    }

    private fun showLoadFailureToastIfNoReceiverError(messageRes: Int) {
        mainHandler.postDelayed({
            if (_binding == null || view == null || hasRecentReceiverMediaError()) {
                return@postDelayed
            }
            Toast.makeText(requireContext(), messageRes, Toast.LENGTH_SHORT).show()
        }, CAST_LOAD_FAILURE_FALLBACK_DELAY_MS)
    }

    private fun currentSelection(): SelectedMedia? {
        val uri = if (mode == MODE_PHOTO) {
            photos.getOrNull(selectedPhotoIndex)
        } else {
            videoUri
        } ?: return null

        val mimeType = requireContext().contentResolver.getType(uri) ?: if (mode == MODE_PHOTO) {
            "image/jpeg"
        } else {
            "video/mp4"
        }
        val title = LocalMediaHttpServer.queryDisplayName(requireContext(), uri)
        return SelectedMedia(
            uri = uri,
            title = title,
            mimeType = normalizeCastMimeType(mimeType, title, mode == MODE_PHOTO),
            isPhoto = mode == MODE_PHOTO
        )
    }

    private fun showStopCastingDialog() {
        MaterialAlertDialogBuilder(requireContext())
            .setMessage(R.string.text_stop_casting_message)
            .setPositiveButton(R.string.text_stop_casting) { _, _ -> stopCasting() }
            .setNegativeButton(R.string.text_cancel, null)
            .show()
    }

    private fun stopCasting() {
        currentCastSession()?.remoteMediaClient?.stop()
        mediaServer.clear()
        isCasting = false
        binding.preparingOverlay.isVisible = false
        updateControls()
    }

    private fun updateControls() {
        if (_binding == null || view == null) return

        val hasMedia = currentSelection() != null
        binding.emptyMediaContainer.isVisible = !hasMedia
        binding.photoList.isVisible = mode == MODE_PHOTO && photos.isNotEmpty()
        binding.photoControls.isVisible = mode == MODE_PHOTO
        binding.videoTimeRow.isVisible = mode == MODE_VIDEO && videoUri != null
        binding.btnStartCasting.isEnabled = true
        binding.btnStartCasting.alpha = 1f
        binding.btnStartCasting.text = when {
            isCasting -> getString(R.string.text_stop_casting)
            pendingCast -> getString(R.string.text_connecting_to_tv)
            !hasMedia && mode == MODE_PHOTO -> getString(R.string.text_select_photos)
            !hasMedia -> getString(R.string.text_select_video)
            else -> getString(R.string.text_start_casting)
        }
        binding.btnStartCasting.setBackgroundResource(
            if (isCasting) R.drawable.bg_cast_media_stop_action else R.drawable.bg_cast_youtube_action
        )

        updateNavigationButtons()
    }

    private fun updateCastStatusFromSession() {
        val state = if (currentCastSession()?.isConnected == true) {
            CastConnectionState.Connected
        } else {
            CastConnectionState.Disconnected
        }
        updateCastStatus(state)
        updateControls()
    }

    private fun updateCastStatus(state: CastConnectionState) {
        val color = when (state) {
            CastConnectionState.Disconnected -> "#777777"
            CastConnectionState.Connecting -> "#F4D188"
            CastConnectionState.Connected -> "#84FF6A"
            CastConnectionState.Error -> "#FF5C5C"
        }
        binding.connectionDot.backgroundTintList = ColorStateList.valueOf(Color.parseColor(color))
    }

    private fun currentCastSession(): CastSession? {
        return castContext?.sessionManager?.currentCastSession
    }

    private fun handleBackPressed() {
        // Không tự động ngắt kết nối, chỉ quay lại màn trước
        popBackStack()
    }

    private data class SelectedMedia(
        val uri: Uri,
        val title: String,
        val mimeType: String,
        val isPhoto: Boolean
    )

    private enum class CastConnectionState {
        Disconnected,
        Connecting,
        Connected,
        Error
    }

    companion object {
        const val ARG_MODE = "mode"
        const val MODE_PHOTO = "photo"
        const val MODE_VIDEO = "video"
        private const val MAX_PHOTOS = 20
        private const val VIDEO_PROGRESS_INTERVAL_MS = 500L
        private const val CAST_SELECTION_TIMEOUT_MS = 30_000L
        private const val MEDIA_RECEIVER_RECONNECT_DELAY_MS = 700L
        private const val CAST_LOAD_FAILURE_FALLBACK_DELAY_MS = 1_200L
        private const val TAG = "CastMediaDebug"

        private fun normalizeCastMimeType(
            rawMimeType: String,
            fileName: String,
            isPhoto: Boolean
        ): String {
            val normalized = rawMimeType.substringBefore(";").trim().lowercase()
            val extension = fileName.substringBeforeLast('?')
                .substringBeforeLast('#')
                .substringAfterLast('.', missingDelimiterValue = "")
                .lowercase()

            if (isPhoto) {
                return when {
                    extension == "jpg" || extension == "jpeg" -> "image/jpeg"
                    extension == "png" -> "image/png"
                    extension == "webp" -> "image/webp"
                    normalized == "image/jpg" -> "image/jpeg"
                    normalized.startsWith("image/") -> normalized
                    else -> "image/jpeg"
                }
            }

            return when {
                extension == "m3u8" -> "application/x-mpegURL"
                extension == "mpd" -> "application/dash+xml"
                extension == "webm" -> "video/webm"
                extension == "ts" -> "video/mp2t"
                extension == "mp4" || extension == "m4v" || extension == "mov" -> "video/mp4"
                normalized == "video/quicktime" -> "video/mp4"
                normalized == "application/octet-stream" || normalized.isBlank() -> "video/mp4"
                normalized.startsWith("video/") || normalized.startsWith("application/") -> normalized
                else -> "video/mp4"
            }
        }
    }
}

private class PhotoThumbAdapter(
    private val onClick: (Int) -> Unit
) : RecyclerView.Adapter<PhotoThumbAdapter.PhotoThumbViewHolder>() {

    private var items: List<Uri> = emptyList()
    private var selectedIndex = 0

    fun submit(newItems: List<Uri>, selected: Int) {
        items = newItems
        selectedIndex = selected
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): PhotoThumbViewHolder {
        val imageView = ImageView(parent.context).apply {
            layoutParams = RecyclerView.LayoutParams(
                parent.resources.getDimensionPixelSize(CodeBaseR.dimen._42sdp),
                parent.resources.getDimensionPixelSize(CodeBaseR.dimen._54sdp)
            ).apply {
                marginEnd = parent.resources.getDimensionPixelSize(CodeBaseR.dimen._6sdp)
            }
            background = androidx.core.content.ContextCompat.getDrawable(
                parent.context,
                R.drawable.bg_cast_media_thumb
            )
            clipToOutline = true
            scaleType = ImageView.ScaleType.FIT_CENTER
            setPadding(2, 2, 2, 2)
        }
        return PhotoThumbViewHolder(imageView)
    }

    override fun onBindViewHolder(holder: PhotoThumbViewHolder, position: Int) {
        Glide.with(holder.imageView)
            .load(items[position])
            .dontTransform()
            .into(holder.imageView)
        holder.imageView.background = androidx.core.content.ContextCompat.getDrawable(
            holder.imageView.context,
            if (position == selectedIndex) {
                R.drawable.bg_cast_media_thumb_selected
            } else {
                R.drawable.bg_cast_media_thumb
            }
        )
        holder.imageView.setOnClickListener { onClick(position) }
    }

    override fun onViewRecycled(holder: PhotoThumbViewHolder) {
        Glide.with(holder.imageView).clear(holder.imageView)
        super.onViewRecycled(holder)
    }

    override fun getItemCount(): Int = items.size

    class PhotoThumbViewHolder(
        val imageView: ImageView
    ) : RecyclerView.ViewHolder(imageView)
}
