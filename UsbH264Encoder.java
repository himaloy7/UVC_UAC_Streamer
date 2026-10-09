package com.serenegiant.usbcameratest.managers;

import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaCodecList;
import android.media.MediaFormat;
import android.os.Build;
import android.os.Bundle;
import android.util.Log;

import com.pedro.rtmp.rtmp.RtmpClient;

import java.nio.ByteBuffer;
import java.util.concurrent.atomic.AtomicLong;

public final class UsbH264Encoder {

    private static final String TAG = "UsbH264Encoder";

    // ✅ ADD THIS BLOCK
    static {
        try {
            System.loadLibrary("mjpeg-decoder");
            Log.i(TAG, "✅ mjpeg-decoder native library loaded");
        } catch (UnsatisfiedLinkError e) {
            Log.e(TAG, "Failed to load mjpeg-decoder: " + e.getMessage());
        }
    }

    // ✅ ADD THIS NATIVE METHOD DECLARATION
    private static native int nativeDecodeMjpeg(byte[] jpegData, int jpegSize, byte[] nv12Buffer, int width, int height);

    private int width;
    private int height;
    private int fps;
    private int inputWidth;
    private int inputHeight;
    private int inputFps;

    private MediaCodec videoEncoder;
    private MediaCodec audioEncoder;
    private RtmpClient rtmpClient;
    private RTMPManager rtmpManager;

    private byte[] nv12Buffer;

    private volatile boolean rtmpConnected = false;
    private String rtmpUrl;

    // SYNC FIX: Use atomic counters for frame-accurate PTS
    private final AtomicLong videoFrameCounter = new AtomicLong(0);
    private final AtomicLong audioFrameCounter = new AtomicLong(0);

    // Audio configuration
    private int audioSampleRate;
    private int audioBitrate;
    private int audioChannels;
    private long audioFrameDurationUs; // Microseconds per audio frame
    private long videoFrameDurationUs; // Microseconds per video frame

    // Timebase management
    private boolean isFirstVideoFrame = true;
    private boolean isFirstAudioFrame = true;
    private RecordingManager recordingManager;
    private boolean isRecording = false;
    private ByteBuffer cachedSps;
    private ByteBuffer cachedPps;
    private boolean hasCachedCodecData = false;

    // Add at the top with other fields
    private boolean pendingRecordingStart = false;
    private MediaFormat pendingVideoFormat;
    private MediaFormat pendingAudioFormat;
    private final Object recordingLock = new Object();
    private boolean recordingRequested = false;
    private int audioSamplesPerFrame = 1024; // AAC standard
    private int frameWriteCount = 0;

    // Add these fields for drift correction
    private long masterClockOffset = 0;
    private long lastVideoPts = -1;
    private long lastAudioPts = -1;
    private static final long MAX_DRIFT_US = 50000; // 50ms max allowed drift
    private long lastResyncTime = 0;
    private static final long RESYNC_INTERVAL_US = 60000000; // 1 minute
    private int frameCounter = 0;
    private static final int SYNC_CHECK_INTERVAL = 1000;
    private byte[] paddedBuffer;  // ✅ ADD THIS LINE

    // Add this setter method
    public void setRtmpManager(RTMPManager rtmpManager) {
        this.rtmpManager = rtmpManager;

        // Pre-configure immediately if we have audio parameters
        if (rtmpManager != null && audioSampleRate > 0) {
            rtmpManager.preConfigureAudio(audioSampleRate, audioChannels == 2);
            Log.i(TAG, "✅ RTMPManager pre-configured from UsbH264Encoder: " + audioSampleRate + "Hz");
        }
    }
    // Add setter
    public void setRecordingManager(RecordingManager recordingManager) {
        this.recordingManager = recordingManager;
    }

    // Add recording control
    public void startRecording() {
        synchronized (recordingLock) {
            if (isRecording) {
                Log.d(TAG, "Already recording, ignoring duplicate start");
                return;
            }

            Log.d(TAG, "=== START RECORDING CALLED ===");

            // Use the current audioSampleRate and audioChannels values
            int currentSampleRate = this.audioSampleRate;
            int currentChannels = this.audioChannels;

            // Completely recreate audio encoder for clean state
            if (audioEncoder != null) {
                try {
                    audioEncoder.stop();
                    audioEncoder.release();
                } catch (Exception e) {
                    Log.e(TAG, "Error stopping audio encoder: " + e.getMessage());
                }
                audioEncoder = null;
            }

            // Create new audio encoder with current parameters
            try {
                MediaFormat format = MediaFormat.createAudioFormat(
                        MediaFormat.MIMETYPE_AUDIO_AAC,
                        currentSampleRate,
                        currentChannels
                );
                format.setInteger(MediaFormat.KEY_BIT_RATE, audioBitrate);
                format.setInteger(MediaFormat.KEY_AAC_PROFILE,
                        MediaCodecInfo.CodecProfileLevel.AACObjectLC);
                format.setInteger(MediaFormat.KEY_CHANNEL_COUNT, currentChannels);
                format.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 4096);

                Log.i(TAG, "=== RECREATING AUDIO ENCODER ===");
                Log.i(TAG, "Sample rate: " + currentSampleRate);
                Log.i(TAG, "Channels: " + currentChannels);
                Log.i(TAG, "Bitrate: " + audioBitrate);
                Log.i(TAG, "================================");

                audioEncoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC);
                audioEncoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
                audioEncoder.start();

                Log.i(TAG, "Audio encoder recreated successfully with " + currentSampleRate + "Hz");
            } catch (Exception e) {
                Log.e(TAG, "Failed to recreate audio encoder: " + e.getMessage());
            }

