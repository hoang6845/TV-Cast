package com.example.base.ui.cast_youtube

import android.annotation.SuppressLint
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Color
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import androidx.core.view.updatePadding
import com.example.base.R
import com.example.base.cast.CastReceiverIds
import com.example.base.databinding.FragmentCastYoutubeBinding
import com.example.base.ui.common.showCastFailureDialog
import com.google.android.gms.cast.Cast
import com.google.android.gms.cast.framework.CastButtonFactory
import com.google.android.gms.cast.framework.CastContext
import com.google.android.gms.cast.framework.CastSession
import com.google.android.gms.cast.framework.SessionManagerListener
import hoang.dqm.codebase.base.activity.BaseFragment
import hoang.dqm.codebase.base.activity.onBackPressed
import hoang.dqm.codebase.base.activity.popBackStack
import org.json.JSONObject
import java.util.Locale
import kotlin.math.abs

class CastYoutubeFragment : BaseFragment<FragmentCastYoutubeBinding, CastYoutubeViewModel>() {

    private val mainHandler = Handler(Looper.getMainLooper())
    private var castContext: CastContext? = null
    private var currentVideoId: String? = null
    private var pendingCastVideoId: String? = null
    private var isCasting = false
    private var toolbarBaseHeight = 0
    private var bottomBarBaseHeight = 0
    private var lastPhoneTimelineSeconds: Float? = null
    private var lastPhoneTimelineSyncAtMs = 0L
    private var lastSeekSentAtMs = 0L
    private var lastCastVideoId: String? = null
    private var pendingAutoCastVideoId: String? = null

    private val phoneTimelinePollRunnable = object : Runnable {
        override fun run() {
            pollPhoneTimelineForSeek()
            if (isCasting) {
                mainHandler.postDelayed(this, PHONE_TIMELINE_POLL_INTERVAL_MS)
            }
        }
    }

    private val autoCastChangedVideoRunnable = Runnable {
        val videoId = pendingAutoCastVideoId
        pendingAutoCastVideoId = null

        if (videoId != null &&
            isCasting &&
            currentCastSession()?.isConnected == true &&
            videoId != lastCastVideoId
        ) {
            Log.i(TAG, "Auto casting changed YouTube video: videoId=$videoId")
            castYoutubeVideo(videoId)
        }
    }

    private val receiverMessageCallback = Cast.MessageReceivedCallback { _, _, message ->
        Log.d(TAG, "YouTube receiver message: $message")
        handleReceiverMessage(message)
    }

    private val castSessionListener = object : SessionManagerListener<CastSession> {
        override fun onSessionStarting(session: CastSession) {
            Log.i(TAG, "Cast session starting")
            updateCastStatus(CastConnectionState.Connecting)
        }

        override fun onSessionStarted(session: CastSession, sessionId: String) {
            Log.i(TAG, "Cast session started: sessionId=$sessionId device=${session.castDevice?.friendlyName}")
            updateCastStatus(CastConnectionState.Connected)
            setReceiverCallback(session)
            pendingCastVideoId?.let {
                Log.i(TAG, "Sending pending YouTube video after session start: videoId=$it")
                pendingCastVideoId = null
                castYoutubeVideo(it)
            }
        }

        override fun onSessionStartFailed(session: CastSession, error: Int) {
            Log.e(TAG, "Cast session start failed: error=$error")
            pendingCastVideoId = null
            isCasting = false
            updateCastStatus(CastConnectionState.Error)
            showCastFailureDialog()
            updateControls()
        }

        override fun onSessionEnding(session: CastSession) {
            updateCastStatus(CastConnectionState.Connecting)
        }

        override fun onSessionEnded(session: CastSession, error: Int) {
            Log.i(TAG, "Cast session ended: error=$error")
            removeReceiverCallback(session)
            pendingCastVideoId = null
            isCasting = false
            lastCastVideoId = null
            pendingAutoCastVideoId = null
            mainHandler.removeCallbacks(autoCastChangedVideoRunnable)
            stopTimelinePolling()
            updateCastStatus(CastConnectionState.Disconnected)
            updateControls()
        }

        override fun onSessionResuming(session: CastSession, sessionId: String) {
            updateCastStatus(CastConnectionState.Connecting)
        }

        override fun onSessionResumed(session: CastSession, wasSuspended: Boolean) {
            Log.i(TAG, "Cast session resumed: wasSuspended=$wasSuspended")
            updateCastStatus(CastConnectionState.Connected)
            setReceiverCallback(session)
            pendingCastVideoId?.let {
                pendingCastVideoId = null
                castYoutubeVideo(it)
            }
            updateControls()
        }

        override fun onSessionResumeFailed(session: CastSession, error: Int) {
            Log.e(TAG, "Cast session resume failed: error=$error")
            updateCastStatus(CastConnectionState.Error)
            updateControls()
        }

        override fun onSessionSuspended(session: CastSession, reason: Int) {
            Log.w(TAG, "Cast session suspended: reason=$reason")
            isCasting = false
            pendingAutoCastVideoId = null
            mainHandler.removeCallbacks(autoCastChangedVideoRunnable)
            stopTimelinePolling()
            updateCastStatus(CastConnectionState.Disconnected)
            updateControls()
        }
    }

