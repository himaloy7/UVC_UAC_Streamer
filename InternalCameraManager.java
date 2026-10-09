package com.serenegiant.usbcameratest.managers;

import android.Manifest;
import android.app.Activity;
import android.content.Context;
import android.content.pm.PackageManager;
import android.graphics.Matrix;
import android.graphics.SurfaceTexture;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaFormat;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.util.Log;
import android.util.Size;
import android.view.Surface;
import android.view.TextureView;

import androidx.annotation.NonNull;
import androidx.core.content.ContextCompat;

import com.pedro.encoder.input.video.CameraHelper;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import android.graphics.ImageFormat;
import java.util.Collections;

public class InternalCameraManager implements CameraControls {
    private static final String TAG = "InternalCameraManager";

    // Video parameters - use StreamProfile
    // Video parameters - use StreamProfile dynamically
    private int width = StreamProfile.getWidth();
    private int height = StreamProfile.getHeight();
    private int fps = StreamProfile.getFps();

    // Audio parameters (configurable) - use StreamProfile as defaults
    private int audioSampleRate = StreamProfile.INTERNAL_AUDIO_SAMPLE_RATE;
    private int audioBitrate = StreamProfile.getAudioBitrate();
    private int audioChannels = StreamProfile.AUDIO_CHANNELS;

    // Encoders
    private MediaCodec videoEncoder;
    private MediaCodec audioEncoder;
    private AudioManager audioManager;
    private RecordingManager recordingManager;
    private RTMPManager rtmpManager;
    private Handler mainHandler; // Add this
    private boolean isFirstAudioFrame = true;

    // Add these fields to your class
    private volatile long recordingStartTimeUs = 0;
    private volatile boolean recordingStarted = false;

    // State flags
    private String rtmpUrl;
    private boolean isRecording = false;
    private boolean recordingRequested = false;

    // Sync fields
    private AtomicLong videoFrameCounter = new AtomicLong(0);
    private AtomicLong audioFrameCounter = new AtomicLong(0);
    private boolean isFirstVideoFrame = true;

    private long masterClockOffset = 0;
    private static final long MAX_DRIFT_US = 50000;
    private long lastResyncTime = 0;
    private static final long RESYNC_INTERVAL_US = 60000000;
    private boolean videoFormatCaptured = false;
    private boolean audioReady = false;

    // Audio constants
    private int audioSamplesPerFrame = 1024;
    private long audioFrameDurationUs;
    private long videoFrameDurationUs;
    private int sensorOrientation = 90; // Default

    // SPS/PPS for RTMP
    private ByteBuffer cachedSps;
    private ByteBuffer cachedPps;
    private boolean hasCachedCodecData = false;

    // Pending formats for recording
    private MediaFormat pendingVideoFormat;
    private MediaFormat pendingAudioFormat;
    private final Object recordingLock = new Object();

    // Camera2 API
    private Surface inputSurface;
    private final Activity activity;
    private final TextureView textureView;
    private CameraHelper.Facing facing = CameraHelper.Facing.BACK;
    private CameraDevice cameraDevice;
    private CameraCaptureSession captureSession;
    private Handler backgroundHandler;
    private HandlerThread backgroundThread;
    private boolean isCameraOpen = false;
    private boolean isSessionConfigured = false;
    private SurfaceTexture surfaceTexture;
    private int textureWidth = 0;
    private int textureHeight = 0;

    // Draining
    private Handler drainHandler;
    private boolean isDraining = false;
    private final Object drainLock = new Object();
    private long keyframePts = 0;

    // Add these fields at the top with other fields
    private String currentCameraId;
    private List<Size> supportedResolutions;
    private List<Integer> supportedFpsRanges;

    // Add with other fields at the top
    private long lastVideoFrameTime = 0;
    private int videoFrameCount = 0;
    private long totalVideoFps = 0;
    private int lastReportedFps = 0;
    private FpsUpdateListener fpsUpdateListener;

    // Add interface
    public interface FpsUpdateListener {
        void onFpsMeasured(int fps);
    }

    public void setFpsUpdateListener(FpsUpdateListener listener) {
        this.fpsUpdateListener = listener;
    }

    // Camera Controls fields
    private float currentZoom = 1.0f;
    private float maxZoom = 1.0f;
    private int currentExposure = 0;
    private int minExposure = 0;
    private int maxExposure = 0;
    private String currentFocusMode = "auto";
    private String currentWhiteBalance = "auto";
    private CaptureRequest.Builder currentRequestBuilder;

    private Runnable drainRunnable = new Runnable() {
        @Override
        public void run() {
            if (!isDraining) return;
            drainVideoEncoder();
            drainAudioEncoder();
            // In drainRunnable
            //Log.d(TAG, "drainRunnable running, isDraining=" + isDraining);
            drainHandler.postDelayed(this, 5);
        }
    };

    // Constructor
    public InternalCameraManager(Activity activity, TextureView textureView, AudioManager audioManager) {
        this.activity = activity;
        this.textureView = textureView;
        this.audioManager = audioManager;
        this.mainHandler = new Handler(Looper.getMainLooper()); // Initialize main handler
        calculateFrameDurations();
    }

    private void calculateFrameDurations() {
        videoFrameDurationUs = 1_000_000 / fps;
        audioFrameDurationUs = (audioSamplesPerFrame * 1_000_000L) / audioSampleRate;
        Log.d(TAG, "Video frame duration: " + videoFrameDurationUs + "us");
        Log.d(TAG, "Audio frame duration: " + audioFrameDurationUs + "us");
    }

    public void setAudioParameters(int sampleRate, int channels, int bitrate) {
        this.audioSampleRate = sampleRate;
        this.audioChannels = channels;
        this.audioBitrate = bitrate;
        calculateFrameDurations();

        if (audioEncoder != null && !isRecording) {
            recreateAudioEncoder();
        }
    }

    public void setRecordingManager(RecordingManager recordingManager) {
        this.recordingManager = recordingManager;
    }

    public void setRtmpManager(RTMPManager rtmpManager) {
        this.rtmpManager = rtmpManager;

        // ✅ Pre-configure RTMP with correct audio parameters IMMEDIATELY
        if (rtmpManager != null && audioSampleRate > 0) {
            rtmpManager.preConfigureAudio(audioSampleRate, audioChannels == 2);
            Log.i(TAG, "✅ RTMPManager pre-configured with audio: " + audioSampleRate + "Hz, " + audioChannels + "ch");
        }
    }

    public void setFacing(CameraHelper.Facing facing) {
        this.facing = facing;
    }

    // ==================== Draining Control ====================

    public void startDraining() {
        synchronized (drainLock) {
            if (isDraining) return;
            isDraining = true;
            if (drainHandler != null) {
                drainHandler.post(drainRunnable);
                Log.d(TAG, "Draining started");
            } else {
                Log.e(TAG, "drainHandler is null, cannot start draining");
            }
        }
    }

    public void stopDraining() {
        synchronized (drainLock) {
            isDraining = false;
            if (drainHandler != null) {
                drainHandler.removeCallbacks(drainRunnable);
            }
            Log.d(TAG, "Draining stopped");
        }
    }

    // ==================== Camera Preview ====================

    // FIX: startPreview - ensure audio encoder is created with proper format
// In startPreview(), ensure encoders are fresh
    public void startPreview() {
        try {
            Log.i(TAG, "Starting preview...");

            // Reset encoders
            if (videoEncoder != null) {
                videoEncoder.stop();
                videoEncoder.release();
                videoEncoder = null;
            }
            if (audioEncoder != null) {
                audioEncoder.stop();
                audioEncoder.release();
                audioEncoder = null;
            }

            // ✅ Ensure audio is running (it will be started if needed)
            if (audioManager != null && !audioManager.isRunning()) {
                audioManager.startAudioLoopback();
                Log.i(TAG, "Audio capture started for preview");
            }

            // Reset counters
            videoFrameCounter.set(0);
            audioFrameCounter.set(0);
            isFirstVideoFrame = true;
            masterClockOffset = 0;
            lastResyncTime = 0;

            // Setup encoders
            setupVideoEncoder();
            setupAudioEncoder();
            setupAudioCallback();

            // Initialize drain handler on main thread
            drainHandler = new Handler(Looper.getMainLooper());

            // Start camera immediately
            if (textureView != null) {
                if (textureView.isAvailable()) {
                    configureTransform(textureView.getWidth(), textureView.getHeight());
                    openCamera();
                } else {
                    textureView.setSurfaceTextureListener(new TextureView.SurfaceTextureListener() {
                        @Override
                        public void onSurfaceTextureAvailable(SurfaceTexture surface, int width, int height) {
                            Log.d(TAG, "TextureView available: " + width + "x" + height);
                            configureTransform(width, height);
                            openCamera();
                        }

                        @Override
                        public void onSurfaceTextureSizeChanged(SurfaceTexture surface, int width, int height) {
                            configureTransform(width, height);
                        }

                        @Override
                        public boolean onSurfaceTextureDestroyed(SurfaceTexture surface) {
                            return true;
                        }

                        @Override
                        public void onSurfaceTextureUpdated(SurfaceTexture surface) {}
                    });
                }
            } else {
                Log.e(TAG, "TextureView is null!");
            }

        } catch (Exception e) {
            Log.e(TAG, "Failed to start preview: " + e.getMessage(), e);
        }
    }

