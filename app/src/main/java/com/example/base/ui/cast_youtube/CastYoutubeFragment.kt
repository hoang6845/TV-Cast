package com.example.base.ui.cast_youtube

import android.annotation.SuppressLint
import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
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
import com.example.base.databinding.FragmentCastYoutubeBinding
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import hoang.dqm.codebase.base.activity.BaseFragment
import hoang.dqm.codebase.base.activity.onBackPressed
import hoang.dqm.codebase.base.activity.popBackStack
import java.util.Locale

class CastYoutubeFragment : BaseFragment<FragmentCastYoutubeBinding, CastYoutubeViewModel>() {

    private var currentVideoId: String? = null
    private var isOpeningYoutube = false
    private var toolbarBaseHeight = 0
    private var bottomBarBaseHeight = 0

    override fun initView() {
        applySystemInsets()
        setupWebView()
        updateControls()
    }

    override fun initListener() {
        binding.btnBack.setOnClickListener { handleBackPressed() }
        binding.btnTopCast.setOnClickListener { openSelectedVideoOrPrompt() }
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
        binding.btnCastWithYoutube.setOnClickListener { openSelectedVideoOrPrompt() }
        onBackPressed(Runnable { handleBackPressed() })
    }

    override fun initData() {
        if (binding.webView.url.isNullOrBlank()) {
            binding.webView.loadUrl(YOUTUBE_HOME_URL)
        }
    }

    override fun onResume() {
        super.onResume()
        if (isOpeningYoutube) {
            isOpeningYoutube = false
            updateControls()
        }
    }

    override fun onDestroyView() {
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
        currentVideoId = url?.let(::extractYoutubeVideoId)
        updateControls()
    }

    private fun extractYoutubeVideoId(url: String): String? {
        val uri = Uri.parse(url)
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

    private fun updateControls() {
        val hasVideo = currentVideoId != null

        binding.btnWebBack.isEnabled = binding.webView.canGoBack()
        binding.btnWebBack.alpha = if (binding.btnWebBack.isEnabled) 1f else 0.4f

        binding.btnWebForward.isEnabled = binding.webView.canGoForward()
        binding.btnWebForward.alpha = if (binding.btnWebForward.isEnabled) 1f else 0.4f

        binding.btnCastWithYoutube.isEnabled = hasVideo && !isOpeningYoutube
        binding.btnCastWithYoutube.alpha = if (hasVideo || isOpeningYoutube) 1f else 0.75f
        binding.btnTopCast.alpha = if (hasVideo) 1f else 0.55f

        binding.btnCastWithYoutube.text = when {
            isOpeningYoutube -> getString(R.string.text_opening_youtube)
            hasVideo -> getString(R.string.text_cast_with_youtube)
            else -> getString(R.string.text_open_youtube_video)
        }
    }

    private fun handleBackPressed() {
        if (binding.webView.canGoBack()) {
            binding.webView.goBack()
        } else {
            popBackStack()
        }
    }

    private fun openSelectedVideoOrPrompt() {
        val videoId = currentVideoId
        if (videoId == null) {
            Toast.makeText(
                requireContext(),
                R.string.text_select_youtube_video_first,
                Toast.LENGTH_SHORT
            ).show()
            return
        }

        openVideoInYoutube(videoId)
    }

    private fun openVideoInYoutube(videoId: String) {
        val youtubeUri = Uri.Builder()
            .scheme("https")
            .authority("www.youtube.com")
            .path("watch")
            .appendQueryParameter("v", videoId)
            .build()

        isOpeningYoutube = true
        updateControls()

        val intent = Intent(Intent.ACTION_VIEW, youtubeUri).apply {
            setPackage(YOUTUBE_PACKAGE)
        }

        try {
            startActivity(intent)
        } catch (_: ActivityNotFoundException) {
            isOpeningYoutube = false
            updateControls()
            showInstallYoutubeDialog()
        } catch (_: Exception) {
            isOpeningYoutube = false
            updateControls()
            Toast.makeText(
                requireContext(),
                R.string.text_could_not_open_youtube,
                Toast.LENGTH_SHORT
            ).show()
        }
    }

    private fun showInstallYoutubeDialog() {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.text_youtube_required)
            .setMessage(R.string.text_youtube_required_message)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.text_install_youtube) { _, _ ->
                openYoutubeInPlayStore()
            }
            .show()
    }

    private fun openYoutubeInPlayStore() {
        val marketIntent = Intent(
            Intent.ACTION_VIEW,
            Uri.parse("market://details?id=$YOUTUBE_PACKAGE")
        ).apply {
            setPackage(PLAY_STORE_PACKAGE)
        }

        try {
            startActivity(marketIntent)
        } catch (_: Exception) {
            startActivity(
                Intent(
                    Intent.ACTION_VIEW,
                    Uri.parse("https://play.google.com/store/apps/details?id=$YOUTUBE_PACKAGE")
                )
            )
        }
    }

    companion object {
        private const val YOUTUBE_HOME_URL = "https://m.youtube.com"
        private const val YOUTUBE_PACKAGE = "com.google.android.youtube"
        private const val PLAY_STORE_PACKAGE = "com.android.vending"
        private val YOUTUBE_VIDEO_ID_REGEX = Regex("^[A-Za-z0-9_-]{11}$")
    }
}