            // Reset all counters
            videoFrameCounter.set(0);
            audioFrameCounter.set(0);
            isFirstVideoFrame = true;
            isFirstAudioFrame = true;
            masterClockOffset = 0;
            lastResyncTime = 0;

            // Force a keyframe to reset video
            requestKeyFrame();

            try {
                Thread.sleep(50);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }

            isRecording = true;
            pendingRecordingStart = true;

            // ✅ Check if video format already exists in cache
            if (hasCachedCodecData && pendingVideoFormat == null) {
                Log.i(TAG, "Video format already cached, using it for recording");
                pendingVideoFormat = createVideoFormatFromCache();
                if (pendingVideoFormat != null) {
                    Log.i(TAG, "✅ Using cached video format for recording");
                    // Check if we can start recording now
                    checkAndStartRecording();
                }
            }

            Log.i(TAG, "✅ Recording flag set with fresh audio encoder");
        }
    }

    private void checkSpsPpsStatus() {
        Log.d(TAG, "=== SPS/PPS STATUS ===");
        Log.d(TAG, "hasCachedCodecData: " + hasCachedCodecData);
        if (cachedSps != null) {
            Log.d(TAG, "cachedSps size: " + cachedSps.capacity() + ", remaining: " + cachedSps.remaining());
        }
        if (cachedPps != null) {
            Log.d(TAG, "cachedPps size: " + cachedPps.capacity() + ", remaining: " + cachedPps.remaining());
        }
    }

    public void requestKeyFrame() {
        if (videoEncoder != null) {
            try {
                Bundle params = new Bundle();
                params.putInt(MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME, 0);
                videoEncoder.setParameters(params);
                Log.d(TAG, "Keyframe requested");
            } catch (Exception e) {
                Log.e(TAG, "Failed to request keyframe: " + e.getMessage());
            }
        }
    }

    public void stopRecording() {
        synchronized (recordingLock) {
            if (recordingManager != null && recordingManager.isRecording()) {
                recordingManager.stopRecording();
            }
            isRecording = false;
            recordingRequested = false;
            pendingRecordingStart = false;

            // ✅ ADD THESE TWO LINES - Clear formats for next recording
            pendingVideoFormat = null;
            pendingAudioFormat = null;

            Log.i(TAG, "Recording stopped, formats cleared");
        }
    }

    public boolean isRecording() {
        return isRecording;
    }


    // In the constructor, update this line:
    public UsbH264Encoder(
            int inputWidth,
            int inputHeight,
            int inputFps,
            int outputWidth,
            int outputHeight,
            int outputFps,
            int audioSampleRate,
            int audioBitrate,
            int audioChannels
    ) throws Exception {
        // Force 48000 at construction
        if (audioSampleRate != 48000) {
            Log.w(TAG, "Constructor forcing sample rate from " + audioSampleRate + " to 48000 Hz");
            audioSampleRate = 48000;
        }

        this.inputWidth = inputWidth;
        this.inputHeight = inputHeight;
        this.width = outputWidth;
        this.height = outputHeight;
        this.fps = outputFps;
        this.audioSampleRate = audioSampleRate;
        this.audioBitrate = audioBitrate;
        this.audioChannels = audioChannels;

        // Calculate frame durations
        this.videoFrameDurationUs = 1_000_000 / fps;
        this.audioSamplesPerFrame = 1024;
        this.audioFrameDurationUs = (audioSamplesPerFrame * 1_000_000L) / audioSampleRate;

        // Calculate frame durations CORRECTLY
        this.videoFrameDurationUs = 1_000_000 / fps;
        this.audioSamplesPerFrame = 1024; // AAC always uses 1024 samples per frame
        this.audioFrameDurationUs = (audioSamplesPerFrame * 1_000_000L) / audioSampleRate;

        Log.d(TAG, "Video frame duration: " + videoFrameDurationUs + "us");
        Log.d(TAG, "Audio frame duration: " + audioFrameDurationUs + "us ("
                + audioSamplesPerFrame + " samples @ " + audioSampleRate + "Hz)");
        Log.d(TAG, "AUDIO CONFIG: rate=" + audioSampleRate + "Hz, channels=" + audioChannels +
                ", frameDuration=" + audioFrameDurationUs + "us");

        // ADD THIS DEBUG LOG
        Log.i(TAG, "========== AUDIO CONFIG ==========");
        Log.i(TAG, "Sample Rate: " + audioSampleRate + " Hz");
        Log.i(TAG, "Channels: " + audioChannels);
        Log.i(TAG, "Bitrate: " + audioBitrate + " bps");
        Log.i(TAG, "Frame Duration: " + audioFrameDurationUs + " us");
        Log.i(TAG, "Samples per frame: " + audioSamplesPerFrame);
        Log.i(TAG, "===================================");

        // With this:
        int alignedHeight = ((height + 15) / 16) * 16;
        // Allocate for actual size - the converter fills this
        nv12Buffer = new byte[width * height * 3 / 2];
        Log.d(TAG, "NV12 buffer: " + width + "x" + height + " (encoder expects " + width + "x" + alignedHeight + ")");

        setupVideoEncoder();
        setupAudioEncoder();
    }

    public MediaFormat getVideoFormat() {
        return videoEncoder != null ? videoEncoder.getOutputFormat() : null;
    }

    public MediaFormat getAudioFormat() {
        return audioEncoder != null ? audioEncoder.getOutputFormat() : null;
    }

    /* =========================
       HARDWARE ENCODER DETECTION
       ========================= */

    private MediaCodec createHardwareEncoder() throws Exception {
        // List of known hardware encoder names by chipset manufacturer
        String[] hardwareEncoders = {
                "OMX.qcom.video.encoder.avc",           // Qualcomm
                "OMX.Exynos.avc.encoder",              // Samsung Exynos
                "OMX.MTK.VIDEO.ENCODER.AVC",           // MediaTek
                "OMX.hisi.video.encoder.avc",          // HiSilicon
                "OMX.IMG.MSVDX.Encoder.avc",           // PowerVR
                "c2.android.avc.encoder",              // Android Codec2
                "OMX.Intel.hw_vd.h264",                // Intel
                "OMX.k3.video.encoder.avc",            // HiSilicon K3
                "OMX.Nvidia.h264.encoder",             // NVIDIA Tegra
                "OMX.amlogic.video.encoder.avc"        // Amlogic
        };

        // Try known hardware encoder names first (fastest method)
        for (String encoderName : hardwareEncoders) {
            try {
                MediaCodec codec = MediaCodec.createByCodecName(encoderName);
                Log.i(TAG, "✓ Using hardware encoder: " + encoderName);
                return codec;
            } catch (IllegalArgumentException e) {
                // Encoder not available on this device
                continue;
            } catch (Exception e) {
                Log.w(TAG, "Failed to create encoder " + encoderName + ": " + e.getMessage());
            }
        }

        // Fallback: Use MediaCodecList for detection (API 21+)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            try {
                MediaCodecList codecList = new MediaCodecList(MediaCodecList.REGULAR_CODECS);

                for (MediaCodecInfo info : codecList.getCodecInfos()) {
                    if (!info.isEncoder()) continue;

                    String[] types = info.getSupportedTypes();
                    for (String type : types) {
                        if (type.equalsIgnoreCase(MediaFormat.MIMETYPE_VIDEO_AVC)) {
                            String name = info.getName().toLowerCase();

                            // Check if it's likely a hardware encoder
                            boolean isHardware = false;

                            // Hardware encoder patterns
                            if (name.contains("qcom") || name.contains("exynos") ||
                                    name.contains("mali") || name.contains("mtk") ||
                                    name.contains("hisi") || name.contains("nvidia") ||
                                    name.contains("intel") || name.contains("amd") ||
                                    name.contains("power") || name.contains("img") ||
                                    name.contains("video") || name.contains("hw") ||
                                    name.contains("hardware") || name.contains("omx")) {
                                isHardware = true;
                            }

                            // Software encoder patterns (avoid these)
                            if (name.contains("google") || name.contains("sw") ||
                                    name.contains("software") || name.contains("avcdecoder") ||
                                    name.contains("c2.android")) {
                                isHardware = false;
                            }

                            if (isHardware) {
                                try {
                                    Log.i(TAG, "Trying hardware encoder: " + info.getName());
                                    return MediaCodec.createByCodecName(info.getName());
                                } catch (Exception e) {
                                    Log.w(TAG, "Failed to create encoder: " + info.getName());
                                }
                            }
                        }
                    }
                }
            } catch (Exception e) {
                Log.w(TAG, "MediaCodecList scan failed: " + e.getMessage());
            }
        }

        throw new Exception("No hardware encoder found");
    }

    /* =========================
       VIDEO ENCODER SETUP WITH HARDWARE FALLBACK
       ========================= */

    private void setupVideoEncoder() throws Exception {
        int alignedHeight = ((height + 15) / 16) * 16;
        MediaFormat format = MediaFormat.createVideoFormat(
                MediaFormat.MIMETYPE_VIDEO_AVC,
                width,
                alignedHeight
        );

        // Try multiple color formats (hardware encoders have preferences)
        int[] colorFormats = {
                MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible,
                MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Planar,
                MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420SemiPlanar,
                MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420PackedPlanar,
                MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420PackedSemiPlanar
        };

        format.setInteger(MediaFormat.KEY_BIT_RATE, StreamProfile.USB_BITRATE);
        format.setInteger(MediaFormat.KEY_FRAME_RATE, fps);
        format.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 2);

        // Quality settings
        format.setInteger(MediaFormat.KEY_PROFILE, MediaCodecInfo.CodecProfileLevel.AVCProfileHigh);
        format.setInteger(MediaFormat.KEY_LEVEL, MediaCodecInfo.CodecProfileLevel.AVCLevel31);

        // ✅ ADD THIS: Tell encoder about actual visible dimensions
        /*if (alignedHeight != height) {
            format.setInteger(MediaFormat.KEY_WIDTH, width);
            format.setInteger(MediaFormat.KEY_HEIGHT, height);  // Actual height (1080)
            format.setInteger("crop-top", 0);
            format.setInteger("crop-bottom", height - 1);
            format.setInteger("crop-left", 0);
            format.setInteger("crop-right", width - 1);
            Log.i(TAG, "✅ Encoder crop set: actual " + width + "x" + height + " (aligned " + width + "x" + alignedHeight + ")");
        }*/

        // TRY HARDWARE ENCODER FIRST
        boolean hardwareSuccess = false;
        try {
            videoEncoder = createHardwareEncoder();
            Log.i(TAG, "Attempting hardware encoding...");

            // Try each color format with hardware encoder
            for (int colorFormat : colorFormats) {
                try {
                    format.setInteger(MediaFormat.KEY_COLOR_FORMAT, colorFormat);
                    videoEncoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
                    videoEncoder.start();
                    hardwareSuccess = true;
                    Log.i(TAG, "✓ Hardware encoder configured successfully with color format: " + colorFormat);
                    break;
                } catch (Exception e) {
                    Log.w(TAG, "Hardware encoder failed with color format " + colorFormat + ": " + e.getMessage());
                    if (videoEncoder != null) {
                        videoEncoder.release();
                        videoEncoder = createHardwareEncoder();
                    }
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "Hardware encoder creation failed: " + e.getMessage());
        }

        // FALLBACK TO SOFTWARE ENCODER
        if (!hardwareSuccess) {
            Log.i(TAG, "Falling back to software encoder");
            try {
                videoEncoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC);
                format.setInteger(MediaFormat.KEY_COLOR_FORMAT,
                        MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible);
                videoEncoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
                videoEncoder.start();
                Log.i(TAG, "✓ Software encoder configured successfully");
            } catch (Exception e) {
                Log.e(TAG, "Software encoder also failed: " + e.getMessage());
                throw e;
            }
        }

        Log.i(TAG, "Video encoder ready: " + width + "x" + height + " @" + fps + "fps");
    }

    /* =========================
       AUDIO ENCODER SETUP
       ========================= */

    private void setupAudioEncoder() throws Exception {
        MediaFormat format = MediaFormat.createAudioFormat(
                MediaFormat.MIMETYPE_AUDIO_AAC,
                audioSampleRate,
                audioChannels
        );

        format.setInteger(MediaFormat.KEY_BIT_RATE, audioBitrate);
        format.setInteger(MediaFormat.KEY_AAC_PROFILE,
                MediaCodecInfo.CodecProfileLevel.AACObjectLC);
        format.setInteger(MediaFormat.KEY_CHANNEL_COUNT, audioChannels);
        format.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 4096);

        audioEncoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC);
        audioEncoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
        audioEncoder.start();

        Log.i(TAG, "Audio encoder ready: " + audioSampleRate + "Hz, " + audioChannels + "ch");
    }

    /* =========================
       AUDIO ENCODING (FRAME-ACCURATE PTS)
       ========================= */

    // In encodeAudioFrame - use OLD style PTS with drift correction
    public void encodeAudioFrame(byte[] pcmData, int size) {
        if (audioEncoder == null) return;

        try {
            int index = audioEncoder.dequeueInputBuffer(10000);
            if (index < 0) return;

            ByteBuffer inputBuffer = audioEncoder.getInputBuffer(index);
            if (inputBuffer == null) return;

            inputBuffer.clear();
            inputBuffer.put(pcmData, 0, size);

            // CORRECTED: Calculate frames, not samples
            int bytesPerSample = 2; // PCM 16-bit = 2 bytes per sample regardless of channels
            int totalSamples = size / bytesPerSample;
            int framesInBuffer = totalSamples / audioChannels / audioSamplesPerFrame;

            long frameIndex = audioFrameCounter.getAndAdd(framesInBuffer);
            long expectedPts = frameIndex * audioFrameDurationUs;
            long ptsUs;

            /*if (isFirstAudioFrame) {
                isFirstAudioFrame = false;
                ptsUs = 0;  // Always start at 0
                masterClockOffset = System.nanoTime() / 1000; // Store reference time
                Log.i(TAG, "FIRST AUDIO FRAME: pts=" + ptsUs);
            } else {
                ptsUs = expectedPts; // Use expected PTS like old version

                // Add drift correction from new version
                long systemTimeUs = System.nanoTime() / 1000;
                long expectedSystemTime = masterClockOffset + expectedPts;
                long drift = Math.abs(systemTimeUs - expectedSystemTime);

                if (drift > MAX_DRIFT_US) {
                    Log.w(TAG, "Audio drift detected: " + drift + "us, correcting");
                    masterClockOffset = systemTimeUs - expectedPts;
                }

                // Periodic resync
                if (systemTimeUs - lastResyncTime > RESYNC_INTERVAL_US) {
                    long newOffset = systemTimeUs - expectedPts;
                    long offsetDrift = Math.abs(newOffset - masterClockOffset);
                    Log.i(TAG, "Periodic resync: offset drift=" + offsetDrift + "us");
                    masterClockOffset = newOffset;
                    lastResyncTime = systemTimeUs;
                }
            }*/

            if (isFirstAudioFrame) {
                isFirstAudioFrame = false;
                masterClockOffset = System.nanoTime() / 1000;
                ptsUs = 0;
                Log.i(TAG, "FIRST AUDIO FRAME: pts=0");
            } else {
                long nowUs = System.nanoTime() / 1000;
                ptsUs = nowUs - masterClockOffset;
            }
            // Keep audioFrameCounter for stats
            audioFrameCounter.getAndAdd(framesInBuffer);

            audioEncoder.queueInputBuffer(index, 0, size, ptsUs, 0);
            drainAudioEncoder();

        } catch (Exception e) {
            Log.e(TAG, "Audio encoding error: " + e.getMessage());
        }
    }

    // Modify drainAudioEncoder similarly
    private void drainAudioEncoder() {
        if (audioEncoder == null) return;

        try {
            // During flush phase, just release buffers without writing
            if (!isRecording && pendingRecordingStart) {
                MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
                int audioIndex;
                while ((audioIndex = audioEncoder.dequeueOutputBuffer(info, 0)) >= 0) {
                    audioEncoder.releaseOutputBuffer(audioIndex, false);
                }
                return;
            }

            // Audio thread should NOT start the muxer - just log and wait
            synchronized (recordingLock) {
                if (pendingRecordingStart && recordingManager != null && !recordingManager.isRecording()) {
                    Log.d(TAG, "Audio thread waiting for video thread to start muxer");
                    try {
                        Thread.sleep(10);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                }
            }

            MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
            int index;

            while (true) {
                index = audioEncoder.dequeueOutputBuffer(info, 0);

                if (index == MediaCodec.INFO_TRY_AGAIN_LATER) {
                    break;
                } else if (index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    MediaFormat format = audioEncoder.getOutputFormat();
                    Log.d(TAG, "Audio output format: " + format);

                    // ✅ UPDATE THIS BLOCK - Capture audio format for recording
                    synchronized (recordingLock) {
                        if (pendingRecordingStart && pendingAudioFormat == null) {
                            pendingAudioFormat = format;
                            Log.i(TAG, "✅ Captured audio format for recording: " +
                                    format.getInteger(MediaFormat.KEY_SAMPLE_RATE) + "Hz, " +
                                    format.getInteger(MediaFormat.KEY_CHANNEL_COUNT) + "ch");
                            checkAndStartRecording();
                        }
                    }
                } else if (index >= 0) {
                    ByteBuffer out = audioEncoder.getOutputBuffer(index);
                    if (out != null && info.size > 0) {
                        int oldPosition = out.position();
                        int oldLimit = out.limit();

                        out.position(info.offset);
                        out.limit(info.offset + info.size);

                        // Send to RTMP
                        if (rtmpManager != null) {
                            ByteBuffer rtmpBuffer = out.duplicate();
                            rtmpManager.sendAudio(rtmpBuffer, info);  // ✅ Use rtmpManager
                        }

                        // Write to recording if active
                        if (isRecording && recordingManager != null && recordingManager.isRecording()) {
                            ByteBuffer recordBuffer = out.duplicate();
                            recordBuffer.position(info.offset);
                            recordBuffer.limit(info.offset + info.size);
                            recordingManager.writeAudioFrame(recordBuffer, info);
                        }

                        out.position(oldPosition);
                        out.limit(oldLimit);
                    }
                    audioEncoder.releaseOutputBuffer(index, false);
                }
            }
        } catch (IllegalStateException e) {
            Log.e(TAG, "Audio encoder in bad state, skipping this frame");
        }
    }

    /* =========================
       VIDEO ENCODING (FRAME-ACCURATE PTS)
       ========================= */

    // Update encodeYuyvFrame
    public void encodeYuyvFrame(ByteBuffer yuyv) {
        if (videoEncoder == null) return;

        if (frameCounter++ % 100 == 0) {
            Log.i(TAG, "🎬 Encoding YUYV frame - size: " + yuyv.remaining() + " bytes, input: " + inputWidth + "x" + inputHeight + ", output: " + width + "x" + height);
        }

        // Use optimized converter for ALL YUYV resolutions
        YuyvToNv12.convertWithScale(yuyv, nv12Buffer, inputWidth, inputHeight, width, height);

        feedVideoEncoder(nv12Buffer);
        drainVideoEncoder();
    }

    public void encodeMjpegFrame(ByteBuffer mjpeg) {
        if (videoEncoder == null) return;

        int frameSize = mjpeg.remaining();

        if (frameCounter++ % 100 == 0) {
            Log.i(TAG, "🎬 Encoding MJPEG frame - size: " + frameSize + " bytes, input: " + inputWidth + "x" + inputHeight + ", output: " + width + "x" + height);
        }

        // Get JPEG data from ByteBuffer
        byte[] jpegData = new byte[frameSize];
        mjpeg.get(jpegData);
        mjpeg.rewind();  // Reset for potential fallback

        // Try native libjpeg-turbo decode first
        boolean nativeSuccess = false;
        try {
            int result = nativeDecodeMjpeg(jpegData, frameSize, nv12Buffer, width, height);
            if (result == 0) {
                nativeSuccess = true;
                if (frameCounter % 100 == 0) {
                    Log.i(TAG, "✅ Native MJPEG decode successful");
                }
            } else {
                //Log.w(TAG, "Native decode returned error: " + result);
            }
        } catch (UnsatisfiedLinkError e) {
            Log.w(TAG, "Native decoder not available, using fallback");
        } catch (Exception e) {
            Log.e(TAG, "Native decode exception: " + e.getMessage());
        }

        // Fallback to YUYV converter if native fails
        if (!nativeSuccess) {
            YuyvToNv12.convertWithScale(mjpeg, nv12Buffer, inputWidth, inputHeight, width, height);
        }

        feedVideoEncoder(nv12Buffer);
        drainVideoEncoder();
    }

    // In feedVideoEncoder - use simple PTS like old version
    private void feedVideoEncoder(byte[] nv21) {
        if (videoEncoder == null) return;

        int index = videoEncoder.dequeueInputBuffer(10000);
        if (index < 0) return;

        ByteBuffer input = videoEncoder.getInputBuffer(index);
        if (input == null) return;

        input.clear();

        int alignedHeight = ((height + 15) / 16) * 16;

        if (alignedHeight != height) {
            int expectedYSize = width * alignedHeight;
            int expectedTotalSize = expectedYSize + (width * alignedHeight / 2);

            // Reuse buffer - only allocate if size changed
            if (paddedBuffer == null || paddedBuffer.length != expectedTotalSize) {
                paddedBuffer = new byte[expectedTotalSize];
            }

            int actualYSize = width * height;
            int uvSrcOffset = actualYSize;
            int uvDstOffset = expectedYSize;
            int uvRows = height / 2;

            // Copy Y plane
            for (int row = 0; row < height; row++) {
                System.arraycopy(nv21, row * width, paddedBuffer, row * width, width);
            }

            // Copy UV plane
            for (int row = 0; row < uvRows; row++) {
                System.arraycopy(nv21, uvSrcOffset + row * width,
                        paddedBuffer, uvDstOffset + row * width, width);
            }

            input.put(paddedBuffer);
        } else {
            input.put(nv21);
        }

        /*long frameIndex = videoFrameCounter.getAndIncrement();
        long ptsUs = frameIndex * videoFrameDurationUs;

        if (isFirstVideoFrame) {
            isFirstVideoFrame = false;
            Log.i(TAG, "First video frame PTS: " + ptsUs);
        }*/

        long ptsUs;
        if (isFirstVideoFrame) {
            isFirstVideoFrame = false;
            masterClockOffset = System.nanoTime() / 1000;
            ptsUs = 0;
            Log.i(TAG, "First video frame PTS: 0");
        } else {
            long nowUs = System.nanoTime() / 1000;
            ptsUs = nowUs - masterClockOffset;
        }
        videoFrameCounter.getAndIncrement(); // Keep for stats only

        lastVideoPts = ptsUs;
        videoEncoder.queueInputBuffer(index, 0, input.position(), ptsUs, 0);
    }

    // Modify drainVideoEncoder to write to file when recording
    private void drainVideoEncoder() {
        if (videoEncoder == null) return;

        MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
        while (true) {
            int index = videoEncoder.dequeueOutputBuffer(info, 0);

            if (index == MediaCodec.INFO_TRY_AGAIN_LATER) {
                break;
            } else if (index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                MediaFormat format = videoEncoder.getOutputFormat();
                ByteBuffer spsBuf = format.getByteBuffer("csd-0");
                ByteBuffer ppsBuf = format.getByteBuffer("csd-1");

                Log.d(TAG, "INFO_OUTPUT_FORMAT_CHANGED - sps=" + (spsBuf != null) + ", pps=" + (ppsBuf != null));

                if (spsBuf != null && ppsBuf != null) {
                    // Store SPS/PPS immediately
                    spsBuf.position(0);
                    ppsBuf.position(0);

                    cachedSps = ByteBuffer.allocate(spsBuf.remaining());
                    cachedSps.put(spsBuf);
                    cachedSps.flip();

                    cachedPps = ByteBuffer.allocate(ppsBuf.remaining());
                    cachedPps.put(ppsBuf);
                    cachedPps.flip();
                    hasCachedCodecData = true;

                    Log.i(TAG, "✅ SPS/PPS captured and cached");

                    // ✅ Capture video format for recording
                    synchronized (recordingLock) {
                        if (pendingRecordingStart && pendingVideoFormat == null) {
                            pendingVideoFormat = format;
                            Log.i(TAG, "✅ Captured video format for recording: " +
                                    format.getInteger(MediaFormat.KEY_WIDTH) + "x" +
                                    format.getInteger(MediaFormat.KEY_HEIGHT));
                            checkAndStartRecording();
                        }
                    }

                    // If RTMP is already connected, send them immediately
                    if (rtmpManager != null) {
                        setSpsPpsForRtmp();
                    }
                }
            } else if (index >= 0) {
                ByteBuffer out = videoEncoder.getOutputBuffer(index);
                if (out != null && info.size > 0) {

                    // Write to recording if active
                    if (isRecording && recordingManager != null && recordingManager.isRecording()) {
                        ByteBuffer recordBuffer = out.duplicate();
                        recordBuffer.position(info.offset);
                        recordBuffer.limit(info.offset + info.size);
                        recordingManager.writeVideoFrame(recordBuffer, info);
                    }

                    // Send to RTMP if connected
                    if (rtmpManager != null) {
                        ByteBuffer rtmpBuffer = out.duplicate();
                        rtmpManager.sendVideo(rtmpBuffer, info);
                    }
                }
                videoEncoder.releaseOutputBuffer(index, false);
            }
        }
    }

    /* =========================
       RTMP SETUP
       ========================= */

    public void startStreaming(String rtmpUrl) {
        this.rtmpUrl = rtmpUrl;

        videoFrameCounter.set(0);
        audioFrameCounter.set(0);
        isFirstVideoFrame = true;
        isFirstAudioFrame = true;

        Log.d(TAG, "Starting stream to: " + rtmpUrl);
        Log.d(TAG, "Audio: " + audioSampleRate + "Hz, Video: " + width + "x" + height + " @" + fps + "fps");

        checkSpsPpsStatus();

        // ✅ FORCE A KEYFRAME - This makes the encoder produce output
        requestKeyFrame();

        if (rtmpManager == null) {
            Log.e(TAG, "RTMPManager is null! Cannot start streaming.");
            return;
        }

        if (!rtmpManager.isConnected()) {
            setupRtmp(rtmpUrl);
        } else {
            Log.d(TAG, "RTMP already connected");
            if (hasCachedCodecData) {
                setSpsPpsForRtmp();
            }
        }
    }

    private void setSpsPpsForRtmp() {
        if (!hasCachedCodecData || cachedSps == null || cachedPps == null) {
            Log.w(TAG, "Cannot set SPS/PPS - missing data");
            return;
        }

        if (rtmpManager == null) {
            Log.w(TAG, "RTMPManager not ready, will send later");
            return;
        }

        // Create fresh copies
        cachedSps.position(0);
        cachedPps.position(0);

        ByteBuffer spsCopy = ByteBuffer.allocate(cachedSps.remaining());
        spsCopy.put(cachedSps);
        spsCopy.flip();

        ByteBuffer ppsCopy = ByteBuffer.allocate(cachedPps.remaining());
        ppsCopy.put(cachedPps);
        ppsCopy.flip();

        cachedSps.position(0);
        cachedPps.position(0);

        // ✅ Use rtmpManager instead of rtmpClient
        rtmpManager.setVideoInfo(spsCopy, ppsCopy, null);
        Log.i(TAG, "✅ SPS/PPS set for RTMP via RTMPManager");
    }

    // Modify the RTMP connection success callback
    private void setupRtmp(String url) {
        if (rtmpManager == null) {
            Log.e(TAG, "RTMPManager is null! Cannot setup RTMP.");
            return;
        }

        // Just connect - RTMPManager handles everything
        rtmpManager.connect(url);
        Log.i(TAG, "RTMP connection initiated via RTMPManager");
    }

    public void stopStreaming() {
        if (rtmpManager != null) {
            rtmpManager.disconnect();
            Log.i(TAG, "Streaming stopped");
        }
    }

    public boolean isStreaming() {
        return rtmpManager != null && rtmpManager.isConnected();
    }


    /* =========================
       LIFECYCLE
       ========================= */

    public void stop() {
        stopStreaming();

        if (videoEncoder != null) {
            try {
                videoEncoder.stop();
                videoEncoder.release();
            } catch (Exception e) {
                Log.w(TAG, "Error stopping video encoder: " + e.getMessage());
            }
            videoEncoder = null;
        }

        if (audioEncoder != null) {
            try {
                audioEncoder.stop();
                audioEncoder.release();
            } catch (Exception e) {
                Log.w(TAG, "Error stopping audio encoder: " + e.getMessage());
            }
            audioEncoder = null;
        }

        Log.i(TAG, "Encoder stopped");
    }
    private void flushEncoderPipeline() {
        if (videoEncoder != null) {
            try {
                Log.d(TAG, "Flushing encoder pipeline...");

                // Method 1: Request a keyframe to reset the encoder state
                requestKeyFrame();

                // Method 2: Drain any pending output buffers from VIDEO encoder
                MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
                int videoFlushCount = 0;
                int videoIndex;

                long startTime = System.currentTimeMillis();
                while ((videoIndex = videoEncoder.dequeueOutputBuffer(info, 0)) != MediaCodec.INFO_TRY_AGAIN_LATER) {
                    if (videoIndex >= 0) {
                        videoEncoder.releaseOutputBuffer(videoIndex, false);
                        videoFlushCount++;
                    } else if (videoIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                        // Ignore format changes during flush
                        continue;
                    }

                    if (System.currentTimeMillis() - startTime > 100) break;
                }

                Log.d(TAG, "Flushed " + videoFlushCount + " video frames from encoder pipeline");

                // Method 3: Drain any pending output buffers from AUDIO encoder
                if (audioEncoder != null) {
                    int audioFlushCount = 0;
                    int audioIndex;
                    MediaCodec.BufferInfo audioInfo = new MediaCodec.BufferInfo();

                    // First, drain any pending output buffers
                    startTime = System.currentTimeMillis();
                    while ((audioIndex = audioEncoder.dequeueOutputBuffer(audioInfo, 0)) != MediaCodec.INFO_TRY_AGAIN_LATER) {
                        if (audioIndex >= 0) {
                            audioEncoder.releaseOutputBuffer(audioIndex, false);
                            audioFlushCount++;
                        } else if (audioIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                            continue;
                        }
                        if (System.currentTimeMillis() - startTime > 100) break;
                    }

                    Log.d(TAG, "Flushed " + audioFlushCount + " audio frames from encoder pipeline");

                    // Just reset the counter - don't stop/start the encoder
                    audioFrameCounter.set(0);
                    isFirstAudioFrame = true;
                }

                // Small delay to let everything settle
                Thread.sleep(50);

            } catch (Exception e) {
                Log.e(TAG, "Failed to flush encoder: " + e.getMessage());
            }
        }
    }
    public void updateAudioParameters(int sampleRate, int channels) {
        // Force 48000 if anything else comes in
        if (sampleRate != 48000) {
            Log.w(TAG, "Forcing sample rate from " + sampleRate + " to 48000 Hz");
            sampleRate = 48000;
        }

        this.audioSampleRate = sampleRate;
        this.audioChannels = channels;
        this.audioFrameDurationUs = (audioSamplesPerFrame * 1_000_000L) / audioSampleRate;
        Log.i(TAG, "Audio parameters updated to: " + sampleRate + "Hz, " + channels + "ch, frameDuration=" + audioFrameDurationUs + "us");
    }
    // Add this one method to UsbH264Encoder - it's small and safe
    public void setRtmpClient(RtmpClient client) {
        this.rtmpClient = client;
    }
    private void checkAndStartRecording() {
        synchronized (recordingLock) {
            Log.i(TAG, "checkAndStartRecording: pendingStart=" + pendingRecordingStart +
                    ", videoFormatReady=" + (pendingVideoFormat != null) +
                    ", audioFormatReady=" + (pendingAudioFormat != null));

            if (pendingRecordingStart && pendingVideoFormat != null && pendingAudioFormat != null && recordingManager != null) {
                if (!recordingManager.isRecording()) {
                    Log.i(TAG, "✓ Both formats ready, starting recording!");
                    recordingManager.startRecording(pendingVideoFormat, pendingAudioFormat);
                    pendingRecordingStart = false;
                    Log.i(TAG, "✅ Recording started successfully");
                }
            }
        }
    }
    private MediaFormat createVideoFormatFromCache() {
        if (!hasCachedCodecData || cachedSps == null || cachedPps == null) {
            Log.w(TAG, "Cannot create video format: no cached SPS/PPS");
            return null;
        }

        MediaFormat format = MediaFormat.createVideoFormat(
                MediaFormat.MIMETYPE_VIDEO_AVC,
                width,
                height  // Actual height (1080)
        );

        // Set SPS/PPS
        cachedSps.position(0);
        cachedPps.position(0);

        ByteBuffer spsCopy = ByteBuffer.allocate(cachedSps.remaining());
        spsCopy.put(cachedSps);
        spsCopy.flip();

        ByteBuffer ppsCopy = ByteBuffer.allocate(cachedPps.remaining());
        ppsCopy.put(cachedPps);
        ppsCopy.flip();

        format.setByteBuffer("csd-0", spsCopy);
        format.setByteBuffer("csd-1", ppsCopy);

        // ✅ CRITICAL: Set crop rectangle to remove green bar
        format.setInteger(MediaFormat.KEY_WIDTH, width);
        format.setInteger(MediaFormat.KEY_HEIGHT, height);

        // Tell decoder to crop from 1088 to 1080
        format.setInteger("crop-left", 0);
        format.setInteger("crop-right", width - 1);
        format.setInteger("crop-top", 0);
        format.setInteger("crop-bottom", height - 1);

        // Other parameters
        format.setInteger(MediaFormat.KEY_BIT_RATE, StreamProfile.USB_BITRATE);
        format.setInteger(MediaFormat.KEY_FRAME_RATE, fps);
        format.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 2);
        format.setInteger(MediaFormat.KEY_PROFILE, MediaCodecInfo.CodecProfileLevel.AVCProfileHigh);
        format.setInteger(MediaFormat.KEY_LEVEL, MediaCodecInfo.CodecProfileLevel.AVCLevel31);

        Log.i(TAG, "✅ Created video format from cache: " + width + "x" + height + " with crop");
        return format;
    }
}
