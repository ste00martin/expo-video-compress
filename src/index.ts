// Reexport the native module. On web, it will be resolved to ExpoVideoCompressModule.web.ts
// and on native platforms to ExpoVideoCompressModule.ts
export { default } from './ExpoVideoCompressModule';
export { default as ExpoVideoCompressView } from './ExpoVideoCompressView';
export * from  './ExpoVideoCompress.types';