    private void openCamera() {

        try {
            // Start background thread for camera operations
            backgroundThread = new HandlerThread("CameraBackground");
            backgroundThread.start();
            backgroundHandler = new Handler(backgroundThread.getLooper());

            // Check permission
            if (ContextCompat.checkSelfPermission(activity, Manifest.permission.CAMERA)
                    != PackageManager.PERMISSION_GRANTED) {
                Log.e(TAG, "Camera permission not granted");
                return;
            }

            // Get camera ID
            String cameraId = getCameraId(facing == CameraHelper.Facing.FRONT);
            this.currentCameraId = cameraId; // Store it

            // Get CameraManager and open camera
            CameraManager cameraManager = (CameraManager) activity.getSystemService(Context.CAMERA_SERVICE);

            // In openCamera(), update the callback:
            cameraManager.openCamera(cameraId, new CameraDevice.StateCallback() {
                @Override
                public void onOpened(@NonNull CameraDevice camera) {
                    cameraDevice = camera;
                    isCameraOpen = true;

                    try {
                        CameraManager cameraManager = (CameraManager) activity.getSystemService(Context.CAMERA_SERVICE);
                        CameraCharacteristics characteristics = cameraManager.getCameraCharacteristics(cameraId);
                        sensorOrientation = characteristics.get(CameraCharacteristics.SENSOR_ORIENTATION);

                        // ✅ ADD THIS LINE - Initialize camera controls
                        initCameraControls();

                        // ✅ DEBUG: Check supported FPS ranges
                        android.util.Range<Integer>[] fpsRanges = characteristics.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES);
                        Log.i(TAG, "Supported FPS ranges on this device:");
                        for (android.util.Range<Integer> range : fpsRanges) {
                            Log.i(TAG, "  " + range.getLower() + " - " + range.getUpper() + " fps");
                        }

                        Log.i(TAG, "Camera sensor orientation: " + sensorOrientation);
                    } catch (Exception e) {
                        Log.e(TAG, "Failed to get sensor orientation", e);
                    }

                    Log.i(TAG, "Camera opened successfully");
                    createCaptureSession();
                }

                @Override
                public void onDisconnected(@NonNull CameraDevice camera) {
                    Log.w(TAG, "Camera disconnected");
                    isCameraOpen = false;
                    isSessionConfigured = false;
                    if (cameraDevice != null) {
                        cameraDevice.close();
                        cameraDevice = null;
                    }
                }

                @Override
                public void onError(@NonNull CameraDevice camera, int error) {
                    Log.e(TAG, "Camera error: " + error);
                    isCameraOpen = false;
                    isSessionConfigured = false;
                    if (cameraDevice != null) {
                        cameraDevice.close();
                        cameraDevice = null;
                    }
                }
            }, backgroundHandler);

        } catch (Exception e) {
            Log.e(TAG, "Failed to open camera: " + e.getMessage(), e);
        }
    }

    private void initCameraControls() {
        try {
            if (currentCameraId == null) return;

            CameraManager cameraManager = (CameraManager) activity.getSystemService(Context.CAMERA_SERVICE);
            CameraCharacteristics characteristics = cameraManager.getCameraCharacteristics(currentCameraId);

            // Get max zoom
            Float maxZoomFloat = characteristics.get(CameraCharacteristics.SCALER_AVAILABLE_MAX_DIGITAL_ZOOM);
            if (maxZoomFloat != null) {
                maxZoom = maxZoomFloat;
                Log.i(TAG, "Max zoom: " + maxZoom + "x");
            }

            // Get exposure range
            android.util.Range<Integer> exposureRange = characteristics.get(
                    CameraCharacteristics.CONTROL_AE_COMPENSATION_RANGE);
            if (exposureRange != null) {
                minExposure = exposureRange.getLower();
                maxExposure = exposureRange.getUpper();
                Log.i(TAG, "Exposure range: " + minExposure + " to " + maxExposure);
            }

            // Get supported focus modes
            int[] focusModes = characteristics.get(CameraCharacteristics.CONTROL_AF_AVAILABLE_MODES);
            if (focusModes != null) {
                Log.i(TAG, "Supported focus modes: " + java.util.Arrays.toString(focusModes));
            }

            // Get supported white balance modes
            int[] wbModes = characteristics.get(CameraCharacteristics.CONTROL_AWB_AVAILABLE_MODES);
            if (wbModes != null) {
                Log.i(TAG, "Supported WB modes: " + java.util.Arrays.toString(wbModes));
            }
        } catch (Exception e) {
            Log.e(TAG, "Failed to init camera controls", e);
        }
    }

    private void createCaptureSession() {
        try {
            if (cameraDevice == null || !isCameraOpen) {
                Log.e(TAG, "Camera device not ready");
                return;
            }

            // Prepare surfaces
            List<Surface> surfaces = new ArrayList<>();

            // Add encoder surface (required for recording/streaming)
            if (inputSurface == null) {
                Log.e(TAG, "Input surface is null!");
                return;
            }
            surfaces.add(inputSurface);

            // Add preview surface if TextureView is ready
            Surface previewSurface = null;
            if (textureView != null && textureView.isAvailable()) {
                surfaceTexture = textureView.getSurfaceTexture();
                if (surfaceTexture != null) {
                    surfaceTexture.setDefaultBufferSize(width, height);
                    previewSurface = new Surface(surfaceTexture);
                    surfaces.add(previewSurface);
                }
            }

            // Create capture request builder
            CaptureRequest.Builder builder = cameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_RECORD);
            // ✅ Store the builder for later use
            this.currentRequestBuilder = builder;
            for (Surface surface : surfaces) {
                builder.addTarget(surface);
            }

            // ✅ CRITICAL FIX: Set FPS range on the camera
            android.util.Range<Integer> fpsRange = new android.util.Range<>(fps, fps);
            builder.set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, fpsRange);

            // Also set control mode to ensure FPS is respected
            builder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE);
            builder.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON);

            Log.i(TAG, "✅ Setting camera FPS range to: " + fpsRange.getLower() + "-" + fpsRange.getUpper());

            // Create capture session
            cameraDevice.createCaptureSession(surfaces,
                    new CameraCaptureSession.StateCallback() {
                        @Override
                        public void onConfigured(@NonNull CameraCaptureSession session) {
                            try {
                                captureSession = session;
                                isSessionConfigured = true;

                                // Set repeating request with the builder
                                CaptureRequest request = builder.build();
                                session.setRepeatingRequest(request, null, backgroundHandler);

                                // Start draining only after session is configured
                                startDraining();

                                // Fix preview rotation
                                if (textureView != null && textureView.isAvailable()) {
                                    activity.runOnUiThread(() -> fixPreviewRotation());
                                }

                            } catch (Exception e) {
                                Log.e(TAG, "Failed to set repeating request: " + e.getMessage(), e);
                            }
                        }

                        @Override
                        public void onConfigureFailed(@NonNull CameraCaptureSession session) {
                            Log.e(TAG, "Camera session configuration failed");
                            isSessionConfigured = false;
                        }
                    }, backgroundHandler);

        } catch (Exception e) {
            Log.e(TAG, "Failed to create capture session: " + e.getMessage(), e);
        }
    }

    // FIX: configureTransform - ensure UI thread
    private void configureTransform(int viewWidth, int viewHeight) {
        // Run on UI thread
        if (Looper.myLooper() != Looper.getMainLooper()) {
            mainHandler.post(() -> configureTransform(viewWidth, viewHeight));
            return;
        }

        try {
            if (textureView == null || !textureView.isAvailable()) {
                return;
            }

            textureWidth = viewWidth;
            textureHeight = viewHeight;

            float cameraAspect = (float) width / height;
            float viewAspect = (float) viewWidth / viewHeight;

            float scaleX, scaleY;
            if (cameraAspect > viewAspect) {
                scaleX = (float) viewWidth / width;
                scaleY = scaleX * ((float) height / viewHeight);
            } else {
                scaleY = (float) viewHeight / height;
                scaleX = scaleY * ((float) width / viewWidth);
            }

            Matrix matrix = new Matrix();
            matrix.setScale(scaleX, scaleY);
            textureView.setTransform(matrix);

            Log.d(TAG, "Transform configured: scaleX=" + scaleX + ", scaleY=" + scaleY);

        } catch (Exception e) {
            Log.e(TAG, "Failed to configure transform: " + e.getMessage(), e);
        }
    }

    private String getCameraId(boolean useFront) {
        try {
            CameraManager cameraManager = (CameraManager) activity.getSystemService(Context.CAMERA_SERVICE);
            String[] cameraIds = cameraManager.getCameraIdList();
            for (String id : cameraIds) {
                CameraCharacteristics characteristics = cameraManager.getCameraCharacteristics(id);
                Integer facing = characteristics.get(CameraCharacteristics.LENS_FACING);
                if (useFront && facing != null && facing == CameraCharacteristics.LENS_FACING_FRONT) {
                    return id;
                } else if (!useFront && facing != null && facing == CameraCharacteristics.LENS_FACING_BACK) {
                    return id;
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "Error getting camera ID", e);
        }
        return "0"; // fallback to first camera
    }

    public void stopPreview() {
        Log.d(TAG, "stopPreview() called");

        // First stop recording if active
        if (isRecording) {
            stopRecording();
        }

        // Stop streaming if active
        if (isStreaming()) {
            stopStreaming();
        }

        // ✅ Stop audio loopback only when preview is fully stopping
        if (audioManager != null) {
            audioManager.stopAudioLoopback();
            Log.i(TAG, "Audio capture stopped (preview ending)");
        }

        stopDraining();

        // Close capture session first
        if (captureSession != null) {
            try {
                captureSession.close();  // Just close, no stop() method
                captureSession = null;
            } catch (Exception e) {
                Log.e(TAG, "Error closing capture session", e);
            }
        }

        isSessionConfigured = false;

        // Close camera device
        if (cameraDevice != null) {
            try {
                cameraDevice.close();
                cameraDevice = null;
            } catch (Exception e) {
                Log.e(TAG, "Error closing camera device", e);
            }
        }

        isCameraOpen = false;

        // Stop background thread
        if (backgroundThread != null) {
            backgroundThread.quitSafely();
            try {
                backgroundThread.join(500); // Wait up to 500ms
                backgroundThread = null;
                backgroundHandler = null;
            } catch (InterruptedException e) {
                Log.e(TAG, "Error stopping background thread", e);
            }
        }

        // Release encoders
        stop();

        Log.i(TAG, "Camera preview stopped");
    }

    // ==================== Video Encoder Setup ====================

    private void setupVideoEncoder() throws Exception {
        MediaFormat format = MediaFormat.createVideoFormat(
                MediaFormat.MIMETYPE_VIDEO_AVC,
                this.width,
                this.height
        );

        format.setInteger(MediaFormat.KEY_BIT_RATE, StreamProfile.getBitrate());
        format.setInteger(MediaFormat.KEY_FRAME_RATE, this.fps);
        format.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 2);
        format.setInteger(MediaFormat.KEY_COLOR_FORMAT,
                MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface);
        format.setInteger(MediaFormat.KEY_PROFILE, MediaCodecInfo.CodecProfileLevel.AVCProfileHigh);
        format.setInteger(MediaFormat.KEY_LEVEL, MediaCodecInfo.CodecProfileLevel.AVCLevel31);

        // ✅ Add this - force frame rate to be included in output format
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            format.setInteger(MediaFormat.KEY_PRIORITY, 0);
        }

        // ✅ Also add this - explicitly set capture rate
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            format.setFloat(MediaFormat.KEY_CAPTURE_RATE, (float) this.fps);
        }

        videoEncoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC);
        videoEncoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
        inputSurface = videoEncoder.createInputSurface();
        videoEncoder.start();

        Log.i(TAG, "Video encoder ready: " + width + "x" + height + " @" + fps + "fps");
    }

    // ==================== Audio Encoder Setup ====================

    // FIX: setupAudioCallback - ensure we're using the correct audio source
    private void setupAudioCallback() {
        if (audioManager != null) {
            Log.i(TAG, "Setting callback on AudioManager instance: " + System.identityHashCode(audioManager));
            // Remove old callback first if needed
            audioManager.setAudioEncoderCallback(null);
            audioManager.setAudioEncoderCallback(new AudioManager.AudioEncoderCallback() {
                @Override
                public void onAudioFrame(byte[] pcmData, int size) {
                    encodeAudioFrame(pcmData, size);
                }
            });

            // Ensure audio is running
            if (!audioManager.isRunning()) {
                Log.d(TAG, "Starting audio loopback from setupAudioCallback");
                audioManager.startAudioLoopback();
            }
        }
    }

    // FIX: setupAudioEncoder - ensure we're using the correct sample rate from AudioManager
    private void setupAudioEncoder() throws Exception {
        // Use current audio parameters from AudioManager if available
        int currentSampleRate = audioSampleRate;
        int currentChannels = audioChannels;

        if (audioManager != null) {
            currentSampleRate = audioManager.getCurrentSampleRate();
            currentChannels = audioManager.getCurrentChannels();
            if (currentSampleRate > 0) {
                Log.d(TAG, "Using AudioManager sample rate: " + currentSampleRate);
                audioSampleRate = currentSampleRate;
            }
            if (currentChannels > 0) {
                audioChannels = currentChannels;
            }
        }

        // ✅ FORCE 48000Hz if it's not already
        if (audioSampleRate != 48000) {
            Log.w(TAG, "⚠️ Audio sample rate is " + audioSampleRate + "Hz, forcing to 48000Hz for RTMP compatibility");
            audioSampleRate = 48000;
        }

        MediaFormat format = MediaFormat.createAudioFormat(
                MediaFormat.MIMETYPE_AUDIO_AAC,
                audioSampleRate,
                audioChannels
        );

        format.setInteger(MediaFormat.KEY_BIT_RATE, audioBitrate);
        format.setInteger(MediaFormat.KEY_AAC_PROFILE,
                MediaCodecInfo.CodecProfileLevel.AACObjectLC);
        format.setInteger(MediaFormat.KEY_CHANNEL_COUNT, audioChannels);
        format.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 8192);

        // ✅ CRITICAL: Set these parameters BEFORE configuring the encoder
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            format.setInteger(MediaFormat.KEY_PRIORITY, 0); // Real-time priority
        }

        // ✅ Force sample rate in the format (should already be set, but be explicit)
        format.setInteger(MediaFormat.KEY_SAMPLE_RATE, audioSampleRate);

        Log.i(TAG, "🎵 Creating audio encoder with:");
        Log.i(TAG, "   Sample rate: " + audioSampleRate + " Hz");
        Log.i(TAG, "   Channels: " + audioChannels);
        Log.i(TAG, "   Bitrate: " + audioBitrate);

        audioEncoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC);
        audioEncoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
        audioEncoder.start();

        // ✅ Immediately capture the format after start to ensure it's correct
        MediaFormat actualFormat = audioEncoder.getOutputFormat();
        int actualSampleRate = actualFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE);
        Log.i(TAG, "✅ Audio encoder actual format: " + actualSampleRate + "Hz, " +
                actualFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT) + "ch");

        if (actualSampleRate != audioSampleRate) {
            Log.e(TAG, "❌ CRITICAL: Encoder using " + actualSampleRate + "Hz but we need " + audioSampleRate + "Hz!");
        }
    }

    private void recreateAudioEncoder() {
        try {
            if (audioEncoder != null) {
                audioEncoder.stop();
                audioEncoder.release();
            }
            setupAudioEncoder();
        } catch (Exception e) {
            Log.e(TAG, "Failed to recreate audio encoder: " + e.getMessage());
        }
    }

    // Add this helper method to clone MediaFormat
