import { requireNativeView } from 'expo';
import * as React from 'react';

import { ExpoVideoCompressViewProps } from './ExpoVideoCompress.types';

const NativeView: React.ComponentType<ExpoVideoCompressViewProps> =
  requireNativeView('ExpoVideoCompress');

export default function ExpoVideoCompressView(props: ExpoVideoCompressViewProps) {
  return <NativeView {...props} />;
}
