package com.tvchromecast.screenmirroringplus.ui.cast_media

import android.content.Context
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import android.util.Log
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.FrameDropEffect
import androidx.media3.effect.Presentation
import androidx.media3.transformer.DefaultEncoderFactory
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.Effects
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.Transformer
import androidx.media3.transformer.VideoEncoderSettings
import java.io.File
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine

@UnstableApi
internal object CastMediaVideoPreparer {
    private const val TAG = "CastMediaVideoPrep"
    private const val MAX_CAST_WIDTH = 1920
    private const val MAX_CAST_HEIGHT = 1080
    private const val MAX_CAST_FRAME_RATE = 30
    private const val MAX_CAST_BITRATE = 5_000_000
    private const val TARGET_CAST_BITRATE = 4_000_000

    fun inspect(context: Context, uri: Uri): CastVideoTrackInfo {
        val extractor = MediaExtractor()
        return try {
            extractor.setDataSource(context, uri, null)
            var videoTrack: TrackInfo? = null
            var audioTrack: TrackInfo? = null

            for (index in 0 until extractor.trackCount) {
                val format = extractor.getTrackFormat(index)
                val mime = format.getString(MediaFormat.KEY_MIME).orEmpty()
                when {
                    mime.startsWith("video/") && videoTrack == null -> {
                        videoTrack = TrackInfo(
                            mime = mime,
                            width = format.optionalInteger(MediaFormat.KEY_WIDTH),
                            height = format.optionalInteger(MediaFormat.KEY_HEIGHT),
                            frameRate = format.optionalInteger(MediaFormat.KEY_FRAME_RATE),
                            bitrate = format.optionalInteger(MediaFormat.KEY_BIT_RATE),
                            rotationDegrees = format.optionalInteger(MediaFormat.KEY_ROTATION) ?: 0,
                            durationMs = format.optionalLong(MediaFormat.KEY_DURATION)
                                ?.takeIf { it > 0L }
                                ?.div(1000L)
                        )
                    }

                    mime.startsWith("audio/") && audioTrack == null -> {
                        audioTrack = TrackInfo(mime = mime)
                    }
                }
            }

            CastVideoTrackInfo(video = videoTrack, audio = audioTrack)
        } finally {
            extractor.release()
        }
    }

    suspend fun transformForCast(
        context: Context,
        uri: Uri,
        displayName: String,
        outputHeight: Int,
        onCancelReady: (() -> Unit) -> Unit
    ): File = suspendCancellableCoroutine { continuation ->
        val outputFile = createOutputFile(context, displayName)
        val inputMediaItem = MediaItem.fromUri(uri)
        val editedMediaItem = EditedMediaItem.Builder(inputMediaItem)
            .setEffects(
                Effects(
                    emptyList(),
                    listOf(
                        Presentation.createForHeight(outputHeight.coerceIn(1, MAX_CAST_HEIGHT)),
                        FrameDropEffect.createDefaultFrameDropEffect(MAX_CAST_FRAME_RATE.toFloat())
                    )
                )
            )
            .build()
        val encoderFactory = DefaultEncoderFactory.Builder(context)
            .setRequestedVideoEncoderSettings(
                VideoEncoderSettings.Builder()
                    .setBitrate(TARGET_CAST_BITRATE)
                    .setiFrameIntervalSeconds(2f)
                    .setMaxBFrames(0)
                    .build()
            )
            .setEnableFallback(true)
            .build()

        val transformer = Transformer.Builder(context)
            .setVideoMimeType(MimeTypes.VIDEO_H264)
            .setAudioMimeType(MimeTypes.AUDIO_AAC)
            .setEncoderFactory(encoderFactory)
            .setPortraitEncodingEnabled(true)
            .addListener(
                object : Transformer.Listener {
                    override fun onCompleted(
                        composition: androidx.media3.transformer.Composition,
                        exportResult: ExportResult
                    ) {
                        Log.i(
                            TAG,
                            "Video transform completed: output=${outputFile.absolutePath} " +
                                "sizeBytes=${outputFile.length()} result=$exportResult"
                        )
                        if (!continuation.isActive) return
                        if (outputFile.length() > 0L) {
                            continuation.resume(outputFile)
                        } else {
                            continuation.resumeWithException(
                                IllegalStateException("Transformer produced an empty output file")
                            )
                        }
                    }

                    override fun onError(
                        composition: androidx.media3.transformer.Composition,
                        exportResult: ExportResult,
                        exportException: ExportException
                    ) {
                        outputFile.delete()
                        Log.e(TAG, "Video transform failed: result=$exportResult", exportException)
                        if (!continuation.isActive) return
                        continuation.resumeWithException(exportException)
                    }
                }
            )
            .build()

        continuation.invokeOnCancellation {
            transformer.cancel()
            outputFile.delete()
        }

        onCancelReady { transformer.cancel() }
        Log.i(TAG, "Starting video transform: input=$uri output=${outputFile.absolutePath}")
        transformer.start(editedMediaItem, outputFile.absolutePath)
    }

    private fun createOutputFile(context: Context, displayName: String): File {
        val cleanName = displayName
            .substringBeforeLast('.', missingDelimiterValue = displayName)
            .replace(Regex("""[^A-Za-z0-9._-]+"""), "_")
            .take(48)
            .ifBlank { "cast_video" }
        val outputDir = File(context.cacheDir, "cast_transcoded").apply { mkdirs() }
        return File(outputDir, "${cleanName}_${System.currentTimeMillis()}.mp4")
    }

    private fun MediaFormat.optionalInteger(key: String): Int? {
        return if (containsKey(key)) runCatching { getInteger(key) }.getOrNull() else null
    }

    private fun MediaFormat.optionalLong(key: String): Long? {
        return if (containsKey(key)) runCatching { getLong(key) }.getOrNull() else null
    }

    data class CastVideoTrackInfo(
        val video: TrackInfo?,
        val audio: TrackInfo?
    ) {
        val isReadyForCast: Boolean
            get() {
                val videoTrack = video ?: return false
                val width = videoTrack.width ?: return false
                val height = videoTrack.height ?: return false
                val audioMime = audio?.mime
                return videoTrack.mime == MimeTypes.VIDEO_H264 &&
                    width <= MAX_CAST_WIDTH &&
                    height <= MAX_CAST_HEIGHT &&
                    (videoTrack.frameRate == null || videoTrack.frameRate <= MAX_CAST_FRAME_RATE) &&
                    (videoTrack.bitrate == null || videoTrack.bitrate <= MAX_CAST_BITRATE) &&
                    (audioMime == null || audioMime == MimeTypes.AUDIO_AAC)
            }

        val transformOutputHeight: Int
            get() = (video?.height ?: MAX_CAST_HEIGHT).coerceIn(1, MAX_CAST_HEIGHT)

        fun toDebugString(): String {
            return "video=$video audio=$audio readyForCast=$isReadyForCast"
        }
    }

    data class TrackInfo(
        val mime: String,
        val width: Int? = null,
        val height: Int? = null,
        val frameRate: Int? = null,
        val bitrate: Int? = null,
        val rotationDegrees: Int = 0,
        val durationMs: Long? = null
    )
}
