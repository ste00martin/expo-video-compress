# expo-video-compress

An Expo native module that processes videos recorded on device:

1. **Trims leading dead frames** — Many device cameras produce videos where the first frame's presentation timestamp (PTS) is not at 0. This module detects that offset and trims the beginning so the first valid frame starts at t=0.
2. **Converts H.264 to H.265/HEVC** (Android only) — If the source video is not already HEVC-encoded, it re-encodes to H.265 for smaller file sizes. iOS devices record in HEVC natively so no conversion is needed.
3. **HDR tone-mapping** (Android only) — HDR videos are tone-mapped to SDR using OpenGL.

## API

### `trimVideo(videoPath: string): Promise<VideoCompressResult>`

Processes a video and returns metadata about what was done.

**Parameters:**
- `videoPath` — A local `file://` URI pointing to the source video.

**Returns** a `VideoCompressResult`:

```typescript
type VideoCompressResult = {
  uri: string;                 // file:// URI of the processed (or original) video
  trimmedStartSeconds: number; // seconds trimmed from the beginning (0 if no trim was needed)
  convertedToHevc: boolean;    // true if the video was re-encoded from H.264 to H.265
};
```

If no processing is needed (first frame is already at t=0 and codec is already HEVC), the original `videoPath` is returned as `uri` with `trimmedStartSeconds: 0` and `convertedToHevc: false`.

**Errors** include `trimmedStartSeconds` and `convertedToHevc` in the error message when those values are known at the point of failure.

### Usage

```typescript
import ExpoVideoCompress from 'expo-video-compress';

const result = await ExpoVideoCompress.trimVideo(videoUri);
console.log(result.uri);                 // "file:///path/to/processed.mp4"
console.log(result.trimmedStartSeconds); // e.g. 0.033
console.log(result.convertedToHevc);     // true on Android if source was H.264
```

## Installation

### Managed Expo projects

```
npx expo install expo-video-compress
```

### Bare React Native projects

Ensure you have [installed and configured the `expo` package](https://docs.expo.dev/bare/installing-expo-modules/) first.

```
npm install expo-video-compress
```

Then run `npx pod-install` for iOS.

## Platform support

| Feature | iOS | Android | Web |
|---|---|---|---|
| Trim leading frames | Yes | Yes | No |
| H.264 to H.265 | No (not needed) | Yes | No |
| HDR tone-mapping | No | Yes | No |

**Minimum versions:** iOS 15.1, Android SDK 33.
