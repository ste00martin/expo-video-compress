import { registerWebModule, NativeModule } from 'expo';
import type { VideoCompressResult } from './ExpoVideoCompressModule';

class ExpoVideoCompressModule extends NativeModule {
  async trimVideo(_videoPath: string): Promise<VideoCompressResult> {
    throw new Error('trimVideo is not supported on web');
  }
}

export default registerWebModule(ExpoVideoCompressModule, 'ExpoVideoCompressModule');
