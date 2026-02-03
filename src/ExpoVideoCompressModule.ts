import { NativeModule, requireNativeModule } from 'expo';

import { ExpoVideoCompressModuleEvents } from './ExpoVideoCompress.types';

declare class ExpoVideoCompressModule extends NativeModule<ExpoVideoCompressModuleEvents> {
  PI: number;
  hello(): string;
  setValueAsync(value: string): Promise<void>;
}

// This call loads the native module object from the JSI.
export default requireNativeModule<ExpoVideoCompressModule>('ExpoVideoCompress');
