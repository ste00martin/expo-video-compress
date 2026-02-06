import { NativeModule, requireNativeModule } from 'expo';

export type VideoCompressResult = {
  uri: string;
  trimmedStartSeconds: number;
  convertedToHevc: boolean;
  duration: number;
};

declare class ExpoVideoCompressModule extends NativeModule {
  /**
   * Trims a video so that its first frame starts at timestamp 0,
   * and converts non-HEVC videos to H.265 on Android.
   * @param videoPath Local file path (file:// URI) to the source video
   * @returns A promise that resolves with an object containing the output URI and processing metadata
   */
  trimVideo(videoPath: string): Promise<VideoCompressResult>;
}

export default requireNativeModule<ExpoVideoCompressModule>('ExpoVideoCompress');
