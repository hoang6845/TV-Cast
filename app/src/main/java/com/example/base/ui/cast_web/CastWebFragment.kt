package com.example.base.ui.cast_web

import android.annotation.SuppressLint
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Color
import android.net.Uri
import android.os.Handler
import android.os.Looper
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
import com.example.base.R
import com.example.base.cast.CastReceiverIds
import com.example.base.databinding.FragmentCastWebBinding
import com.example.base.databinding.ItemCastWebSiteBinding
import com.example.base.ui.common.showCastFailureDialog
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
import hoang.dqm.codebase.base.activity.onBackPressed
import hoang.dqm.codebase.base.activity.popBackStack
import org.json.JSONArray
import java.net.URLEncoder
import java.util.Locale

class CastWebFragment : BaseFragment<FragmentCastWebBinding, CastWebViewModel>() {

    private val mainHandler = Handler(Looper.getMainLooper())
    private val detectedVideos = linkedMapOf<String, DetectedVideo>()
    private val sessionBookmarks = mutableSetOf<String>()
    private var castContext: CastContext? = null
    private var pendingVideo: DetectedVideo? = null
    private var isCasting = false
    private var isPageLoading = false
    private var toolbarBaseHeight = 0
    private var bottomBarBaseHeight = 0

    private val castSessionListener = object : SessionManagerListener<CastSession> {
        override fun onSessionStarting(session: CastSession) {
            updateCastStatus(CastConnectionState.Connecting)
        }

        override fun onSessionStarted(session: CastSession, sessionId: String) {
            updateCastStatus(CastConnectionState.Connected)
            pendingVideo?.let {
                pendingVideo = null
                castVideo(it)
            }
        }

        override fun onSessionStartFailed(session: CastSession, error: Int) {
            pendingVideo = null
            updateCastStatus(CastConnectionState.Error)
            showCastFailureDialog()
            updateControls()
        }

        override fun onSessionEnding(session: CastSession) {
            updateCastStatus(CastConnectionState.Connecting)
        }

        override fun onSessionEnded(session: CastSession, error: Int) {
            pendingVideo = null
            isCasting = false
            updateCastStatus(CastConnectionState.Disconnected)
            updateControls()
        }

        override fun onSessionResuming(session: CastSession, sessionId: String) {
            updateCastStatus(CastConnectionState.Connecting)
        }

        override fun onSessionResumed(session: CastSession, wasSuspended: Boolean) {
            updateCastStatus(CastConnectionState.Connected)
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
            castContext?.setReceiverApplicationId(CastReceiverIds.DEFAULT_MEDIA)
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
                request?.url?.toString()?.let(::detectMediaUrl)
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
            url = YOUTUBE_URL
        )
        configureSite(
            binding.siteFacebook,
            logoRes = R.drawable.facebook,
            title = getString(R.string.text_facebook),
            url = FACEBOOK_URL
        )
        configureSite(
            binding.siteTed,
            logoRes = R.drawable.ted,
            title = getString(R.string.text_ted),
            url = TED_URL
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
        url: String
    ) {
        site.siteLogo.setImageResource(logoRes)
        site.siteLogo.contentDescription = title
        site.siteTitle.text = title
        site.root.setOnClickListener { loadUrl(url) }
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
        showWebPage(url)
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

    private fun detectMediaUrl(url: String) {
        val video = url.toDetectedVideoOrNull() ?: return
        mainHandler.post {
            if (_binding == null || view == null) return@post

            val key = video.url.substringBefore("#")
            if (detectedVideos.containsKey(key)) return@post
            detectedVideos[key] = video.copy(
                title = binding.webView.title?.takeIf { it.isNotBlank() } ?: video.title
            )
            updateControls()
        }
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
            thumbnail = null
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

        val videos = detectedVideos.values.toList()
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
            .setItems(labels) { _, which -> castOrConnect(videos[which]) }
            .setNegativeButton(R.string.text_cancel, null)
            .show()
    }

    private fun castOrConnect(video: DetectedVideo) {
        val session = currentCastSession()
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

        castVideo(video)
    }

    private fun castVideo(video: DetectedVideo) {
        val session = currentCastSession()
        if (session?.isConnected != true) {
            castOrConnect(video)
            return
        }

        val metadata = MediaMetadata(MediaMetadata.MEDIA_TYPE_MOVIE).apply {
            putString(MediaMetadata.KEY_TITLE, video.title ?: getString(R.string.text_web_video))
            video.thumbnail?.let { addImage(WebImage(Uri.parse(it))) }
        }

        val mediaInfo = MediaInfo.Builder(video.url)
            .setStreamType(MediaInfo.STREAM_TYPE_BUFFERED)
            .setContentType(video.mimeType ?: "video/mp4")
            .setMetadata(metadata)
            .build()

        val requestData = MediaLoadRequestData.Builder()
            .setMediaInfo(mediaInfo)
            .setAutoplay(true)
            .build()

        isCasting = true
        updateControls()

        session.remoteMediaClient
            ?.load(requestData)
            ?.setResultCallback { result ->
                mainHandler.post {
                    if (_binding == null || view == null) return@post

                    isCasting = result.status.isSuccess
                    if (!result.status.isSuccess) {
                        Toast.makeText(
                            requireContext(),
                            R.string.text_could_not_cast_video,
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                    updateControls()
                }
            }
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
        updateControls()
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

        val videos = detectedVideos.values.toList()
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

    private fun handleBackPressed() {
        if (binding.webView.isVisible && binding.webView.canGoBack()) {
            binding.webView.goBack()
        } else {
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
        val thumbnail: String?
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
        private const val GOOGLE_SEARCH_URL = "https://www.google.com/search?q="
        private const val YOUTUBE_URL = "https://m.youtube.com"
        private const val FACEBOOK_URL = "https://m.facebook.com"
        private const val TED_URL = "https://www.ted.com"
        private const val VEVO_URL = "https://www.vevo.com"
        private const val TWITCH_URL = "https://m.twitch.tv"
        private const val VEOH_URL = "https://www.veoh.com"
    }
}