// Add this helper method to clone MediaFormat
    private MediaFormat cloneMediaFormat(MediaFormat original) {
        // Create a new MediaFormat
        MediaFormat clone = new MediaFormat();

        // Copy all keys using the correct MediaFormat methods
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            for (String key : original.getKeys()) {
                try {
                    // Check the type of value for each key
                    if (key.equals("csd-0") || key.equals("csd-1")) {
                        // For ByteBuffer keys, clone the buffer
                        ByteBuffer originalBuffer = original.getByteBuffer(key);
                        if (originalBuffer != null && originalBuffer.remaining() > 0) {
                            ByteBuffer cloneBuffer = ByteBuffer.allocate(originalBuffer.remaining());
                            originalBuffer.position(0);
                            cloneBuffer.put(originalBuffer);
                            cloneBuffer.flip();
                            clone.setByteBuffer(key, cloneBuffer);
                            // Reset original position
                            originalBuffer.position(0);
                        }
                    } else {
                        // Try to copy as different types using try-catch
                        try {
                            Integer intValue = original.getInteger(key);
                            clone.setInteger(key, intValue);
                        } catch (Exception e) {
                            try {
                                String stringValue = original.getString(key);
                                clone.setString(key, stringValue);
                            } catch (Exception e2) {
                                try {
                                    Long longValue = original.getLong(key);
                                    clone.setLong(key, longValue);
                                } catch (Exception e3) {
                                    try {
                                        Float floatValue = original.getFloat(key);
                                        clone.setFloat(key, floatValue);
                                    } catch (Exception e4) {
                                        Log.w(TAG, "Could not copy key: " + key);
                                    }
                                }
                            }
                        }
                    }
                } catch (Exception e) {
                    Log.w(TAG, "Failed to copy key: " + key, e);
                }
            }
        }
        return clone;
    }

    // ==================== Audio Encoding ====================

    // Modify encodeAudioFrame() to use system time:
    public void encodeAudioFrame(byte[] pcmData, int size) {
        // Don't process audio before recording is ready
        if (recordingRequested && !audioReady) {
            return;
        }

        // Process if recording is active, streaming is active, OR about to start
        boolean isStreaming = (rtmpManager != null && rtmpManager.isConnected());
        if (!recordingStarted && !isRecording && !isStreaming) {
            return;
        }

        if (audioEncoder == null) return;

        try {
            int index = audioEncoder.dequeueInputBuffer(10000);
            if (index < 0) return;

            ByteBuffer inputBuffer = audioEncoder.getInputBuffer(index);
            if (inputBuffer == null) return;

            inputBuffer.clear();
            inputBuffer.put(pcmData, 0, size);

            long currentTimeUs = System.nanoTime() / 1000;
            long ptsUs;
            long frameIndex = audioFrameCounter.getAndIncrement();

            // ✅ If recording started, check if clock offset is set
            if (recordingStarted) {
                if (masterClockOffset == 0) {
                    // First frame after recording started, reset clock
                    masterClockOffset = currentTimeUs;
                    ptsUs = 0;
                    Log.i(TAG, "🔄 Audio PTS reset for new recording at frame " + frameIndex);
                } else {
                    // Use system time relative to recording start
                    ptsUs = currentTimeUs - masterClockOffset;
                }
            } else {
                // Before recording officially starts, use placeholder
                ptsUs = 0;
            }

            // Ensure PTS is not negative
            if (ptsUs < 0) ptsUs = 0;

            // Log first few frames
            if (frameIndex < 3) {
                Log.i(TAG, "🎵 Audio frame: index=" + frameIndex + ", pts=" + ptsUs);
            }

            audioEncoder.queueInputBuffer(index, 0, size, ptsUs, 0);

            // Drain the encoder
            drainAudioEncoder();

        } catch (Exception e) {
            Log.e(TAG, "Audio encoding error: " + e.getMessage());
        }
    }

    // FIX: Update drainAudioEncoder to capture format for recording
    private void drainAudioEncoder() {
        if (audioEncoder == null) return;

        try {
            MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
            int index;

            while (true) {
                index = audioEncoder.dequeueOutputBuffer(info, 0);

                if (index == MediaCodec.INFO_TRY_AGAIN_LATER) {
                    break;
                } else if (index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    MediaFormat format = audioEncoder.getOutputFormat();
                    Log.d(TAG, "Audio output format: " + format);

                    synchronized (recordingLock) {
                        pendingAudioFormat = cloneMediaFormat(format);
                        Log.i(TAG, "✅ Audio format captured and cloned");

                        ByteBuffer csd0 = pendingAudioFormat.getByteBuffer("csd-0");
                        Log.i(TAG, "  Cloned audio CSD size: " + (csd0 != null ? csd0.remaining() : 0));

                        int sampleRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE);
                        int channelCount = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT);

                        if (rtmpManager != null) {
                            rtmpManager.setAudioInfo(sampleRate, channelCount == 2);
                            Log.i(TAG, "✅ Audio config sent to RTMPManager: " + sampleRate + "Hz, " + channelCount + "ch");
                        }
                    }
                } else if (index >= 0) {
                    ByteBuffer out = audioEncoder.getOutputBuffer(index);
                    if (out != null && info.size > 0) {
                        long ptsUs = info.presentationTimeUs;

                        // ✅ CRITICAL: Save original buffer state
                        int oldPosition = out.position();
                        int oldLimit = out.limit();

                        // Set to the actual data region
                        out.position(info.offset);
                        out.limit(info.offset + info.size);

                        // ✅ Extract the data into a clean byte array
                        byte[] data = new byte[info.size];
                        out.get(data);

                        // ✅ Restore original buffer state
                        out.position(oldPosition);
                        out.limit(oldLimit);

                        // ✅ For RECORDING - use recording-relative PTS
                        if (isRecording && recordingManager != null && recordingManager.isRecording()) {
                            MediaCodec.BufferInfo recordInfo = new MediaCodec.BufferInfo();

                            // ✅ Calculate recording-relative timestamp
                            long relativePtsUs;
                            if (recordingStarted && recordingStartTimeUs > 0) {
                                // Use the same system time base as video
                                long currentTimeUs = System.nanoTime() / 1000;
                                relativePtsUs = currentTimeUs - recordingStartTimeUs;
                            } else {
                                relativePtsUs = 0;
                            }

                            recordInfo.set(0, info.size, relativePtsUs, info.flags);
                            ByteBuffer recordBuffer = ByteBuffer.wrap(data.clone());
                            recordingManager.writeAudioFrame(recordBuffer, recordInfo);
                            Log.d(TAG, "🎵 Audio written to recording, relativePts=" + relativePtsUs);
                        }

                        // ✅ For RTMP - create a clean buffer
                        if (rtmpManager != null) {
                            MediaCodec.BufferInfo rtmpInfo = new MediaCodec.BufferInfo();
                            rtmpInfo.set(0, info.size, ptsUs, info.flags);
                            ByteBuffer rtmpBuffer = ByteBuffer.wrap(data.clone());
                            rtmpManager.sendAudio(rtmpBuffer, rtmpInfo);
                        }
                    }
                    audioEncoder.releaseOutputBuffer(index, false);
                }
            }
        } catch (IllegalStateException e) {
            Log.e(TAG, "Audio encoder in bad state, skipping this frame");
        }
    }

    // ==================== Video Encoding ====================

    // Modify drainVideoEncoder() to use system time for recording:
    private void drainVideoEncoder() {
        if (videoEncoder == null) {
            Log.w(TAG, "Video encoder is null in drainVideoEncoder");
            return;
        }

        MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
        //int batchCount = 0;

        while (true) {  // ← Change from while (batchCount < 10)
            int index = videoEncoder.dequeueOutputBuffer(info, 0);

            if (index == MediaCodec.INFO_TRY_AGAIN_LATER) {
                break;
            } else if (index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                // ... existing format capture code remains the same ...
                MediaFormat format = videoEncoder.getOutputFormat();
                Log.i(TAG, "Video output format changed: " + format);

                synchronized (recordingLock) {
                    pendingVideoFormat = cloneMediaFormat(format);
                    hasCachedCodecData = true;
                    videoFormatCaptured = true;
                    Log.i(TAG, "✅ Video format captured and cloned");

                    ByteBuffer csd0 = pendingVideoFormat.getByteBuffer("csd-0");
                    ByteBuffer csd1 = pendingVideoFormat.getByteBuffer("csd-1");
                    Log.i(TAG, "  Cloned CSD0 size: " + (csd0 != null ? csd0.remaining() : 0));
                    Log.i(TAG, "  Cloned CSD1 size: " + (csd1 != null ? csd1.remaining() : 0));
                }

                // Extract SPS/PPS for RTMP if available
                ByteBuffer csd0 = format.getByteBuffer("csd-0");
                ByteBuffer csd1 = format.getByteBuffer("csd-1");

                // In drainVideoEncoder, after capturing SPS/PPS:
                if (csd0 != null && csd1 != null && csd0.remaining() > 0 && csd1.remaining() > 0) {
                    cachedSps = ByteBuffer.allocate(csd0.remaining());
                    cachedSps.put(csd0);
                    cachedSps.flip();

                    cachedPps = ByteBuffer.allocate(csd1.remaining());
                    cachedPps.put(csd1);
                    cachedPps.flip();
                    hasCachedCodecData = true;

                    Log.i(TAG, "✅ SPS/PPS captured and cached");

                    // ✅ Also cache in RTMPManager
                    if (rtmpManager != null) {
                        ByteBuffer spsCopy = ByteBuffer.allocate(cachedSps.remaining());
                        cachedSps.position(0);
                        spsCopy.put(cachedSps);
                        spsCopy.flip();
                        cachedSps.position(0);

                        ByteBuffer ppsCopy = ByteBuffer.allocate(cachedPps.remaining());
                        cachedPps.position(0);
                        ppsCopy.put(cachedPps);
                        ppsCopy.flip();
                        cachedPps.position(0);

                        rtmpManager.setCachedVideoConfig(spsCopy, ppsCopy);
                    }

                    // Only send if already connected
                    if (rtmpManager != null && rtmpManager.isConnected()) {
                        setSpsPpsForRtmp();
                    }
                }

            } else if (index >= 0) {
                ByteBuffer out = videoEncoder.getOutputBuffer(index);
                if (out != null && info.size > 0) {

                    // ==================== FPS MEASUREMENT ====================
                    long now = System.nanoTime();
                    if (lastVideoFrameTime > 0) {
                        long intervalUs = (now - lastVideoFrameTime) / 1000;
                        if (intervalUs > 0) {
                            int currentFps = (int)(1_000_000 / intervalUs);
                            totalVideoFps += currentFps;
                            videoFrameCount++;

                            if (videoFrameCount >= 30) {
                                int avgFps = Math.round(totalVideoFps / (float)videoFrameCount);
                                if (avgFps != lastReportedFps) {
                                    lastReportedFps = avgFps;
                                    if (fpsUpdateListener != null) {
                                        activity.runOnUiThread(() -> fpsUpdateListener.onFpsMeasured(avgFps));
                                    }
                                }
                                // Reset for next window
                                totalVideoFps = 0;
                                videoFrameCount = 0;
                            }
                        }
                    }
                    lastVideoFrameTime = now;
                    // ========================================================

                    boolean isKeyFrame = (info.flags & MediaCodec.BUFFER_FLAG_KEY_FRAME) != 0;

                    // Get current frame counter
                    long frameIndex = videoFrameCounter.getAndIncrement();

                    // Calculate PTS based on frame counter (for encoder output)
                    long encoderPtsUs = frameIndex * videoFrameDurationUs;

                    // Start recording when we get a keyframe and recording is requested
                    if (isKeyFrame && recordingRequested && !isRecording &&
                            pendingVideoFormat != null && pendingAudioFormat != null) {

                        // ✅ Set recording start time to NOW
                        recordingStartTimeUs = System.nanoTime() / 1000;
                        recordingStarted = true;

                        // ✅ Reset masterClockOffset for audio (will be set on first audio frame)
                        masterClockOffset = 0;

                        Log.i(TAG, "Keyframe received - starting recording at system time: " + recordingStartTimeUs);
                        actuallyStartRecording();
                    }

                    // ✅ For recording, use system-time based PTS
                    MediaCodec.BufferInfo adjustedInfo = new MediaCodec.BufferInfo();

                    if (isRecording && recordingStarted) {
                        // Use system time for perfect sync with audio
                        long currentTimeUs = System.nanoTime() / 1000;
                        long systemPtsUs = currentTimeUs - recordingStartTimeUs;

                        adjustedInfo.set(info.offset, info.size, systemPtsUs, info.flags);

                        // Log first few frames
                        if (frameIndex < 5) {
                            Log.i(TAG, "📹 Video frame: count=" + frameIndex +
                                    ", isKey=" + isKeyFrame +
                                    ", systemPts=" + systemPtsUs +
                                    ", encoderPts=" + encoderPtsUs);
                        }
                    } else {
                        // Not recording yet, just use encoder PTS (for preview/streaming)
                        adjustedInfo.set(info.offset, info.size, encoderPtsUs, info.flags);

                        if (frameIndex < 5) {
                            Log.i(TAG, "📹 Video frame (preview): count=" + frameIndex +
                                    ", isKey=" + isKeyFrame +
                                    ", encoderPts=" + encoderPtsUs);
                        }
                    }

                    // Write to recording if active
                    synchronized (recordingLock) {
                        if (isRecording && recordingManager != null && recordingManager.isRecording()) {
                            ByteBuffer frameCopy = ByteBuffer.allocate(info.size);
                            out.position(info.offset);
                            out.limit(info.offset + info.size);
                            frameCopy.put(out);
                            frameCopy.flip();
                            recordingManager.writeVideoFrame(frameCopy, adjustedInfo);
                        }
                    }

                    // RTMP streaming (use encoder PTS for streaming)
                    if (rtmpManager != null && rtmpManager.isConnected()) {
                        MediaCodec.BufferInfo rtmpInfo = new MediaCodec.BufferInfo();
                        rtmpInfo.set(info.offset, info.size, encoderPtsUs, info.flags);
                        ByteBuffer rtmpBuffer = out.duplicate();
                        rtmpManager.sendVideo(rtmpBuffer, rtmpInfo);
                    }
                }
                videoEncoder.releaseOutputBuffer(index, false);
                //batchCount++;
            }
        }
    }

    // ==================== Recording Control ====================

    // FIX: startRecording - proper format capture and threading
    public void startRecording() {
        synchronized (recordingLock) {
            if (isRecording) {
                Log.d(TAG, "Already recording");
                return;
            }

            Log.d(TAG, "=== START RECORDING CALLED ===");

            // ✅ Reset counters FIRST
            videoFrameCounter.set(0);
            audioFrameCounter.set(0);
            audioReady = false;
            isFirstVideoFrame = true;
            isFirstAudioFrame = true;
            masterClockOffset = 0;
            lastResyncTime = 0;

            // ✅ Reset recording state
            recordingStarted = false;
            recordingStartTimeUs = 0;

            // ✅ CRITICAL: Flush RTMP audio buffer to prevent old audio
            if (rtmpManager != null) {
                rtmpManager.flushAudioBufferForRecording();
            }

            // ✅ Recreate formats from cache if needed
            if (pendingVideoFormat == null && hasCachedCodecData) {
                pendingVideoFormat = recreateVideoFormatFromCache();
            }

            if (pendingAudioFormat == null && audioEncoder != null) {
                pendingAudioFormat = recreateAudioFormatFromEncoder();
            }

            recordingRequested = true;
            requestKeyFrame();
        }
    }

    // Update actuallyStartRecording() to set recordingStarted flag:
    private void actuallyStartRecording() {
        synchronized (recordingLock) {
            Log.d(TAG, "actuallyStartRecording called - recordingManager=" + (recordingManager != null));

            if (recordingManager != null && !recordingManager.isRecording()) {
                if (pendingVideoFormat == null) {
                    Log.e(TAG, "Video format missing, cannot start recording");
                    return;
                }

                // ✅ Log CSD sizes
                ByteBuffer videoCsd0 = pendingVideoFormat.getByteBuffer("csd-0");
                ByteBuffer videoCsd1 = pendingVideoFormat.getByteBuffer("csd-1");
                Log.i(TAG, "Before startRecording - Video CSD0 size: " + (videoCsd0 != null ? videoCsd0.remaining() : 0));
                Log.i(TAG, "Before startRecording - Video CSD1 size: " + (videoCsd1 != null ? videoCsd1.remaining() : 0));

                if (pendingAudioFormat != null) {
                    ByteBuffer audioCsd0 = pendingAudioFormat.getByteBuffer("csd-0");
                    Log.i(TAG, "Before startRecording - Audio CSD0 size: " + (audioCsd0 != null ? audioCsd0.remaining() : 0));
                }

                recordingManager.startRecording(pendingVideoFormat, pendingAudioFormat);

                isRecording = true;
                recordingRequested = false;

                // recordingStarted is already set when keyframe was received
                Log.i(TAG, "✅ Recording started with formats ready - isRecording=" + isRecording);
            }
        }
    }

    // FIX: stopRecording - clean up properly
    public void stopRecording() {
        synchronized (recordingLock) {
            if (recordingManager != null && recordingManager.isRecording()) {
                recordingManager.stopRecording();
            }

            isRecording = false;
            recordingRequested = false;
            recordingStarted = false;

            videoFormatCaptured = false;
            audioReady = false;

            // ✅ RECREATE AUDIO ENCODER to reset PTS
            if (audioEncoder != null) {
                try {
                    audioEncoder.stop();
                    audioEncoder.release();
                    audioEncoder = null;

                    // Recreate audio encoder
                    setupAudioEncoder();

                    // Re-setup callback
                    setupAudioCallback();

                    Log.i(TAG, "✅ Audio encoder recreated for fresh recording");
                } catch (Exception e) {
                    Log.e(TAG, "Failed to recreate audio encoder", e);
                }
            }

            // ✅ Also reset pending formats - they will be re-captured
            pendingVideoFormat = null;
            pendingAudioFormat = null;

            Log.i(TAG, "Recording stopped - encoder reset for next recording");
        }
    }

    public boolean isRecording() {
        return isRecording;
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

    // ==================== RTMP Streaming ====================

    public void startStreaming(String rtmpUrl) {
        this.rtmpUrl = rtmpUrl;

        videoFrameCounter.set(0);
        audioFrameCounter.set(0);
        isFirstVideoFrame = true;

        Log.d(TAG, "Starting stream to: " + rtmpUrl);

        if (rtmpManager != null) {
            rtmpManager.setCachedAudioConfig(audioSampleRate, audioChannels == 2);
            rtmpManager.connect(rtmpUrl);

            // ✅ Only start audio if not already running (recording might be active)
            if (audioManager != null && !audioManager.isRunning()) {
                audioManager.startAudioLoopback();
                Log.i(TAG, "Audio capture started for streaming");
            } else if (audioManager != null && audioManager.isRunning()) {
                Log.d(TAG, "Audio already running (recording active)");
            }
        }
        Log.i(TAG, "=== Starting stream with audio: " + audioSampleRate + "Hz, " + audioChannels + "ch ===");
    }

    public void stopStreaming() {
        if (rtmpManager != null) {
            rtmpManager.disconnect();
        }
        Log.i(TAG, "Streaming stopped (audio continues for recording)");
    }

    public boolean isStreaming() {
        return rtmpManager != null && rtmpManager.isConnected();
    }

    private void setSpsPpsForRtmp() {
        if (!hasCachedCodecData || cachedSps == null || cachedPps == null || rtmpManager == null) {
            Log.w(TAG, "Cannot set SPS/PPS - missing data or client");
            return;
        }

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

        if (rtmpManager != null && rtmpManager.isConnected()) {
            // ✅ Pass null for the MediaFormat parameter (3rd parameter)
            rtmpManager.setVideoInfo(spsCopy, ppsCopy, null);
            Log.i(TAG, "✅ SPS/PPS sent to RTMP manager");
        }
    }

    // ==================== Lifecycle ====================

    public void stop() {
        stopStreaming();
        stopDraining();

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

    // FIX: fixPreviewRotation - ensure UI thread
    private void fixPreviewRotation() {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            mainHandler.post(this::fixPreviewRotation);
            return;
        }
        if (textureView == null || !textureView.isAvailable()) return;

        textureView.post(() -> {
            try {
                int viewWidth = textureView.getWidth();
                int viewHeight = textureView.getHeight();

                if (viewWidth == 0 || viewHeight == 0) return;

                // Get display rotation
                int displayRotation = activity.getWindowManager().getDefaultDisplay().getRotation();
                int displayRotationDegrees = 0;
                switch (displayRotation) {
                    case android.view.Surface.ROTATION_0:
                        displayRotationDegrees = 0;
                        break;
                    case android.view.Surface.ROTATION_90:
                        displayRotationDegrees = 90;
                        break;
                    case android.view.Surface.ROTATION_180:
                        displayRotationDegrees = 180;
                        break;
                    case android.view.Surface.ROTATION_270:
                        displayRotationDegrees = 270;
                        break;
                }

                // Get camera sensor orientation
                int sensorOrientationDegrees = sensorOrientation;

                // Calculate relative rotation (from documentation)
                int sign = (facing == CameraHelper.Facing.FRONT) ? 1 : -1;
                int relativeRotation = (sensorOrientationDegrees - (displayRotationDegrees * sign) + 360) % 360;

                // Determine if rotation is required (width and height swap)
                boolean isRotationRequired = (relativeRotation % 180) != 0;

                // Get the preview size (your video dimensions)
                int previewWidth = width;
                int previewHeight = height;

                // Calculate scale factors
                float scaleX = 1f;
                float scaleY = 1f;

                if (sensorOrientationDegrees == 0) {
                    // Sensor orientation is 0 (uncommon)
                    if (!isRotationRequired) {
                        scaleX = (float) viewWidth / previewHeight;
                        scaleY = (float) viewHeight / previewWidth;
                    } else {
                        scaleX = (float) viewWidth / previewWidth;
                        scaleY = (float) viewHeight / previewHeight;
                    }
                } else {
                    // Sensor orientation is 90 (most common, including your case)
                    if (isRotationRequired) {
                        scaleX = (float) viewWidth / previewHeight;
                        scaleY = (float) viewHeight / previewWidth;
                    } else {
                        scaleX = (float) viewWidth / previewWidth;
                        scaleY = (float) viewHeight / previewHeight;
                    }
                }

                // Calculate final scale (use the larger factor to fill the view)
                float finalScale = Math.max(scaleX, scaleY);

                float halfWidth = viewWidth / 2f;
                float halfHeight = viewHeight / 2f;

                Matrix matrix = new Matrix();

                if (isRotationRequired) {
                    // When rotation is required, we need to scale differently
                    matrix.setScale(
                            1 / scaleX * finalScale,
                            1 / scaleY * finalScale,
                            halfWidth,
                            halfHeight
                    );
                } else {
                    // When no rotation, standard scaling
                    matrix.setScale(
                            viewHeight / (float) viewWidth / scaleY * finalScale,
                            viewWidth / (float) viewHeight / scaleX * finalScale,
                            halfWidth,
                            halfHeight
                    );
                }

                // Apply rotation to compensate for display rotation
                matrix.postRotate(
                        -displayRotationDegrees,
                        halfWidth,
                        halfHeight
                );

                textureView.setTransform(matrix);

                Log.i(TAG, String.format("Preview fixed: sensorOrientation=%d, displayRotation=%d, relativeRotation=%d, isRotationRequired=%b, finalScale=%.2f",
                        sensorOrientationDegrees, displayRotationDegrees, relativeRotation, isRotationRequired, finalScale));

            } catch (Exception e) {
                Log.e(TAG, "Rotation fix failed", e);
            }
        });
    }
    // Add this method to recreate video format from cached SPS/PPS
    private MediaFormat recreateVideoFormatFromCache() {
        if (!hasCachedCodecData || cachedSps == null || cachedPps == null) {
            Log.w(TAG, "Cannot recreate video format: no cached SPS/PPS");
            return null;
        }

        MediaFormat format = MediaFormat.createVideoFormat(
                MediaFormat.MIMETYPE_VIDEO_AVC,
                width,
                height
        );

        // Clone SPS
        cachedSps.position(0);
        ByteBuffer spsCopy = ByteBuffer.allocate(cachedSps.remaining());
        spsCopy.put(cachedSps);
        spsCopy.flip();
        cachedSps.position(0);

        // Clone PPS
        cachedPps.position(0);
        ByteBuffer ppsCopy = ByteBuffer.allocate(cachedPps.remaining());
        ppsCopy.put(cachedPps);
        ppsCopy.flip();
        cachedPps.position(0);

        format.setByteBuffer("csd-0", spsCopy);
        format.setByteBuffer("csd-1", ppsCopy);
        format.setInteger(MediaFormat.KEY_BIT_RATE, StreamProfile.INTERNAL_BITRATE);
        format.setInteger(MediaFormat.KEY_FRAME_RATE, fps);
        format.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 2);
        format.setInteger(MediaFormat.KEY_PROFILE, MediaCodecInfo.CodecProfileLevel.AVCProfileHigh);
        format.setInteger(MediaFormat.KEY_LEVEL, MediaCodecInfo.CodecProfileLevel.AVCLevel31);

        Log.i(TAG, "✅ Recreated video format from cache: " + width + "x" + height);
        return format;
    }

    // Add this method to recreate audio format from current encoder
    private MediaFormat recreateAudioFormatFromEncoder() {
        if (audioEncoder == null) {
            Log.w(TAG, "Cannot recreate audio format: audioEncoder is null");
            return null;
        }

        try {
            MediaFormat format = audioEncoder.getOutputFormat();
            MediaFormat cloned = cloneMediaFormat(format);
            Log.i(TAG, "✅ Recreated audio format from encoder: " +
                    format.getInteger(MediaFormat.KEY_SAMPLE_RATE) + "Hz");
            return cloned;
        } catch (Exception e) {
            Log.e(TAG, "Failed to recreate audio format", e);
            return null;
        }
    }
    public void updateResolution(int newWidth, int newHeight, int newFps) {
        Log.i(TAG, "Updating resolution from " + width + "x" + height + "@" + fps +
                " to " + newWidth + "x" + newHeight + "@" + newFps);

        this.width = newWidth;
        this.height = newHeight;
        this.fps = newFps;
        this.videoFrameDurationUs = 1_000_000 / fps;

        // Recreate video encoder with new settings
        if (videoEncoder != null) {
            try {
                videoEncoder.stop();
                videoEncoder.release();
                videoEncoder = null;
                setupVideoEncoder();

                // Recreate input surface and attach to camera session
                if (captureSession != null && cameraDevice != null) {
                    // Need to recreate capture session with new surface
                    recreateCaptureSession();
                }

                Log.i(TAG, "✅ Video encoder recreated with new resolution: " + width + "x" + height + " @" + fps + "fps");
            } catch (Exception e) {
                Log.e(TAG, "Failed to recreate video encoder", e);
            }
        }
    }

    private void recreateCaptureSession() throws Exception {
        // Close old session
        if (captureSession != null) {
            captureSession.close();
            captureSession = null;
        }

        // Create new capture session with updated surface
        createCaptureSession();
    }
    // Add these methods to InternalCameraManager class

    /**
     * Get all supported video resolutions from Camera2
     */
    public List<Size> getSupportedResolutions() {
        if (supportedResolutions != null) {
            return supportedResolutions;
        }

        supportedResolutions = new ArrayList<>();

        try {
            CameraManager cameraManager = (CameraManager) activity.getSystemService(Context.CAMERA_SERVICE);
            String cameraId = getCameraId(facing == CameraHelper.Facing.FRONT);
            CameraCharacteristics characteristics = cameraManager.getCameraCharacteristics(cameraId);
            StreamConfigurationMap configMap = characteristics.get(
                    CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);

            if (configMap != null) {
                // Get sizes for video recording
                android.util.Size[] sizes = configMap.getOutputSizes(android.media.MediaRecorder.class);

                // If MediaRecorder doesn't work, try YUV_420_888
                if (sizes == null || sizes.length == 0) {
                    sizes = configMap.getOutputSizes(android.graphics.ImageFormat.YUV_420_888);
                }

                if (sizes != null && sizes.length > 0) {
                    for (android.util.Size size : sizes) {
                        // Filter reasonable resolutions (minimum 640x480)
                        if (size.getWidth() >= 640 && size.getHeight() >= 480) {
                            supportedResolutions.add(new Size(size.getWidth(), size.getHeight()));
                        }
                    }

                    // Sort by resolution (descending) - works on all API levels
                    Collections.sort(supportedResolutions, (a, b) ->
                            Integer.compare(b.width * b.height, a.width * a.height));
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "Failed to get supported resolutions", e);
        }

        // Ensure we have at least some resolutions
        if (supportedResolutions.isEmpty()) {
            addFallbackResolutions();
        }

        return supportedResolutions;
    }

    /**
     * Get all supported FPS ranges from Camera2
     */
    public List<Integer> getSupportedFpsRanges() {
        if (supportedFpsRanges != null) {
            return supportedFpsRanges;
        }

        supportedFpsRanges = new ArrayList<>();

        try {
            CameraManager cameraManager = (CameraManager) activity.getSystemService(Context.CAMERA_SERVICE);
            String cameraId = getCameraId(facing == CameraHelper.Facing.FRONT);
            CameraCharacteristics characteristics = cameraManager.getCameraCharacteristics(cameraId);
            android.util.Range<Integer>[] fpsRanges = characteristics.get(
                    CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES);

            if (fpsRanges != null) {
                for (android.util.Range<Integer> range : fpsRanges) {
                    int fps = range.getUpper();
                    if (!supportedFpsRanges.contains(fps) && fps >= 15 && fps <= 120) {
                        supportedFpsRanges.add(fps);
                    }
                }
                Collections.sort(supportedFpsRanges);
            }
        } catch (Exception e) {
            Log.e(TAG, "Failed to get supported FPS ranges", e);
        }

        // Fallback to standard FPS if none found
        if (supportedFpsRanges.isEmpty()) {
            supportedFpsRanges.add(15);
            supportedFpsRanges.add(24);
            supportedFpsRanges.add(25);
            supportedFpsRanges.add(30);
            supportedFpsRanges.add(50);
            supportedFpsRanges.add(60);
        }

        return supportedFpsRanges;
    }

    /**
     * Get current camera ID
     */
    public String getCurrentCameraId() {
        if (currentCameraId == null) {
            currentCameraId = getCameraId(facing == CameraHelper.Facing.FRONT);
        }
        return currentCameraId;
    }

    private void addFallbackResolutions() {
        supportedResolutions.add(new Size(1920, 1080));
        supportedResolutions.add(new Size(1280, 720));
        supportedResolutions.add(new Size(720, 576));
        supportedResolutions.add(new Size(720, 480));
        supportedResolutions.add(new Size(640, 480));
        supportedResolutions.add(new Size(640, 360));
    }

    // Helper class for resolution
    public static class Size {
        public final int width;
        public final int height;

        public Size(int width, int height) {
            this.width = width;
            this.height = height;
        }
    }
    // ==================== Camera Controls Implementation ====================

    @Override
    public float getMaxZoom() {
        return maxZoom;
    }

    @Override
    public void setZoom(float zoomLevel) {
        if (zoomLevel < 1.0f) zoomLevel = 1.0f;
        if (zoomLevel > maxZoom) zoomLevel = maxZoom;

        this.currentZoom = zoomLevel;

        try {
            if (captureSession != null && currentRequestBuilder != null) {
                android.graphics.Rect zoomRect = getZoomRect(zoomLevel);
                if (zoomRect != null) {
                    currentRequestBuilder.set(CaptureRequest.SCALER_CROP_REGION, zoomRect);
                    captureSession.setRepeatingRequest(currentRequestBuilder.build(), null, backgroundHandler);
                    Log.i(TAG, "Zoom set to: " + zoomLevel + "x");
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "Failed to set zoom", e);
        }
    }

    private android.graphics.Rect getZoomRect(float zoomLevel) {
        try {
            CameraManager cameraManager = (CameraManager) activity.getSystemService(Context.CAMERA_SERVICE);
            CameraCharacteristics characteristics = cameraManager.getCameraCharacteristics(currentCameraId);
            android.graphics.Rect sensorRect = characteristics.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE);

            if (sensorRect != null) {
                int cropWidth = (int) (sensorRect.width() / zoomLevel);
                int cropHeight = (int) (sensorRect.height() / zoomLevel);
                int left = (sensorRect.width() - cropWidth) / 2;
                int top = (sensorRect.height() - cropHeight) / 2;
                return new android.graphics.Rect(left, top, left + cropWidth, top + cropHeight);
            }
        } catch (Exception e) {
            Log.e(TAG, "Failed to calculate zoom rect", e);
        }
        return null;
    }

    @Override
    public float getCurrentZoom() {
        return currentZoom;
    }

    @Override
    public void setExposureCompensation(int ev) {
        if (ev < minExposure) ev = minExposure;
        if (ev > maxExposure) ev = maxExposure;

        this.currentExposure = ev;

        try {
            if (captureSession != null && currentRequestBuilder != null) {
                currentRequestBuilder.set(CaptureRequest.CONTROL_AE_EXPOSURE_COMPENSATION, ev);
                captureSession.setRepeatingRequest(currentRequestBuilder.build(), null, backgroundHandler);
                Log.i(TAG, "Exposure set to: " + ev);
            }
        } catch (Exception e) {
            Log.e(TAG, "Failed to set exposure", e);
        }
    }

    @Override
    public int getExposureCompensation() {
        return currentExposure;
    }

    @Override
    public int getMinExposureCompensation() {
        return minExposure;
    }

    @Override
    public int getMaxExposureCompensation() {
        return maxExposure;
    }

    @Override
    public void setFocusMode(String mode) {
        this.currentFocusMode = mode;

        try {
            if (captureSession != null && currentRequestBuilder != null) {
                int afMode = convertFocusModeToCamera2(mode);
                currentRequestBuilder.set(CaptureRequest.CONTROL_AF_MODE, afMode);
                captureSession.setRepeatingRequest(currentRequestBuilder.build(), null, backgroundHandler);
                Log.i(TAG, "Focus mode set to: " + mode);
            }
        } catch (Exception e) {
            Log.e(TAG, "Failed to set focus mode", e);
        }
    }

    @Override
    public String[] getSupportedFocusModes() {
        try {
            CameraManager cameraManager = (CameraManager) activity.getSystemService(Context.CAMERA_SERVICE);
            CameraCharacteristics characteristics = cameraManager.getCameraCharacteristics(currentCameraId);
            int[] modes = characteristics.get(CameraCharacteristics.CONTROL_AF_AVAILABLE_MODES);

            if (modes != null) {
                java.util.ArrayList<String> modeList = new java.util.ArrayList<>();
                for (int mode : modes) {
                    switch (mode) {
                        case CaptureRequest.CONTROL_AF_MODE_AUTO:
                            modeList.add("auto");
                            break;
                        case CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO:
                            modeList.add("continuous");
                            break;
                        case CaptureRequest.CONTROL_AF_MODE_MACRO:
                            modeList.add("macro");
                            break;
                        case CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE:
                            modeList.add("continuous_picture");
                            break;
                        case CaptureRequest.CONTROL_AF_MODE_OFF:
                            modeList.add("off");
                            break;
                    }
                }
                return modeList.toArray(new String[0]);
            }
        } catch (Exception e) {
            Log.e(TAG, "Failed to get focus modes", e);
        }
        return new String[]{"auto", "continuous"};
    }

    @Override
    public void triggerAutoFocus() {
        try {
            if (captureSession != null && currentRequestBuilder != null) {
                // Trigger AF
                currentRequestBuilder.set(CaptureRequest.CONTROL_AF_TRIGGER, CaptureRequest.CONTROL_AF_TRIGGER_START);
                captureSession.capture(currentRequestBuilder.build(), null, backgroundHandler);

                // Reset after a moment
                new Handler(Looper.getMainLooper()).postDelayed(() -> {
                    try {
                        if (currentRequestBuilder != null) {
                            currentRequestBuilder.set(CaptureRequest.CONTROL_AF_TRIGGER, CaptureRequest.CONTROL_AF_TRIGGER_CANCEL);
                            captureSession.setRepeatingRequest(currentRequestBuilder.build(), null, backgroundHandler);
                        }
                    } catch (Exception e) {
                        Log.e(TAG, "Failed to reset AF trigger", e);
                    }
                }, 500);
            }
        } catch (Exception e) {
            Log.e(TAG, "Failed to trigger autofocus", e);
        }
    }

    private int convertFocusModeToCamera2(String mode) {
        switch (mode.toLowerCase()) {
            case "auto": return CaptureRequest.CONTROL_AF_MODE_AUTO;
            case "continuous": return CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO;
            case "macro": return CaptureRequest.CONTROL_AF_MODE_MACRO;
            case "continuous_picture": return CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE;
            case "off": return CaptureRequest.CONTROL_AF_MODE_OFF;
            default: return CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO;
        }
    }

    @Override
    public void setWhiteBalanceMode(String mode) {
        this.currentWhiteBalance = mode;

        try {
            if (captureSession != null && currentRequestBuilder != null) {
                int wbMode = convertWBModeToCamera2(mode);
                currentRequestBuilder.set(CaptureRequest.CONTROL_AWB_MODE, wbMode);
                captureSession.setRepeatingRequest(currentRequestBuilder.build(), null, backgroundHandler);
                Log.i(TAG, "White balance set to: " + mode);
            }
        } catch (Exception e) {
            Log.e(TAG, "Failed to set white balance", e);
        }
    }

    @Override
    public String[] getSupportedWhiteBalanceModes() {
        try {
            CameraManager cameraManager = (CameraManager) activity.getSystemService(Context.CAMERA_SERVICE);
            CameraCharacteristics characteristics = cameraManager.getCameraCharacteristics(currentCameraId);
            int[] modes = characteristics.get(CameraCharacteristics.CONTROL_AWB_AVAILABLE_MODES);

            if (modes != null) {
                java.util.ArrayList<String> modeList = new java.util.ArrayList<>();
                for (int mode : modes) {
                    switch (mode) {
                        case CaptureRequest.CONTROL_AWB_MODE_AUTO:
                            modeList.add("auto");
                            break;
                        case CaptureRequest.CONTROL_AWB_MODE_INCANDESCENT:
                            modeList.add("incandescent");
                            break;
                        case CaptureRequest.CONTROL_AWB_MODE_FLUORESCENT:
                            modeList.add("fluorescent");
                            break;
                        case CaptureRequest.CONTROL_AWB_MODE_DAYLIGHT:
                            modeList.add("daylight");
                            break;
                        case CaptureRequest.CONTROL_AWB_MODE_CLOUDY_DAYLIGHT:
                            modeList.add("cloudy");
                            break;
                    }
                }
                return modeList.toArray(new String[0]);
            }
        } catch (Exception e) {
            Log.e(TAG, "Failed to get WB modes", e);
        }
        return new String[]{"auto", "daylight", "cloudy"};
    }

    private int convertWBModeToCamera2(String mode) {
        switch (mode.toLowerCase()) {
            case "auto": return CaptureRequest.CONTROL_AWB_MODE_AUTO;
            case "incandescent": return CaptureRequest.CONTROL_AWB_MODE_INCANDESCENT;
            case "fluorescent": return CaptureRequest.CONTROL_AWB_MODE_FLUORESCENT;
            case "daylight": return CaptureRequest.CONTROL_AWB_MODE_DAYLIGHT;
            case "cloudy": return CaptureRequest.CONTROL_AWB_MODE_CLOUDY_DAYLIGHT;
            default: return CaptureRequest.CONTROL_AWB_MODE_AUTO;
        }
    }

    @Override
    public boolean isFlashSupported() {
        try {
            CameraManager cameraManager = (CameraManager) activity.getSystemService(Context.CAMERA_SERVICE);
            CameraCharacteristics characteristics = cameraManager.getCameraCharacteristics(currentCameraId);
            Boolean hasFlash = characteristics.get(CameraCharacteristics.FLASH_INFO_AVAILABLE);
            return hasFlash != null && hasFlash;
        } catch (Exception e) {
            return false;
        }
    }

    @Override
    public void setFlash(boolean enabled) {
        try {
            if (captureSession != null && currentRequestBuilder != null) {
                if (enabled && isFlashSupported()) {
                    currentRequestBuilder.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON_ALWAYS_FLASH);
                } else {
                    currentRequestBuilder.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON);
                }
                captureSession.setRepeatingRequest(currentRequestBuilder.build(), null, backgroundHandler);
                Log.i(TAG, "Flash set to: " + enabled);
            }
        } catch (Exception e) {
            Log.e(TAG, "Failed to set flash", e);
        }
    }

    @Override
    public void setManualFocusDistance(float distance) {
        Log.w(TAG, "Manual focus not implemented");
    }
}
