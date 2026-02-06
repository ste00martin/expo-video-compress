package expo.modules.videocompress

import android.content.Context
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.transformer.Composition
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.EditedMediaItemSequence
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.Transformer
import expo.modules.kotlin.Promise
import expo.modules.kotlin.exception.Exceptions
import expo.modules.kotlin.modules.Module
import expo.modules.kotlin.modules.ModuleDefinition
import java.io.File
import java.util.UUID

class ExpoVideoCompressModule : Module() {
  companion object {
    private const val TAG = "ExpoVideoCompress"

    fun buildResult(uri: String, trimmedStartSeconds: Double, convertedToHevc: Boolean, duration: Double): Map<String, Any> {
      return mapOf(
        "uri" to uri,
        "trimmedStartSeconds" to trimmedStartSeconds,
        "convertedToHevc" to convertedToHevc,
        "duration" to duration
      )
    }

    @OptIn(UnstableApi::class)
    fun getExportErrorCodeName(errorCode: Int): String {
      return when (errorCode) {
        ExportException.ERROR_CODE_UNSPECIFIED -> "ERROR_CODE_UNSPECIFIED"
        ExportException.ERROR_CODE_IO_UNSPECIFIED -> "ERROR_CODE_IO_UNSPECIFIED"
        ExportException.ERROR_CODE_IO_FILE_NOT_FOUND -> "ERROR_CODE_IO_FILE_NOT_FOUND"
        ExportException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED -> "ERROR_CODE_IO_NETWORK_CONNECTION_FAILED"
        ExportException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT -> "ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT"
        ExportException.ERROR_CODE_DECODER_INIT_FAILED -> "ERROR_CODE_DECODER_INIT_FAILED"
        ExportException.ERROR_CODE_DECODING_FAILED -> "ERROR_CODE_DECODING_FAILED"
        ExportException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED -> "ERROR_CODE_DECODING_FORMAT_UNSUPPORTED"
        ExportException.ERROR_CODE_ENCODER_INIT_FAILED -> "ERROR_CODE_ENCODER_INIT_FAILED"
        ExportException.ERROR_CODE_ENCODING_FAILED -> "ERROR_CODE_ENCODING_FAILED"
        ExportException.ERROR_CODE_ENCODING_FORMAT_UNSUPPORTED -> "ERROR_CODE_ENCODING_FORMAT_UNSUPPORTED"
        ExportException.ERROR_CODE_VIDEO_FRAME_PROCESSING_FAILED -> "ERROR_CODE_VIDEO_FRAME_PROCESSING_FAILED"
        ExportException.ERROR_CODE_AUDIO_PROCESSING_FAILED -> "ERROR_CODE_AUDIO_PROCESSING_FAILED"
        ExportException.ERROR_CODE_MUXING_FAILED -> "ERROR_CODE_MUXING_FAILED"
        ExportException.ERROR_CODE_MUXING_TIMEOUT -> "ERROR_CODE_MUXING_TIMEOUT"
        else -> "UNKNOWN_ERROR_CODE($errorCode)"
      }
    }
  }

  private val context: Context
    get() = appContext.reactContext ?: throw Exceptions.AppContextLost()

  @OptIn(UnstableApi::class)
  override fun definition() = ModuleDefinition {
    Name("ExpoVideoCompress")

    AsyncFunction("trimVideo") { videoPath: String, promise: Promise ->
      processTrim(videoPath, promise)
    }
  }

  @OptIn(UnstableApi::class)
  private fun processTrim(videoPath: String, promise: Promise) {
    val cleanVideoPath = videoPath.removePrefix("file://")

    // Step 1: Validate video file exists
    val videoFile = File(cleanVideoPath)
    if (!videoFile.exists()) {
      promise.reject("VIDEO_NOT_FOUND", "Video file not found at path: $cleanVideoPath", null)
      return
    }

    // Step 2: Find the first video frame's presentation timestamp
    val extractor = MediaExtractor()
    var firstPtsUs = 0L
    var videoMime = ""
    try {
      extractor.setDataSource(cleanVideoPath)
      for (i in 0 until extractor.trackCount) {
        val format = extractor.getTrackFormat(i)
        val mime = format.getString(MediaFormat.KEY_MIME) ?: continue
        if (mime.startsWith("video/")) {
          videoMime = mime
          extractor.selectTrack(i)
          firstPtsUs = extractor.sampleTime // microseconds
          break
        }
      }
    } catch (e: Exception) {
      promise.reject("METADATA_ERROR", "Failed to read video metadata: ${e.message}", e)
      return
    } finally {
      extractor.release()
    }

    val needsTrim = firstPtsUs > 0
    val needsHevcEncode = videoMime != MediaFormat.MIMETYPE_VIDEO_HEVC
    Log.d(TAG, "First video frame PTS: ${firstPtsUs}us (${firstPtsUs / 1000}ms), codec: $videoMime, needsTrim: $needsTrim, needsHevcEncode: $needsHevcEncode")

    val trimmedStartSeconds = if (needsTrim) firstPtsUs / 1_000_000.0 else 0.0

    // If no processing needed, return the original path
    if (!needsTrim && !needsHevcEncode) {
      Log.d(TAG, "No processing needed, returning original")
      val retrieverForDuration = MediaMetadataRetriever()
      var durationSeconds = 0.0
      try {
        retrieverForDuration.setDataSource(cleanVideoPath)
        val durationMs = retrieverForDuration.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
        durationSeconds = Math.round(durationMs.toDouble()) / 1000.0
      } catch (e: Exception) {
        Log.w(TAG, "Failed to read duration: ${e.message}")
      } finally {
        retrieverForDuration.release()
      }
      promise.resolve(buildResult(videoPath, 0.0, false, durationSeconds))
      return
    }

    // Step 3: Check for HDR
    val retriever = MediaMetadataRetriever()
    var isHdr = false
    try {
      retriever.setDataSource(cleanVideoPath)
      val colorTransfer = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_COLOR_TRANSFER)
      val colorTransferInt = colorTransfer?.toIntOrNull()
      isHdr = colorTransferInt == 6 || colorTransferInt == 7
    } catch (e: Exception) {
      Log.w(TAG, "Failed to check HDR status: ${e.message}")
    } finally {
      retriever.release()
    }