    override fun initView() {
        applySystemInsets()
        setupCastButton()
        setupWebView()
        updateControls()
    }

    override fun initListener() {
        binding.btnBack.setOnClickListener { handleBackPressed() }
        binding.btnWebBack.setOnClickListener {
            if (binding.webView.canGoBack()) {
                binding.webView.goBack()
            }
        }
        binding.btnWebForward.setOnClickListener {
            if (binding.webView.canGoForward()) {
                binding.webView.goForward()
            }
        }
        binding.btnCastWithYoutube.setOnClickListener { handlePrimaryAction() }
        onBackPressed(Runnable { handleBackPressed() })
    }

    override fun initData() {
        if (binding.webView.url.isNullOrBlank()) {
            binding.webView.loadUrl(arguments?.getString(ARG_START_URL) ?: YOUTUBE_HOME_URL)
        }
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

    override fun onDestroyView() {
        mainHandler.removeCallbacksAndMessages(null)
        currentCastSession()?.let(::removeReceiverCallback)
        binding.webView.apply {
            stopLoading()
            webChromeClient = null
            webViewClient = WebViewClient()
            destroy()
        }
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

            if (bottomBarBaseHeight == 0) {
                bottomBarBaseHeight = binding.bottomBar.layoutParams.height
            }
            binding.bottomBar.layoutParams = binding.bottomBar.layoutParams.apply {
                height = bottomBarBaseHeight + systemBars.bottom
            }
            binding.bottomBar.updatePadding(bottom = systemBars.bottom)

            insets
        }
    }

    private fun setupCastButton() {
        runCatching {
            castContext = CastContext.getSharedInstance(requireContext())
            castContext?.setReceiverApplicationId(CastReceiverIds.CUSTOM_RECEIVER)
            CastButtonFactory.setUpMediaRouteButton(requireContext(), binding.btnTopCast)
            Log.i(TAG, "Cast button ready: receiver=${CastReceiverIds.CUSTOM_RECEIVER}")
            updateCastStatusFromSession()
        }.onFailure {
            Log.e(TAG, "Could not initialize Cast button", it)
            binding.btnTopCast.isEnabled = false
            binding.btnTopCast.alpha = 0.45f
            updateCastStatus(CastConnectionState.Error)
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun setupWebView() {
        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(binding.webView, true)

        binding.webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            mediaPlaybackRequiresUserGesture = true
            setSupportMultipleWindows(false)
        }

        binding.webView.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView?, newProgress: Int) {
                binding.progressBar.progress = newProgress
                binding.progressBar.isVisible = newProgress in 1..99
            }
        }

        binding.webView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(
                view: WebView?,
                request: WebResourceRequest?
            ): Boolean {
                val uri = request?.url ?: return false
                return shouldOpenOutside(uri)
            }

