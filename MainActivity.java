package com.serenegiant.usbcameratest;

import static androidx.constraintlayout.helper.widget.MotionEffect.TAG;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.graphics.Matrix;
import android.graphics.SurfaceTexture;
import android.hardware.usb.UsbConstants;
import android.hardware.usb.UsbDevice;
import android.media.AudioDeviceInfo;
import android.media.MediaFormat;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.util.Log;
import android.view.SurfaceHolder;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.RequiresApi;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;

import com.pedro.encoder.input.video.CameraHelper;
import com.serenegiant.usb.DeviceFilter;
import com.serenegiant.usb.USBMonitor;
import com.serenegiant.usbcameratest.enums.CameraSource;
import com.serenegiant.usbcameratest.managers.AudioManager;
import com.serenegiant.usbcameratest.managers.MyCameraManager;
import com.serenegiant.usbcameratest.managers.RTMPManager;
import com.serenegiant.usbcameratest.managers.RecordingManager;
import com.serenegiant.usbcameratest.managers.StreamProfile;
import com.serenegiant.usbcameratest.managers.UsbH264Encoder;
import com.serenegiant.widget.LevelMeterView;
import com.serenegiant.widget.UVCCameraTextureView;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.ToggleButton;
import android.view.TextureView;

import java.io.File;
import java.util.ArrayList;

import android.widget.SeekBar;
import android.widget.CheckBox;
import com.serenegiant.usbcameratest.managers.InternalCameraManager;
import com.serenegiant.usbcameratest.managers.CameraControls;
import com.serenegiant.usbcameratest.views.GridOverlayView;
import com.serenegiant.usbcameratest.managers.DeviceOrientationManager;
import android.view.Gravity;
import android.view.WindowManager;
import android.graphics.Color;
import android.widget.GridView;
import android.widget.ImageButton;
import android.view.ViewGroup;