    // Step 4: Generate output path in cache directory
    val outputFile = File(context.cacheDir, "processed_${UUID.randomUUID()}.mp4")
    val cleanOutputPath = outputFile.absolutePath
    if (outputFile.exists()) {
      outputFile.delete()
    }

    // Step 5: Create MediaItem, with clipping if trimming is needed
    val mediaItemBuilder = MediaItem.Builder()
      .setUri("file://$cleanVideoPath")

    if (needsTrim) {
      val firstPtsMs = firstPtsUs / 1000
      Log.d(TAG, "Clipping video starting at ${firstPtsMs}ms to remove leading dead time")
      mediaItemBuilder.setClippingConfiguration(
        MediaItem.ClippingConfiguration.Builder()
          .setStartPositionMs(firstPtsMs)
          .build()
      )
    }

    val mediaItem = mediaItemBuilder.build()

    val editedMediaItem = try {
      EditedMediaItem.Builder(mediaItem).build()
    } catch (e: Exception) {
      promise.reject("EDITED_MEDIA_ERROR", "Failed to create edited media item: ${e.message} [trimmedStartSeconds=$trimmedStartSeconds, convertedToHevc=$needsHevcEncode]", e)
      return
    }

    // Step 6: Create composition
    val composition: Composition = try {
      val sequence = EditedMediaItemSequence.Builder(listOf(editedMediaItem)).build()
      val compositionBuilder = Composition.Builder(listOf(sequence))
      if (isHdr) {
        Log.d(TAG, "HDR video detected. Applying OpenGL-based tone-mapping.")
        compositionBuilder.setHdrMode(Composition.HDR_MODE_TONE_MAP_HDR_TO_SDR_USING_OPEN_GL)
      }
      compositionBuilder.build()
    } catch (e: Exception) {
      promise.reject("COMPOSITION_ERROR", "Failed to create composition: ${e.message} [trimmedStartSeconds=$trimmedStartSeconds, convertedToHevc=$needsHevcEncode]", e)
      return
    }

    // Step 7: Build and start transformer on main thread (required by Media3)
    val mainHandler = Handler(Looper.getMainLooper())
    mainHandler.post {
      try {
        val transformerBuilder = Transformer.Builder(context)
        if (needsHevcEncode) {
          Log.d(TAG, "Encoding to H.265/HEVC")
          transformerBuilder.setVideoMimeType(MediaFormat.MIMETYPE_VIDEO_HEVC)
        }
        val transformer = transformerBuilder
          .addListener(object : Transformer.Listener {
            override fun onCompleted(composition: Composition, exportResult: ExportResult) {
              val durationSeconds = Math.round(exportResult.durationMs.toDouble()) / 1000.0
              Log.d(TAG, "Trim completed - duration: ${exportResult.durationMs}ms, " +
                "size: ${exportResult.fileSizeBytes} bytes, " +
                "frames: ${exportResult.videoFrameCount}, " +
                "trimmedStartSeconds: $trimmedStartSeconds, convertedToHevc: $needsHevcEncode")
              promise.resolve(buildResult("file://$cleanOutputPath", trimmedStartSeconds, needsHevcEncode, durationSeconds))
            }

            override fun onError(
              composition: Composition,
              exportResult: ExportResult,
              exportException: ExportException
            ) {
              val errorCodeName = getExportErrorCodeName(exportException.errorCode)
              Log.e(TAG, "Transform failed: $errorCodeName - ${exportException.message}")
              Log.e(TAG, Log.getStackTraceString(exportException))

              promise.reject(
                "TRANSFORM_ERROR",
                "Transform failed - ErrorCode: $errorCodeName, Message: ${exportException.message ?: "Unknown error"} [trimmedStartSeconds=$trimmedStartSeconds, convertedToHevc=$needsHevcEncode]",
                exportException
              )
            }
          })
          .build()

        Log.d(TAG, "Starting transform (trim: $needsTrim, hevc: $needsHevcEncode)...")
        transformer.start(composition, cleanOutputPath)
      } catch (e: Exception) {
        Log.e(TAG, "Exception building/starting transformer", e)
        promise.reject(
          "TRANSFORMER_BUILD_ERROR",
          "Failed to build/start transformer: ${e.message} [trimmedStartSeconds=$trimmedStartSeconds, convertedToHevc=$needsHevcEncode]",
          e
        )
      }
    }
  }
}
