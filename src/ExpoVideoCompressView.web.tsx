import * as React from 'react';

import { ExpoVideoCompressViewProps } from './ExpoVideoCompress.types';

export default function ExpoVideoCompressView(props: ExpoVideoCompressViewProps) {
  return (
    <div>
      <iframe
        style={{ flex: 1 }}
        src={props.url}
        onLoad={() => props.onLoad({ nativeEvent: { url: props.url } })}
      />
    </div>
  );
}
