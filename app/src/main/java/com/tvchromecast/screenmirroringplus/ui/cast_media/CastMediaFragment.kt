package com.tvchromecast.screenmirroringplus.ui.cast_media

import android.content.res.ColorStateList
import android.graphics.Color
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.Toast
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.net.toUri
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import androidx.core.view.updatePadding
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.tvchromecast.screenmirroringplus.R
import com.tvchromecast.screenmirroringplus.cast.CastReceiverIds
import com.tvchromecast.screenmirroringplus.databinding.FragmentCastMediaBinding
import com.tvchromecast.screenmirroringplus.media.LocalMediaHttpServer
import com.tvchromecast.screenmirroringplus.ui.common.showCastFailureDialog
import com.google.android.gms.cast.MediaInfo
import com.google.android.gms.cast.MediaLoadRequestData
import com.google.android.gms.cast.MediaMetadata
import com.google.android.gms.cast.Cast
import com.google.android.gms.cast.framework.CastButtonFactory
import com.google.android.gms.cast.framework.CastContext
import com.google.android.gms.cast.framework.CastSession
import com.google.android.gms.cast.framework.SessionManagerListener
import com.google.android.gms.common.images.WebImage
import com.bumptech.glide.Glide
import com.bumptech.glide.load.DecodeFormat
import com.bumptech.glide.load.engine.DiskCacheStrategy
import com.bumptech.glide.load.resource.bitmap.DownsampleStrategy
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import hoang.dqm.codebase.base.activity.BaseFragment
import hoang.dqm.codebase.base.activity.onBackPressed
import hoang.dqm.codebase.base.activity.popBackStack
import org.json.JSONObject
import hoang.dqm.codebase.R as CodeBaseR

class CastMediaFragment : BaseFragment<FragmentCastMediaBinding, CastMediaViewModel>() {

    private val mainHandler = Handler(Looper.getMainLooper())
    private val mediaServer by lazy { LocalMediaHttpServer(requireContext().applicationContext) }
    private val photoAdapter by lazy { PhotoThumbAdapter(::selectPhoto) }
    private val mode: String by lazy {
        arguments?.getString(ARG_MODE, MODE_PHOTO) ?: MODE_PHOTO
    }

    private var castContext: CastContext? = null
    private var pendingCast = false
    private var isCasting = false
    private var selectedPhotoIndex = 0
    private var photos = emptyList<Uri>()
    private var videoUri: Uri? = null
    private var player: ExoPlayer? = null
    private var toolbarBaseHeight = 0
    private var bottomButtonBaseMargin = 0