public final class MainActivity extends AppCompatActivity
        implements RTMPManager.RTMPListener {

    private static final String[] PERMISSIONS = {
            Manifest.permission.CAMERA,
            Manifest.permission.RECORD_AUDIO,
            Manifest.permission.BLUETOOTH_CONNECT, // Add for Android 12+
            Manifest.permission.BLUETOOTH // Add for older versions
    };

    /* ========================= */
    /* MANAGERS                  */
    /* ========================= */

    private RTMPManager rtmpManager;
    private MyCameraManager cameraManager;
    private AudioManager audioManager;

    /* ========================= */
    /* UI                        */
    /* ========================= */

    private TextureView internalCameraTextureView;  // Changed from OpenGlView
    private UVCCameraTextureView usbCameraTextureView;
    private LevelMeterView levelMeterView;
    private View previewContainer;

    private boolean isUsbPreviewActive = false;

    private Button startBtn;
    private Button stopBtn;
    private ImageButton cameraBtn;
    private ToggleButton playbackToggle;  // Keep this for audio monitoring

    private Spinner audioSourceSpinner;
    private ArrayAdapter<String> spinnerAdapter;
    private java.util.List<String> audioSourceOptions;
    private java.util.List<AudioManager.AudioSource> audioSourceValues;
    private ImageButton recordButton;
    private ImageButton browseButton;
    private RecordingManager recordingManager;
    private boolean isRecording = false;
    private ImageButton settingsButton;  // ADD THIS
    private TextView recordingTimer;     // ADD THIS

    // Add timer handler
    private Handler timerHandler = new Handler();
    private Runnable timerRunnable;
    private long startTime = 0;
    private String streamUrl = "rtmp://27.131.14.34:1935/live/test";  // ADD THIS
    private boolean isInternalCameraStarting = false;
    private boolean isInitialized = false;
    private boolean isUsbSwitchInProgress = false;
    private TextView sourceIndicator;
    private TextView resolutionIndicator;
    private ImageButton streamToggleButton;
    private boolean isStreaming = false;

    // Camera Controls
    private GridOverlayView gridOverlay;
    private DeviceOrientationManager orientationManager;
    private int currentRotation = 0;
    private AlertDialog cameraControlsDialog;

    // Bottom Toolbar
    private LinearLayout bottomToolbar;
    private TextView gyroInfo, compassInfo;

    // Overlay management
    private View currentOverlay = null;
    private AlertDialog currentDialog = null;


    /* ========================= */
    /* USB                       */
    /* ========================= */

    private USBMonitor usbMonitor;

    // In MainActivity.java, add this method and call it in onCreate()
    private void suppressRtmpLogs() {
        try {
            // Get the RtmpSender class and set its log level
            Class<?> rtmpSenderClass = Class.forName("com.pedro.rtmp.rtmp.RtmpSender");
            java.lang.reflect.Field debugField = rtmpSenderClass.getDeclaredField("DEBUG");
            debugField.setAccessible(true);
            debugField.setBoolean(null, false); // Disable debug logs

            Log.i(TAG, "RTMP logs suppressed");
        } catch (Exception e) {
            Log.w(TAG, "Could not suppress RTMP logs: " + e.getMessage());
        }
    }

    /* ========================= */
    /* LIFECYCLE                 */
    /* ========================= */

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        suppressRtmpLogs(); // Add this line
        setContentView(R.layout.activity_main);
        //checkStoragePermissions();
        //checkEncoderStatus();

        //ActivityCompat.requestPermissions(this, PERMISSIONS, 1234);

        // Initialize ALL UI elements
        previewContainer = findViewById(R.id.preview_container);
        internalCameraTextureView = findViewById(R.id.internalCameraTextureView);  // Changed
        usbCameraTextureView = findViewById(R.id.usbCameraTextureView);
        levelMeterView = findViewById(R.id.levelMeterView);

        streamToggleButton = findViewById(R.id.stream_toggle_button);
        cameraBtn = findViewById(R.id.camera_button);
        playbackToggle = findViewById(R.id.playback_toggle);
        audioSourceSpinner = findViewById(R.id.audio_source_spinner);
        recordButton = findViewById(R.id.record_button);
        browseButton = findViewById(R.id.browse_button);
        settingsButton = findViewById(R.id.settings_button);     // ADD THIS
        recordingTimer = findViewById(R.id.recording_timer);     // ADD THIS
        sourceIndicator = findViewById(R.id.source_indicator);
        resolutionIndicator = findViewById(R.id.resolution_indicator);
        // Find camera controls button and grid overlay
        gridOverlay = findViewById(R.id.grid_overlay);

        // Bottom Toolbar
        bottomToolbar = findViewById(R.id.bottom_toolbar);
        gyroInfo = findViewById(R.id.gyro_info);
        compassInfo = findViewById(R.id.compass_info);


        setupUi();
        setupUsbMonitor();

        // ===== ADD THIS: Wait for TextureView to be ready before initializing =====
        if (internalCameraTextureView.isAvailable()) {
            // TextureView is already available
            initializeManagers();
        } else {
            // Wait for TextureView to be available
            internalCameraTextureView.setSurfaceTextureListener(new TextureView.SurfaceTextureListener() {
                @Override
                public void onSurfaceTextureAvailable(SurfaceTexture surface, int width, int height) {
                    Log.d(TAG, "TextureView available, initializing managers...");
                    initializeManagers();
                    // Remove listener after initialization
                    internalCameraTextureView.setSurfaceTextureListener(null);
                }

                @Override
                public void onSurfaceTextureSizeChanged(SurfaceTexture surface, int width, int height) {}

                @Override
                public boolean onSurfaceTextureDestroyed(SurfaceTexture surface) {
                    return true;
                }

                @Override
                public void onSurfaceTextureUpdated(SurfaceTexture surface) {}
            });
        }

        // Initially show internal camera preview (but don't start camera yet - it will start after managers initialize)
        //switchToInternalCameraPreview();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED ||
                    checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(this, PERMISSIONS, 1234);
            } else {
                // Permissions already granted
                new Handler(Looper.getMainLooper()).postDelayed(() -> {
                    boolean usbConnected = (usbMonitor != null && usbMonitor.getDeviceCount() > 0);
                    // Block 1 (permissions already granted):
                    if (!usbConnected && cameraManager != null) {
                        Log.d(TAG, "Starting internal camera from onCreate");
                        cameraManager.switchToInternalCamera(CameraHelper.Facing.BACK);  // ← Changed
                    } else if (usbConnected) {
                        Log.d(TAG, "USB connected, skipping internal camera start");
                    } else {
                        Log.e(TAG, "cameraManager is NULL in onCreate delay!");
                    }
                    checkStoragePermissions();
                }, 1000);
            }
        } else {
            new Handler(Looper.getMainLooper()).postDelayed(() -> {
                boolean usbConnected = (usbMonitor != null && usbMonitor.getDeviceCount() > 0);
                // Block 2 (pre-Marshmallow):
                if (!usbConnected && cameraManager != null) {
                    Log.d(TAG, "Starting internal camera from onCreate (pre-M)");
                    cameraManager.switchToInternalCamera(CameraHelper.Facing.BACK);  // ← Changed
                }
                checkStoragePermissions();
            }, 1000);
        }
    }//gg

    private void setupAudioSourceSpinner() {
        audioSourceOptions = new java.util.ArrayList<>();
        audioSourceValues = new java.util.ArrayList<>();

        // Create adapter
        spinnerAdapter = new ArrayAdapter<String>(this,
                R.layout.spinner_item, audioSourceOptions) {
            @Override
            public View getView(int position, View convertView, android.view.ViewGroup parent) {
                View view = super.getView(position, convertView, parent);
                if (view instanceof TextView) {
                    TextView tv = (TextView) view;
                    tv.setTextColor(0xFFFFFFFF);
                    tv.setTextSize(14);
                }
                return view;
            }
        };
        spinnerAdapter.setDropDownViewResource(R.layout.spinner_dropdown_item);

        audioSourceSpinner.setAdapter(spinnerAdapter);

        // Update spinner with available devices
        updateAudioSourceSpinner();

        // Handle selection
        audioSourceSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                if (audioManager == null || position < 0 || position >= audioSourceValues.size())
                    return;

                AudioManager.AudioSource selected = audioSourceValues.get(position);

                // Save current playback state
                boolean wasPlaybackEnabled = playbackToggle.isChecked();

                // Set the audio source
                audioManager.setAudioSource(selected);

                // Add a small delay before starting audio to let things settle
                new Handler(Looper.getMainLooper()).postDelayed(() -> {
                    // CRITICAL: Start audio loopback after changing source
                    audioManager.startAudioLoopback();

                    // Restore playback state after source change
                    audioManager.setPlaybackEnabled(wasPlaybackEnabled);

                    // Check audio status
                    checkAudioStatus();
                }, 200); // 200ms delay

                // Bluetooth permission check
                if (selected == AudioManager.AudioSource.BLUETOOTH && !hasBluetoothPermission()) {
                    toast("Bluetooth permission required");
                    return;
                }

                // Update visual indicator
                if (view instanceof TextView) {
                    switch (selected) {
                        case USB:
                            ((TextView) view).setTextColor(0xFF4CAF50);
                            break;
                        case BLUETOOTH:
                            ((TextView) view).setTextColor(0xFF2196F3);
                            break;
                        case WIRED_HEADSET:
                            ((TextView) view).setTextColor(0xFFFF9800);
                            break;
                        default:
                            ((TextView) view).setTextColor(0xFFFFFFFF);
                            break;
                    }
                }

                // Show current audio info (this will show old info until audio starts)
                String deviceInfo = String.format("%s @ %dHz %s",
                        audioManager.getCurrentDeviceName(),
                        audioManager.getCurrentSampleRate(),
                        audioManager.getCurrentChannels() == 2 ? "Stereo" : "Mono"
                );
                Log.i(TAG, "Audio source selected: " + deviceInfo);
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {}
        });
    }

    private boolean hasBluetoothPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            return checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED;
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR2) {
            return checkSelfPermission(Manifest.permission.BLUETOOTH) == PackageManager.PERMISSION_GRANTED;
        } else {
            return true; // Very old versions
        }
    }

    private void updateAudioSourceSpinner() {
        if (audioManager == null) return;

        audioSourceOptions.clear();
        audioSourceValues.clear();

        // Always add Auto and Internal
        audioSourceOptions.add("🤖 Auto");
        audioSourceValues.add(AudioManager.AudioSource.AUTO);

        audioSourceOptions.add("📱 Internal");
        audioSourceValues.add(AudioManager.AudioSource.INTERNAL);

        // Check what's actually connected
        android.media.AudioManager audioSystem =
                (android.media.AudioManager) getSystemService(AUDIO_SERVICE);
        AudioDeviceInfo[] devices = audioSystem.getDevices(android.media.AudioManager.GET_DEVICES_INPUTS);

        boolean hasUsb = false;
        boolean hasBluetooth = false;
        boolean hasWired = false;
        boolean hasUsbAudioDevice = false;

        for (AudioDeviceInfo device : devices) {
            int type = device.getType();
            if (type == AudioDeviceInfo.TYPE_USB_DEVICE || type == AudioDeviceInfo.TYPE_USB_HEADSET) {
                if (!hasUsb) {
                    audioSourceOptions.add("🔌 USB");
                    audioSourceValues.add(AudioManager.AudioSource.USB);
                    hasUsb = true;
                    hasUsbAudioDevice = true;
                    Log.d(TAG, "USB audio device detected: " + device.getProductName());
                }
            } else if (type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO ||
                    type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP) {
                if (!hasBluetooth) {
                    audioSourceOptions.add("🎧 Bluetooth");
                    audioSourceValues.add(AudioManager.AudioSource.BLUETOOTH);
                    hasBluetooth = true;
                }
            } else if (type == AudioDeviceInfo.TYPE_WIRED_HEADSET) {
                if (!hasWired) {
                    audioSourceOptions.add("🎤 Wired");
                    audioSourceValues.add(AudioManager.AudioSource.WIRED_HEADSET);
                    hasWired = true;
                }
            }
        }

        spinnerAdapter.notifyDataSetChanged();

        // ===== SET DEFAULT SELECTION BASED ON AVAILABLE DEVICES =====
        int defaultIndex = -1;

        // First, check if USB audio is available (prioritize USB)
        if (hasUsbAudioDevice) {
            for (int i = 0; i < audioSourceValues.size(); i++) {
                if (audioSourceValues.get(i) == AudioManager.AudioSource.USB) {
                    defaultIndex = i;
                    Log.d(TAG, "USB audio detected, setting as default");
                    break;
                }
            }
        }

        // If no USB, check for wired headset
        if (defaultIndex == -1 && hasWired) {
            for (int i = 0; i < audioSourceValues.size(); i++) {
                if (audioSourceValues.get(i) == AudioManager.AudioSource.WIRED_HEADSET) {
                    defaultIndex = i;
                    Log.d(TAG, "Wired headset detected, setting as default");
                    break;
                }
            }
        }

        // If no USB or wired, check for Bluetooth
        if (defaultIndex == -1 && hasBluetooth) {
            for (int i = 0; i < audioSourceValues.size(); i++) {
                if (audioSourceValues.get(i) == AudioManager.AudioSource.BLUETOOTH) {
                    defaultIndex = i;
                    Log.d(TAG, "Bluetooth detected, setting as default");
                    break;
                }
            }
        }

        // Finally, fall back to Internal
        if (defaultIndex == -1) {
            for (int i = 0; i < audioSourceValues.size(); i++) {
                if (audioSourceValues.get(i) == AudioManager.AudioSource.INTERNAL) {
                    defaultIndex = i;
                    Log.d(TAG, "No external audio, using Internal");
                    break;
                }
            }
        }

        // Set the selection
        if (defaultIndex != -1) {
            final int finalIndex = defaultIndex;
            audioSourceSpinner.post(() -> {
                audioSourceSpinner.setSelection(finalIndex, false); // false = don't trigger listener
            });
        }
    }

    // In MainActivity.java, update initializeManagers()
    private void initializeManagers() {
        if (isInitialized) {
            Log.d("MainActivity", "Already initialized, skipping");
            return;
        }
        isInitialized = true;

        Log.d("MainActivity", "Initializing managers...");

        rtmpManager = new RTMPManager(this, this);

        // 1. Create AudioManager FIRST
        audioManager = new AudioManager(this, levelMeterView);
        Log.d(TAG, "LevelMeterView passed to AudioManager: " + (levelMeterView != null));

        // 2. Setup audio spinner (needs audioManager)
        setupAudioSourceSpinner();

        // Create RecordingManager
        recordingManager = new RecordingManager(MainActivity.this, new RecordingManager.RecordingListener() {
            @Override
            public void onRecordingStarted(String filePath) {
                runOnUiThread(() -> {
                    isRecording = true;
                    recordButton.setImageResource(R.drawable.ic_record_off);
                    startTimer();
                    toast("Recording started: " + new File(filePath).getName());
                });
            }

            @Override
            public void onRecordingStopped(String filePath) {
                runOnUiThread(() -> {
                    isRecording = false;
                    recordButton.setImageResource(R.drawable.ic_record_on);
                    stopTimer();
                    toast("Recording saved: " + new File(filePath).getName());
                });
            }

            @Override
            public void onRecordingError(String error) {
                runOnUiThread(() -> {
                    isRecording = false;
                    recordButton.setImageResource(R.drawable.ic_record_on);
                    stopTimer();
                    toast("Recording error: " + error);
                    Log.e(TAG, "Recording error: " + error);
                });
            }
        });

        // 3. Create CameraManager with correct parameters
        cameraManager = new MyCameraManager(
                this,                           // Activity
                rtmpManager,                    // RTMPManager
                internalCameraTextureView,      // TextureView for internal camera
                usbCameraTextureView,           // UVCCameraTextureView for USB camera
                new MyCameraManager.CameraEventListener() {
                    @Override
                    public void onUsbCameraReady() {
                        runOnUiThread(() -> {
                            toast("USB camera ready");
                            switchToUsbCameraPreview();
                            updateCameraInfo();  // ✅ ADD THIS LINE
                        });
                        if (cameraManager != null) {
                            cameraManager.connectAudioManager(audioManager);
                            cameraManager.setRecordingManager(recordingManager);
                        }
                    }

                    @Override
                    public void onInternalCameraReady() {
                        runOnUiThread(() -> {
                            toast("Internal camera ready");
                            switchToInternalCameraPreview();  // ← SHOW THE PREVIEW
                            updateCameraInfo();
                        });
                    }
                }
        );
        // ✅ ADD THIS - Set the FPS update listener
        cameraManager.setFpsUpdateListener(measuredFps -> {
            runOnUiThread(() -> {
                int targetFps = StreamProfile.getFps();
                int width = StreamProfile.getWidth();
                int height = StreamProfile.getHeight();

                if (measuredFps == targetFps) {
                    resolutionIndicator.setText(String.format("%dx%d @%dfps", width, height, measuredFps));
                } else {
                    resolutionIndicator.setText(String.format("%dx%d @%d/%dfps", width, height, measuredFps, targetFps));
                }
            });
        });

        cameraManager.setInternalCameraFpsListener(measuredFps -> {
            runOnUiThread(() -> {
                int targetFps = StreamProfile.getFps();
                int width = StreamProfile.getWidth();
                int height = StreamProfile.getHeight();

                if (measuredFps == targetFps) {
                    resolutionIndicator.setText(String.format("%dx%d @%dfps", width, height, measuredFps));
                } else {
                    resolutionIndicator.setText(String.format("%dx%d @%d/%dfps", width, height, measuredFps, targetFps));
                }
            });
        });

        // Set audio manager and recording manager
        cameraManager.setAudioManager(audioManager);
        cameraManager.setRecordingManager(recordingManager);

        // Check for USB camera
        boolean usbConnected = (usbMonitor != null && usbMonitor.getDeviceCount() > 0);

        if (usbConnected) {
            Log.d(TAG, "USB camera detected, will not auto-start internal camera");
            if (usbMonitor != null && usbMonitor.getDeviceCount() > 0) {
                for (UsbDevice device : usbMonitor.getDeviceList()) {
                    for (int i = 0; i < device.getInterfaceCount(); i++) {
                        if (device.getInterface(i).getInterfaceClass() == UsbConstants.USB_CLASS_VIDEO) {
                            Log.d(TAG, "Requesting permission for already attached USB camera");
                            usbMonitor.requestPermission(device);
                            break;
                        }
                    }
                }
            }
        } else {
            // No USB camera detected - will start after permissions
            Log.d(TAG, "No USB camera detected, waiting for permissions");
        }

        // Initialize orientation manager for camera controls
        orientationManager = new DeviceOrientationManager(this,
                new DeviceOrientationManager.OrientationListener() {
                    @Override
                    public void onOrientationChanged(float roll, float pitch, float azimuth) {
                        runOnUiThread(() -> {
                            // Update toolbar orientation info
                            if (gyroInfo != null) {
                                gyroInfo.setText(String.format("%.0f°", roll));
                            }
                            if (compassInfo != null) {
                                String direction = getCompassDirection(azimuth);
                                compassInfo.setText(direction);
                            }
                        });
                    }
                });

        Log.d(TAG, "Managers initialized successfully");
    }

    /* ========================= */
    /* UI LOGIC                  */
    /* ========================= */

    private void setupUi() {
        streamToggleButton.setOnClickListener(v -> {
            if (cameraManager == null) {
                toast("Camera manager not ready");
                return;
            }

            if (isStreaming) {
                // Stop streaming
                cameraManager.stopStreaming();
                isStreaming = false;
                streamToggleButton.setImageResource(R.drawable.ic_stream_start);
                streamToggleButton.setSelected(false);
                streamToggleButton.clearAnimation();
                toast("Stream stopped");
            } else {
                // Start streaming
                cameraManager.startStreaming(streamUrl);
                isStreaming = true;
                streamToggleButton.setImageResource(R.drawable.ic_stream_stop);
                streamToggleButton.setSelected(true);

                // Add pulse animation
                //android.view.animation.Animation pulse = android.view.animation.AnimationUtils.loadAnimation(this, R.drawable.stream_pulse_animation);
                //streamToggleButton.startAnimation(pulse);

                toast("Stream started");
            }
        });
        settingsButton.setOnClickListener(v -> showStreamUrlDialog());
        playbackToggle.setOnCheckedChangeListener(
                (b, checked) -> {
                    if (audioManager != null) {
                        audioManager.setPlaybackEnabled(checked);
                    }
                }
        );

        cameraBtn.setOnClickListener(v -> {
            // Build options based on what's available
            java.util.List<String> options = new java.util.ArrayList<>();
            options.add("Back Camera");
            options.add("Front Camera");

            // Check if USB camera is connected
            if (usbMonitor != null && usbMonitor.getDeviceCount() > 0) {
                options.add("USB Camera");
            }

            new android.app.AlertDialog.Builder(this)
                    .setTitle("Select Camera")
                    .setItems(options.toArray(new String[0]), (dialog, which) -> {
                        if (which == 0 && cameraManager != null) {
                            switchToInternalCameraPreview();
                            // FIX: Use switchToInternalCamera with BACK facing
                            cameraManager.switchToInternalCamera(CameraHelper.Facing.BACK);
                        }
                        if (which == 1 && cameraManager != null) {
                            switchToInternalCameraPreview();
                            // FIX: Use switchToInternalCamera with FRONT facing
                            cameraManager.switchToInternalCamera(CameraHelper.Facing.FRONT);
                        }
                        if (which == 2 && cameraManager != null) {
                            switchToUsbCameraPreview();
                            USBMonitor.UsbControlBlock block = cameraManager.getSavedUsbControlBlock();
                            if (block != null) {
                                cameraManager.switchToUsbCamera(block);
                            } else {
                                toast("USB camera not available");
                            }
                        }
                    })
                    .show();
        });
        recordButton.setOnClickListener(v -> {
            Log.d(TAG, "Record button clicked, isRecording=" + isRecording);
            if (cameraManager == null) {
                toast("Camera not ready");
                return;
            }

            if (recordingManager == null) {
                toast("Recording manager not ready");
                return;
            }

            if (isRecording) {
                Log.d(TAG, "Attempting to STOP recording");
                // Stop recording through camera manager (it handles both sources)
                cameraManager.stopRecording();
            } else {
                Log.d(TAG, "Attempting to START recording");
                // Start recording through camera manager (it handles both sources)
                cameraManager.startRecording();
            }
        });

        // Replace your existing browseButton click handler
        browseButton.setOnClickListener(v -> {
            if (recordingManager == null) {
                toast("Recording manager not ready");
                return;
            }

            // Check the correct directory based on Android version
            File recordDir;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                File moviesDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES);
                recordDir = new File(moviesDir, "USBCamTest");
            } else {
                recordDir = RecordingManager.getAppPrivateDir();
            }

            if (!recordDir.exists() || recordDir.listFiles() == null || recordDir.listFiles().length == 0) {
                toast("No recordings found in: " + recordDir.getAbsolutePath());
                return;
            }

            // Open file browser with expectation of result
            Intent intent = new Intent(this, FileBrowserActivity.class);
            startActivityForResult(intent, 1001); // 1001 is request code
        });
        setupBottomToolbar();  // Add this line at the end
    }

    /*MISC DEBUG */


    private String generateRecordingFileName() {
        String prefix = (cameraManager.getCurrentCameraSource() == CameraSource.USB)
                ? "USB" : "Internal";
        String timestamp = new java.text.SimpleDateFormat("yyyyMMdd_HHmmss",
                java.util.Locale.US).format(new java.util.Date());
        return prefix + "_" + timestamp + ".mp4";
    }

    private void checkStoragePermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            if (checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE)
                    != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(new String[]{Manifest.permission.WRITE_EXTERNAL_STORAGE}, 1235);
            }
        }

        // Log directory info
        File recordDir = RecordingManager.getAppPrivateDir();
        Log.d(TAG, "Recording directory: " + recordDir.getAbsolutePath());
        Log.d(TAG, "Directory exists: " + recordDir.exists());
        Log.d(TAG, "Directory can write: " + recordDir.canWrite());

        if (!recordDir.exists()) {
            boolean created = recordDir.mkdirs();
            Log.d(TAG, "Directory created: " + created);
        }
    }

    /* ========================= */
    /* PREVIEW SWITCHING         */
    /* ========================= */


    private void switchToInternalCameraPreview() {
        Log.d("MainActivity", "switchToInternalCameraPreview called, isInternalCameraStarting=" + isInternalCameraStarting);

        if (isInternalCameraStarting) {
            Log.d("MainActivity", "Already starting, ignoring");
            return;
        }

        /*/ Check if already active
        if (cameraManager != null) {
            CameraSource current = cameraManager.getCurrentCameraSource();
            if (current.toString().contains("INTERNAL")) {
                Log.d("MainActivity", "Internal camera already active, ignoring");
                return;
            }
        }*/

        isInternalCameraStarting = true;

        // Update UI first
        runOnUiThread(() -> {
            Log.d("MainActivity", "Switching to internal camera preview");
            internalCameraTextureView.setVisibility(View.VISIBLE);
            usbCameraTextureView.setVisibility(View.GONE);
            isUsbPreviewActive = false;
        });

        // Then start camera (only if cameraManager exists)
        if (cameraManager != null) {
            cameraManager.switchToInternalCamera(CameraHelper.Facing.BACK);
        }

        new Handler(Looper.getMainLooper()).postDelayed(() -> {
            isInternalCameraStarting = false;
            Log.d("MainActivity", "isInternalCameraStarting reset");
        }, 3000);
    }

    private void switchToUsbCameraPreview() {
        if (isUsbSwitchInProgress) {
            Log.d(TAG, "USB switch already in progress, ignoring");
            return;
        }
        isUsbSwitchInProgress = true;

        runOnUiThread(() -> {
            Log.d("MainActivity", "Switching to USB camera preview");
            internalCameraTextureView.setVisibility(View.GONE);
            usbCameraTextureView.setVisibility(View.VISIBLE);
            isUsbPreviewActive = true;

            // Reset flag after delay - match the 2-second delay in MyCameraManager
            new Handler(Looper.getMainLooper()).postDelayed(() -> {
                isUsbSwitchInProgress = false;
            }, 3000); // Increased to 3 seconds to match the reset delay
        });
    }

    private void showAudioStatus() {
        if (audioManager == null) return;

        String status = String.format("Audio: %s @ %dHz %s\nCallback: %s",
                audioManager.getCurrentDeviceName(),
                audioManager.getCurrentSampleRate(),
                audioManager.getCurrentChannels() == 2 ? "Stereo" : "Mono",
                audioManager.isRunning() ? "Running" : "Stopped"
        );

        Log.i(TAG, status);
        toast(status);
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);

        if (requestCode == 1234) {
            boolean allGranted = true;
            for (int result : grantResults) {
                if (result != PackageManager.PERMISSION_GRANTED) allGranted = false;
            }

            if (allGranted) {
                Log.i(TAG, "All permissions granted - starting app");
                new Handler(Looper.getMainLooper()).postDelayed(() -> {
                    boolean usbConnected = (usbMonitor != null && usbMonitor.getDeviceCount() > 0);
                    // Block 3 (onRequestPermissionsResult):
                    if (!usbConnected && cameraManager != null) {
                        cameraManager.switchToInternalCamera(CameraHelper.Facing.BACK);  // ← Changed
                    }
                    checkStoragePermissions();
                    if (audioManager != null) {
                        audioManager.startAudioLoopback();
                    }
                }, 1000);
            }
        }

        if (requestCode == 1235) { // Storage permissions
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                Log.i(TAG, "Storage permission granted");
                // Recreate directory with permission
                File recordDir = RecordingManager.getAppPrivateDir();
                recordDir.mkdirs();
            } else {
                toast("Storage permission denied - cannot save recordings");
            }
        }
        if (requestCode == 1236) { // Storage permissions for native recording
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                Log.i(TAG, "Storage permission granted for native recording");
                // Retry recording
                // You'll need to trigger the record button again
            } else {
                toast("Storage permission denied - cannot record");
            }
        }
    }

    /* ========================= */
    /* USB MONITOR               */
    /* ========================= */

    private void setupUsbMonitor() {
        usbMonitor = new USBMonitor(this, usbListener);
        usbMonitor.setDeviceFilter(
                DeviceFilter.getDeviceFilters(this, R.xml.device_filter)
        );
    }

    private final USBMonitor.OnDeviceConnectListener usbListener =
            new USBMonitor.OnDeviceConnectListener() {

                @Override
                public void onAttach(UsbDevice device) {
                    boolean hasVideo = false;
                    boolean hasAudio = false;

                    for (int i = 0; i < device.getInterfaceCount(); i++) {
                        int cls = device.getInterface(i).getInterfaceClass();
                        if (cls == UsbConstants.USB_CLASS_VIDEO) hasVideo = true;
                        if (cls == UsbConstants.USB_CLASS_AUDIO) hasAudio = true;
                    }

                    if (hasVideo) {
                        runOnUiThread(() -> usbMonitor.requestPermission(device));
                    }
                    if (hasAudio) {
                        runOnUiThread(() -> {
                            if (audioManager != null) {
                                // Only update spinner to show USB option is available
                                updateAudioSourceSpinner();
                                Log.d(TAG, "USB audio device detected, spinner updated");
                            }
                        });
                    }
                }

                @Override
                public void onConnect(UsbDevice device, USBMonitor.UsbControlBlock ctrl, boolean createNew) {
                    if (isUsbSwitchInProgress) {
                        Log.d(TAG, "USB connection ignored - switch in progress");
                        return;
                    }

                    isUsbSwitchInProgress = true;

                    runOnUiThread(() -> {
                        // First switch camera preview UI
                        switchToUsbCameraPreview();

                        // Then switch camera (this will stop internal and start USB)
                        cameraManager.switchToUsbCamera(ctrl);

                        // Set audio source to USB
                        if (audioManager != null) {
                            Log.d(TAG, "USB connected, setting audio source to USB");
                            audioManager.setAudioSource(AudioManager.AudioSource.USB);
                            updateAudioSourceSpinner();
                        }

                        updateCameraInfo();  // ✅ ADD THIS LINE

                        toast("USB camera connected. Ready to stream.");

                        new Handler(Looper.getMainLooper()).postDelayed(() -> {
                            isUsbSwitchInProgress = false;
                        }, 3000);
                    });
                }

                @Override
                public void onDisconnect(UsbDevice device, USBMonitor.UsbControlBlock ctrl) {
                    runOnUiThread(() -> {
                        if (!isInternalCameraStarting) {
                            // First switch camera preview UI
                            switchToInternalCameraPreview();

                            // Then switch camera (this will stop USB and start internal)
                            cameraManager.switchToInternalCamera(CameraHelper.Facing.BACK);

                            // Set audio source to INTERNAL
                            if (audioManager != null) {
                                Log.d(TAG, "USB disconnected, switching audio to INTERNAL");
                                audioManager.setAudioSource(AudioManager.AudioSource.INTERNAL);
                                updateAudioSourceSpinner();
                            }

                            updateCameraInfo();  // ✅ ADD THIS LINE

                            toast("USB camera disconnected - switched to internal camera");
                        } else {
                            Log.d("MainActivity", "Already switching, ignoring USB disconnect");
                        }
                    });
                }

                @Override
                public void onDettach(UsbDevice device) {
                    runOnUiThread(() -> toast("USB camera detached"));
                }

                @Override
                public void onCancel(UsbDevice device) {
                    runOnUiThread(() -> toast("USB camera permission cancelled"));
                }
            };

    /* ========================= */
    /* RTMP STATUS CALLBACK      */
    /* ========================= */

    @Override
    public void onRtmpStatusChanged(String status) {
        runOnUiThread(() -> {
            if (status.contains("Connected")) {
                // Already handled by button click
            } else if (status.contains("Disconnected") || status.contains("Failed")) {
                if (isStreaming) {
                    isStreaming = false;
                    streamToggleButton.setImageResource(R.drawable.ic_stream_start);
                    streamToggleButton.setSelected(false);
                    streamToggleButton.clearAnimation();
                }
            }
            toast("RTMP: " + status);
        });
    }

    /* ========================= */
    /* LIFECYCLE                 */
    /* ========================= */

    @Override
    protected void onStart() {
        super.onStart();
        usbMonitor.register();
    }

    @Override
    protected void onStop() {
        usbMonitor.unregister();
        super.onStop();
    }

    @Override
    protected void onDestroy() {
        if (rtmpManager != null) {
            rtmpManager.release();
        }
        if (cameraManager != null) {
            cameraManager.stopAll();
        }
        if (recordingManager != null) {
            recordingManager.release();
        }
        usbMonitor.destroy();
        super.onDestroy();
    }

    @Override
    protected void onResume() {
        super.onResume();
        usbMonitor.register();

        // Start orientation tracking
        if (orientationManager != null) {
            orientationManager.startListening();
        }

        // Restart audio with proper permission check
        if (audioManager != null) {
            new Handler(Looper.getMainLooper()).postDelayed(() -> {
                if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
                    audioManager.startAudioLoopback();
                    audioManager.setPlaybackEnabled(playbackToggle.isChecked());
                    Log.d(TAG, "Audio started in onResume");
                    checkAudioStatus();
                }
            }, 500);
        }
    }
    @Override
    protected void onPause() {
        super.onPause();

        // Stop orientation tracking
        if (orientationManager != null) {
            orientationManager.stopListening();
        }
    }

    /* ========================= */
    /* UTILS                     */
    /* ========================= */

    private void toast(String s) {
        runOnUiThread(() -> Toast.makeText(MainActivity.this, s, Toast.LENGTH_SHORT).show());
    }
    private void startTimer() {
        startTime = System.currentTimeMillis();
        recordingTimer.setVisibility(View.VISIBLE);
        recordingTimer.setText("00:00");

        timerRunnable = new Runnable() {
            @Override
            public void run() {
                long millis = System.currentTimeMillis() - startTime;
                int seconds = (int) (millis / 1000);
                int minutes = seconds / 60;
                seconds = seconds % 60;

                recordingTimer.setText(String.format("%02d:%02d", minutes, seconds));
                timerHandler.postDelayed(this, 500);
            }
        };
        timerHandler.postDelayed(timerRunnable, 0);
    }

    private void stopTimer() {
        timerHandler.removeCallbacks(timerRunnable);
        recordingTimer.setVisibility(View.GONE);
    }

    private void showStreamUrlDialog() {
        SettingsDialog.show(this, streamUrl, cameraManager, new SettingsDialog.OnSettingsSavedListener() {
            @Override
            public void onSettingsSaved(String url, int width, int height, int fps, int audioBitrate) {
                streamUrl = url;

                Log.i(TAG, "!!! Settings saved: requested=" + width + "x" + height + "@" + fps);
                Log.i(TAG, "!!! Current StreamProfile: " + StreamProfile.getWidth() + "x" +
                        StreamProfile.getHeight() + "@" + StreamProfile.getFps());

                // Check if resolution/fps changed
                boolean needsRestart = (width != StreamProfile.getWidth() ||
                        height != StreamProfile.getHeight() ||
                        fps != StreamProfile.getFps());

                Log.i(TAG, "!!! needsRestart=" + needsRestart);

                if (needsRestart && cameraManager != null) {
                    Log.i(TAG, "!!! Applying restart...");

                    // Set the new values BEFORE restart
                    StreamProfile.setResolution(width, height);
                    StreamProfile.setFps(fps);
                    StreamProfile.setAudioBitrate(audioBitrate);

                    boolean wasStreaming = cameraManager.isStreaming();
                    boolean wasRecording = cameraManager.isRecording();

                    if (wasStreaming) cameraManager.stopStreaming();
                    if (wasRecording) cameraManager.stopRecording();

                    // Use the restartCamera() method
                    cameraManager.restartCamera();

                    new Handler(Looper.getMainLooper()).postDelayed(() -> {
                        if (wasStreaming) cameraManager.startStreaming(streamUrl);
                        if (wasRecording) cameraManager.startRecording();
                        updateCameraInfo();
                        toast("Settings applied: " + width + "x" + height + " @" + fps + "fps");
                    }, 1500);
                } else {
                    // Just save without restart
                    StreamProfile.setResolution(width, height);
                    StreamProfile.setFps(fps);
                    StreamProfile.setAudioBitrate(audioBitrate);
                    updateCameraInfo();
                    toast("Settings saved: " + width + "x" + height + " @" + fps + "fps");
                }
            }
        });
    }

    // Add this method to handle the result from FileBrowserActivity
    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);

        if (requestCode == 1001 && resultCode == Activity.RESULT_OK) {
            // Get selected files from FileBrowserActivity
            ArrayList<String> selectedFiles = data.getStringArrayListExtra("selected_files");

            if (selectedFiles != null && !selectedFiles.isEmpty()) {
                // Open FTP settings with selected files
                Intent ftpIntent = new Intent(this, FTPSettingsActivity.class);
                ftpIntent.putStringArrayListExtra("selected_files", selectedFiles);
                startActivity(ftpIntent);

                // Show which files were selected
                StringBuilder files = new StringBuilder();
                for (String file : selectedFiles) {
                    files.append(new File(file).getName()).append("\n");
                }
                toast("Selected " + selectedFiles.size() + " file(s) for FTP upload");
                Log.d(TAG, "Files selected for FTP: " + files.toString());
            }
        }
    }
    private void checkAudioStatus() {
        if (audioManager != null) {
            Log.d(TAG, "Audio running: " + audioManager.isRunning());
            Log.d(TAG, "Audio device: " + audioManager.getCurrentDeviceName());
            Log.d(TAG, "Audio sample rate: " + audioManager.getCurrentSampleRate());
            Log.d(TAG, "Audio channels: " + audioManager.getCurrentChannels());

            // If audio isn't running, try to start it
            if (!audioManager.isRunning()) {
                Log.w(TAG, "Audio not running, attempting to start...");
                audioManager.startAudioLoopback();
            }
        }
    }

    public void updatePreviewFps(int measuredFps) {
        runOnUiThread(() -> {
            int targetFps = StreamProfile.getFps();
            int width = StreamProfile.getWidth();
            int height = StreamProfile.getHeight();

            if (measuredFps == targetFps) {
                resolutionIndicator.setText(String.format("%dx%d @%dfps", width, height, measuredFps));
            } else {
                resolutionIndicator.setText(String.format("%dx%d @%d/%dfps", width, height, measuredFps, targetFps));
            }
        });
    }

    private void updateCameraInfo() {
        if (cameraManager == null) return;

        CameraSource source = cameraManager.getCurrentCameraSource();
        String sourceText;
        if (source == CameraSource.USB) {
            sourceText = "USB Camera";
        } else if (source == CameraSource.INTERNAL_FRONT) {
            sourceText = "Internal Front";
        } else {
            sourceText = "Internal Back";
        }

        int width = StreamProfile.getWidth();
        int height = StreamProfile.getHeight();
        int targetFps = StreamProfile.getFps();

        // Initial display without real-time FPS yet
        resolutionIndicator.setText(String.format("%dx%d @%dfps", width, height, targetFps));

        runOnUiThread(() -> {
            sourceIndicator.setText(sourceText);
        });
    }
    // ==================== Camera Controls ====================

    private void applyPreviewRotation() {
        if (internalCameraTextureView != null) {
            Matrix matrix = new Matrix();
            float centerX = internalCameraTextureView.getWidth() / 2f;
            float centerY = internalCameraTextureView.getHeight() / 2f;
            matrix.postRotate(currentRotation, centerX, centerY);
            internalCameraTextureView.setTransform(matrix);
        }
    }

    // ==================== Icon Click Handlers ====================

    private void setupBottomToolbar() {
        ImageButton zoomIcon = findViewById(R.id.icon_zoom);
        ImageButton exposureIcon = findViewById(R.id.icon_exposure);
        ImageButton wbIcon = findViewById(R.id.icon_wb);
        ImageButton focusIcon = findViewById(R.id.icon_focus);
        ImageButton flashIcon = findViewById(R.id.icon_flash);
        ImageButton gridIcon = findViewById(R.id.icon_grid);
        ImageButton rotationIcon = findViewById(R.id.icon_rotation);
        ImageButton routingIcon = findViewById(R.id.icon_routing);  // ADD THIS LINE

        if (zoomIcon != null) zoomIcon.setOnClickListener(v -> showZoomOverlay());
        if (exposureIcon != null) exposureIcon.setOnClickListener(v -> showExposureOverlay());
        if (wbIcon != null) wbIcon.setOnClickListener(v -> showWhiteBalanceOverlay());
        if (focusIcon != null) focusIcon.setOnClickListener(v -> showFocusOverlay());
        if (flashIcon != null) flashIcon.setOnClickListener(v -> showFlashOverlay());
        if (gridIcon != null) gridIcon.setOnClickListener(v -> showGridOverlay());
        if (rotationIcon != null) rotationIcon.setOnClickListener(v -> showRotationOverlay());
        if (routingIcon != null) routingIcon.setOnClickListener(v -> showRoutingOverlay());  // ADD THIS LINE
    }