            override fun doUpdateVisitedHistory(view: WebView?, url: String?, isReload: Boolean) {
                super.doUpdateVisitedHistory(view, url, isReload)
                updateCurrentVideo(url)
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                updateCurrentVideo(url)
                updateControls()
            }
        }
    }

    private fun shouldOpenOutside(uri: Uri): Boolean {
        if (shouldKeepInWebView(uri)) {
            return false
        }

        runCatching {
            startActivity(Intent(Intent.ACTION_VIEW, uri))
        }.onFailure {
            Toast.makeText(
                requireContext(),
                R.string.text_web_page_not_supported,
                Toast.LENGTH_SHORT
            ).show()
        }
        return true
    }

    private fun shouldKeepInWebView(uri: Uri): Boolean {
        val scheme = uri.scheme?.lowercase(Locale.US)
        if (scheme != "http" && scheme != "https") {
            return false
        }

        val host = uri.host?.lowercase(Locale.US) ?: return false
        return host == "youtube.com" ||
            host.endsWith(".youtube.com") ||
            host == "youtu.be" ||
            host == "youtube-nocookie.com" ||
            host.endsWith(".youtube-nocookie.com")
    }

    private fun updateCurrentVideo(url: String?) {
        val newVideoId = url?.let(::extractYoutubeVideoId)
        val didChangeVideo = newVideoId != null && newVideoId != currentVideoId
        currentVideoId = newVideoId

        if (newVideoId != null) {
            Log.i(TAG, "Selected YouTube video: videoId=$newVideoId url=$url")
        }

        if (didChangeVideo && isCasting && newVideoId != lastCastVideoId) {
            scheduleAutoCastChangedVideo(newVideoId)
        }
        updateControls()
    }

    private fun scheduleAutoCastChangedVideo(videoId: String) {
        pendingAutoCastVideoId = videoId
        mainHandler.removeCallbacks(autoCastChangedVideoRunnable)
        mainHandler.postDelayed(autoCastChangedVideoRunnable, AUTO_CAST_CHANGED_VIDEO_DELAY_MS)
    }

    private fun extractYoutubeVideoId(url: String): String? {
        val uri = runCatching { Uri.parse(url) }.getOrNull() ?: return null
        val host = uri.host?.lowercase(Locale.US).orEmpty()

        if (host == "youtu.be") {
            return uri.pathSegments.firstOrNull()?.toYoutubeVideoIdOrNull()
        }

        uri.getQueryParameter("v")?.toYoutubeVideoIdOrNull()?.let { return it }

        val segments = uri.pathSegments
        val videoMarkers = setOf("shorts", "live", "embed", "v")
        val markerIndex = segments.indexOfFirst { it in videoMarkers }
        return segments.getOrNull(markerIndex + 1)?.toYoutubeVideoIdOrNull()
    }

    private fun String.toYoutubeVideoIdOrNull(): String? {
        val candidate = trim()
            .substringBefore("?")
            .substringBefore("&")
            .substringBefore("/")
        return candidate.takeIf { YOUTUBE_VIDEO_ID_REGEX.matches(it) }
    }

    private fun handlePrimaryAction() {
        Log.i(
            TAG,
            "Primary action: videoId=$currentVideoId isCasting=$isCasting " +
                "connected=${currentCastSession()?.isConnected == true}"
        )
        if (isCasting) {
            stopYoutubeCasting()
            return
        }

        val videoId = currentVideoId
        if (videoId == null) {
            Toast.makeText(
                requireContext(),
                R.string.text_select_youtube_video_first,
                Toast.LENGTH_SHORT
            ).show()
            return
        }

        castOrConnect(videoId)
    }

    private fun castOrConnect(videoId: String) {
        val session = currentCastSession()
        if (session?.isConnected != true) {
            Log.i(TAG, "No connected Cast session; opening route chooser for videoId=$videoId")
            pendingCastVideoId = videoId
            Toast.makeText(requireContext(), R.string.text_select_tv_to_cast, Toast.LENGTH_SHORT).show()
            binding.btnTopCast.performClick()
            mainHandler.postDelayed({
                if (_binding != null &&
                    view != null &&
                    pendingCastVideoId == videoId &&
                    currentCastSession()?.isConnected != true
                ) {
                    pendingCastVideoId = null
                    updateCastStatusFromSession()
                    showCastFailureDialog()
                }
            }, CAST_SELECTION_TIMEOUT_MS)
            updateControls()
            return
        }

        castYoutubeVideo(videoId)
    }

    private fun castYoutubeVideo(videoId: String) {
        val session = currentCastSession()
        if (session?.isConnected != true) {
            castOrConnect(videoId)
            return
        }

        setReceiverCallback(session)
        pausePhoneYoutubePlayback { startSeconds ->
            sendYoutubePlayMessage(session, videoId, startSeconds ?: 0f)
        }
    }

    private fun sendYoutubePlayMessage(
        session: CastSession,
        videoId: String,
        startSeconds: Float
    ) {
        val message = JSONObject()
            .put("action", ACTION_PLAY_YOUTUBE)
            .put("videoId", videoId)
            .put("startSeconds", startSeconds.toDouble())
            .toString()

        Log.i(
            TAG,
            "Sending YouTube message namespace=${CastReceiverIds.YOUTUBE_NAMESPACE} " +
                "device=${session.castDevice?.friendlyName} payload=$message"
        )
        isCasting = true
        updateControls()

        runCatching {
            session.sendMessage(CastReceiverIds.YOUTUBE_NAMESPACE, message)
                .setResultCallback { status ->
                    mainHandler.post {
                        if (_binding == null || view == null) return@post

                        isCasting = status.isSuccess
                        Log.i(
                            TAG,
                            "YouTube send result: success=${status.isSuccess} " +
                                "code=${status.statusCode} message=${status.statusMessage}"
                        )
                        if (status.isSuccess) {
                            lastCastVideoId = videoId
                            lastPhoneTimelineSeconds = startSeconds
                            startTimelinePolling()
                            requestReceiverState()
                        } else {
                            updateCastStatus(CastConnectionState.Error)
                            Toast.makeText(
                                requireContext(),
                                R.string.text_could_not_cast_video,
                                Toast.LENGTH_SHORT
                            ).show()
                            Log.e(
                                TAG,
                                "Could not send YouTube cast message: " +
                                    "code=${status.statusCode} message=${status.statusMessage}"
                            )
                        }
                        updateControls()
                    }
                }
        }.onFailure {
            isCasting = false
            updateCastStatus(CastConnectionState.Error)
            Toast.makeText(
                requireContext(),
                R.string.text_could_not_cast_video,
                Toast.LENGTH_SHORT
            ).show()
            Log.e(TAG, "Could not send YouTube cast message", it)
            updateControls()
        }
    }

    private fun stopYoutubeCasting() {
        val session = currentCastSession()
        if (session?.isConnected == true) {
            runCatching {
                Log.i(TAG, "Sending YouTube stop message")
                session.sendMessage(
                    CastReceiverIds.YOUTUBE_NAMESPACE,
                    JSONObject().put("action", ACTION_STOP_YOUTUBE).toString()
                )
            }.onFailure {
                Log.e(TAG, "Could not send YouTube stop message", it)
            }
        }
        isCasting = false
        lastCastVideoId = null
        pendingAutoCastVideoId = null
        mainHandler.removeCallbacks(autoCastChangedVideoRunnable)
        stopTimelinePolling()
        updateControls()
    }

    private fun sendYoutubeSeek(seconds: Float) {
        val session = currentCastSession()
        if (session?.isConnected != true) return

        lastSeekSentAtMs = SystemClock.elapsedRealtime()
        val payload = JSONObject()
            .put("action", ACTION_SEEK_YOUTUBE)
            .put("seconds", seconds.toDouble())
            .toString()

        Log.i(TAG, "Sending YouTube seek: seconds=$seconds payload=$payload")
        runCatching {
            session.sendMessage(CastReceiverIds.YOUTUBE_NAMESPACE, payload)
        }.onFailure {
            Log.e(TAG, "Could not send YouTube seek", it)
        }
    }

    private fun requestReceiverState() {
        val session = currentCastSession()
        if (session?.isConnected != true) return

        runCatching {
            session.sendMessage(
                CastReceiverIds.YOUTUBE_NAMESPACE,
                JSONObject().put("action", ACTION_REQUEST_YOUTUBE_STATE).toString()
            )
        }.onFailure {
            Log.e(TAG, "Could not request YouTube state", it)
        }
    }

    private fun handleReceiverMessage(rawMessage: String) {
        val message = runCatching { JSONObject(rawMessage) }.getOrNull() ?: return
        when (message.optString("action")) {
            "STATE" -> {
                val currentTime = message.optDouble("currentTime", Double.NaN)
                val duration = message.optDouble("duration", Double.NaN)
                val playerState = message.optString("playerState")
                Log.i(
                    TAG,
                    "Receiver state: time=$currentTime duration=$duration state=$playerState"
                )

                if (currentTime.isFinite() && isCasting) {
                    syncPhoneTimelineToReceiver(currentTime.toFloat())
                }
            }
            "DEBUG" -> {
                Log.d(TAG, "Receiver debug: $rawMessage")
            }
        }
    }

    private fun pausePhoneYoutubePlayback(onPosition: (Float?) -> Unit = {}) {
        evaluatePhoneVideoTime(
            """
            (function() {
                const video = document.querySelector('video');
                if (!video) return null;
                video.pause();
                return Number.isFinite(video.currentTime) ? video.currentTime : null;
            })();
            """.trimIndent(),
            onPosition
        )
    }

    private fun syncPhoneTimelineToReceiver(seconds: Float) {
        val now = SystemClock.elapsedRealtime()
        if (now - lastSeekSentAtMs < LOCAL_SEEK_GRACE_MS) return

        val lastPhoneTime = lastPhoneTimelineSeconds
        if (lastPhoneTime != null && abs(lastPhoneTime - seconds) < PHONE_TIMELINE_SYNC_THRESHOLD_SECONDS) {
            return
        }

        lastPhoneTimelineSyncAtMs = now
        evaluatePhoneVideoTime(
            """
            (function() {
                const video = document.querySelector('video');
                const seconds = ${"%.3f".format(Locale.US, seconds)};
                if (!video || !Number.isFinite(seconds)) return null;
                video.pause();
                if (Math.abs(video.currentTime - seconds) > 0.5) {
                    video.currentTime = seconds;
                }
                return Number.isFinite(video.currentTime) ? video.currentTime : null;
            })();
            """.trimIndent()
        ) { phoneTime ->
            phoneTime?.let { lastPhoneTimelineSeconds = it }
        }
    }

    private fun startTimelinePolling() {
        mainHandler.removeCallbacks(phoneTimelinePollRunnable)
        mainHandler.postDelayed(phoneTimelinePollRunnable, PHONE_TIMELINE_POLL_INTERVAL_MS)
    }

    private fun stopTimelinePolling() {
        mainHandler.removeCallbacks(phoneTimelinePollRunnable)
        lastPhoneTimelineSeconds = null
    }

    private fun pollPhoneTimelineForSeek() {
        if (!isCasting) return

        pausePhoneYoutubePlayback { phoneTime ->
            if (!isCasting || phoneTime == null) return@pausePhoneYoutubePlayback

            val now = SystemClock.elapsedRealtime()
            val lastPhoneTime = lastPhoneTimelineSeconds
            if (lastPhoneTime != null &&
                abs(phoneTime - lastPhoneTime) >= PHONE_SEEK_DETECTION_THRESHOLD_SECONDS &&
                now - lastPhoneTimelineSyncAtMs > PHONE_TIMELINE_SYNC_IGNORE_MS &&
                now - lastSeekSentAtMs > PHONE_SEEK_THROTTLE_MS
            ) {
                sendYoutubeSeek(phoneTime)
            }

            lastPhoneTimelineSeconds = phoneTime
        }
    }

    private fun evaluatePhoneVideoTime(
        script: String,
        onPosition: (Float?) -> Unit
    ) {
        if (_binding == null || view == null) {
            onPosition(null)
            return
        }

        binding.webView.evaluateJavascript(script) { rawValue ->
            val position = rawValue
                ?.trim()
                ?.trim('"')
                ?.takeUnless { it == "null" || it == "undefined" || it == "NaN" }
                ?.toFloatOrNull()
            onPosition(position)
        }
    }

    private fun setReceiverCallback(session: CastSession) {
        runCatching {
            session.removeMessageReceivedCallbacks(CastReceiverIds.YOUTUBE_NAMESPACE)
            session.setMessageReceivedCallbacks(
                CastReceiverIds.YOUTUBE_NAMESPACE,
                receiverMessageCallback
            )
            Log.i(TAG, "YouTube receiver callback registered")
        }.onFailure {
            Log.e(TAG, "Could not set YouTube receiver callback", it)
        }
    }

    private fun removeReceiverCallback(session: CastSession) {
        runCatching {
            session.removeMessageReceivedCallbacks(CastReceiverIds.YOUTUBE_NAMESPACE)
        }
    }

    private fun updateControls() {
        if (_binding == null || view == null) return

        val hasVideo = currentVideoId != null
        binding.btnWebBack.isEnabled = binding.webView.canGoBack()
        binding.btnWebBack.alpha = if (binding.btnWebBack.isEnabled) 1f else 0.4f

        binding.btnWebForward.isEnabled = binding.webView.canGoForward()
        binding.btnWebForward.alpha = if (binding.btnWebForward.isEnabled) 1f else 0.4f

        binding.btnCastWithYoutube.isEnabled = hasVideo || isCasting || pendingCastVideoId != null
        binding.btnCastWithYoutube.alpha = if (binding.btnCastWithYoutube.isEnabled) 1f else 0.75f
        binding.btnCastWithYoutube.text = when {
            isCasting -> getString(R.string.text_playing_on_tv)
            pendingCastVideoId != null -> getString(R.string.text_connecting_to_tv)
            currentCastSession()?.isConnected != true -> getString(R.string.text_connect_to_tv)
            hasVideo -> getString(R.string.text_cast_to_tv)
            else -> getString(R.string.text_open_youtube_video)
        }
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
        if (_binding == null || view == null) return

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
        if (binding.webView.canGoBack()) {
            binding.webView.goBack()
        } else {
            popBackStack()
        }
    }

    private enum class CastConnectionState {
        Disconnected,
        Connecting,
        Connected,
        Error
    }

    companion object {
        const val ARG_START_URL = "start_url"
        private const val YOUTUBE_HOME_URL = "https://m.youtube.com"
        private const val CAST_SELECTION_TIMEOUT_MS = 30_000L
        private const val ACTION_PLAY_YOUTUBE = "PLAY_YOUTUBE"
        private const val ACTION_STOP_YOUTUBE = "STOP_YOUTUBE"
        private const val ACTION_SEEK_YOUTUBE = "SEEK_YOUTUBE"
        private const val ACTION_REQUEST_YOUTUBE_STATE = "REQUEST_YOUTUBE_STATE"
        private const val PHONE_TIMELINE_POLL_INTERVAL_MS = 900L
        private const val PHONE_SEEK_DETECTION_THRESHOLD_SECONDS = 2.0f
        private const val PHONE_TIMELINE_SYNC_THRESHOLD_SECONDS = 1.25f
        private const val PHONE_TIMELINE_SYNC_IGNORE_MS = 1_200L
        private const val PHONE_SEEK_THROTTLE_MS = 800L
        private const val LOCAL_SEEK_GRACE_MS = 2_000L
        private const val AUTO_CAST_CHANGED_VIDEO_DELAY_MS = 700L
        private const val TAG = "CastYoutubeDebug"
        private val YOUTUBE_VIDEO_ID_REGEX = Regex("^[A-Za-z0-9_-]{11}$")
    }
}
