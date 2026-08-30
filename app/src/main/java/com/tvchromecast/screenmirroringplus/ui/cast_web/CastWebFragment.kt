package com.tvchromecast.screenmirroringplus.ui.cast_web

import android.annotation.SuppressLint
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.util.Patterns
import android.view.inputmethod.EditorInfo
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import androidx.core.view.updatePadding
import com.tvchromecast.screenmirroringplus.R
import com.tvchromecast.screenmirroringplus.cast.CastReceiverIds
import com.tvchromecast.screenmirroringplus.databinding.FragmentCastWebBinding
import com.tvchromecast.screenmirroringplus.databinding.ItemCastWebSiteBinding
import com.tvchromecast.screenmirroringplus.media.LocalMediaHttpServer
import com.tvchromecast.screenmirroringplus.ui.cast_youtube.CastYoutubeFragment
import com.tvchromecast.screenmirroringplus.ui.common.hasRecentReceiverMediaError
import com.tvchromecast.screenmirroringplus.ui.common.resetReceiverMediaErrorUiState
import com.tvchromecast.screenmirroringplus.ui.common.showReceiverMediaErrorIfAny
import com.tvchromecast.screenmirroringplus.ui.common.showCastFailureDialog
import com.google.android.gms.cast.Cast
import com.google.android.gms.cast.MediaInfo
import com.google.android.gms.cast.MediaLoadRequestData
import com.google.android.gms.cast.MediaMetadata
import com.google.android.gms.cast.framework.CastButtonFactory
import com.google.android.gms.cast.framework.CastContext
import com.google.android.gms.cast.framework.CastSession
import com.google.android.gms.cast.framework.SessionManagerListener
import com.google.android.gms.common.images.WebImage
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import hoang.dqm.codebase.base.activity.BaseFragment
import hoang.dqm.codebase.base.activity.navigate
import hoang.dqm.codebase.base.activity.onBackPressed
import hoang.dqm.codebase.base.activity.popBackStack
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder
import java.util.Locale

class CastWebFragment : BaseFragment<FragmentCastWebBinding, CastWebViewModel>() {
    override val viewModelClass: Class<CastWebViewModel>
        get() = CastWebViewModel::class.java