// ==================== Overlay Methods ====================

    private void showZoomOverlay() {
        if (cameraManager == null || cameraManager.isCurrentCameraUsb()) {
            toast("Zoom not supported for USB camera");
            return;
        }

        InternalCameraManager internalCam = cameraManager.getInternalCameraManager();
        if (internalCam == null) return;

        View zoomOverlay = getLayoutInflater().inflate(R.layout.overlay_zoom, null);
        SeekBar seekBar = zoomOverlay.findViewById(R.id.zoom_seekbar_overlay);
        TextView valueText = zoomOverlay.findViewById(R.id.zoom_value_overlay);

        float maxZoom = internalCam.getMaxZoom();
        if (maxZoom > 1.0f) {
            seekBar.setMax((int)((maxZoom - 1.0f) * 100));
            seekBar.setProgress((int)((internalCam.getCurrentZoom() - 1.0f) * 100));
            seekBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
                @Override
                public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                    float zoom = 1.0f + (progress / 100.0f);
                    valueText.setText(String.format("%.1fx", zoom));
                    if (fromUser) internalCam.setZoom(zoom);
                }
                @Override public void onStartTrackingTouch(SeekBar seekBar) {}
                @Override public void onStopTrackingTouch(SeekBar seekBar) {}
            });
        } else {
            seekBar.setEnabled(false);
            valueText.setText("Not supported");
        }

        showOverlay(zoomOverlay, "Zoom");
    }

    private void showExposureOverlay() {
        if (cameraManager == null || cameraManager.isCurrentCameraUsb()) {
            toast("Exposure not supported for USB camera");
            return;
        }

        InternalCameraManager internalCam = cameraManager.getInternalCameraManager();
        if (internalCam == null) return;

        View exposureOverlay = getLayoutInflater().inflate(R.layout.overlay_exposure, null);
        SeekBar seekBar = exposureOverlay.findViewById(R.id.exposure_seekbar_overlay);
        TextView valueText = exposureOverlay.findViewById(R.id.exposure_value_overlay);

        int minExp = internalCam.getMinExposureCompensation();
        int maxExp = internalCam.getMaxExposureCompensation();

        if (minExp != maxExp) {
            seekBar.setMax(maxExp - minExp);
            seekBar.setProgress(internalCam.getExposureCompensation() - minExp);
            seekBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
                @Override
                public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                    int ev = minExp + progress;
                    valueText.setText(String.format("%d EV", ev));
                    if (fromUser) internalCam.setExposureCompensation(ev);
                }
                @Override public void onStartTrackingTouch(SeekBar seekBar) {}
                @Override public void onStopTrackingTouch(SeekBar seekBar) {}
            });
        } else {
            seekBar.setEnabled(false);
            valueText.setText("Not supported");
        }

        showOverlay(exposureOverlay, "Exposure");
    }

    private void showWhiteBalanceOverlay() {
        if (cameraManager == null || cameraManager.isCurrentCameraUsb()) {
            toast("White Balance not supported for USB camera");
            return;
        }

        InternalCameraManager internalCam = cameraManager.getInternalCameraManager();
        if (internalCam == null) return;

        View wbOverlay = getLayoutInflater().inflate(R.layout.overlay_wb, null);
        GridView gridView = wbOverlay.findViewById(R.id.wb_grid);

        String[] wbModes = internalCam.getSupportedWhiteBalanceModes();

        ArrayAdapter<String> adapter = new ArrayAdapter<String>(this,
                android.R.layout.simple_list_item_1, wbModes) {
            @Override
            public View getView(int position, View convertView, ViewGroup parent) {
                View view = super.getView(position, convertView, parent);
                TextView text = (TextView) view;
                text.setTextColor(Color.WHITE);
                text.setGravity(Gravity.CENTER);
                text.setPadding(16, 12, 16, 12);
                text.setBackgroundResource(R.drawable.control_button_bg);
                return view;
            }
        };

        gridView.setAdapter(adapter);
        gridView.setOnItemClickListener((parent, view, position, id) -> {
            internalCam.setWhiteBalanceMode(wbModes[position]);
            if (currentDialog != null) currentDialog.dismiss();
            toast("White Balance: " + wbModes[position]);
        });

        showOverlay(wbOverlay, "White Balance");
    }

    private void showFocusOverlay() {
        if (cameraManager == null || cameraManager.isCurrentCameraUsb()) {
            toast("Focus not supported for USB camera");
            return;
        }

        InternalCameraManager internalCam = cameraManager.getInternalCameraManager();
        if (internalCam == null) return;

        View focusOverlay = getLayoutInflater().inflate(R.layout.overlay_focus, null);
        GridView gridView = focusOverlay.findViewById(R.id.focus_grid);
        Button afTrigger = focusOverlay.findViewById(R.id.af_trigger_btn);

        String[] focusModes = internalCam.getSupportedFocusModes();

        ArrayAdapter<String> adapter = new ArrayAdapter<String>(this,
                android.R.layout.simple_list_item_1, focusModes) {
            @Override
            public View getView(int position, View convertView, ViewGroup parent) {
                View view = super.getView(position, convertView, parent);
                TextView text = (TextView) view;
                text.setTextColor(Color.WHITE);
                text.setGravity(Gravity.CENTER);
                text.setPadding(16, 12, 16, 12);
                text.setBackgroundResource(R.drawable.control_button_bg);
                return view;
            }
        };

        gridView.setAdapter(adapter);
        gridView.setOnItemClickListener((parent, view, position, id) -> {
            internalCam.setFocusMode(focusModes[position]);
            if (currentDialog != null) currentDialog.dismiss();
            toast("Focus Mode: " + focusModes[position]);
        });

        afTrigger.setOnClickListener(v -> {
            internalCam.triggerAutoFocus();
            toast("Auto-focus triggered");
            if (currentDialog != null) currentDialog.dismiss();
        });

        showOverlay(focusOverlay, "Focus");
    }

    private void showFlashOverlay() {
        if (cameraManager == null || cameraManager.isCurrentCameraUsb()) {
            toast("Flash not supported for USB camera");
            return;
        }

        InternalCameraManager internalCam = cameraManager.getInternalCameraManager();
        if (internalCam == null || !internalCam.isFlashSupported()) {
            toast("Flash not available");
            return;
        }

        View flashOverlay = getLayoutInflater().inflate(R.layout.overlay_flash, null);
        ToggleButton flashToggle = flashOverlay.findViewById(R.id.flash_toggle_overlay);

        // You can set initial state if needed
        // flashToggle.setChecked(internalCam.isFlashEnabled()); // You'd need to add this method

        flashToggle.setOnCheckedChangeListener((buttonView, isChecked) -> {
            internalCam.setFlash(isChecked);
        });

        showOverlay(flashOverlay, "Flash");
    }

    private void showGridOverlay() {
        View gridOverlayView = getLayoutInflater().inflate(R.layout.overlay_grid, null);
        ToggleButton gridToggle = gridOverlayView.findViewById(R.id.grid_toggle_overlay);

        if (gridOverlay != null) {
            gridToggle.setChecked(gridOverlay.isGridShowing());

            gridToggle.setOnCheckedChangeListener((buttonView, isChecked) -> {
                gridOverlay.setShowGrid(isChecked);
            });
        }

        showOverlay(gridOverlayView, "Grid Lines");
    }

    private void showRotationOverlay() {
        View rotationOverlay = getLayoutInflater().inflate(R.layout.overlay_rotation, null);
        Button rotateLeft = rotationOverlay.findViewById(R.id.rotate_left_overlay);
        Button rotateRight = rotationOverlay.findViewById(R.id.rotate_right_overlay);
        Button resetRotation = rotationOverlay.findViewById(R.id.reset_rotation_overlay);

        rotateLeft.setOnClickListener(v -> {
            currentRotation = (currentRotation - 90) % 360;
            applyPreviewRotation();
            if (currentDialog != null) currentDialog.dismiss();
        });

        rotateRight.setOnClickListener(v -> {
            currentRotation = (currentRotation + 90) % 360;
            applyPreviewRotation();
            if (currentDialog != null) currentDialog.dismiss();
        });

        resetRotation.setOnClickListener(v -> {
            currentRotation = 0;
            applyPreviewRotation();
            if (currentDialog != null) currentDialog.dismiss();
        });

        showOverlay(rotationOverlay, "Preview Rotation");
    }

    private void showRoutingOverlay() {
        View routingOverlay = getLayoutInflater().inflate(R.layout.overlay_routing, null);

        Button phoneSpeakerBtn = routingOverlay.findViewById(R.id.routing_phone_btn);
        Button bluetoothDeviceBtn = routingOverlay.findViewById(R.id.routing_bluetooth_btn);
        Button autoBtn = routingOverlay.findViewById(R.id.routing_auto_btn);

        // Highlight current selection
        AudioManager.PlaybackRouting currentRouting = audioManager.getPlaybackRouting();
        updateRoutingButtonHighlight(phoneSpeakerBtn, bluetoothDeviceBtn, autoBtn, currentRouting);

        phoneSpeakerBtn.setOnClickListener(v -> {
            audioManager.setPlaybackRouting(AudioManager.PlaybackRouting.PHONE_SPEAKER);
            toast("Audio: Phone Speaker");
            updateRoutingButtonHighlight(phoneSpeakerBtn, bluetoothDeviceBtn, autoBtn, AudioManager.PlaybackRouting.PHONE_SPEAKER);
            if (currentDialog != null) currentDialog.dismiss();
        });

        bluetoothDeviceBtn.setOnClickListener(v -> {
            audioManager.setPlaybackRouting(AudioManager.PlaybackRouting.BLUETOOTH_DEVICE);
            toast("Audio: Bluetooth Device");
            updateRoutingButtonHighlight(phoneSpeakerBtn, bluetoothDeviceBtn, autoBtn, AudioManager.PlaybackRouting.BLUETOOTH_DEVICE);
            if (currentDialog != null) currentDialog.dismiss();
        });

        autoBtn.setOnClickListener(v -> {
            audioManager.setPlaybackRouting(AudioManager.PlaybackRouting.AUTO);
            toast("Audio: Auto");
            updateRoutingButtonHighlight(phoneSpeakerBtn, bluetoothDeviceBtn, autoBtn, AudioManager.PlaybackRouting.AUTO);
            if (currentDialog != null) currentDialog.dismiss();
        });

        showOverlay(routingOverlay, "Audio Routing");
    }

    private void updateRoutingButtonHighlight(Button phoneBtn, Button bluetoothBtn, Button autoBtn,
                                              AudioManager.PlaybackRouting routing) {
        // Reset all buttons
        phoneBtn.setSelected(false);
        bluetoothBtn.setSelected(false);
        autoBtn.setSelected(false);

        // Highlight selected
        switch (routing) {
            case PHONE_SPEAKER:
                phoneBtn.setSelected(true);
                break;
            case BLUETOOTH_DEVICE:
                bluetoothBtn.setSelected(true);
                break;
            case AUTO:
                autoBtn.setSelected(true);
                break;
        }
    }

    private void showOverlay(View overlayView, String title) {
        // Close any existing overlay
        if (currentDialog != null && currentDialog.isShowing()) {
            currentDialog.dismiss();
        }

        AlertDialog.Builder builder = new AlertDialog.Builder(this, R.style.TransparentDialog);
        builder.setView(overlayView);
        builder.setCancelable(true);

        currentDialog = builder.create();
        currentDialog.show();

        // Set transparent background and position at bottom
        if (currentDialog.getWindow() != null) {
            currentDialog.getWindow().setLayout(
                    WindowManager.LayoutParams.MATCH_PARENT,
                    WindowManager.LayoutParams.WRAP_CONTENT
            );
            currentDialog.getWindow().setGravity(Gravity.BOTTOM);
            currentDialog.getWindow().setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT));
        }
    }

    private String getCompassDirection(float azimuth) {
        if (azimuth >= 337.5f || azimuth < 22.5f) return "N";
        if (azimuth >= 22.5f && azimuth < 67.5f) return "NE";
        if (azimuth >= 67.5f && azimuth < 112.5f) return "E";
        if (azimuth >= 112.5f && azimuth < 157.5f) return "SE";
        if (azimuth >= 157.5f && azimuth < 202.5f) return "S";
        if (azimuth >= 202.5f && azimuth < 247.5f) return "SW";
        if (azimuth >= 247.5f && azimuth < 292.5f) return "W";
        if (azimuth >= 292.5f && azimuth < 337.5f) return "NW";
        return "N";
    }
}
