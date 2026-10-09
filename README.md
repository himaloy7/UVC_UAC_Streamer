# USBCameraTest — USB UVC Camera Streaming & Recording for Android

A production-oriented Android application that captures video from USB UVC
cameras (tested with the MS2109 chipset), previews them on screen, streams
them over RTMP, and records them to MP4 — with proper A/V synchronization
and support for multiple resolutions and native formats.

Built on top of the [saki4510t / alexey-pelykh UVCCamera](https://github.com/alexey-pelykh/UVCCamera)
library, extended with a hardware-accelerated H.264 encoding pipeline,
libjpeg-turbo integration for MJPEG decoding, and a resolution-aware
conversion layer.

---

## Features

- **Live USB camera preview** via `TextureView` / `UVCCameraTextureView`
- **RTMP streaming** through `pedroSG94/rtmp-rtsp-stream-client-java`
- **MP4 recording** with muxed H.264 video + AAC audio
- **Wall-clock A/V synchronization** — video and audio PTS are derived from
  a shared `System.nanoTime()` reference, so lip-sync stays correct even when
  the encoder drops or delays frames
- **Hardware H.264 encoder** with automatic fallback to software
- **Native MJPEG → NV12 decoding** via a small JNI wrapper around
  `libjpeg-turbo`
- **Adaptive format handling** — MJPEG for ≥1080p sources, YUYV for lower
  resolutions, routed automatically
- **Encoder stride/padding handling** — properly pads NV12 to the encoder's
  16-byte-aligned stride (1080 → 1088) to eliminate the classic green bar
  at the bottom of 1080p recordings
- **Runtime camera switching** between internal (back/front) and USB cameras
- **Configurable resolution / FPS / bitrate** through an in-app settings dialog
- **USB hotplug handling** — automatically switches preview and audio source
  when the USB camera is attached or detached
- **16 KB page size compatibility** (Android 15+)
- **Runtime permission handling** for `CAMERA` and `RECORD_AUDIO`

---
