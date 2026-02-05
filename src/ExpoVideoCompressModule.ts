import { NativeModule, requireNativeModule } from 'expo';

declare class ExpoVideoCompressModule extends NativeModule {
  /**
   * Trims a video so that its first frame starts at timestamp 0.
   * @param videoPath Local file path (file:// URI) to the source video
   * @returns A promise that resolves with the file:// URI of the trimmed video
   */
  trimVideo(videoPath: string): Promise<string>;
}

export default requireNativeModule<ExpoVideoCompressModule>('ExpoVideoCompress');