    override fun inflateBinding(
        inflater: android.view.LayoutInflater,
        container: android.view.ViewGroup?
    ): FragmentCastWebBinding {
        return FragmentCastWebBinding.inflate(inflater, container, false)
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private val mediaServer by lazy { LocalMediaHttpServer.shared(requireContext()) }
    private val detectedVideos = linkedMapOf<String, DetectedVideo>()
    private val sessionBookmarks = mutableSetOf<String>()
    private var castContext: CastContext? = null
    private var pendingVideo: DetectedVideo? = null
    private var isSwitchingCastReceiver = false
    private var isCasting = false
    private var isPageLoading = false
    private var toolbarBaseHeight = 0
    private var bottomBarBaseHeight = 0
    private var lastCastVideoKey: String? = null
    private var pendingAutoCastVideoKey: String? = null
    private val receiverMessageCallback = Cast.MessageReceivedCallback { _, _, message ->
        logReceiverMessage(message)
    }

    private val autoCastChangedVideoRunnable = Runnable {
        val key = pendingAutoCastVideoKey
        pendingAutoCastVideoKey = null

        if (key != null &&
            isCasting &&
            currentCastSession()?.isConnected == true &&
            key != lastCastVideoKey
        ) {
            detectedVideos[key]
                ?.takeUnless { it.isLikelyStreamingSegment() }
                ?.let { video ->
                    Log.i(TAG, "Auto casting changed web video: key=$key url=${video.url}")
                    castVideo(video)
                }
        }
    }

    private val castSessionListener = object : SessionManagerListener<CastSession> {
        override fun onSessionStarting(session: CastSession) {
            updateCastStatus(CastConnectionState.Connecting)
        }

        override fun onSessionStarted(session: CastSession, sessionId: String) {
            Log.i(TAG, "Cast web session started: sessionId=$sessionId device=${session.castDevice?.friendlyName}")
            isSwitchingCastReceiver = false
            updateCastStatus(CastConnectionState.Connected)
            configureReceiverDebugCallback(session)
            pendingVideo?.let {
                pendingVideo = null
                castVideo(it)
            }
        }

        override fun onSessionStartFailed(session: CastSession, error: Int) {
            isSwitchingCastReceiver = false
            pendingVideo = null
            updateCastStatus(CastConnectionState.Error)
            showCastFailureDialog()
            updateControls()
        }

        override fun onSessionEnding(session: CastSession) {
            updateCastStatus(CastConnectionState.Connecting)
        }

        override fun onSessionEnded(session: CastSession, error: Int) {
            Log.i(TAG, "Cast web session ended: error=$error")
            removeReceiverDebugCallback(session)
            if (!isSwitchingCastReceiver) {
                pendingVideo = null
            }
            isCasting = false
            resetCastingState()
            updateCastStatus(CastConnectionState.Disconnected)
            updateControls()
        }

        override fun onSessionResuming(session: CastSession, sessionId: String) {
            updateCastStatus(CastConnectionState.Connecting)
        }

        override fun onSessionResumed(session: CastSession, wasSuspended: Boolean) {
            updateCastStatus(CastConnectionState.Connected)
            configureReceiverDebugCallback(session)
            updateControls()
        }

        override fun onSessionResumeFailed(session: CastSession, error: Int) {
            updateCastStatus(CastConnectionState.Error)
            updateControls()
        }

        override fun onSessionSuspended(session: CastSession, reason: Int) {
            Log.w(TAG, "Cast web session suspended: reason=$reason")
            isCasting = false
            resetCastingState()
            updateCastStatus(CastConnectionState.Disconnected)
            updateControls()
        }
    }

    override fun initView() {
        applySystemInsets()
        setupCastButton()
        setupWebView()
        showBrowserHome()
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
        binding.btnCastWebAction.setOnClickListener { handlePrimaryAction() }
        binding.btnReload.setOnClickListener { reloadOrStopLoading() }
        binding.btnBookmark.setOnClickListener { toggleCurrentBookmark() }

        setupInput(binding.inputSearch)
        setupInput(binding.inputAddress)
        setupFavoriteSites()

        onBackPressed(Runnable { handleBackPressed() })
    }

    override fun initData() = Unit

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
        currentCastSession()?.let(::removeReceiverDebugCallback)
        mainHandler.removeCallbacksAndMessages(null)

        binding.webView.apply {
            stopLoading()
            webChromeClient = null
            webViewClient = WebViewClient()
            removeJavascriptInterface(JS_BRIDGE_NAME)
            clearHistory()
            destroy()
        }

        CookieManager.getInstance().flush()
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
            updateCastStatusFromSession()
        }.onFailure {
            binding.btnTopCast.isEnabled = false
            binding.btnTopCast.alpha = 0.45f
            updateCastStatus(CastConnectionState.Error)
        }
    }

    @SuppressLint("SetJavaScriptEnabled", "AddJavascriptInterface")
    private fun setupWebView() {
        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(binding.webView, true)

        binding.webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            mediaPlaybackRequiresUserGesture = true
            mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
            setSupportMultipleWindows(false)
        }

        binding.webView.addJavascriptInterface(
            VideoDetectorBridge { url -> detectMediaUrl(url) },
            JS_BRIDGE_NAME
        )

        binding.webView.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView?, newProgress: Int) {
                binding.progressBar.progress = newProgress
                binding.progressBar.isVisible = newProgress in 1..99
                isPageLoading = newProgress in 1..99
                updateControls()
            }

            override fun onReceivedTitle(view: WebView?, title: String?) {
                super.onReceivedTitle(view, title)
                refreshDetectedVideoTitles(title)
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

            override fun shouldInterceptRequest(
                view: WebView?,
                request: WebResourceRequest?
            ): android.webkit.WebResourceResponse? {
                request?.url?.toString()?.let { url ->
                    detectMediaUrl(url, request.requestHeaders)
                }
                return super.shouldInterceptRequest(view, request)
            }

            override fun onLoadResource(view: WebView?, url: String?) {
                super.onLoadResource(view, url)
                url?.let(::detectMediaUrl)
            }

            override fun onPageStarted(view: WebView?, url: String?, favicon: android.graphics.Bitmap?) {
                super.onPageStarted(view, url, favicon)
                detectedVideos.clear()
                isPageLoading = true
                showWebPage(url)
                updateAddress(url)
                updateControls()
            }

            override fun doUpdateVisitedHistory(view: WebView?, url: String?, isReload: Boolean) {
                super.doUpdateVisitedHistory(view, url, isReload)
                updateAddress(url)
                updateControls()
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                isPageLoading = false
                updateAddress(url)
                updateControls()
                runVideoDetectorScript()
                mainHandler.postDelayed({ runVideoDetectorScript() }, DETECTOR_RETRY_DELAY_MS)
            }
        }
    }

    private fun setupInput(input: android.widget.EditText) {
        input.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH || actionId == EditorInfo.IME_ACTION_GO) {
                loadInput(input.text?.toString().orEmpty())
                true
            } else {
                false
            }
        }
    }

    private fun setupFavoriteSites() {
        configureSite(
            binding.siteYoutube,
            logoRes = R.drawable.youtube,
            title = getString(R.string.text_youtube),
            url = YOUTUBE_URL,
            onClick = { openCastYoutube(YOUTUBE_URL) }
        )
        configureSite(
            binding.siteFacebook,
            logoRes = R.drawable.facebook,
            title = getString(R.string.text_facebook),
            url = FACEBOOK_URL
        )
        configureSite(
            binding.siteArchive,
            logoRes = R.drawable.ic_archive_org,
            title = getString(R.string.text_archive_org),
            url = ARCHIVE_ORG_SAMPLE_VIDEO_URL
        )
        configureSite(
            binding.siteVevo,
            logoRes = R.drawable.vevo,
            title = getString(R.string.text_vevo),
            url = VEVO_URL
        )
        configureSite(
            binding.siteTwitch,
            logoRes = R.drawable.twich,
            title = getString(R.string.text_twitch),
            url = TWITCH_URL
        )
        configureSite(
            binding.siteVeoh,
            logoRes = R.drawable.veoh,
            title = getString(R.string.text_veoh),
            url = VEOH_URL
        )
    }

    private fun configureSite(
        site: ItemCastWebSiteBinding,
        logoRes: Int,
        title: String,
        url: String,
        onClick: (() -> Unit)? = null
    ) {
        site.siteLogo.setImageResource(logoRes)
        site.siteLogo.contentDescription = title
        site.siteTitle.text = title
        site.root.setOnClickListener { onClick?.invoke() ?: loadUrl(url) }
    }

    private fun shouldOpenOutside(uri: Uri): Boolean {
        val scheme = uri.scheme?.lowercase(Locale.US)
        if (scheme == "http" || scheme == "https") {
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

    private fun loadInput(rawInput: String) {
        val target = rawInput.trim()
        if (target.isBlank()) return
        loadUrl(target.toBrowserUrl())
    }

    private fun loadUrl(url: String) {
        Log.i(TAG, "Loading web page: url=$url")
        showWebPage(url)
        detectMediaUrl(url)
        binding.webView.loadUrl(url)
    }

    private fun String.toBrowserUrl(): String {
        val lower = lowercase(Locale.US)
        if (lower.startsWith("http://") || lower.startsWith("https://")) return this

        val looksLikeUrl = contains(".") && !contains(" ") && Patterns.WEB_URL.matcher(this).matches()
        return if (looksLikeUrl) {
            "https://$this"
        } else {
            val query = URLEncoder.encode(this, "UTF-8")
            "$GOOGLE_SEARCH_URL$query"
        }
    }

    private fun detectMediaUrl(
        url: String,
        requestHeaders: Map<String, String> = emptyMap()
    ) {
        if (url.isYoutubeRelatedUrl()) return

        val video = url.toDetectedVideoOrNull() ?: return
        if (video.isLikelyStreamingSegment()) {
            Log.d(TAG, "Ignoring streaming segment candidate: ${video.url}")
            return
        }

        mainHandler.post {
            if (_binding == null || view == null) return@post

            val key = video.url.substringBefore("#")
            val existing = detectedVideos[key]
            if (existing != null) {
                if (existing.requestHeaders.isEmpty() && requestHeaders.isNotEmpty()) {
                    detectedVideos[key] = existing.copy(requestHeaders = requestHeaders)
                    Log.i(
                        TAG,
                        "Updated detected web video headers: key=$key url=${video.url} " +
                            "headerNames=${requestHeaders.toDebugHeaderNames()}"
                    )
                    if (pendingAutoCastVideoKey == key) {
                        scheduleAutoCastChangedVideo(key)
                    }
                    updateControls()
                }
                return@post
            }
            detectedVideos[key] = video.copy(
                title = binding.webView.title?.takeIf { it.isNotBlank() } ?: video.title,
                requestHeaders = requestHeaders
            )
            Log.i(
                TAG,
                "Detected castable web video: page=${binding.webView.url} key=$key " +
                    "title=${binding.webView.title} mime=${video.mimeType} " +
                    "headerNames=${requestHeaders.toDebugHeaderNames()} url=${video.url}"
            )
            if (isCasting && key != lastCastVideoKey) {
                scheduleAutoCastChangedVideo(key)
            }
            updateControls()
        }
    }

    private fun scheduleAutoCastChangedVideo(videoKey: String) {
        pendingAutoCastVideoKey = videoKey
        mainHandler.removeCallbacks(autoCastChangedVideoRunnable)
        mainHandler.postDelayed(autoCastChangedVideoRunnable, AUTO_CAST_CHANGED_VIDEO_DELAY_MS)
    }

    private fun String.toDetectedVideoOrNull(): DetectedVideo? {
        val trimmed = trim()
        if (trimmed.isBlank()) return null

        val lower = trimmed.lowercase(Locale.US)
        if (lower.startsWith("blob:") || lower.startsWith("data:") || lower.startsWith("file:")) {
            return null
        }

        val cleanUrl = lower.substringBefore("#").substringBefore("?")
        val mimeType = guessMimeType(cleanUrl) ?: return null
        val uri = runCatching { Uri.parse(trimmed) }.getOrNull()
        val title = uri?.host?.removePrefix("www.") ?: getString(R.string.text_web_video)

        return DetectedVideo(
            url = trimmed,
            title = title,
            mimeType = mimeType,
            thumbnail = null,
            requestHeaders = emptyMap()
        )
    }

    private fun guessMimeType(cleanUrl: String): String? {
        return when {
            cleanUrl.endsWith(".m3u8") || cleanUrl.contains(".m3u8/") -> "application/x-mpegURL"
            cleanUrl.endsWith(".mpd") || cleanUrl.contains(".mpd/") -> "application/dash+xml"
            cleanUrl.endsWith(".mp4") || cleanUrl.contains(".mp4/") -> "video/mp4"
            cleanUrl.endsWith(".webm") || cleanUrl.contains(".webm/") -> "video/webm"
            cleanUrl.endsWith(".m4v") || cleanUrl.contains(".m4v/") -> "video/mp4"
            cleanUrl.endsWith(".mp3") || cleanUrl.contains(".mp3/") -> "audio/mpeg"
            cleanUrl.endsWith(".m4a") || cleanUrl.contains(".m4a/") -> "audio/mp4"
            else -> null
        }
    }

    private fun runVideoDetectorScript() {
        if (_binding == null || view == null || !binding.webView.isVisible) return

        binding.webView.evaluateJavascript(
            """
            (function() {
                const urls = [];
                const pushUrl = function(value) {
                    if (!value) return;
                    try {
                        urls.push(new URL(value, window.location.href).href);
                    } catch (e) {}
                };
                document.querySelectorAll('video').forEach(function(video) {
                    pushUrl(video.currentSrc);
                    pushUrl(video.src);
                    video.querySelectorAll('source').forEach(function(source) {
                        pushUrl(source.src);
                    });
                });
                document.querySelectorAll('source[src]').forEach(function(source) {
                    pushUrl(source.src);
                });
                if (urls.length && window.$JS_BRIDGE_NAME) {
                    window.$JS_BRIDGE_NAME.onVideosDetected(JSON.stringify(urls));
                }
            })();
            """.trimIndent(),
            null
        )
    }

    private fun refreshDetectedVideoTitles(title: String?) {
        if (title.isNullOrBlank() || detectedVideos.isEmpty()) return

        detectedVideos.entries.forEach { entry ->
            entry.setValue(entry.value.copy(title = title))
        }
        updateControls()
    }

    private fun handlePrimaryAction() {
        if (isCasting) {
            showStopCastingDialog()
            return
        }

        val currentUrl = binding.webView.url
        if (currentUrl?.isYoutubeUrl() == true) {
            openCastYoutube(currentUrl)
            return
        }

        val videos = castableVideos()
        if (videos.isEmpty()) {
            Toast.makeText(
                requireContext(),
                R.string.text_play_video_on_webpage_first,
                Toast.LENGTH_SHORT
            ).show()
            return
        }

        if (videos.size == 1) {
            castOrConnect(videos.first())
        } else {
            showVideoPicker(videos)
        }
    }

    private fun showVideoPicker(videos: List<DetectedVideo>) {
        val labels = videos.mapIndexed { index, video ->
            val type = video.mimeType?.toVideoTypeLabel().orEmpty()
            val host = Uri.parse(video.url).host?.removePrefix("www.").orEmpty()
            "${video.title ?: getString(R.string.text_web_video)} ${index + 1}\n$type - $host"
        }.toTypedArray()

        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.text_select_video)
            .setItems(labels) { _, which ->
                val selected = videos[which]
                Log.i(
                    TAG,
                    "Selected detected web video: index=$which title=${selected.title} " +
                        "mime=${selected.mimeType} url=${selected.url}"
                )
                castOrConnect(selected)
            }
            .setNegativeButton(R.string.text_cancel, null)
            .show()
    }

    private fun castOrConnect(video: DetectedVideo) {
        if (video.isLikelyStreamingSegment()) {
            Toast.makeText(
                requireContext(),
                R.string.text_play_video_on_webpage_first,
                Toast.LENGTH_SHORT
            ).show()
            return
        }

        val session = currentCastSession()
        val receiverId = video.preferredReceiverId()
        resetReceiverMediaErrorUiState()
        castContext?.setReceiverApplicationId(receiverId)
        Log.i(
            TAG,
            "Cast web video requested: connected=${session?.isConnected == true} " +
                "receiverId=$receiverId currentReceiverId=${session?.applicationMetadata?.applicationId} " +
                "page=${binding.webView.url} title=${video.title} mime=${video.mimeType} url=${video.url}"
        )
        if (session?.isConnected != true) {
            pendingVideo = video
            Toast.makeText(requireContext(), R.string.text_select_tv_to_cast, Toast.LENGTH_SHORT).show()
            binding.btnTopCast.performClick()
            mainHandler.postDelayed({
                if (_binding != null &&
                    view != null &&
                    pendingVideo === video &&
                    currentCastSession()?.isConnected != true
                ) {
                    pendingVideo = null
                    updateCastStatusFromSession()
                    showCastFailureDialog()
                }
            }, CAST_SELECTION_TIMEOUT_MS)
            updateControls()
            return
        }

        if (!session.isRunningReceiver(receiverId)) {
            pendingVideo = video
            isSwitchingCastReceiver = true
            Log.i(
                TAG,
                "Switching Cast receiver for web video: currentReceiverId=" +
                    "${session.applicationMetadata?.applicationId} targetReceiverId=$receiverId url=${video.url}"
            )
            castContext?.sessionManager?.endCurrentSession(true)
            Toast.makeText(requireContext(), R.string.text_select_tv_to_cast, Toast.LENGTH_SHORT).show()
            mainHandler.postDelayed({
                if (_binding != null &&
                    view != null &&
                    pendingVideo === video &&
                    currentCastSession()?.isConnected != true
                ) {
                    binding.btnTopCast.performClick()
                }
            }, RECEIVER_SWITCH_CAST_PICKER_DELAY_MS)
            mainHandler.postDelayed({
                if (_binding != null &&
                    view != null &&
                    pendingVideo === video &&
                    currentCastSession()?.isConnected != true
                ) {
                    isSwitchingCastReceiver = false
                    pendingVideo = null
                    updateCastStatusFromSession()
                    showCastFailureDialog()
                    updateControls()
                }
            }, RECEIVER_SWITCH_CAST_PICKER_DELAY_MS + CAST_SELECTION_TIMEOUT_MS)
            updateControls()
            return
        }

        castVideo(video)
    }

    private fun castVideo(video: DetectedVideo) {
        if (video.isLikelyStreamingSegment()) return

        val session = currentCastSession()
        if (session?.isConnected != true) {
            castOrConnect(video)
            return
        }

        pausePhoneWebPlayback { startSeconds ->
            loadCastVideo(session, video, startSeconds ?: 0f)
        }
    }

    private fun loadCastVideo(
        session: CastSession,
        video: DetectedVideo,
        startSeconds: Float
    ) {
        val metadata = MediaMetadata(MediaMetadata.MEDIA_TYPE_MOVIE).apply {
            putString(MediaMetadata.KEY_TITLE, video.title ?: getString(R.string.text_web_video))
            video.thumbnail?.let { addImage(WebImage(Uri.parse(it))) }
        }

        val requestHeaders = buildRemoteRequestHeaders(video)
        val useDirectCastUrl = video.shouldUseDirectCastUrl()
        val castUrl = if (useDirectCastUrl) {
            video.url
        } else {
            mediaServer.registerRemoteUrl(
                video.url,
                video.mimeType ?: "video/mp4",
                requestHeaders
            )
        }
        if (castUrl == null) {
            Log.e(
                TAG,
                "Could not register web video for Cast: page=${binding.webView.url} " +
                    "mime=${video.mimeType} url=${video.url}"
            )
            Toast.makeText(
                requireContext(),
                R.string.text_could_not_cast_video,
                Toast.LENGTH_SHORT
            ).show()
            return
        }

        val mediaInfo = MediaInfo.Builder(castUrl)
            .setContentUrl(castUrl)
            .setStreamType(video.inferCastStreamType())
            .setContentType(video.mimeType ?: "video/mp4")
            .setMetadata(metadata)
            .build()

        val requestData = MediaLoadRequestData.Builder()
            .setMediaInfo(mediaInfo)
            .setAutoplay(true)
            .setCurrentTime((startSeconds * 1000L).toLong().coerceAtLeast(0L))
            .build()

        Log.i(
            TAG,
            "Loading web video on Cast: key=${video.castKey()} startSeconds=$startSeconds " +
                "page=${binding.webView.url} receiverDevice=${session.castDevice?.friendlyName} " +
                "delivery=${if (useDirectCastUrl) "direct" else "proxy"} " +
                "castUrl=$castUrl sourceUrl=${video.url} mime=${video.mimeType} " +
                "headerNames=${requestHeaders.toDebugHeaderNames()}"
        )
        isCasting = true
        updateControls()
        configureReceiverDebugCallback(session)

        session.remoteMediaClient
            ?.load(requestData)
            ?.setResultCallback { result ->
                mainHandler.post {
                    if (_binding == null || view == null) return@post

                    isCasting = result.status.isSuccess
                    Log.i(
                        TAG,
                        "Cast web load result: success=${result.status.isSuccess} " +
                            "code=${result.status.statusCode} message=${result.status.statusMessage} " +
                            "delivery=${if (useDirectCastUrl) "direct" else "proxy"} " +
                            "castUrl=$castUrl sourceUrl=${video.url}"
                    )
                    if (result.status.isSuccess) {
                        lastCastVideoKey = video.castKey()
                    } else {
                        showLoadFailureToastIfNoReceiverError(R.string.text_could_not_cast_video)
                    }
                    updateControls()
                }
            }
    }

    private fun showLoadFailureToastIfNoReceiverError(messageRes: Int) {
        mainHandler.postDelayed({
            if (_binding == null || view == null || hasRecentReceiverMediaError()) {
                return@postDelayed
            }
            Toast.makeText(requireContext(), messageRes, Toast.LENGTH_SHORT).show()
        }, CAST_LOAD_FAILURE_FALLBACK_DELAY_MS)
    }

    private fun buildRemoteRequestHeaders(video: DetectedVideo): Map<String, String> {
        val headers = linkedMapOf<String, String>()
        video.requestHeaders.forEach { (name, value) ->
            if (name.isNotBlank() && value.isNotBlank()) {
                headers[name] = value
            }
        }

        val pageUrl = binding.webView.url
        if (!pageUrl.isNullOrBlank() && headers.keys.none { it.equals("Referer", true) }) {
            headers["Referer"] = pageUrl
        }

        val origin = pageUrl?.toOrigin()
        if (!origin.isNullOrBlank() && headers.keys.none { it.equals("Origin", true) }) {
            headers["Origin"] = origin
        }

        val cookie = CookieManager.getInstance().getCookie(video.url)
            ?: pageUrl?.let { CookieManager.getInstance().getCookie(it) }
        if (!cookie.isNullOrBlank() && headers.keys.none { it.equals("Cookie", true) }) {
            headers["Cookie"] = cookie
        }

        return headers
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
        resetCastingState()
        updateControls()
    }

    private fun disconnectCastingOnExit(updateUi: Boolean = true) {
        if (currentCastSession()?.isConnected != true) return

        currentCastSession()?.remoteMediaClient?.stop()
        castContext?.sessionManager?.endCurrentSession(true)
        mediaServer.clear()
        isSwitchingCastReceiver = false
        pendingVideo = null
        isCasting = false
        resetCastingState()
        if (updateUi) {
            updateCastStatus(CastConnectionState.Disconnected)
            updateControls()
        }
    }

    private fun resetCastingState() {
        lastCastVideoKey = null
        pendingAutoCastVideoKey = null
        mainHandler.removeCallbacks(autoCastChangedVideoRunnable)
    }

    private fun pausePhoneWebPlayback(onPosition: (Float?) -> Unit = {}) {
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

    private fun reloadOrStopLoading() {
        if (isPageLoading) {
            binding.webView.stopLoading()
            isPageLoading = false
        } else {
            binding.webView.reload()
        }
        updateControls()
    }

    private fun toggleCurrentBookmark() {
        val url = binding.webView.url ?: return
        val messageRes = if (sessionBookmarks.add(url)) {
            R.string.text_bookmark_added
        } else {
            sessionBookmarks.remove(url)
            R.string.text_bookmark_removed
        }

        Toast.makeText(requireContext(), messageRes, Toast.LENGTH_SHORT).show()
        updateBookmarkState(url)
    }

    private fun showBrowserHome() {
        binding.addressBar.isVisible = false
        binding.webView.isVisible = false
        binding.browserHome.isVisible = true
        detectedVideos.clear()
        isPageLoading = false
        updateControls()
    }

    private fun showWebPage(url: String?) {
        binding.addressBar.isVisible = true
        binding.webView.isVisible = true
        binding.browserHome.isVisible = false
        updateAddress(url)
    }

    private fun updateAddress(url: String?) {
        if (!url.isNullOrBlank() && !binding.inputAddress.hasFocus()) {
            binding.inputAddress.setText(url)
            binding.inputAddress.setSelection(binding.inputAddress.text?.length ?: 0)
        }

        val isHttps = url?.startsWith("https://", ignoreCase = true) == true
        binding.iconSecurity.imageTintList = ColorStateList.valueOf(
            Color.parseColor(if (isHttps) "#84FF6A" else "#F4D188")
        )
        updateBookmarkState(url)
    }

    private fun updateBookmarkState(url: String?) {
        val bookmarked = url != null && sessionBookmarks.contains(url)
        binding.btnBookmark.imageTintList = ColorStateList.valueOf(
            Color.parseColor(if (bookmarked) "#F4D188" else "#BFBFBF")
        )
    }

    private fun updateControls() {
        if (_binding == null || view == null) return

        binding.btnWebBack.isEnabled = binding.webView.canGoBack()
        binding.btnWebBack.alpha = if (binding.btnWebBack.isEnabled) 1f else 0.4f

        binding.btnWebForward.isEnabled = binding.webView.canGoForward()
        binding.btnWebForward.alpha = if (binding.btnWebForward.isEnabled) 1f else 0.4f

        binding.btnReload.setImageResource(
            if (isPageLoading) R.drawable.ic_close_web_white else R.drawable.ic_reload_white
        )

        val videos = castableVideos()
        binding.btnCastWebAction.isEnabled = true
        binding.btnCastWebAction.text = when {
            isCasting -> getString(R.string.text_playing_on_tv)
            pendingVideo != null -> getString(R.string.text_connecting_to_tv)
            isPageLoading -> getString(R.string.text_detecting_videos)
            videos.isEmpty() -> getString(R.string.text_no_videos_detected)
            currentCastSession()?.isConnected != true -> getString(R.string.text_connect_to_tv)
            videos.size == 1 -> getString(R.string.text_cast_video)
            else -> getString(R.string.text_select_video_count, videos.size)
        }
        binding.btnCastWebAction.alpha = if (videos.isEmpty() && !isCasting && !isPageLoading) 0.75f else 1f
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

    private fun configureReceiverDebugCallback(session: CastSession) {
        if (session.isRunningReceiver(CastReceiverIds.CUSTOM_RECEIVER)) {
            setReceiverDebugCallback(session)
            return
        }

        removeReceiverDebugCallback(session)
        Log.i(
            TAG,
            "Skipping custom receiver debug callback: receiverId=${session.applicationMetadata?.applicationId}"
        )
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
        showReceiverMediaErrorIfAny(rawMessage, TAG)
    }

    private fun openCastYoutube(startUrl: String) {
        navigate(
            R.id.castYoutubeFragment,
            Bundle().apply {
                putString(CastYoutubeFragment.ARG_START_URL, startUrl)
            }
        )
    }

    private fun handleBackPressed() {
        if (binding.webView.isVisible && binding.webView.canGoBack()) {
            binding.webView.goBack()
        } else {
            // Không tự động ngắt kết nối, chỉ quay lại màn trước
            popBackStack()
        }
    }

    private fun String.toVideoTypeLabel(): String {
        return when (this) {
            "application/x-mpegURL" -> "HLS"
            "application/dash+xml" -> "DASH"
            "video/mp4" -> "MP4"
            "video/webm" -> "WEBM"
            "audio/mpeg" -> "MP3"
            "audio/mp4" -> "M4A"
            else -> this
        }
    }

    private fun String.toOrigin(): String? {
        val uri = runCatching { Uri.parse(this) }.getOrNull() ?: return null
        val scheme = uri.scheme ?: return null
        val host = uri.host ?: return null
        val port = if (uri.port > 0) ":${uri.port}" else ""
        return "$scheme://$host$port"
    }

    private fun Map<String, String>.toDebugHeaderNames(): String {
        if (isEmpty()) return "[]"
        return keys
            .map { name -> if (name.equals("Cookie", ignoreCase = true)) "Cookie(redacted)" else name }
            .sorted()
            .joinToString(prefix = "[", postfix = "]")
    }

    private fun String.isYoutubeUrl(): Boolean {
        val host = runCatching { Uri.parse(this).host?.lowercase(Locale.US) }
            .getOrNull()
            ?: return false
        return host == "youtube.com" ||
            host.endsWith(".youtube.com") ||
            host == "youtu.be" ||
            host == "youtube-nocookie.com" ||
            host.endsWith(".youtube-nocookie.com")
    }

    private fun String.isYoutubeRelatedUrl(): Boolean {
        val host = runCatching { Uri.parse(this).host?.lowercase(Locale.US) }
            .getOrNull()
            ?: return false
        return isYoutubeUrl() ||
            host == "googlevideo.com" ||
            host.endsWith(".googlevideo.com") ||
            host == "ytimg.com" ||
            host.endsWith(".ytimg.com")
    }

    private fun DetectedVideo.inferCastStreamType(): Int {
        return MediaInfo.STREAM_TYPE_BUFFERED
    }

    private fun DetectedVideo.castKey(): String {
        return url.substringBefore("#")
    }

    private fun DetectedVideo.shouldUseDirectCastUrl(): Boolean {
        val uri = runCatching { Uri.parse(url) }.getOrNull() ?: return false
        if (!uri.scheme.equals("https", ignoreCase = true)) return false

        val host = uri.host?.lowercase(Locale.US) ?: return false
        val isArchiveOrgHost = host == "archive.org" ||
            host.endsWith(".archive.org")
        if (!isArchiveOrgHost) return false

        val normalizedMimeType = mimeType?.lowercase(Locale.US)
        val isDirectVideoMimeType = normalizedMimeType == "video/mp4" ||
            normalizedMimeType == "video/webm" ||
            normalizedMimeType == "application/x-mpegurl" ||
            normalizedMimeType == "application/vnd.apple.mpegurl"
        val isProgressiveVideoUrl = uri.path.orEmpty().endsWith(".mp4", ignoreCase = true) ||
            uri.path.orEmpty().endsWith(".webm", ignoreCase = true)
        val isHlsUrl = uri.path.orEmpty().endsWith(".m3u8", ignoreCase = true)
        return isDirectVideoMimeType || isProgressiveVideoUrl || isHlsUrl
    }

    private fun DetectedVideo.preferredReceiverId(): String {
        return if (shouldUseDirectCastUrl()) {
            CastReceiverIds.MEDIA_RECEIVER
        } else {
            CastReceiverIds.CUSTOM_RECEIVER
        }
    }

    private fun CastSession.isRunningReceiver(receiverId: String): Boolean {
        return applicationMetadata?.applicationId == receiverId
    }

    private fun castableVideos(): List<DetectedVideo> {
        return detectedVideos.values
            .filterNot { it.isLikelyStreamingSegment() }
            .sortedWith(
                compareByDescending<DetectedVideo> { it.castCandidateScore() }
                    .thenBy { it.title.orEmpty() }
                    .thenBy { it.url }
            )
    }

    private fun DetectedVideo.castCandidateScore(): Int {
        return when (mimeType) {
            "video/mp4" -> 400
            "video/webm" -> 350
            "application/x-mpegURL" -> 250
            "application/dash+xml" -> 200
            "audio/mp4",
            "audio/mpeg" -> 100
            else -> 0
        }
    }

    private fun DetectedVideo.isLikelyStreamingSegment(): Boolean {
        val cleanUrl = url.lowercase(Locale.US).substringBefore("#").substringBefore("?")
        val path = runCatching { Uri.parse(url).path?.lowercase(Locale.US) }
            .getOrNull()
            ?: cleanUrl
        val fileName = path.substringAfterLast("/")

        if (cleanUrl.endsWith(".m4s") ||
            cleanUrl.contains(".m4s/") ||
            cleanUrl.endsWith(".cmfv") ||
            cleanUrl.endsWith(".cmfa")
        ) {
            return true
        }

        if (mimeType != "video/mp4") return false
        if (fileName.startsWith("init.") || fileName == "init.mp4") return true
        if (STREAMING_SEGMENT_FILE_REGEX.matches(fileName)) return true

        return STREAMING_SEGMENT_PATH_MARKERS.any { marker -> path.contains(marker) }
    }

    private class VideoDetectorBridge(
        private val onVideoFound: (String) -> Unit
    ) {
        @JavascriptInterface
        fun onVideosDetected(json: String) {
            runCatching {
                val array = JSONArray(json)
                for (index in 0 until array.length()) {
                    onVideoFound(array.optString(index))
                }
            }
        }
    }

    private data class DetectedVideo(
        val url: String,
        val title: String?,
        val mimeType: String?,
        val thumbnail: String?,
        val requestHeaders: Map<String, String>
    )

    private enum class CastConnectionState {
        Disconnected,
        Connecting,
        Connected,
        Error
    }

    companion object {
        private const val JS_BRIDGE_NAME = "AndroidVideoDetector"
        private const val DETECTOR_RETRY_DELAY_MS = 900L
        private const val CAST_SELECTION_TIMEOUT_MS = 30_000L
        private const val CAST_LOAD_FAILURE_FALLBACK_DELAY_MS = 1_200L
        private const val RECEIVER_SWITCH_CAST_PICKER_DELAY_MS = 650L
        private const val AUTO_CAST_CHANGED_VIDEO_DELAY_MS = 900L
        private const val RECEIVER_NAMESPACE = "urn:x-cast:com.example.camera.webrtc"
        private const val TAG = "CastWebDebug"
        private const val GOOGLE_SEARCH_URL = "https://www.google.com/search?q="
        private const val YOUTUBE_URL = "https://m.youtube.com"
        private const val FACEBOOK_URL = "https://m.facebook.com"
        private const val ARCHIVE_ORG_SAMPLE_VIDEO_URL =
            "https://archive.org/download/BigBuckBunny_124/Content/big_buck_bunny_720p_surround.mp4"
        private const val VEVO_URL = "https://www.vevo.com"
        private const val TWITCH_URL = "https://m.twitch.tv"
        private const val VEOH_URL = "https://veoh.com/"
        private val STREAMING_SEGMENT_FILE_REGEX =
            Regex(""".*(^|[-_.])(seg|segment|frag|fragment|chunk|part)[-_.]?\d+.*\.(mp4|m4v)$""")
        private val STREAMING_SEGMENT_PATH_MARKERS = listOf(
            "/hls/",
            "/dash/",
            "/cmaf/",
            "/segment",
            "/segments/",
            "/fragment",
            "/fragments/",
            "/chunk",
            "/chunks/"
        )
    }
}
