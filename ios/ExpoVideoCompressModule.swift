import ExpoModulesCore
import AVFoundation

public class ExpoVideoCompressModule: Module {
  public func definition() -> ModuleDefinition {
    Name("ExpoVideoCompress")

    AsyncFunction("trimVideo") { (videoPath: String, promise: Promise) in
      DispatchQueue.global(qos: .userInitiated).async {
        self.processTrim(videoPath: videoPath, promise: promise)
      }
    }
  }

  private func processTrim(videoPath: String, promise: Promise) {
    // Strip file:// prefix if present
    let cleanVideoPath = videoPath.hasPrefix("file://") ? String(videoPath.dropFirst(7)) : videoPath

    // Validate video file exists
    guard FileManager.default.fileExists(atPath: cleanVideoPath) else {
      promise.reject("VIDEO_NOT_FOUND", "Video file not found at path: \(cleanVideoPath)")
      return
    }

    let videoURL = URL(fileURLWithPath: cleanVideoPath)
    let asset = AVURLAsset(url: videoURL)

    // Get video track
    guard let videoTrack = asset.tracks(withMediaType: .video).first else {
      promise.reject("INVALID_VIDEO", "No video track found")
      return
    }

    // Find the presentation time of the first video sample using AVAssetReader
    guard let reader = try? AVAssetReader(asset: asset) else {
      promise.reject("READER_ERROR", "Failed to create asset reader")
      return
    }

    let readerOutput = AVAssetReaderTrackOutput(track: videoTrack, outputSettings: nil)
    readerOutput.alwaysCopiesSampleData = false
    reader.add(readerOutput)
    reader.startReading()

    var firstSampleTime = CMTime.zero
    if let sampleBuffer = readerOutput.copyNextSampleBuffer() {
      firstSampleTime = CMSampleBufferGetPresentationTimeStamp(sampleBuffer)
    }
    reader.cancelReading()

    let trimmedStartSeconds = max(firstSampleTime.seconds, 0)

    // If first frame is already at timestamp 0, return the original path
    if firstSampleTime == .zero || firstSampleTime.seconds <= 0 {
      promise.resolve([
        "uri": videoPath,
        "trimmedStartSeconds": 0.0,
        "convertedToHevc": false
      ])
      return
    }

    // Trim from the first sample time to the end of the video
    let duration = asset.duration
    let trimmedDuration = CMTimeSubtract(duration, firstSampleTime)
    let timeRange = CMTimeRange(start: firstSampleTime, duration: trimmedDuration)

    // Create composition
    let composition = AVMutableComposition()

    guard let compositionVideoTrack = composition.addMutableTrack(
      withMediaType: .video,
      preferredTrackID: kCMPersistentTrackID_Invalid
    ) else {
      promise.reject("COMPOSITION_ERROR", "Failed to create video composition track")
      return
    }

    do {
      // Insert trimmed video track at time zero
      try compositionVideoTrack.insertTimeRange(timeRange, of: videoTrack, at: .zero)

      // Handle audio track if present
      if let audioTrack = asset.tracks(withMediaType: .audio).first,
         let compositionAudioTrack = composition.addMutableTrack(
           withMediaType: .audio,
           preferredTrackID: kCMPersistentTrackID_Invalid
         ) {
        try compositionAudioTrack.insertTimeRange(timeRange, of: audioTrack, at: .zero)
      }
    } catch {
      promise.reject("COMPOSITION_ERROR", "Failed to insert tracks: \(error.localizedDescription) [trimmedStartSeconds=\(trimmedStartSeconds), convertedToHevc=false]")
      return
    }

    // Handle video rotation
    let videoSize = self.naturalSizeForTrack(videoTrack)

    let videoComposition = AVMutableVideoComposition()
    videoComposition.renderSize = videoSize

    let fps = videoTrack.nominalFrameRate
    videoComposition.frameDuration = fps > 0
      ? CMTime(value: 1, timescale: CMTimeScale(ceil(fps)))
      : CMTime(value: 1, timescale: 30)

    let instruction = AVMutableVideoCompositionInstruction()
    instruction.timeRange = CMTimeRange(start: .zero, duration: trimmedDuration)

    let layerInstruction = AVMutableVideoCompositionLayerInstruction(assetTrack: compositionVideoTrack)
    layerInstruction.setTransform(self.transformForTrack(videoTrack), at: .zero)

    instruction.layerInstructions = [layerInstruction]
    videoComposition.instructions = [instruction]

    // Generate output path in temp directory
    let outputURL = FileManager.default.temporaryDirectory
      .appendingPathComponent(UUID().uuidString)
      .appendingPathExtension("mp4")

    try? FileManager.default.removeItem(at: outputURL)

    guard let exportSession = AVAssetExportSession(
      asset: composition,
      presetName: AVAssetExportPresetHighestQuality
    ) else {
      promise.reject("EXPORT_ERROR", "Could not create export session [trimmedStartSeconds=\(trimmedStartSeconds), convertedToHevc=false]")
      return
    }

    exportSession.outputURL = outputURL
    exportSession.outputFileType = .mp4
    exportSession.videoComposition = videoComposition

    exportSession.exportAsynchronously {
      switch exportSession.status {
      case .completed:
        promise.resolve([
          "uri": "file://\(outputURL.path)",
          "trimmedStartSeconds": trimmedStartSeconds,
          "convertedToHevc": false
        ])
      case .failed:
        let errorMessage = exportSession.error?.localizedDescription ?? "Unknown error"
        promise.reject("EXPORT_FAILED", "Video export failed: \(errorMessage) [trimmedStartSeconds=\(trimmedStartSeconds), convertedToHevc=false]")
      case .cancelled:
        promise.reject("EXPORT_CANCELLED", "Video export was cancelled [trimmedStartSeconds=\(trimmedStartSeconds), convertedToHevc=false]")
      default:
        promise.reject("EXPORT_ERROR", "Export ended with status: \(exportSession.status.rawValue) [trimmedStartSeconds=\(trimmedStartSeconds), convertedToHevc=false]")
      }
    }
  }

  // Calculate the natural size accounting for video transform/rotation
  private func naturalSizeForTrack(_ track: AVAssetTrack) -> CGSize {
    let size = track.naturalSize
    let transform = track.preferredTransform

    // Check if video is rotated 90 or 270 degrees
    if abs(transform.a) == 0 && abs(transform.d) == 0 {
      return CGSize(width: size.height, height: size.width)
    }
    return size
  }

  // Get the transform needed to render the video correctly
  private func transformForTrack(_ track: AVAssetTrack) -> CGAffineTransform {
    let size = track.naturalSize
    let transform = track.preferredTransform

    if transform.a == 0 && transform.d == 0 {
      if transform.b == 1.0 && transform.c == -1.0 {
        // 90 degrees rotation
        return CGAffineTransform(translationX: size.height, y: 0).rotated(by: .pi / 2)
      } else if transform.b == -1.0 && transform.c == 1.0 {
        // 270 degrees rotation
        return CGAffineTransform(translationX: 0, y: size.width).rotated(by: -.pi / 2)
      }
    } else if transform.a == -1.0 && transform.d == -1.0 {
      // 180 degrees rotation
      return CGAffineTransform(translationX: size.width, y: size.height).rotated(by: .pi)
    }

    return .identity
  }
}
