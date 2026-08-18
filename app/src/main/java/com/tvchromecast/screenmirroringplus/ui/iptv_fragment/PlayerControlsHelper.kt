package com.tvchromecast.screenmirroringplus.ui.iptv_fragment

import android.app.Activity
import android.content.Context
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.View
import android.view.WindowManager
import android.widget.ImageView
import android.widget.SeekBar
import android.widget.TextView
import androidx.core.view.isVisible
import androidx.media3.common.Player
import androidx.media3.ui.PlayerView
import com.tvchromecast.screenmirroringplus.R
import java.util.concurrent.TimeUnit

class PlayerControlsHelper(
    private val context: Context,
    private val activity: Activity,
    private val playerView: PlayerView,
    private val controlsOverlay: View,
    private val brightnessPanel: View,
    private val volumePanel: View,
    private val brightnessSeekbar: SeekBar,
    private val volumeSeekbar: SeekBar,
    private val seekbarTime: SeekBar,
    private val tvCurrentTime: TextView,
    private val tvTotalTime: TextView,
    private val btnPlayPause: ImageView,
    private val btnFullscreen: ImageView,
    private val btnLock: ImageView,
    private val btnUnlock: View,
    private val topBarControls: View,
    private val bottomControls: View,
    private val seekFeedback: TextView,
    private val loadingIndicator: View
) {

    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val handler = Handler(Looper.getMainLooper())
    private var hideControlsRunnable: Runnable? = null
    private var isControlsLocked = false
    private var isFullscreen = false
    private var player: Player? = null

    companion object {
        private const val HIDE_CONTROLS_DELAY = 3000L
        private const val SEEK_INCREMENT = 10000L // 10 seconds
    }

    init {
        setupBrightnessControl()
        setupVolumeControl()
        setupPlaybackControls()
        setupGestureControls()
    }

    fun setPlayer(newPlayer: Player?) {
        this.player = newPlayer
        newPlayer?.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(state: Int) {
                when (state) {
                    Player.STATE_BUFFERING -> loadingIndicator.isVisible = true
                    Player.STATE_READY -> {
                        loadingIndicator.isVisible = false
                        updatePlayPauseButton()
                    }
                    Player.STATE_ENDED -> {
                        loadingIndicator.isVisible = false
                        btnPlayPause.setImageResource(R.drawable.ic_play)
                    }
                    else -> loadingIndicator.isVisible = false
                }
            }

            override fun onIsPlayingChanged(isPlaying: Boolean) {
                updatePlayPauseButton()
            }
        })
        startProgressUpdate()
    }

    private fun setupBrightnessControl() {
        val currentBrightness = getCurrentBrightness()
        brightnessSeekbar.progress = currentBrightness

        brightnessSeekbar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) {
                    setBrightness(progress)
                }
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) {
                brightnessPanel.isVisible = true
                cancelHideControls()
            }

            override fun onStopTrackingTouch(seekBar: SeekBar?) {
                scheduleHideControls()
            }
        })
    }

    private fun setupVolumeControl() {
        val maxVolume = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        val currentVolume = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
        volumeSeekbar.max = maxVolume
        volumeSeekbar.progress = currentVolume

        volumeSeekbar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) {
                    audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, progress, 0)
                }
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) {
                volumePanel.isVisible = true
                cancelHideControls()
            }

            override fun onStopTrackingTouch(seekBar: SeekBar?) {
                scheduleHideControls()
            }
        })
    }

    private fun setupPlaybackControls() {
        btnPlayPause.setOnClickListener {
            player?.let {
                if (it.isPlaying) {
                    it.pause()
                } else {
                    it.play()
                }
            }
            scheduleHideControls()
        }

        seekbarTime.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) {
                    player?.let {
                        val duration = it.duration
                        if (duration > 0) {
                            val position = (duration * progress) / 1000
                            tvCurrentTime.text = formatTime(position)
                        }
                    }
                }
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) {
                cancelHideControls()
            }

            override fun onStopTrackingTouch(seekBar: SeekBar?) {
                player?.let {
                    val duration = it.duration
                    if (duration > 0) {
                        val position = (duration * seekbarTime.progress) / 1000
                        it.seekTo(position)
                    }
                }
                scheduleHideControls()
            }
        })

        btnFullscreen.setOnClickListener {
            toggleFullscreen()
            scheduleHideControls()
        }

        btnLock.setOnClickListener {
            lockControls()
        }

        btnUnlock.setOnClickListener {
            unlockControls()
        }
    }

    private fun setupGestureControls() {
        controlsOverlay.setOnClickListener {
            if (!isControlsLocked) {
                toggleControlsVisibility()
            }
        }
    }

    fun setupRewindButton(btnRewind: View) {
        btnRewind.setOnClickListener {
            player?.let {
                val newPosition = (it.currentPosition - SEEK_INCREMENT).coerceAtLeast(0)
                it.seekTo(newPosition)
                showSeekFeedback("-10s")
            }
            scheduleHideControls()
        }
    }

    fun setupForwardButton(btnForward: View) {
        btnForward.setOnClickListener {
            player?.let {
                val newPosition = (it.currentPosition + SEEK_INCREMENT).coerceAtMost(it.duration)
                it.seekTo(newPosition)
                showSeekFeedback("+10s")
            }
            scheduleHideControls()
        }
    }

    private fun toggleControlsVisibility() {
        val shouldShow = !topBarControls.isVisible
        topBarControls.isVisible = shouldShow
        bottomControls.isVisible = shouldShow
        brightnessPanel.isVisible = shouldShow
        volumePanel.isVisible = shouldShow

        if (shouldShow) {
            scheduleHideControls()
        } else {
            cancelHideControls()
        }
    }

    private fun showControls() {
        if (!isControlsLocked) {
            topBarControls.isVisible = true
            bottomControls.isVisible = true
            brightnessPanel.isVisible = true
            volumePanel.isVisible = true
            scheduleHideControls()
        }
    }

    private fun hideControls() {
        if (!isControlsLocked) {
            topBarControls.isVisible = false
            bottomControls.isVisible = false
            brightnessPanel.isVisible = false
            volumePanel.isVisible = false
        }
    }

    private fun scheduleHideControls() {
        cancelHideControls()
        hideControlsRunnable = Runnable {
            hideControls()
        }
        handler.postDelayed(hideControlsRunnable!!, HIDE_CONTROLS_DELAY)
    }

    private fun cancelHideControls() {
        hideControlsRunnable?.let {
            handler.removeCallbacks(it)
            hideControlsRunnable = null
        }
    }

    private fun lockControls() {
        isControlsLocked = true
        btnUnlock.isVisible = true
        hideControls()
    }

    private fun unlockControls() {
        isControlsLocked = false
        btnUnlock.isVisible = false
        showControls()
    }

    private fun toggleFullscreen() {
        isFullscreen = !isFullscreen
        if (isFullscreen) {
            // Enter fullscreen
            activity.window.decorView.systemUiVisibility = (
                    View.SYSTEM_UI_FLAG_FULLSCREEN
                            or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                            or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                    )
            btnFullscreen.setImageResource(R.drawable.ic_fullscreen) // Exit fullscreen icon (same icon)
        } else {
            // Exit fullscreen
            activity.window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_VISIBLE
            btnFullscreen.setImageResource(R.drawable.ic_fullscreen)
        }
    }

    private fun getCurrentBrightness(): Int {
        return try {
            val brightness = Settings.System.getInt(
                context.contentResolver,
                Settings.System.SCREEN_BRIGHTNESS
            )
            (brightness * 100) / 255
        } catch (e: Exception) {
            50 // Default to 50%
        }
    }

    private fun setBrightness(brightness: Int) {
        val layoutParams = activity.window.attributes
        layoutParams.screenBrightness = brightness / 100f
        activity.window.attributes = layoutParams
    }

    private fun updatePlayPauseButton() {
        player?.let {
            if (it.isPlaying) {
                btnPlayPause.setImageResource(R.drawable.ic_pause)
            } else {
                btnPlayPause.setImageResource(R.drawable.ic_play)
            }
        }
    }

    private fun startProgressUpdate() {
        handler.post(object : Runnable {
            override fun run() {
                player?.let {
                    val position = it.currentPosition
                    val duration = it.duration
                    
                    if (duration > 0) {
                        seekbarTime.progress = ((position * 1000) / duration).toInt()
                        tvCurrentTime.text = formatTime(position)
                        tvTotalTime.text = formatTime(duration)
                    }
                }
                handler.postDelayed(this, 1000)
            }
        })
    }

    private fun formatTime(millis: Long): String {
        val hours = TimeUnit.MILLISECONDS.toHours(millis)
        val minutes = TimeUnit.MILLISECONDS.toMinutes(millis) % 60
        val seconds = TimeUnit.MILLISECONDS.toSeconds(millis) % 60

        return if (hours > 0) {
            String.format("%02d:%02d:%02d", hours, minutes, seconds)
        } else {
            String.format("%02d:%02d", minutes, seconds)
        }
    }

    private fun showSeekFeedback(text: String) {
        seekFeedback.text = text
        seekFeedback.isVisible = true
        handler.postDelayed({
            seekFeedback.isVisible = false
        }, 800)
    }

    fun release() {
        cancelHideControls()
        handler.removeCallbacksAndMessages(null)
    }

    fun onResume() {
        // Restore brightness if needed
        val currentBrightness = getCurrentBrightness()
        brightnessSeekbar.progress = currentBrightness
    }

    fun showLoading() {
        loadingIndicator.isVisible = true
    }

    fun hideLoading() {
        loadingIndicator.isVisible = false
    }
}