    private val receiverMessageCallback = Cast.MessageReceivedCallback { _, _, message ->
        logReceiverMessage(message)
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
            updateControls()
        } else {
            setPhotos(uris)
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

    private val castSessionListener = object : SessionManagerListener<CastSession> {
        override fun onSessionStarting(session: CastSession) {
            updateCastStatus(CastConnectionState.Connecting)
        }

        override fun onSessionStarted(session: CastSession, sessionId: String) {
            updateCastStatus(CastConnectionState.Connected)
            setReceiverDebugCallback(session)
            if (pendingCast) {
                pendingCast = false
                castSelectedMedia()
            }
        }

        override fun onSessionStartFailed(session: CastSession, error: Int) {
            pendingCast = false
            updateCastStatus(CastConnectionState.Error)
            updateControls()
        }

        override fun onSessionEnding(session: CastSession) {
            updateCastStatus(CastConnectionState.Connecting)
        }

        override fun onSessionEnded(session: CastSession, error: Int) {
            removeReceiverDebugCallback(session)
            pendingCast = false
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
            updateControls()
        }

        override fun onSessionResumeFailed(session: CastSession, error: Int) {
            updateCastStatus(CastConnectionState.Error)
            updateControls()
        }

        override fun onSessionSuspended(session: CastSession, reason: Int) {
            isCasting = false
            updateCastStatus(CastConnectionState.Disconnected)
            updateControls()
        }
    }

    override fun initView() {
        applySystemInsets()
        setupCastButton()
        setupModeUi()
        setupPhotoList()
        updateControls()
    }

    override fun initListener() {
        binding.btnBack.setOnClickListener { handleBackPressed() }
        binding.btnStartCasting.setOnClickListener { handleCastButton() }
        binding.photoPreview.setOnClickListener { openPicker() }
        binding.videoPlayer.setOnClickListener { openPicker() }
        binding.emptyMediaContainer.setOnClickListener { openPicker() }
        onBackPressed(Runnable { handleBackPressed() })
    }

    override fun initData() {
        mainHandler.post { openPicker() }
    }

    override fun onStart() {
        super.onStart()
        castContext?.sessionManager?.addSessionManagerListener(
            castSessionListener,
            CastSession::class.java
        )
        updateCastStatusFromSession()
    }

    override fun onStop() {
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
        currentCastSession()?.let(::removeReceiverDebugCallback)
        player?.release()
        player = null
        mediaServer.close()
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

            val params = binding.btnStartCasting.layoutParams as ViewGroup.MarginLayoutParams
            if (bottomButtonBaseMargin == 0) {
                bottomButtonBaseMargin = params.bottomMargin
            }
            params.bottomMargin = bottomButtonBaseMargin + systemBars.bottom
            binding.btnStartCasting.layoutParams = params

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
        binding.photoPreview.loadLocalPhoto(
            uri = uris.first(),
            widthPx = PHOTO_PREVIEW_MAX_SIZE_PX,
            heightPx = PHOTO_PREVIEW_MAX_SIZE_PX
        )
        photoAdapter.submit(uris, selectedPhotoIndex)
        if (isCasting) {
            castSelectedMedia()
        }
        updateControls()
    }

    private fun selectPhoto(position: Int) {
        val uri = photos.getOrNull(position) ?: return
        selectedPhotoIndex = position
        binding.photoPreview.loadLocalPhoto(
            uri = uri,
            widthPx = PHOTO_PREVIEW_MAX_SIZE_PX,
            heightPx = PHOTO_PREVIEW_MAX_SIZE_PX
        )
        photoAdapter.submit(photos, selectedPhotoIndex)
        if (isCasting) {
            castSelectedMedia()
        }
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
            Toast.makeText(requireContext(), R.string.text_select_tv_to_cast, Toast.LENGTH_SHORT).show()
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

        val castUrl = mediaServer.register(
            selected.uri,
            selected.mimeType
        )
        if (castUrl == null) {
            Toast.makeText(requireContext(), R.string.text_could_not_prepare_media, Toast.LENGTH_SHORT).show()
            return
        }

        binding.preparingOverlay.isVisible = true
        isCasting = true
        updateControls()
        setReceiverDebugCallback(session)
        sendReceiverPing(session)
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
            .setStreamType(
                if (selected.isPhoto) MediaInfo.STREAM_TYPE_NONE else MediaInfo.STREAM_TYPE_BUFFERED
            )
            .setContentType(selected.mimeType)
            .setMetadata(metadata)
            .build()

        session.remoteMediaClient
            ?.load(
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
                    if (!result.status.isSuccess) {
                        Toast.makeText(
                            requireContext(),
                            R.string.text_could_not_cast_media,
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                    updateControls()
                }
            }
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
            mimeType = mimeType,
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
        isCasting = false
        binding.preparingOverlay.isVisible = false
        updateControls()
    }

    private fun updateControls() {
        if (_binding == null || view == null) return

        val hasMedia = currentSelection() != null
        binding.emptyMediaContainer.isVisible = !hasMedia
        binding.photoList.isVisible = mode == MODE_PHOTO && photos.isNotEmpty()
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

    private fun handleBackPressed() {
        if (currentCastSession()?.isConnected == true) {
            showDisconnectBeforeExitDialog()
            return
        }
        popBackStack()
    }

    private fun showDisconnectBeforeExitDialog() {
        MaterialAlertDialogBuilder(requireContext())
            .setMessage(R.string.text_stop_casting_message)
            .setPositiveButton(R.string.text_disconnect) { _, _ ->
                currentCastSession()?.remoteMediaClient?.stop()
                castContext?.sessionManager?.endCurrentSession(true)
                pendingCast = false
                isCasting = false
                binding.preparingOverlay.isVisible = false
                updateControls()
                popBackStack()
            }
            .setNegativeButton(R.string.text_cancel, null)
            .show()
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
        private const val PHOTO_PREVIEW_MAX_SIZE_PX = 2048
        private const val VIDEO_PROGRESS_INTERVAL_MS = 500L
        private const val CAST_SELECTION_TIMEOUT_MS = 30_000L
        private const val RECEIVER_NAMESPACE = "urn:x-cast:com.example.camera.webrtc"
        private const val TAG = "CastMediaDebug"
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
            scaleType = ImageView.ScaleType.CENTER_CROP
            setPadding(2, 2, 2, 2)
        }
        return PhotoThumbViewHolder(imageView)
    }

    override fun onBindViewHolder(holder: PhotoThumbViewHolder, position: Int) {
        holder.imageView.loadLocalPhoto(
            uri = items[position],
            widthPx = holder.imageView.layoutParams.width,
            heightPx = holder.imageView.layoutParams.height
        )
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

private fun ImageView.loadLocalPhoto(uri: Uri, widthPx: Int, heightPx: Int) {
    Glide.with(this)
        .load(uri)
        .override(widthPx.coerceAtLeast(1), heightPx.coerceAtLeast(1))
        .downsample(DownsampleStrategy.AT_MOST)
        .format(DecodeFormat.PREFER_RGB_565)
        .centerCrop()
        .thumbnail(0.1f)
        .diskCacheStrategy(DiskCacheStrategy.NONE)
        .skipMemoryCache(false)
        .into(this)
}
