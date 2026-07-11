package hoang.dqm.codebase.service.sound

import android.annotation.SuppressLint
import android.media.AudioAttributes
import android.media.SoundPool
import hoang.dqm.codebase.R
import hoang.dqm.codebase.base.application.getBaseApplication
import hoang.dqm.codebase.service.session.isMusic
import hoang.dqm.codebase.service.session.isSound
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

sealed class SoundType(val res: Int) {
    data object Click : SoundType(R.raw.sound_click)
}

class SoundManager private constructor() {

    private var sp: SoundPool? = null
    private val sounds by lazy {
        listOf(
            SoundType.Click
        )
    }
    private val _map = mutableMapOf<SoundType, Int>()
    private val audioAttributes by lazy {
        AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build()
    }

    private val externalScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    companion object {
        @SuppressLint("StaticFieldLeak")
        @Volatile
        private var instance: SoundManager? = null

        fun getInstance(): SoundManager = instance ?: synchronized(this) {
            instance ?: SoundManager().also { instance = it }
        }
    }

    /**
     * Load sounds into SoundPool only once during app initialization.
     */
    fun loadSoundOnce() {
        sp = SoundPool.Builder().setAudioAttributes(audioAttributes).setMaxStreams(4).build()
        externalScope.launch(CoroutineExceptionHandler { _, throwable ->
            throwable.printStackTrace()
        }) {
            sounds.forEach { soundType ->
                sp?.load(getBaseApplication(), soundType.res, 1)?.let { soundId ->
                    _map[soundType] = soundId
                }
            }
        }
    }

    /**
     * Play the sound if it's loaded and music is enabled.
     */
    fun play(sound: SoundType, loop: Int = 0) {
        try {
            _map[sound]?.let { soundId ->
                if (isMusic()) {
                    sp?.play(soundId, 1f, 1f, 0, loop, 1f)
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    /**
     * Play the sound fx if it's loaded and music is enabled.
     */
    fun playSoundFx(sound: SoundType, loop: Int = 0) {
        try {
            _map[sound]?.let { soundId ->
                if (isSound()) {
                    sp?.play(soundId, 1f, 1f, 0, loop, 1f)
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    /**
     * Pause all currently playing sounds.
     */
    fun pause() {
        try {
            sp?.autoPause()
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun pause(sound: SoundType) {
        try {
            sp?.pause(sound.res)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun stop(sound: SoundType) {
        try {
            sp?.stop(sound.res)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    /**
     * Resume all paused sounds.
     */
    fun resume() {
        try {
            sp?.autoResume()
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun resume(sound: SoundType) {
        try {
            sp?.autoPause()
            sp?.autoResume()
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    /**
     * Release resources when no longer needed.
     */
    fun destroy() {
        try {
            _map.clear()
            sp?.release()
            externalScope.cancel()
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
}
