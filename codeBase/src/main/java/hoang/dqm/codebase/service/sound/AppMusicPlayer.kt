package hoang.dqm.codebase.service.sound

import android.net.Uri
import androidx.annotation.OptIn
import androidx.annotation.RawRes
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import hoang.dqm.codebase.base.application.getBaseApplication
import hoang.dqm.codebase.service.session.isMusic
import hoang.dqm.codebase.service.session.isSound
import hoang.dqm.codebase.utils.Constant


object AppMusicPlayer {
    private var backgroundMusicPlayer: ExoPlayer? = null
    private var fxMusicPlayer: ExoPlayer? = null
    private var currentMusic = Constant.DEFAULT_BACKGROUND_SOUND

    fun getCurrentMusic(): String {
        return currentMusic
    }

    private fun getBackgroundMusicPlayer(): ExoPlayer {
        if (backgroundMusicPlayer == null) {
            backgroundMusicPlayer =
                ExoPlayer.Builder(getBaseApplication()).build()
        }
        return backgroundMusicPlayer!!
    }

    private fun getFxMusicPlayer(): ExoPlayer {
        if (fxMusicPlayer == null) {
            fxMusicPlayer =
                ExoPlayer.Builder(getBaseApplication()).build()
        }
        return fxMusicPlayer!!
    }

    @OptIn(UnstableApi::class)
    fun playBackgroundMusic(url: String? = currentMusic) {
        if (isMusic().not()) return
        currentMusic = url ?: currentMusic
        val mediaSource = generateMediaSource(url ?: currentMusic)
        getBackgroundMusicPlayer().apply {
            setMediaSource(mediaSource)
            repeatMode = ExoPlayer.REPEAT_MODE_ALL
            prepare()
            play()
        }
    }

    fun playFxMusic(@RawRes rawFxMusic: Int, loop: Boolean = false) {
        if (isSound().not()) return
        getFxMusicPlayer().apply {
            setMediaItem(
                MediaItem.fromUri(
                    getSoundUri(
                        packageName = getBaseApplication().packageName,
                        soundRes = rawFxMusic
                    )
                )
            )
            repeatMode = if (loop) ExoPlayer.REPEAT_MODE_ONE else ExoPlayer.REPEAT_MODE_OFF
            prepare()
            play()
        }
    }

    @OptIn(UnstableApi::class)
    fun generateMediaSource(endpoint: String): MediaSource {
        val token = "AppRemoteConfig.getAccessToken()"
        val dataSource: DataSource.Factory = DefaultHttpDataSource.Factory()
            .setDefaultRequestProperties(
                hashMapOf(
                    "Accept" to "application/vnd.github.v3.raw",
                    "Authorization" to "token $token"
                )
            )
        return ProgressiveMediaSource.Factory(dataSource)
            .createMediaSource(MediaItem.fromUri(endpoint))
    }

    fun getSoundUri(packageName: String, soundRes: Int): Uri {
        return Uri.parse("android.resource://$packageName/$soundRes")
    }

    fun checkAndPlay() {
        backgroundMusicPlayer?.let {
            if (isPlaying()) resume()
            else playBackgroundMusic()
        } ?: playBackgroundMusic()
    }

    fun stop() {
        try {
            if (isPlaying()) backgroundMusicPlayer?.stop()
        } catch (ex: Exception) {
            ex.printStackTrace()
        }
    }

    fun stopFxMusicPlayer() {
        try {
            if (isSoundFxPlaying()) fxMusicPlayer?.stop()
        } catch (ex: Exception) {
            ex.printStackTrace()
        }
    }

    fun pause() {
        try {
            if (isPlaying()) backgroundMusicPlayer?.pause()
        } catch (ex: Exception) {
            ex.printStackTrace()
        }
    }

    fun resume() {
        try {
            backgroundMusicPlayer?.play()
        } catch (ex: Exception) {
            ex.printStackTrace()
        }

    }

    fun releaseBackgroundMusic() {
        try {
            backgroundMusicPlayer?.let { player ->
                if (player.isPlaying) player.stop()
                player.release()
                backgroundMusicPlayer = null
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun releaseFxMusic() {
        try {
            if (fxMusicPlayer?.isPlaying == true) {
                fxMusicPlayer?.stop()
            }
            fxMusicPlayer?.release()
            fxMusicPlayer = null
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun isPlaying() = backgroundMusicPlayer?.isPlaying ?: false
    fun isSoundFxPlaying() = fxMusicPlayer?.isPlaying ?: false
}
