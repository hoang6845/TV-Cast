package com.tvchromecast.screenmirroringplus.ui.cast_media

import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Color
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.net.toUri
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import androidx.core.view.updatePadding
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.google.android.gms.cast.Cast
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
import com.tvchromecast.screenmirroringplus.ui.common.resetReceiverMediaErrorUiState
import com.tvchromecast.screenmirroringplus.ui.common.showReceiverMediaErrorIfAny
import com.tvchromecast.screenmirroringplus.ui.common.showCastFailureDialog
import hoang.dqm.codebase.base.activity.BaseFragment
import hoang.dqm.codebase.base.activity.onBackPressed
import hoang.dqm.codebase.base.activity.popBackStack
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import hoang.dqm.codebase.R as CodeBaseR

@UnstableApi
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
    private var reconnectingForCustomReceiver = false
    private var isCasting = false
    private var selectedPhotoIndex = 0
    private var photos = emptyList<Uri>()
    private var videoUri: Uri? = null
    private var player: ExoPlayer? = null
    private var toolbarBaseHeight = 0
    private var bottomButtonBaseMargin = 0
    private var observedRemoteClient: RemoteMediaClient? = null
    private var cancelActiveTransform: (() -> Unit)? = null

    private val receiverMessageCallback = Cast.MessageReceivedCallback { _, _, message ->
        logReceiverMessage(message)
    }

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
        ActivityResultContracts.OpenMultipleDocuments()
    ) { uris ->
        if (uris.isEmpty()) {
            if (photos.isEmpty()) {
                updateControls()
            }
        } else {
            val pickedUris = uris.take(MAX_PHOTOS).onEach(::persistReadPermission)
            if (photos.isEmpty()) {
                setPhotos(pickedUris)
            } else {
                addPhotos(pickedUris)
            }
        }
    }

    private val videoPicker = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri == null) {
            updateControls()
        } else {
            persistReadPermission(uri)
            setVideo(uri)
        }
    }

    private val castSessionListener = object : SessionManagerListener<CastSession> {
        override fun onSessionStarting(session: CastSession) {
            updateCastStatus(CastConnectionState.Connecting)
        }

        override fun onSessionStarted(session: CastSession, sessionId: String) {
            updateCastStatus(CastConnectionState.Connected)
            setReceiverDebugCallback(session)
            observeRemoteMediaClient(session)
            if (pendingCast) {
                pendingCast = false
                castSelectedMedia()
            }
        }

        override fun onSessionStartFailed(session: CastSession, error: Int) {
            pendingCast = false
            reconnectingForCustomReceiver = false
            updateCastStatus(CastConnectionState.Error)
            updateControls()
        }

        override fun onSessionEnding(session: CastSession) {
            updateCastStatus(CastConnectionState.Connecting)
        }

        override fun onSessionEnded(session: CastSession, error: Int) {
            removeReceiverDebugCallback(session)
            stopObservingRemoteMediaClient()
            if (reconnectingForCustomReceiver) {
                reconnectingForCustomReceiver = false
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
            setReceiverDebugCallback(session)
            observeRemoteMediaClient(session)
            updateControls()
        }

        override fun onSessionResumeFailed(session: CastSession, error: Int) {
            reconnectingForCustomReceiver = false
            updateCastStatus(CastConnectionState.Error)
            updateControls()
        }

        override fun onSessionSuspended(session: CastSession, reason: Int) {
            removeReceiverDebugCallback(session)
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
        binding.emptyMediaContainer.setOnClickListener { openPicker() }
        binding.btnAddPhotos.setOnClickListener { openPicker() }
        binding.btnPreviousPhoto.setOnClickListener { navigateToPreviousPhoto() }
        binding.btnNextPhoto.setOnClickListener { navigateToNextPhoto() }
        onBackPressed(Runnable { handleBackPressed() })
    }

    override fun initData() {
//        openPicker()
    }

    override fun onStart() {
        super.onStart()
        castContext?.sessionManager?.addSessionManagerListener(
            castSessionListener,
            CastSession::class.java
        )
        currentCastSession()?.let(::setReceiverDebugCallback)
        currentCastSession()?.let(::observeRemoteMediaClient)
        updateCastStatusFromSession()
    }

    override fun onStop() {
        currentCastSession()?.let(::removeReceiverDebugCallback)
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
        cancelActiveTransform?.invoke()
        cancelActiveTransform = null
        currentCastSession()?.let(::removeReceiverDebugCallback)
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
            castContext?.setReceiverApplicationId(CastReceiverIds.CUSTOM_RECEIVER)
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
            photoPicker.launch(arrayOf(IMAGE_MIME_TYPE))
        } else {
            videoPicker.launch(arrayOf(VIDEO_MIME_TYPE))
        }
    }

    private fun persistReadPermission(uri: Uri) {
        runCatching {
            requireContext().contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION
            )
        }.onFailure {
            Log.d(TAG, "Could not persist media read permission for uri=$uri", it)
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
        Log.i(
            TAG,
            "Selected cast video: uri=$uri title=${LocalMediaHttpServer.queryDisplayName(requireContext(), uri)} " +
                "rawMime=${requireContext().contentResolver.getType(uri)} sizeBytes=${uri.queryDebugSizeBytes()}"
        )
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
        if (!selected.isPhoto) {
            Log.i(
                TAG,
                "Cast video requested: connected=${session?.isConnected == true} " +
                    "receiverDevice=${session?.castDevice?.friendlyName} title=${selected.title} " +
                    "mime=${selected.mimeType} uri=${selected.uri} sizeBytes=${selected.uri.queryDebugSizeBytes()}"
            )
        }
        resetReceiverMediaErrorUiState()
        if (session?.isConnected != true) {
            pendingCast = true
            castContext?.setReceiverApplicationId(CastReceiverIds.CUSTOM_RECEIVER)
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

        if (!session.isRunningReceiver(CastReceiverIds.CUSTOM_RECEIVER)) {
            reconnectWithCustomReceiver()
            return
        }

        val remoteClient = session.remoteMediaClient
        if (remoteClient == null) {
            reconnectWithCustomReceiver()
            return
        }
        setReceiverDebugCallback(session)
        observeRemoteMediaClient(session)

        showPreparingOverlay(R.string.text_preparing_to_cast, showSpinner = false)
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
            Log.i(
                TAG,
                "Preparing cast video proxy: title=${selected.title} mime=${selected.mimeType} " +
                    "uri=${selected.uri} sizeBytes=${selected.uri.queryDebugSizeBytes()} " +
                    "positionMs=${player?.currentPosition}"
            )
            showPreparingOverlay(R.string.text_preparing_to_cast, showSpinner = false)

            val videoInfo = withContext(Dispatchers.IO) {
                CastMediaVideoPreparer.inspect(requireContext(), selected.uri)
            }
            Log.i(TAG, "Selected local media tracks: uri=${selected.uri} ${videoInfo.toDebugString()}")

            if (_binding == null || view == null) return@launch

            val current = currentSelection()
            if (current?.uri != selected.uri) {
                Log.i(
                    TAG,
                    "Skipping prepared cast video because selection changed: " +
                        "preparedUri=${selected.uri} currentUri=${current?.uri}"
                )
                binding.preparingOverlay.isVisible = false
                isCasting = false
                updateControls()
                return@launch
            }

            val preparedMedia = if (videoInfo.isReadyForCast) {
                Log.i(TAG, "Video already compatible with Cast; using original file")
                selected.copy(mimeType = MimeTypes.VIDEO_MP4)
            } else {
                showPreparingOverlay(R.string.text_converting_video_for_tv, showSpinner = true)
                val outputFile = runCatching {
                    CastMediaVideoPreparer.transformForCast(
                        requireContext().applicationContext,
                        selected.uri,
                        selected.title,
                        videoInfo.transformOutputHeight
                    ) { cancelTransform ->
                        cancelActiveTransform = cancelTransform
                    }
                }.onFailure { error ->
                    Log.e(
                        TAG,
                        "Could not transform video for Cast: title=${selected.title} " +
                            "uri=${selected.uri} tracks=${videoInfo.toDebugString()}",
                        error
                    )
                }.getOrNull()

                cancelActiveTransform = null

                if (_binding == null || view == null) return@launch
                val afterTransformSelection = currentSelection()
                if (afterTransformSelection?.uri != selected.uri) {
                    Log.i(
                        TAG,
                        "Skipping transformed cast video because selection changed: " +
                            "preparedUri=${selected.uri} currentUri=${afterTransformSelection?.uri}"
                    )
                    outputFile?.delete()
                    binding.preparingOverlay.isVisible = false
                    isCasting = false
                    updateControls()
                    return@launch
                }

                if (outputFile == null) {
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

                selected.copy(
                    uri = Uri.fromFile(outputFile),
                    mimeType = MimeTypes.VIDEO_MP4
                )
            }

            showPreparingOverlay(R.string.text_preparing_to_cast, showSpinner = false)
            val castUrl = withContext(Dispatchers.IO) {
                if (preparedMedia.uri.scheme == "file") {
                    preparedMedia.uri.path
                        ?.let(::File)
                        ?.let { file -> mediaServer.registerCachedFile(file, preparedMedia.mimeType) }
                } else {
                    mediaServer.registerCached(
                        preparedMedia.uri,
                        preparedMedia.mimeType,
                        preparedMedia.title
                    )
                }
            }

            if (castUrl == null) {
                Log.e(
                    TAG,
                    "Could not prepare cast video proxy: title=${preparedMedia.title} " +
                        "mime=${preparedMedia.mimeType} uri=${preparedMedia.uri}"
                )
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

            Log.i(
                TAG,
                "Prepared cast video proxy: castUrl=$castUrl sourceUri=${preparedMedia.uri} " +
                    "title=${preparedMedia.title} mime=${preparedMedia.mimeType}"
            )
            loadCastMedia(remoteClient, preparedMedia, castUrl)
        }
    }

    private fun loadCastMedia(
        remoteClient: RemoteMediaClient,
        selected: SelectedMedia,
        castUrl: String
    ) {
        if (selected.isPhoto) {
            Log.d(
                TAG,
                "Loading cast photo: castUrl=$castUrl mime=${selected.mimeType} uri=${selected.uri}"
            )
        } else {
            Log.i(
                TAG,
                "Loading cast video: castUrl=$castUrl sourceUri=${selected.uri} " +
                    "title=${selected.title} mime=${selected.mimeType} " +
                    "streamType=${MediaInfo.STREAM_TYPE_BUFFERED} autoplay=true"
            )
        }

        val metadata = MediaMetadata(
            if (selected.isPhoto) MediaMetadata.MEDIA_TYPE_PHOTO else MediaMetadata.MEDIA_TYPE_MOVIE
        ).apply {
            putString(MediaMetadata.KEY_TITLE, selected.title)
            if (selected.isPhoto) {
                addImage(WebImage(castUrl.toUri()))
            }
        }

        val mediaInfoBuilder = MediaInfo.Builder(castUrl)
            .setContentUrl(castUrl)
            .setStreamType(
                if (selected.isPhoto) MediaInfo.STREAM_TYPE_NONE else MediaInfo.STREAM_TYPE_BUFFERED
            )
            .setContentType(selected.mimeType)
            .setMetadata(metadata)

        if (!selected.isPhoto) {
            player?.duration
                ?.takeIf { it != C.TIME_UNSET && it > 0 }
                ?.let { mediaInfoBuilder.setStreamDuration(it) }
        }

        val mediaInfo = mediaInfoBuilder
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
                                "code=${result.status.statusCode} message=${result.status.statusMessage} " +
                                "castUrl=$castUrl sourceUri=${selected.uri} isPhoto=${selected.isPhoto}"
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

    private fun reconnectWithCustomReceiver() {
        Log.w(
            TAG,
            "Reconnecting with custom receiver: currentReceiver=" +
                "${currentCastSession()?.applicationMetadata?.applicationId}"
        )
        pendingCast = true
        reconnectingForCustomReceiver = true
        isCasting = false
        binding.preparingOverlay.isVisible = false
        updateControls()

        castContext?.setReceiverApplicationId(CastReceiverIds.CUSTOM_RECEIVER)
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
        }, CUSTOM_RECEIVER_RECONNECT_DELAY_MS)
    }

    private fun showLoadFailureToastIfNoReceiverError(messageRes: Int) {
        mainHandler.postDelayed({
            if (_binding == null || view == null || hasRecentReceiverMediaError()) {
                return@postDelayed
            }
            Toast.makeText(requireContext(), messageRes, Toast.LENGTH_SHORT).show()
        }, CAST_LOAD_FAILURE_FALLBACK_DELAY_MS)
    }

    private fun describeLocalMediaTracks(uri: Uri): String {
        return runCatching {
            val extractor = MediaExtractor()
            try {
                extractor.setDataSource(requireContext(), uri, null)
                if (extractor.trackCount <= 0) {
                    return@runCatching "no tracks"
                }

                (0 until extractor.trackCount).joinToString(separator = "; ") { index ->
                    val format = extractor.getTrackFormat(index)
                    val mime = format.getString(MediaFormat.KEY_MIME).orEmpty()
                    val width = format.optionalInteger(MediaFormat.KEY_WIDTH)
                    val height = format.optionalInteger(MediaFormat.KEY_HEIGHT)
                    val durationUs = format.optionalLong(MediaFormat.KEY_DURATION)
                    buildString {
                        append("#").append(index).append(" mime=").append(mime)
                        if (width != null && height != null) {
                            append(" size=").append(width).append("x").append(height)
                        }
                        if (durationUs != null && durationUs > 0) {
                            append(" durationMs=").append(durationUs / 1000L)
                        }
                    }
                }
            } finally {
                extractor.release()
            }
        }.getOrElse { error ->
            "unavailable: ${error.message}"
        }
    }

    private fun MediaFormat.optionalInteger(key: String): Int? {
        return if (containsKey(key)) runCatching { getInteger(key) }.getOrNull() else null
    }

    private fun MediaFormat.optionalLong(key: String): Long? {
        return if (containsKey(key)) runCatching { getLong(key) }.getOrNull() else null
    }

    private fun Uri.queryDebugSizeBytes(): Long? {
        return runCatching {
            requireContext().contentResolver.openAssetFileDescriptor(this, "r")?.use { descriptor ->
                descriptor.length
                    .takeIf { it >= 0 }
                    ?: descriptor.parcelFileDescriptor.statSize.takeIf { it >= 0 }
            }
        }.getOrNull()
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
        cancelActiveTransform?.invoke()
        cancelActiveTransform = null
        currentCastSession()?.remoteMediaClient?.stop()
        mediaServer.clear()
        isCasting = false
        binding.preparingOverlay.isVisible = false
        updateControls()
    }

    private fun showPreparingOverlay(messageRes: Int, showSpinner: Boolean) {
        if (_binding == null || view == null) return
        binding.textPreparingOverlay.setText(messageRes)
        binding.progressPreparingOverlay.isVisible = showSpinner
        binding.preparingOverlay.isVisible = true
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

    private fun CastSession.isRunningReceiver(receiverId: String): Boolean {
        return applicationMetadata?.applicationId == receiverId
    }

    private fun setReceiverDebugCallback(session: CastSession) {
        if (!session.isRunningReceiver(CastReceiverIds.CUSTOM_RECEIVER)) {
            Log.d(
                TAG,
                "Skipping custom receiver debug callback: receiverId=" +
                    "${session.applicationMetadata?.applicationId}"
            )
            return
        }

        runCatching {
            session.removeMessageReceivedCallbacks(RECEIVER_NAMESPACE)
            session.setMessageReceivedCallbacks(RECEIVER_NAMESPACE, receiverMessageCallback)
            sendReceiverPing(session)
        }.onFailure {
            Log.e(TAG, "Could not set receiver debug callback", it)
        }
    }

    private fun removeReceiverDebugCallback(session: CastSession) {
        if (!session.isRunningReceiver(CastReceiverIds.CUSTOM_RECEIVER)) return

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
        showReceiverMediaErrorIfAny(rawMessage, TAG)
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
        private const val IMAGE_MIME_TYPE = "image/*"
        private const val VIDEO_MIME_TYPE = "video/*"
        private const val VIDEO_PROGRESS_INTERVAL_MS = 500L
        private const val CAST_SELECTION_TIMEOUT_MS = 30_000L
        private const val CUSTOM_RECEIVER_RECONNECT_DELAY_MS = 700L
        private const val CAST_LOAD_FAILURE_FALLBACK_DELAY_MS = 1_200L
        private const val RECEIVER_NAMESPACE = "urn:x-cast:com.example.camera.webrtc"
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

        private fun String.castMediaUrlExtension(): String? {
            return when (substringBefore(";").trim().lowercase()) {
                "video/webm",
                "video/x-msvideo",
                "video/avi",
                "video/quicktime",
                "video/mkv",
                "video/mp4",
                "video/mov",
                "video/m4v",
                "video/x-matroska" -> "mp4"
                else -> null
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
