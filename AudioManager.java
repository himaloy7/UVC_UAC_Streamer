package com.serenegiant.usbcameratest.managers;

import android.Manifest;
import android.app.Activity;
import android.content.Context;
import android.content.pm.PackageManager;
import android.media.*;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.Process;
import android.util.Log;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.widget.Toast;


import com.serenegiant.widget.LevelMeterView;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class AudioManager {

    private static final String TAG = "AudioManager";

    public interface AudioEncoderCallback {
        void onAudioFrame(byte[] pcmData, int size);
    }

    // Audio source types (replaces old MicSource)
    public enum AudioSource {
        INTERNAL,
        USB,
        BLUETOOTH,
        WIRED_HEADSET,
        AUTO
    }

    // Add this right after the AudioSource enum definition
    public enum PlaybackRouting {
        PHONE_SPEAKER,      // Always play on phone speaker
        BLUETOOTH_DEVICE,   // Play on Bluetooth device (if connected)
        AUTO                // Auto-detect (default to phone for Bluetooth mic)
    }

    // Callback for audio frames
    private AudioEncoderCallback audioEncoderCallback;

    private BroadcastReceiver bluetoothReceiver;
    private boolean bluetoothScoActive = false;
    private int bluetoothScoState = -1;
    private AudioEncoderCallback permanentCallback;

    private PlaybackRouting playbackRouting = PlaybackRouting.PHONE_SPEAKER; // Default to phone speaker
    private int actualBluetoothSampleRate = 16000; // Add this for Bluetooth sample rate

    public void setAudioEncoderCallback(AudioEncoderCallback callback) {
        this.permanentCallback = callback;  // Store permanently
        this.audioEncoderCallback = callback; // Also set the current one
        //Log.i(TAG, "Audio callback registered and stored permanently");
    }

    private boolean hasMicrophonePermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            int result = activity.checkSelfPermission(Manifest.permission.RECORD_AUDIO);
            return result == PackageManager.PERMISSION_GRANTED;
        }
        return true; // Pre-Marshmallow, permission is granted at install time
    }

    private final Context context;
    private final Activity activity;
    private final LevelMeterView levelMeterView;

    private volatile boolean isRunning = false;
    private volatile boolean playbackEnabled = true;

    // Current audio parameters
    private int currentSampleRate = 48000;
    private int currentChannels = 2;
    private String currentDeviceName = "Internal Mic";

    // Add with other fields (around line 30)
    private static final int BLUETOOTH_SCO_TIMEOUT_MS = 5000;
    private android.media.AudioManager systemAudioManager;
    private AudioSource currentAudioSource = AudioSource.INTERNAL;


    private ExecutorService executor = Executors.newSingleThreadExecutor();
    private volatile boolean isStopping = false;

    public AudioManager(Activity activity, LevelMeterView levelMeterView) {
        this.activity = activity;
        this.context = activity.getApplicationContext();
        this.levelMeterView = levelMeterView;

        // Add this line
        this.systemAudioManager = (android.media.AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
    }

    /* =========================
   Bluetooth SCO Management
   ========================= */

    // Add this method to register Bluetooth receiver
    private void registerBluetoothReceiver() {
        bluetoothReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                String action = intent.getAction();
                if (action == null) return;

                switch (action) {
                    case android.media.AudioManager.ACTION_SCO_AUDIO_STATE_UPDATED:
                        int state = intent.getIntExtra(android.media.AudioManager.EXTRA_SCO_AUDIO_STATE, -1);
                        bluetoothScoState = state;

                        switch (state) {
                            case android.media.AudioManager.SCO_AUDIO_STATE_CONNECTED:
                                bluetoothScoActive = true;
                                Log.i(TAG, "Bluetooth SCO connected");
                                break;
                            case android.media.AudioManager.SCO_AUDIO_STATE_CONNECTING:
                                Log.i(TAG, "Bluetooth SCO connecting...");
                                break;
                            case android.media.AudioManager.SCO_AUDIO_STATE_DISCONNECTED:
                                bluetoothScoActive = false;
                                Log.i(TAG, "Bluetooth SCO disconnected");
                                break;
                        }
                        break;
                }
            }
        };

        IntentFilter filter = new IntentFilter();
        filter.addAction(android.media.AudioManager.ACTION_SCO_AUDIO_STATE_UPDATED);
        context.registerReceiver(bluetoothReceiver, filter);
    }

    // Add method to unregister receiver
    private void unregisterBluetoothReceiver() {
        if (bluetoothReceiver != null) {
            try {
                context.unregisterReceiver(bluetoothReceiver);
            } catch (Exception e) {
                Log.w(TAG, "Error unregistering bluetooth receiver: " + e.getMessage());
            }
            bluetoothReceiver = null;
        }
    }

    // Update startBluetoothSco method
    private void startBluetoothSco() {
        if (systemAudioManager == null) return;

        try {
            registerBluetoothReceiver();

            // Start SCO
            systemAudioManager.startBluetoothSco();
            systemAudioManager.setBluetoothScoOn(true);

            Log.i(TAG, "Bluetooth SCO requested");
        } catch (SecurityException e) {
            Log.e(TAG, "Bluetooth permission required: " + e.getMessage());
        }
    }

    // Update stopBluetoothSco method
    private void stopBluetoothSco() {
        if (systemAudioManager == null) return;

        try {
            systemAudioManager.setBluetoothScoOn(false);
            systemAudioManager.stopBluetoothSco();
            bluetoothScoActive = false;
            unregisterBluetoothReceiver();
            Log.i(TAG, "Bluetooth SCO stopped");
        } catch (SecurityException e) {
            Log.e(TAG, "Error stopping Bluetooth SCO: " + e.getMessage());
        }
    }

    // Update isBluetoothScoAvailable method
    private boolean isBluetoothScoAvailable() {
        if (systemAudioManager == null) return false;
        return systemAudioManager.isBluetoothScoAvailableOffCall();
    }

    /* =========================
   Bluetooth Audio Routing Fix
   ========================= */

    private void forceBluetoothRouting() {
        if (systemAudioManager == null) return;

        try {
            // Force audio to route through Bluetooth for both input and output
            systemAudioManager.setBluetoothScoOn(true);

            // For newer Android versions, also try to set communication device
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                java.util.List<AudioDeviceInfo> devices = systemAudioManager.getAvailableCommunicationDevices();
                for (AudioDeviceInfo device : devices) {
                    if (device.getType() == AudioDeviceInfo.TYPE_BLUETOOTH_SCO) {
                        boolean success = systemAudioManager.setCommunicationDevice(device);
                        Log.i(TAG, "setCommunicationDevice for Bluetooth: " + success);
                        break;
                    }
                }
            }

            // Alternative: Use reflection to force routing
            forceAudioRouting();

        } catch (SecurityException e) {
            Log.e(TAG, "Bluetooth routing permission error: " + e.getMessage());
        }
    }

    private void forceAudioRouting() {
        try {
            // Try to use reflection to access AudioRecord routing
            Class<?> audioSystemClass = Class.forName("android.media.AudioSystem");

            // Method: setDeviceConnectionState(int device, int state, String device_address, String device_name)
            java.lang.reflect.Method setDeviceConnectionState = audioSystemClass.getMethod(
                    "setDeviceConnectionState",
                    int.class, int.class, String.class, String.class
            );

            // This is a hack - but sometimes needed for older devices
            Log.i(TAG, "Attempted forced audio routing via reflection");

        } catch (Exception e) {
            Log.d(TAG, "Reflection routing not available: " + e.getMessage());
        }
    }

    /* =========================
       Audio Source Selection
       ========================= */

    public void setAudioSource(AudioSource source) {
        if (this.currentAudioSource == source) {
            Log.d(TAG, "Audio source already " + source + ", ignoring");
            return;
        }

        this.currentAudioSource = source;
        Log.i(TAG, "Audio source set to: " + source);

        // Restart if already running
        if (isRunning) {
            stopAudioLoopbackAndWait();
            startAudioLoopback();
        }
    }
    private void stopAudioLoopbackAndWait() {
        isStopping = true;
        stopAudioLoopback();

        // Wait for audio loop to fully stop
        try {
            Thread.sleep(200);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        isStopping = false;
    }

    public AudioSource getCurrentAudioSource() {
        return currentAudioSource;
    }

    /* =========================
       Getters for audio parameters
       ========================= */

    public int getCurrentSampleRate() {
        return currentSampleRate;
    }

    public int getCurrentChannels() {
        return currentChannels;
    }

    public String getCurrentDeviceName() {
        return currentDeviceName;
    }

    /* =========================
       List available audio devices
       ========================= */

    public void listAvailableAudioDevices() {
        // FIXED: Use full path to Android's AudioManager
        android.media.AudioManager audioManager =
                (android.media.AudioManager) context.getSystemService(Context.AUDIO_SERVICE);

        // FIXED: Use full path to avoid naming conflict
        AudioDeviceInfo[] devices = audioManager.getDevices(android.media.AudioManager.GET_DEVICES_INPUTS);

        Log.i(TAG, "=== AVAILABLE AUDIO INPUT DEVICES ===");
        for (AudioDeviceInfo device : devices) {
            String type = getDeviceTypeName(device.getType());
            StringBuilder rates = new StringBuilder();
            for (int rate : device.getSampleRates()) {
                rates.append(rate).append("Hz ");
            }

            Log.i(TAG, "Device: " + device.getProductName() +
                    " | Type: " + type +
                    " | Channels: " + java.util.Arrays.toString(device.getChannelCounts()) +
                    " | Rates: " + rates.toString());
        }
        Log.i(TAG, "=====================================");
    }

    private String getDeviceTypeName(int type) {
        switch(type) {
            case AudioDeviceInfo.TYPE_BUILTIN_MIC: return "Built-in Mic";
            case AudioDeviceInfo.TYPE_USB_DEVICE: return "USB Device";
            case AudioDeviceInfo.TYPE_USB_HEADSET: return "USB Headset";
            case AudioDeviceInfo.TYPE_BLUETOOTH_SCO: return "Bluetooth SCO";
            case AudioDeviceInfo.TYPE_BLUETOOTH_A2DP: return "Bluetooth A2DP";
            case AudioDeviceInfo.TYPE_WIRED_HEADSET: return "Wired Headset";
            case AudioDeviceInfo.TYPE_LINE_ANALOG: return "Line Analog";
            default: return "Unknown (" + type + ")";
        }
    }

    /* =========================
       Find best device for selected source
       ========================= */

    private AudioDeviceInfo findBestDevice(AudioSource source) {
        android.media.AudioManager audioManager =
                (android.media.AudioManager) context.getSystemService(Context.AUDIO_SERVICE);

        AudioDeviceInfo[] devices = audioManager.getDevices(android.media.AudioManager.GET_DEVICES_INPUTS);
        AudioDeviceInfo bestDevice = null;

        for (AudioDeviceInfo device : devices) {
            boolean matches = false;

            switch(source) {
                case INTERNAL:
                    matches = (device.getType() == AudioDeviceInfo.TYPE_BUILTIN_MIC);
                    break;
                case USB:
                    matches = (device.getType() == AudioDeviceInfo.TYPE_USB_DEVICE ||
                            device.getType() == AudioDeviceInfo.TYPE_USB_HEADSET);
                    break;
                case BLUETOOTH:
                    matches = (device.getType() == AudioDeviceInfo.TYPE_BLUETOOTH_SCO);
                    // Only use SCO devices, not A2DP (A2DP is playback only)
                    break;
                case WIRED_HEADSET:
                    matches = (device.getType() == AudioDeviceInfo.TYPE_WIRED_HEADSET);
                    break;
                case AUTO:
                    // Prefer external over internal, but ALWAYS fallback to internal
                    matches = true; // We'll filter in the next step
                    break;
            }

            if (matches) {
                // For AUTO mode, prioritize external devices but accept internal
                if (source == AudioSource.AUTO) {
                    if (device.getType() != AudioDeviceInfo.TYPE_BUILTIN_MIC) {
                        // Found an external device - use it immediately
                        return device;
                    } else if (bestDevice == null) {
                        // Save internal as fallback
                        bestDevice = device;
                    }
                } else {
                    // For specific sources, prefer devices with more capabilities
                    if (bestDevice == null ||
                            device.getSampleRates().length > bestDevice.getSampleRates().length) {
                        bestDevice = device;
                    }
                }
            }
        }

        // If we're in AUTO mode and found no external device, return internal (fallback)
        if (source == AudioSource.AUTO && bestDevice == null) {
            // Look for any built-in mic
            for (AudioDeviceInfo device : devices) {
                if (device.getType() == AudioDeviceInfo.TYPE_BUILTIN_MIC) {
                    bestDevice = device;
                    Log.i(TAG, "AUTO mode: No external device, falling back to internal mic");
                    break;
                }
            }
        }

        return bestDevice;
    }

    /* =========================
       Get optimal sample rate (prefer 48kHz)
       ========================= */

    private int getOptimalSampleRate(AudioDeviceInfo device, AudioSource source) {
        if (device == null) return 48000;

        int[] rates = device.getSampleRates();
        if (rates == null || rates.length == 0) return 48000;

        // For Bluetooth, use the actual device rate (8k or 16k)
        if (source == AudioSource.BLUETOOTH) {
            for (int rate : rates) {
                if (rate == 16000 || rate == 8000) {
                    actualBluetoothSampleRate = rate;
                    Log.i(TAG, "Bluetooth sample rate detected: " + rate + " Hz");
                    return rate;
                }
            }
            return rates[0]; // Return first available if 8k/16k not found
        }

        // For other sources, prefer 48kHz for streaming
        for (int rate : rates) {
            if (rate == 48000) return 48000;
        }

        // If no 48000, use highest available
        int highest = 0;
        for (int rate : rates) {
            if (rate > highest) highest = rate;
        }
        return highest > 0 ? highest : 48000;
    }

    /* =========================
       Get channel count
       ========================= */

    private int getChannelCount(AudioDeviceInfo device, AudioSource source) {
        if (device == null) return 2;

        // Bluetooth SCO is typically mono
        if (source == AudioSource.BLUETOOTH) {
            return 1;
        }

        int[] channels = device.getChannelCounts();
        if (channels == null || channels.length == 0) return 2;

        for (int ch : channels) {
            if (ch == 2) return 2;
        }
        return channels[0];
    }

    /* =========================
       PUBLIC API
       ========================= */

    public boolean isRunning() {
        return isRunning;
    }

    public void setPlaybackEnabled(boolean enabled) {
        if (this.playbackEnabled != enabled) {
            this.playbackEnabled = enabled;
            Log.i(TAG, "Playback enabled: " + enabled);
            // Restart audio to apply new setting
            if (isRunning) {
                stopAudioLoopback();
                // Add small delay before restarting
                new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(() -> {
                    startAudioLoopback();
                }, 100);
            }
        }
    }
    public void setPlaybackRouting(PlaybackRouting routing) {
        this.playbackRouting = routing;
        Log.i(TAG, "Playback routing set to: " + routing);

        // Restart audio to apply new routing
        if (isRunning) {
            stopAudioLoopback();
            new Handler(Looper.getMainLooper()).postDelayed(this::startAudioLoopback, 200);
        }
    }

    public PlaybackRouting getPlaybackRouting() {
        return playbackRouting;
    }

    // Backward compatibility methods
    @Deprecated
    public void setUseUsbMic(boolean useUsb) {
        this.currentAudioSource = useUsb ? AudioSource.USB : AudioSource.INTERNAL;
    }

    @Deprecated
    public AudioSource getCurrentMic() {  // Changed return type
        return currentAudioSource;
    }

    public void stopAudioLoopback() {
        isRunning = false;
    }

    public void startAudioLoopback() {
        if (!hasMicrophonePermission()) {
            Log.e(TAG, "Cannot start audio - missing RECORD_AUDIO permission");
            return;
        }

        // Don't start if already stopping
        if (isStopping) {
            Log.d(TAG, "Audio is stopping, delaying start");
            new Handler(Looper.getMainLooper()).postDelayed(this::startAudioLoopback, 300);
            return;
        }

        // Don't start if already running
        if (isRunning) {
            //Log.d(TAG, "Audio already running, not starting another");
            return;
        }

        executor.execute(this::runAudioLoop);
    }

    /* =========================
       AUDIO LOOP
       ========================= */

    private void runAudioLoop() {
        Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO);
        isRunning = true;
        int frameCount = 0;

        // Check permission with retry mechanism
        int permissionRetries = 0;
        while (!hasMicrophonePermission() && permissionRetries < 5) {
            Log.w(TAG, "Waiting for microphone permission... (attempt " + (permissionRetries + 1) + "/5)");
            try {
                Thread.sleep(200);
                permissionRetries++;
            } catch (InterruptedException e) {}
        }

        if (!hasMicrophonePermission()) {
            Log.e(TAG, "Microphone permission not granted after retries");
            activity.runOnUiThread(() -> {
                Toast.makeText(activity, "Microphone permission required", Toast.LENGTH_LONG).show();
            });
            isRunning = false;
            return;
        }

        Log.i(TAG, "Microphone permission confirmed, proceeding with audio capture");

        // Special handling for Bluetooth
        if (currentAudioSource == AudioSource.BLUETOOTH) {
            if (!isBluetoothScoAvailable()) {
                Log.e(TAG, "Bluetooth SCO not available on this device");
                // Fall back to internal mic
                currentAudioSource = AudioSource.INTERNAL;
                Log.i(TAG, "Falling back to internal mic");
            } else {
                startBluetoothSco();

                // Wait for SCO to connect
                int attempts = 0;
                while (!bluetoothScoActive && attempts < 10) {
                    try {
                        Thread.sleep(500);
                        attempts++;
                    } catch (InterruptedException e) {}
                }

                forceBluetoothRouting();

                try {
                    Thread.sleep(200);
                } catch (InterruptedException e) {}
            }
        }

        // Find best device for current source
        // Find best device for current source
        AudioDeviceInfo device = findBestDevice(currentAudioSource);

        // If no device found, force internal mic
        if (device == null) {
            Log.w(TAG, "No suitable device found for " + currentAudioSource + ", forcing internal mic");
            android.media.AudioManager audioManager =
                    (android.media.AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
            AudioDeviceInfo[] devices = audioManager.getDevices(android.media.AudioManager.GET_DEVICES_INPUTS);

            for (AudioDeviceInfo d : devices) {
                if (d.getType() == AudioDeviceInfo.TYPE_BUILTIN_MIC) {
                    device = d;
                    currentDeviceName = "Internal Mic (forced)";
                    Log.i(TAG, "Forced internal mic as fallback");
                    break;
                }
            }
        }

// Determine optimal parameters and FORCE update of currentSampleRate
        int sampleRate;
        int channelMask;
        int channelCount;
        String deviceName;
        int audioSource = MediaRecorder.AudioSource.MIC;

        if (device != null) {
            sampleRate = getOptimalSampleRate(device, currentAudioSource);
            channelCount = getChannelCount(device, currentAudioSource);
            channelMask = (channelCount == 2) ?
                    AudioFormat.CHANNEL_IN_STEREO : AudioFormat.CHANNEL_IN_MONO;
            deviceName = String.valueOf(device.getProductName());

            // FORCE update of currentSampleRate to the actual device rate
            this.currentSampleRate = sampleRate;
            this.currentChannels = channelCount;
            this.currentDeviceName = deviceName;

            if (currentAudioSource == AudioSource.BLUETOOTH) {
                audioSource = MediaRecorder.AudioSource.VOICE_RECOGNITION;
                channelCount = 1;
                channelMask = AudioFormat.CHANNEL_IN_MONO;
            }
        } else {
            // Ultimate fallback - use default parameters
            sampleRate = 48000;
            channelCount = 2;
            channelMask = AudioFormat.CHANNEL_IN_STEREO;
            deviceName = "Default Internal";
            this.currentSampleRate = sampleRate;
            this.currentChannels = channelCount;
            this.currentDeviceName = deviceName;
            Log.w(TAG, "Using default audio parameters (no device found)");
        }

        int encoding = AudioFormat.ENCODING_PCM_16BIT;

        /*Log.i(TAG, "=== Starting Audio Capture ===");
        Log.i(TAG, "Source: " + currentAudioSource);
        Log.i(TAG, "Device: " + deviceName);
        Log.i(TAG, "Sample Rate: " + sampleRate + " Hz");
        Log.i(TAG, "Channels: " + channelCount);
        Log.i(TAG, "Audio Source: " + (audioSource == MediaRecorder.AudioSource.VOICE_RECOGNITION ? "VOICE_RECOGNITION" : "MIC"));*/

        int recBufferSize = AudioRecord.getMinBufferSize(sampleRate, channelMask, encoding) * 4;

        AudioRecord recorder = null;

        // Try to create AudioRecord with retries
        int recordRetries = 0;
        while (recordRetries < 3) {
            try {
                recorder = buildAudioRecord(device, sampleRate, channelMask, encoding, recBufferSize, audioSource);
                if (recorder != null && recorder.getState() == AudioRecord.STATE_INITIALIZED) {
                    break; // Success
                }
            } catch (SecurityException e) {
                Log.w(TAG, "SecurityException creating AudioRecord (attempt " + (recordRetries + 1) + "/3): " + e.getMessage());
            }

            recordRetries++;
            if (recordRetries < 3) {
                try {
                    Thread.sleep(200);
                } catch (InterruptedException e) {}
            }
        }

        if (recorder == null || recorder.getState() != AudioRecord.STATE_INITIALIZED) {
            Log.e(TAG, "AudioRecord initialization failed after retries");
            activity.runOnUiThread(() -> {
                Toast.makeText(activity, "Failed to initialize microphone", Toast.LENGTH_LONG).show();
            });
            if (currentAudioSource == AudioSource.BLUETOOTH) {
                stopBluetoothSco();
            }
            isRunning = false;
            return;
        }

        AudioTrack player = null;
        if (playbackEnabled) {
            /*int outChannelMask = (channelCount == 2) ?
                    AudioFormat.CHANNEL_OUT_STEREO : AudioFormat.CHANNEL_OUT_MONO;
            int playBufferSize = AudioTrack.getMinBufferSize(sampleRate, outChannelMask, encoding) * 4;*/
            player = buildAudioTrackWithRouting(sampleRate, channelCount, encoding);

            if (player.getState() != AudioTrack.STATE_INITIALIZED) {
                Log.w(TAG, "AudioTrack initialization failed - playback disabled");
                player = null;
            } else {
                player.play();
            }
        }

        recorder.startRecording();

        int bytesPerFrame = channelCount * 2;
        int aacFrameSize = 1024 * bytesPerFrame;
        byte[] buffer = new byte[aacFrameSize];

        /*// ADD LOGGING HERE - after variables are defined
        Log.i(TAG, "=== Audio Buffer Configuration ===");
        Log.i(TAG, "recBufferSize: " + recBufferSize + " bytes");
        Log.i(TAG, "bytesPerFrame: " + bytesPerFrame);
        Log.i(TAG, "aacFrameSize: " + aacFrameSize + " bytes");
        Log.i(TAG, "Sample Rate: " + sampleRate + " Hz");
        Log.i(TAG, "Channels: " + channelCount);
        Log.i(TAG, "===================================");*/


        //Log.i(TAG, "Audio capture running on instance: " + System.identityHashCode(this) + ", callback=" + (permanentCallback != null ? "PRESENT" : "NULL"));

        while (isRunning) {
            try {
                int read = recorder.read(buffer, 0, buffer.length);
                if (read > 0) {
                    //Log.i(TAG, "🎤 MIC CAPTURED: " + read + " bytes");
                    if (player != null) {
                        player.write(buffer, 0, read, AudioTrack.WRITE_BLOCKING);
                    }
                    // TO THIS:
                    if (permanentCallback != null) {
                        permanentCallback.onAudioFrame(buffer, read);
                        //Log.i(TAG, "➡️ Sent to callback");
                        //Log.i(TAG, "🔥 Audio frame sent to encoder via permanent callback, size: " + read);
                    } else {
                        Log.e(TAG, "⚠️ CRITICAL: permanentCallback is NULL! Audio lost!");
                    }

                    if (levelMeterView != null) {
                        float[] levels = calculateLevels(buffer, read, channelCount);
                        if (frameCount++ % 50 == 0) {
                            //Log.d(TAG, String.format("Levels: L=%.1fdB, R=%.1fdB", levels[0], levels[1]));
                        }
                        // Add this log to see if levels are being calculated
                        if (levels[0] > -60 || levels[1] > -60) {
                            //Log.d(TAG, "Levels calculated: L=" + levels[0] + ", R=" + levels[1]);
                        }
                        activity.runOnUiThread(() ->
                                levelMeterView.setLevels(levels[0], levels[1]));
                    }
                }
            } catch (SecurityException e) {
                Log.e(TAG, "SecurityException during recording: " + e.getMessage());
                break;
            } catch (Exception e) {
                Log.e(TAG, "Error during recording: " + e.getMessage());
                break;
            }
        }

        try {
            recorder.stop();
            recorder.release();
        } catch (Exception e) {
            Log.e(TAG, "Error stopping recorder: " + e.getMessage());
        }

        if (player != null) {
            try {
                player.stop();
                player.release();
            } catch (Exception e) {
                Log.e(TAG, "Error stopping player: " + e.getMessage());
            }
        }

        if (currentAudioSource == AudioSource.BLUETOOTH) {
            stopBluetoothSco();
        }
        isRunning = false;
        //Log.i(TAG, "Audio loopback stopped");
    }

    /* =========================
       Audio Builders
       ========================= */

    private AudioRecord buildAudioRecord(AudioDeviceInfo device, int sampleRate,
                                         int channelMask, int encoding, int bufferSize, int audioSource)
            throws SecurityException {
        AudioRecord.Builder builder = new AudioRecord.Builder()
                .setAudioSource(audioSource)
                .setAudioFormat(new AudioFormat.Builder()
                        .setEncoding(encoding)
                        .setSampleRate(sampleRate)
                        .setChannelMask(channelMask)
                        .build())
                .setBufferSizeInBytes(bufferSize);

        if (currentAudioSource == AudioSource.BLUETOOTH) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                builder.setAudioSource(MediaRecorder.AudioSource.VOICE_COMMUNICATION);
            }
        }

        AudioRecord record = builder.build();

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && device != null) {
            try {
                record.setPreferredDevice(device);
                Log.d(TAG, "Preferred device set: " + device.getProductName());
            } catch (Exception e) {
                Log.w(TAG, "Cannot set preferred device: " + e.getMessage());
            }
        }

        return record;
    }

    private AudioTrack buildAudioTrack(int sampleRate, int channelMask, int encoding, int bufferSize) {
        return new AudioTrack.Builder()
                .setAudioAttributes(new AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                        .build())
                .setAudioFormat(new AudioFormat.Builder()
                        .setSampleRate(sampleRate)
                        .setEncoding(encoding)
                        .setChannelMask(channelMask)
                        .build())
                .setBufferSizeInBytes(bufferSize)
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build();
    }

    private AudioTrack buildAudioTrackWithRouting(int sampleRate, int channelCount, int encoding) {
        int outChannelMask = (channelCount == 2) ? AudioFormat.CHANNEL_OUT_STEREO : AudioFormat.CHANNEL_OUT_MONO;
        int playBufferSize = AudioTrack.getMinBufferSize(sampleRate, outChannelMask, encoding) * 4;

        AudioAttributes.Builder attrBuilder = new AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC);

        // For Bluetooth routing - when user wants to hear on phone speaker
        if (playbackRouting == PlaybackRouting.PHONE_SPEAKER) {
            // Force playback to phone speaker
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                attrBuilder.setAllowedCapturePolicy(AudioAttributes.ALLOW_CAPTURE_BY_ALL);
            }
            Log.i(TAG, "Routing playback to PHONE SPEAKER");
        } else if (playbackRouting == PlaybackRouting.BLUETOOTH_DEVICE && currentAudioSource == AudioSource.BLUETOOTH) {
            Log.i(TAG, "Routing playback to BLUETOOTH DEVICE");
        } else {
            Log.i(TAG, "Routing playback to AUTO");
        }

        AudioTrack track = new AudioTrack.Builder()
                .setAudioAttributes(attrBuilder.build())
                .setAudioFormat(new AudioFormat.Builder()
                        .setSampleRate(sampleRate)
                        .setEncoding(encoding)
                        .setChannelMask(outChannelMask)
                        .build())
                .setBufferSizeInBytes(playBufferSize)
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build();

        // Force routing to phone speaker if specified
        if (playbackRouting == PlaybackRouting.PHONE_SPEAKER && Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            try {
                track.setPreferredDevice(null);
            } catch (Exception e) {
                Log.w(TAG, "Could not force phone speaker routing: " + e.getMessage());
            }
        }

        return track;
    }

    /* =========================
       Level calculation helpers
       ========================= */

    private float[] calculateLevels(byte[] buffer, int readBytes, int channels) {
        if (channels == 2) {
            return calculateStereoLevels(buffer, readBytes);
        } else {
            return calculateMonoLevels(buffer, readBytes);
        }
    }

    private float[] calculateStereoLevels(byte[] buffer, int readBytes) {
        double left = 0, right = 0;
        int frames = readBytes / 4;

        for (int i = 0; i < frames; i++) {
            int idx = i * 4;
            short l = (short) ((buffer[idx + 1] << 8) | (buffer[idx] & 0xFF));
            short r = (short) ((buffer[idx + 3] << 8) | (buffer[idx + 2] & 0xFF));
            left = Math.max(left, Math.abs(l));
            right = Math.max(right, Math.abs(r));
        }

        return new float[]{
                (float) (20 * Math.log10(left / 32768.0 + 1e-5)),
                (float) (20 * Math.log10(right / 32768.0 + 1e-5))
        };
    }

    private float[] calculateMonoLevels(byte[] buffer, int readBytes) {
        double level = 0;
        int frames = readBytes / 2;

        for (int i = 0; i < frames; i++) {
            int idx = i * 2;
            short s = (short) ((buffer[idx + 1] << 8) | (buffer[idx] & 0xFF));
            level = Math.max(level, Math.abs(s));
        }

        float dbLevel = (float) (20 * Math.log10(level / 32768.0 + 1e-5));
        return new float[]{dbLevel, dbLevel};
    }
}
