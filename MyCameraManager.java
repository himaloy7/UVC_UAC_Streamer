package com.serenegiant.usbcameratest.managers;

import android.app.Activity;
import android.graphics.Matrix;
import android.graphics.SurfaceTexture;
import android.media.MediaFormat;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.Surface;
import android.view.TextureView;
import android.view.View;
import android.widget.Toast;

import androidx.annotation.NonNull;

import com.pedro.encoder.input.video.CameraHelper;
import com.serenegiant.usb.Size;
import com.serenegiant.usb.UVCCamera;
import com.serenegiant.usb.USBMonitor;
import com.serenegiant.usbcameratest.enums.CameraSource;
import com.serenegiant.widget.UVCCameraTextureView;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class MyCameraManager {

    private static final String TAG = "MyCameraManager";

    public interface CameraEventListener {
        void onUsbCameraReady();
        void onInternalCameraReady();
    }

    private final Activity activity;
    private final RTMPManager rtmpManager;
    private final TextureView internalTextureView;
    private final UVCCameraTextureView usbTextureView;
    private final CameraEventListener listener;

    private final ExecutorService executor = Executors.newSingleThreadExecutor();

    // USB Camera components
    private UVCCamera uvcCamera;
    private UsbH264Encoder usbEncoder;

    // Internal Camera components
    private InternalCameraManager internalCameraManager;

    // Shared components
    private AudioManager audioManager;
    private RecordingManager recordingManager;
    private CameraSource currentSource = CameraSource.INTERNAL_BACK;
    private int usbRotationDegrees = 0;
    private boolean isUsbInitializing = false;
    private USBMonitor.UsbControlBlock savedUsbControlBlock = null;
    private boolean isProgrammaticRestart = false;

    // Add with other fields at the top
    private long lastFrameTime = 0;
    private int frameCount = 0;
    private long totalFps = 0;
    private boolean framerateDetected = false;
    private FpsUpdateListener fpsUpdateListener;
    // Add this interface at the top of MyCameraManager class
    public interface FpsUpdateListener {
        void onFpsMeasured(int fps);
    }
    public void setFpsUpdateListener(FpsUpdateListener listener) {
        this.fpsUpdateListener = listener;
    }

    // Audio callback for both cameras
    private class AudioCallback implements AudioManager.AudioEncoderCallback {
        @Override
        public void onAudioFrame(byte[] pcmData, int size) {
            if (currentSource == CameraSource.USB && usbEncoder != null) {
                usbEncoder.encodeAudioFrame(pcmData, size);
            } else if (currentSource.toString().contains("INTERNAL") && internalCameraManager != null) {
                internalCameraManager.encodeAudioFrame(pcmData, size);
            }
        }
    }

    private AudioCallback audioCallback;

    public MyCameraManager(
            Activity activity,
            RTMPManager rtmpManager,
            TextureView internalTextureView,
            UVCCameraTextureView usbTextureView,
            CameraEventListener listener
    ) {
        this.activity = activity;
        this.rtmpManager = rtmpManager;
        this.internalTextureView = internalTextureView;
        this.usbTextureView = usbTextureView;
        this.listener = listener;
        this.audioCallback = new AudioCallback();
    }

    public void setRecordingManager(RecordingManager recordingManager) {
        this.recordingManager = recordingManager;
        if (usbEncoder != null) {
            usbEncoder.setRecordingManager(recordingManager);
        }
        if (internalCameraManager != null) {
            internalCameraManager.setRecordingManager(recordingManager);
        }
    }

    public void setAudioManager(AudioManager audioManager) {
        Log.i(TAG, "setAudioManager called with instance: " + System.identityHashCode(audioManager));
        this.audioManager = audioManager;
        if (audioManager != null) {
            audioManager.setAudioEncoderCallback(audioCallback);

            int sampleRate = audioManager.getCurrentSampleRate();
            int channels = audioManager.getCurrentChannels();

            Log.d(TAG, "Audio parameters: " + sampleRate + "Hz, " + channels + "ch");

            if (currentSource == CameraSource.USB && usbEncoder != null) {
                usbEncoder.updateAudioParameters(sampleRate, channels);
                Log.d(TAG, "Updated USB encoder audio parameters");
            } else if (currentSource.toString().contains("INTERNAL") && internalCameraManager != null) {
                internalCameraManager.setAudioParameters(sampleRate, channels, StreamProfile.AUDIO_BITRATE);
                Log.d(TAG, "Updated internal camera audio parameters");
            }
        }
    }

    // ========================= Camera Switching =========================

    public void switchToUsbCamera(USBMonitor.UsbControlBlock ctrlBlock) {
        StreamProfile.setCurrentCameraType("usb");  // ✅ Add this
        executor.execute(() -> {
            stopCurrentSource();
            currentSource = CameraSource.USB;
            startUsbCamera(ctrlBlock);
        });
    }

    public void switchToInternalCamera(CameraHelper.Facing facing) {
        StreamProfile.setCurrentCameraType("internal");  // ✅ Add this
        // ✅ Don't switch to internal if this is a programmatic USB restart
        if (isProgrammaticRestart) {
            Log.i(TAG, "Programmatic restart in progress, ignoring internal camera switch");
            return;
        }
        executor.execute(() -> {
            stopCurrentSource();
            currentSource = facing == CameraHelper.Facing.FRONT ?
                    CameraSource.INTERNAL_FRONT : CameraSource.INTERNAL_BACK;
            startInternalCamera(facing);
        });
    }

    private void stopCurrentSource() {
        if (currentSource == CameraSource.USB) {
            releaseUsbCameraInternal();
        } else if (currentSource.toString().contains("INTERNAL")) {
            if (internalCameraManager != null) {
                internalCameraManager.stopPreview();
                internalCameraManager = null;
            }
        }
    }

    // ========================= USB Camera =========================

    public void testUsbRotation(int rotation) {
        this.usbRotationDegrees = rotation;
        activity.runOnUiThread(() -> fixUsbCameraPreviewRotation());
        Log.i(TAG, "Testing USB rotation: " + rotation + "°");
    }

    private void fixUsbCameraPreviewRotation() {
        if (usbTextureView == null || !usbTextureView.isAvailable()) return;
        Log.d(TAG, "USB camera preview state - aspect ratio already set");
    }

    private void startUsbCamera(USBMonitor.UsbControlBlock ctrlBlock) {
        Log.i(TAG, "startUsbCamera called, isProgrammaticRestart=" + isProgrammaticRestart);
        if (isUsbInitializing) {
            Log.w(TAG, "USB camera already initializing, skipping");
            return;
        }
        isUsbInitializing = true;

        try {
            int forcedSampleRate = 48000;
            int forcedChannels = 2;

            Log.i(TAG, "=== CREATING USB ENCODER ===");
            Log.i(TAG, "Forcing sample rate: " + forcedSampleRate + " Hz");
            Log.i(TAG, "Forcing channels: " + forcedChannels);

            // First, clean up any existing USB camera resources
            releaseUsbCameraInternal();

            usbEncoder = new UsbH264Encoder(
                    StreamProfile.getUsbInputWidth(),     // input width (from hardware)
                    StreamProfile.getUsbInputHeight(),    // input height (from hardware)
                    StreamProfile.getUsbInputFps(),       // input fps (from hardware)
                    StreamProfile.getUsbOutputWidth(),    // output width (user selected for USB)
                    StreamProfile.getUsbOutputHeight(),   // output height (user selected for USB)
                    StreamProfile.getUsbOutputFps(),      // output fps (user selected for USB)
                    forcedSampleRate,
                    StreamProfile.getAudioBitrate(),
                    forcedChannels
            );

            // ✅ ADD THIS LINE - Pass RTMPManager to encoder
            usbEncoder.setRtmpManager(rtmpManager);

            if (audioManager != null) {
                int sampleRate = audioManager.getCurrentSampleRate();
                int channels = audioManager.getCurrentChannels();
                usbEncoder.updateAudioParameters(sampleRate, channels);
                Log.d(TAG, "Set USB encoder audio to: " + sampleRate + "Hz, " + channels + "ch");
            }

            // ✅ ADD THIS BLOCK - Pre-configure RTMPManager with audio parameters
            if (rtmpManager != null) {
                int audioRate = (audioManager != null) ? audioManager.getCurrentSampleRate() : forcedSampleRate;
                int audioChan = (audioManager != null) ? audioManager.getCurrentChannels() : forcedChannels;

                // Force 48000 if needed
                if (audioRate != 48000) {
                    Log.w(TAG, "Forcing RTMP audio to 48000Hz (was " + audioRate + "Hz)");
                    audioRate = 48000;
                }

                rtmpManager.preConfigureAudio(audioRate, audioChan == 2);
                Log.i(TAG, "✅ RTMPManager pre-configured with audio: " + audioRate + "Hz, " + audioChan + "ch");
            }

            // ✅ Set encoder type for USB
            if (recordingManager != null) {
                recordingManager.setEncoderType(RecordingManager.EncoderType.USB);
                usbEncoder.setRecordingManager(recordingManager);
                Log.i(TAG, "RecordingManager set to USB mode");
            }

            // CRITICAL: Ensure TextureView is properly set up BEFORE opening camera
            activity.runOnUiThread(() -> {
                // First, make the view visible
                usbTextureView.setVisibility(TextureView.VISIBLE);

                // Set aspect ratio
                double aspectRatio = (double) StreamProfile.getWidth() / StreamProfile.getHeight();
                usbTextureView.setAspectRatio(aspectRatio);
                usbTextureView.requestLayout();

                // Force recreation of SurfaceTexture by toggling visibility
                // This ensures a fresh SurfaceTexture is created
                usbTextureView.setVisibility(TextureView.GONE);
                usbTextureView.setVisibility(TextureView.VISIBLE);

                // Wait a bit for the view to be ready
                new Handler(Looper.getMainLooper()).postDelayed(() -> {
                    if (!usbTextureView.isAvailable()) {
                        usbTextureView.setSurfaceTextureListener(
                                createUsbSurfaceListener(ctrlBlock)
                        );
                    } else {
                        SurfaceTexture surfaceTexture = usbTextureView.getSurfaceTexture();
                        if (surfaceTexture != null) {
                            openUsbCamera(ctrlBlock, surfaceTexture);
                        } else {
                            Log.e(TAG, "SurfaceTexture is still null after visibility toggle");
                            // Try one more time with listener
                            usbTextureView.setSurfaceTextureListener(
                                    createUsbSurfaceListener(ctrlBlock)
                            );
                        }
                    }
                }, 200);
            });

        } catch (Exception e) {
            Log.e(TAG, "Failed to create USB encoder", e);
            isUsbInitializing = false;
        }
    }

    private void resetUsbInitializingFlag() {
        new Handler(Looper.getMainLooper()).postDelayed(() -> {
            isUsbInitializing = false;
            Log.d(TAG, "USB camera initialization flag reset");
        }, 2000);
    }

    private TextureView.SurfaceTextureListener createUsbSurfaceListener(
            USBMonitor.UsbControlBlock ctrlBlock
    ) {
        return new TextureView.SurfaceTextureListener() {
            @Override
            public void onSurfaceTextureAvailable(
                    @NonNull SurfaceTexture surface,
                    int width,
                    int height
            ) {
                Log.d(TAG, "SurfaceTexture available, opening USB camera");
                openUsbCamera(ctrlBlock, surface);
            }

            @Override
            public void onSurfaceTextureSizeChanged(
                    @NonNull SurfaceTexture surface,
                    int width,
                    int height
            ) {
                Log.d(TAG, "SurfaceTexture size changed: " + width + "x" + height);
            }

            @Override
            public boolean onSurfaceTextureDestroyed(
                    @NonNull SurfaceTexture surface
            ) {
                Log.d(TAG, "SurfaceTexture destroyed");
                releaseUsbCamera();
                return true;
            }

            @Override
            public void onSurfaceTextureUpdated(
                    @NonNull SurfaceTexture surface
            ) {}
        };
    }

    private void openUsbCamera(USBMonitor.UsbControlBlock ctrlBlock, SurfaceTexture surfaceTexture) {
        executor.execute(() -> {
            try {
                savedUsbControlBlock = ctrlBlock;
                if (surfaceTexture == null) {
                    Log.e(TAG, "SurfaceTexture is null, cannot open USB camera");
                    resetUsbInitializingFlag();
                    return;
                }

                uvcCamera = new UVCCamera();
                uvcCamera.open(ctrlBlock);

                // In MyCameraManager.java, replace the detection code in openUsbCamera:

                // ==================== AUTO-DETECTION ====================
                // Step 1: Get supported preview sizes from camera
                String supportedSizesString = uvcCamera.getSupportedSize();
                List<Size> supportedSizesList = uvcCamera.getSupportedSizeList();
                parseAndStoreSupportedResolutions(supportedSizesString);
                parseAndStoreSupportedFramerates(supportedSizesString);

                Log.i(TAG, "Supported sizes string: " + supportedSizesString);
                Log.i(TAG, "Supported sizes list size: " + (supportedSizesList != null ? supportedSizesList.size() : 0));

                int detectedWidth = 1920;  // Default
                int detectedHeight = 1080; // Default
                int detectedFps = 30;      // Default - DECLARE ONCE HERE

                // Parse supported sizes to find max resolution
                if (supportedSizesList != null && !supportedSizesList.isEmpty()) {
                    int maxArea = 0;
                    for (Size size : supportedSizesList) {
                        int width = size.width;
                        int height = size.height;
                        int area = width * height;
                        if (area > maxArea) {
                            maxArea = area;
                            detectedWidth = width;
                            detectedHeight = height;
                        }
                    }
                    Log.i(TAG, "Max resolution from list: " + detectedWidth + "x" + detectedHeight);
                } else if (supportedSizesString != null && !supportedSizesString.isEmpty()) {
                    // Parse string format like "1920x1080,1280x720,640x480"
                    String[] parts = supportedSizesString.split(",");
                    int maxArea = 0;
                    for (String part : parts) {
                        String[] dimensions = part.trim().split("x");
                        if (dimensions.length == 2) {
                            try {
                                int width = Integer.parseInt(dimensions[0]);
                                int height = Integer.parseInt(dimensions[1]);
                                int area = width * height;
                                if (area > maxArea) {
                                    maxArea = area;
                                    detectedWidth = width;
                                    detectedHeight = height;
                                }
                            } catch (NumberFormatException e) {
                                Log.w(TAG, "Failed to parse size: " + part);
                            }
                        }
                    }
                    Log.i(TAG, "Max resolution from string: " + detectedWidth + "x" + detectedHeight);
                }

                // Try to get FPS from camera - DON'T REDECLARE, just update the existing variable
                try {
                    // Some UVCCamera versions have getFrameRate()
                    // int fps = uvcCamera.getFrameRate();
                    // if (fps > 0) detectedFps = fps;
                } catch (Exception e) {
                    // Ignore, use default
                }

                Log.i(TAG, "🔍 Auto-detected USB input: " + detectedWidth + "x" + detectedHeight + "@" + detectedFps);

                // Store detected dimensions
                StreamProfile.setUsbInputDimensions(detectedWidth, detectedHeight, detectedFps);

                // Step 3: Set default output to match input (if not already set by user)
                // Only reset if output is still at default values
                if (StreamProfile.getUsbOutputWidth() == 1280 && StreamProfile.getUsbOutputHeight() == 720) {
                    StreamProfile.resetOutputToInput();
                    Log.i(TAG, "Default output set to match detected input");
                }
                // ========================================================

                // Use detected dimensions for camera preview
                int previewWidth = StreamProfile.getUsbOutputWidth();
                int previewHeight = StreamProfile.getUsbOutputHeight();
                int previewFps = StreamProfile.getUsbOutputFps();

                // Choose format based on resolution (MJPEG for 1080p+, YUYV for lower)
                int frameFormat = (previewWidth * previewHeight > 1280 * 720) ?
                        UVCCamera.FRAME_FORMAT_MJPEG : UVCCamera.FRAME_FORMAT_YUYV;

                surfaceTexture.setDefaultBufferSize(previewWidth, previewHeight);

                uvcCamera.setPreviewSize(previewWidth, previewHeight, frameFormat);
                uvcCamera.setPreviewSize(previewWidth, previewHeight, previewFps, previewFps,
                        frameFormat, 0.8f);

                // Update TextureView aspect ratio
                activity.runOnUiThread(() -> {
                    double aspectRatio = (double) previewWidth / previewHeight;
                    usbTextureView.setAspectRatio(aspectRatio);
                    usbTextureView.requestLayout();
                });

                Surface previewSurface = new Surface(surfaceTexture);
                uvcCamera.setPreviewDisplay(previewSurface);
                uvcCamera.startPreview();

                // Set frame callback with appropriate format
                // In openUsbCamera(), replace the frame callback with:

                // ✅ Use the ACTUAL format that was negotiated
                int actualFormat = frameFormat;  // Capture the format we requested

                uvcCamera.setFrameCallback(
                        frame -> {
                            // FPS measurement code (unchanged)...
                            long now = System.nanoTime();
                            if (lastFrameTime > 0) {
                                long intervalUs = (now - lastFrameTime) / 1000;
                                if (intervalUs > 0) {
                                    int currentFps = (int)(1_000_000 / intervalUs);
                                    totalFps += currentFps;
                                    frameCount++;

                                    if (frameCount >= 30) {
                                        int avgFps = Math.round(totalFps / (float)frameCount);
                                        if (fpsUpdateListener != null) {
                                            activity.runOnUiThread(() -> fpsUpdateListener.onFpsMeasured(avgFps));
                                        }
                                        totalFps = 0;
                                        frameCount = 0;
                                    }
                                }
                            }
                            lastFrameTime = now;

                            if (usbEncoder != null) {
                                // ✅ Route based on ACTUAL format, not hardcoded
                                if (actualFormat == UVCCamera.FRAME_FORMAT_MJPEG) {
                                    usbEncoder.encodeMjpegFrame(frame);
                                } else {
                                    usbEncoder.encodeYuyvFrame(frame);
                                }
                            } else {
                                Log.e(TAG, "usbEncoder is null in frame callback!");
                            }
                        },
                        actualFormat  // ✅ Use actual format here too
                );

                Log.i(TAG, "✅ Frame callback registered");
                Log.i(TAG, "USB camera preview started at: " + previewWidth + "x" + previewHeight + "@" + previewFps);

                // ✅ RECREATE ENCODER WITH CORRECT DETECTED DIMENSIONS
                if (usbEncoder != null) {
                    usbEncoder.stop();
                    usbEncoder = null;
                }

                int forcedSampleRate = 48000;
                int forcedChannels = 2;

                usbEncoder = new UsbH264Encoder(
                        StreamProfile.getUsbInputWidth(),     // 1920 - detected from camera
                        StreamProfile.getUsbInputHeight(),    // 1080 - detected from camera
                        StreamProfile.getUsbInputFps(),       // 30
                        StreamProfile.getUsbOutputWidth(),    // 1920 - after reset
                        StreamProfile.getUsbOutputHeight(),   // 1080 - after reset
                        StreamProfile.getUsbOutputFps(),      // 30
                        forcedSampleRate,
                        StreamProfile.getAudioBitrate(),
                        forcedChannels
                );

                usbEncoder.setRtmpManager(rtmpManager);
                usbEncoder.setRecordingManager(recordingManager);

                if (audioManager != null) {
                    int sampleRate = audioManager.getCurrentSampleRate();
                    int channels = audioManager.getCurrentChannels();
                    usbEncoder.updateAudioParameters(sampleRate, channels);
                }

                Log.i(TAG, "✅ Encoder recreated with detected output: " + StreamProfile.getUsbOutputWidth() + "x" + StreamProfile.getUsbOutputHeight());

                activity.runOnUiThread(() -> {
                    listener.onUsbCameraReady();
                    Toast.makeText(activity, "USB camera ready: " + previewWidth + "x" + previewHeight, Toast.LENGTH_LONG).show();
                    resetUsbInitializingFlag();
                });

            } catch (Exception e) {
                Log.e(TAG, "USB camera failed: " + e.getMessage(), e);
                resetUsbInitializingFlag();
                openUsbCameraWithYuyv(ctrlBlock, surfaceTexture);
            }
        });
    }

    private void openUsbCameraWithYuyv(USBMonitor.UsbControlBlock ctrlBlock,
                                       SurfaceTexture surfaceTexture) {
        try {
            if (uvcCamera != null) {
                uvcCamera.destroy();
            }

            int previewWidth = StreamProfile.getWidth();
            int previewHeight = StreamProfile.getHeight();

            uvcCamera = new UVCCamera();
            uvcCamera.open(ctrlBlock);
            uvcCamera.setPreviewSize(previewWidth, previewHeight, UVCCamera.FRAME_FORMAT_YUYV);

            Surface previewSurface = new Surface(surfaceTexture);
            uvcCamera.setPreviewDisplay(previewSurface);

            uvcCamera.setFrameCallback(
                    frame -> {
                        if (usbEncoder != null) {
                            usbEncoder.encodeYuyvFrame(frame);
                        }
                    },
                    UVCCamera.FRAME_FORMAT_YUYV
            );

            uvcCamera.startPreview();

            activity.runOnUiThread(() -> {
                fixUsbCameraPreviewRotation();
                listener.onUsbCameraReady();
            });
            Log.i(TAG, "YUYV preview started");

        } catch (Exception e) {
            Log.e(TAG, "YUYV also failed: " + e.getMessage());
            releaseUsbCameraInternal();
        }
    }

    public void releaseUsbCamera() {
        executor.execute(this::releaseUsbCameraInternal);
    }
    public USBMonitor.UsbControlBlock getSavedUsbControlBlock() {
        return savedUsbControlBlock;
    }

    private void releaseUsbCameraInternal() {
        Log.i(TAG, "releaseUsbCameraInternal called, isProgrammaticRestart=" + isProgrammaticRestart);
        isUsbInitializing = false;

        try {
            if (uvcCamera != null) {
                uvcCamera.stopPreview();
                uvcCamera.setFrameCallback(null, 0);
                uvcCamera.destroy();
                uvcCamera = null;
                Log.i(TAG, "USB camera released");
            }

            activity.runOnUiThread(() -> {
                if (usbTextureView != null) {
                    // Reset transform
                    usbTextureView.setTransform(null);
                    // Hide the view to clean up
                    usbTextureView.setVisibility(TextureView.GONE);
                    // Force recreation of SurfaceTexture for next time
                    //usbTextureView.setVisibility(TextureView.VISIBLE);
                    usbTextureView.setVisibility(TextureView.GONE);
                }
            });

            if (usbEncoder != null) {
                usbEncoder.stop();
                usbEncoder = null;
            }
        } catch (Exception e) {
            Log.e(TAG, "Error releasing USB camera", e);
        }
    }

    // ========================= Internal Camera =========================

    private void startInternalCamera(CameraHelper.Facing facing) {
        try {
            Log.i(TAG, "=== STARTING INTERNAL CAMERA ===");
            Log.i(TAG, "Using resolution: " + StreamProfile.getWidth() + "x" + StreamProfile.getHeight() + " @" + StreamProfile.getFps() + "fps");

            internalCameraManager = new InternalCameraManager(activity, internalTextureView, audioManager);
            internalCameraManager.setFacing(facing);

            // ✅ Force update resolution before start
            internalCameraManager.updateResolution(
                    StreamProfile.getWidth(),
                    StreamProfile.getHeight(),
                    StreamProfile.getFps()
            );

            // ✅ Set encoder type for internal
            if (recordingManager != null) {
                recordingManager.setEncoderType(RecordingManager.EncoderType.INTERNAL);
                internalCameraManager.setRecordingManager(recordingManager);
            }

            internalCameraManager.setRtmpManager(rtmpManager);

            if (audioManager != null) {
                int sampleRate = audioManager.getCurrentSampleRate();
                int channels = audioManager.getCurrentChannels();
                internalCameraManager.setAudioParameters(sampleRate, channels, StreamProfile.AUDIO_BITRATE);
                Log.i(TAG, "✅ AudioManager connected to InternalCameraManager");
            }

            internalCameraManager.startPreview();

            activity.runOnUiThread(() -> {
                internalTextureView.setVisibility(TextureView.VISIBLE);
                usbTextureView.setVisibility(TextureView.GONE);
                listener.onInternalCameraReady();
                Toast.makeText(activity, "Internal camera ready", Toast.LENGTH_SHORT).show();
            });

            Log.i(TAG, "Internal camera preview started");

        } catch (Exception e) {
            Log.e(TAG, "Failed to start internal camera", e);
            activity.runOnUiThread(() ->
                    Toast.makeText(activity, "Failed to start internal camera", Toast.LENGTH_SHORT).show()
            );
        }
    }

    // ========================= Streaming Control =========================

    public void startStreaming(String rtmpUrl) {
        if (currentSource == CameraSource.USB && usbEncoder != null) {
            usbEncoder.startStreaming(rtmpUrl);
            Log.i(TAG, "USB streaming started");
        } else if (currentSource.toString().contains("INTERNAL") && internalCameraManager != null) {
            internalCameraManager.startStreaming(rtmpUrl);
            Log.i(TAG, "Internal camera streaming started");
        } else {
            Log.w(TAG, "Cannot start streaming: no active camera");
            Toast.makeText(activity, "Camera not ready", Toast.LENGTH_SHORT).show();
        }
    }

    public void stopStreaming() {
        if (currentSource == CameraSource.USB && usbEncoder != null) {
            usbEncoder.stopStreaming();
        } else if (currentSource.toString().contains("INTERNAL") && internalCameraManager != null) {
            internalCameraManager.stopStreaming();
        }
    }

    public boolean isStreaming() {
        if (currentSource == CameraSource.USB && usbEncoder != null) {
            return usbEncoder.isStreaming();
        } else if (currentSource.toString().contains("INTERNAL") && internalCameraManager != null) {
            return internalCameraManager.isStreaming();
        }
        return false;
    }

    // ========================= Recording Control =========================

    public void startRecording() {
        if (currentSource == CameraSource.USB && usbEncoder != null) {
            usbEncoder.startRecording();
            Log.i(TAG, "USB recording started");
        } else if (currentSource.toString().contains("INTERNAL") && internalCameraManager != null) {
            internalCameraManager.startRecording();
            Log.i(TAG, "Internal camera recording started");
        }
    }

    public void stopRecording() {
        if (currentSource == CameraSource.USB && usbEncoder != null) {
            usbEncoder.stopRecording();
        } else if (currentSource.toString().contains("INTERNAL") && internalCameraManager != null) {
            internalCameraManager.stopRecording();
        }
    }

    public boolean isRecording() {
        if (currentSource == CameraSource.USB && usbEncoder != null) {
            return usbEncoder.isRecording();
        } else if (currentSource.toString().contains("INTERNAL") && internalCameraManager != null) {
            return internalCameraManager.isRecording();
        }
        return false;
    }

    // ========================= Audio Manager Connection =========================

    public void connectAudioManager(AudioManager audioManager) {
        setAudioManager(audioManager);
    }

    public void disconnectAudioManager() {
        if (audioManager != null) {
            audioManager.setAudioEncoderCallback(null);
            audioManager = null;
        }
    }

    // ========================= Shared =========================

    public void stopAll() {
        executor.execute(() -> {
            stopStreaming();
            if (currentSource == CameraSource.USB) {
                releaseUsbCameraInternal();
            } else if (currentSource.toString().contains("INTERNAL") && internalCameraManager != null) {
                internalCameraManager.stopPreview();
                internalCameraManager = null;
            }
        });
    }

    public CameraSource getCurrentCameraSource() {
        return currentSource;
    }

    public UsbH264Encoder getUsbEncoder() {
        return usbEncoder;
    }

    public InternalCameraManager getInternalCameraManager() {
        return internalCameraManager;
    }
    public void stopPreview() {
        if (internalCameraManager != null) {
            internalCameraManager.stopPreview();
        }
        // USB: preview is handled by UVCCameraTextureView, not encoder
    }

    public void startPreview() {
        if (internalCameraManager != null) {
            internalCameraManager.startPreview();
        }
        // USB: preview is handled by UVCCameraTextureView, not encoder
    }
    public void restartCamera() {
        if (currentSource == CameraSource.USB) {
            Log.i(TAG, "USB camera restart - reinitializing with new settings");
            if (savedUsbControlBlock != null) {
                isProgrammaticRestart = true;

                // Release current camera
                releaseUsbCameraInternal();

                // ✅ CRITICAL: Force TextureView to fully release
                activity.runOnUiThread(() -> {
                    usbTextureView.setVisibility(TextureView.GONE);
                });

                // ✅ Wait longer for SurfaceTexture cleanup (500ms)
                new Handler(Looper.getMainLooper()).postDelayed(() -> {
                    // Clear the saved block temporarily to force fresh detection
                    USBMonitor.UsbControlBlock block = savedUsbControlBlock;
                    savedUsbControlBlock = null;

                    // Restart with saved control block
                    startUsbCamera(block);

                    // Reset flag after restart completes
                    new Handler(Looper.getMainLooper()).postDelayed(() -> {
                        isProgrammaticRestart = false;
                    }, 2000);
                }, 500);
            } else {
                Log.w(TAG, "No saved USB control block, cannot restart");
            }
        } else if (currentSource.toString().contains("INTERNAL")) {
            CameraHelper.Facing facing = (currentSource == CameraSource.INTERNAL_FRONT) ?
                    CameraHelper.Facing.FRONT : CameraHelper.Facing.BACK;

            if (internalCameraManager != null) {
                internalCameraManager.stopPreview();
                internalCameraManager = null;
            }

            try {
                Thread.sleep(200);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }

            startInternalCamera(facing);
        }
    }

    /**
     * Get supported resolutions for current camera
     */
    public List<InternalCameraManager.Size> getCurrentCameraResolutions() {
        if (currentSource.toString().contains("INTERNAL") && internalCameraManager != null) {
            return internalCameraManager.getSupportedResolutions();
        } else if (currentSource == CameraSource.USB) {
            // Return null for USB - we'll use hardcoded values
            return null;
        }
        return null;
    }

    /**
     * Get supported FPS ranges for current camera
     */
    public List<Integer> getCurrentCameraFpsRanges() {
        if (currentSource.toString().contains("INTERNAL") && internalCameraManager != null) {
            return internalCameraManager.getSupportedFpsRanges();
        } else if (currentSource == CameraSource.USB) {
            // Return null for USB - we'll use hardcoded values
            return null;
        }
        return null;
    }

    /**
     * Get current camera ID (for internal camera)
     */
    public String getCurrentCameraId() {
        if (currentSource.toString().contains("INTERNAL") && internalCameraManager != null) {
            return internalCameraManager.getCurrentCameraId();
        }
        return null;
    }

    /**
     * Check if current camera is USB
     */
    public boolean isCurrentCameraUsb() {
        return currentSource == CameraSource.USB;
    }

    // Add this method to parse supported resolutions from the JSON string
    private void parseAndStoreSupportedResolutions(String supportedSizesString) {
        List<StreamProfile.ResolutionItem> resolutions = new ArrayList<>();

        if (supportedSizesString == null || supportedSizesString.isEmpty()) {
            Log.w(TAG, "No supported sizes string to parse");
            return;
        }

        try {
            // Parse JSON format: {"formats":[{"index":1,"type":6,"default":1,"size":["1920x1080","1280x720",...]}]}
            // Find the size array
            int startIdx = supportedSizesString.indexOf("\"size\":[");
            if (startIdx > 0) {
                String sizesPart = supportedSizesString.substring(startIdx + 8);
                int endIdx = sizesPart.indexOf("]");
                if (endIdx > 0) {
                    String sizesStr = sizesPart.substring(0, endIdx);
                    // Split by comma, but be careful with quotes
                    String[] sizeTokens = sizesStr.split(",");
                    for (String token : sizeTokens) {
                        token = token.replace("\"", "").trim();
                        String[] dimensions = token.split("x");
                        if (dimensions.length == 2) {
                            try {
                                int w = Integer.parseInt(dimensions[0]);
                                int h = Integer.parseInt(dimensions[1]);
                                String label = w + "x" + h;
                                if (w == 1920 && h == 1080) label += " (1080p)";
                                else if (w == 1280 && h == 720) label += " (720p)";
                                else if (w == 720 && h == 576) label += " (576i)";
                                else if (w == 720 && h == 480) label += " (480p)";
                                resolutions.add(new StreamProfile.ResolutionItem(w, h, label));
                            } catch (NumberFormatException e) {
                                Log.w(TAG, "Failed to parse resolution: " + token);
                            }
                        }
                    }
                }
            }

            // Remove duplicates (keep first occurrence)
            List<StreamProfile.ResolutionItem> uniqueResolutions = new ArrayList<>();
            for (StreamProfile.ResolutionItem res : resolutions) {
                boolean exists = false;
                for (StreamProfile.ResolutionItem existing : uniqueResolutions) {
                    if (existing.width == res.width && existing.height == res.height) {
                        exists = true;
                        break;
                    }
                }
                if (!exists) {
                    uniqueResolutions.add(res);
                }
            }

            StreamProfile.setUsbSupportedResolutions(uniqueResolutions);
            Log.i(TAG, "✅ Parsed and stored " + uniqueResolutions.size() + " supported resolutions");

        } catch (Exception e) {
            Log.e(TAG, "Failed to parse supported resolutions: " + e.getMessage());
        }
    }
    private void parseAndStoreSupportedFramerates(String supportedSizesString) {
        List<Integer> framerates = new ArrayList<>();

        // Simply add ALL standard framerates - no parsing, no capping
        framerates.add(60);
        framerates.add(59);
        framerates.add(50);
        framerates.add(48);
        framerates.add(30);
        framerates.add(29);
        framerates.add(25);
        framerates.add(24);
        framerates.add(23);
        framerates.add(20);
        framerates.add(15);
        framerates.add(10);

        // Remove duplicates and sort descending
        List<Integer> uniqueFramerates = new ArrayList<>();
        for (int fps : framerates) {
            if (!uniqueFramerates.contains(fps)) {
                uniqueFramerates.add(fps);
            }
        }
        java.util.Collections.sort(uniqueFramerates, (a, b) -> b - a);

        StreamProfile.setUsbSupportedFps(uniqueFramerates);
        Log.i(TAG, "✅ USB supported FPS (all options): " + uniqueFramerates);
    }
    // Add this method to MyCameraManager class
    public void setInternalCameraFpsListener(InternalCameraManager.FpsUpdateListener listener) {
        if (internalCameraManager != null) {
            internalCameraManager.setFpsUpdateListener(listener);
        }
    }
}
