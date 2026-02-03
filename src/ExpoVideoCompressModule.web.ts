import { registerWebModule, NativeModule } from 'expo';

import { ExpoVideoCompressModuleEvents } from './ExpoVideoCompress.types';

class ExpoVideoCompressModule extends NativeModule<ExpoVideoCompressModuleEvents> {
  PI = Math.PI;
  async setValueAsync(value: string): Promise<void> {
    this.emit('onChange', { value });
  }
  hello() {
    return 'Hello world! 👋';
  }
}

export default registerWebModule(ExpoVideoCompressModule, 'ExpoVideoCompressModule');
