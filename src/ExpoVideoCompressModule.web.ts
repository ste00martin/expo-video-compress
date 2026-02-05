import { registerWebModule, NativeModule } from 'expo';

class ExpoVideoCompressModule extends NativeModule {
  async trimVideo(_videoPath: string): Promise<string> {
    throw new Error('trimVideo is not supported on web');
  }
}

export default registerWebModule(ExpoVideoCompressModule, 'ExpoVideoCompressModule');
