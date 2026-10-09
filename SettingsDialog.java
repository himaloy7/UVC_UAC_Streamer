package com.serenegiant.usbcameratest;

import android.app.AlertDialog;
import android.content.Context;
import android.util.Log;
import android.view.View;
import android.widget.*;

import com.serenegiant.usbcameratest.managers.InternalCameraManager;
import com.serenegiant.usbcameratest.managers.MyCameraManager;
import com.serenegiant.usbcameratest.managers.StreamProfile;
import com.serenegiant.usbcameratest.managers.StreamProfile.ResolutionItem;


import java.util.ArrayList;
import java.util.List;

public class SettingsDialog {

    // Hardcoded USB camera options
    private static final ResolutionItem[] USB_RESOLUTIONS = {
            new ResolutionItem(1920, 1080, "1920x1080 (1080p)"),
            new ResolutionItem(1280, 720, "1280x720 (720p)"),
            new ResolutionItem(720, 576, "720x576 (576i)"),
            new ResolutionItem(720, 480, "720x480 (480p)"),
            new ResolutionItem(640, 480, "640x480"),
            new ResolutionItem(640, 360, "640x360 (360p)")
    };

    private static final int[] USB_BITRATES = {12000000, 8000000, 4000000, 2000000, 1500000};
    private static final int[] USB_FPS_OPTIONS = {15, 24, 25, 30, 50, 60};

    public static void show(Context context, String currentUrl,
                            MyCameraManager cameraManager,
                            OnSettingsSavedListener listener) {
        View view = LinearLayout.inflate(context, R.layout.dialog_settings, null);

        // Stream URL input
        EditText urlInput = view.findViewById(R.id.settings_url_input);
        urlInput.setText(currentUrl);

        boolean isUsbCamera = cameraManager != null && cameraManager.isCurrentCameraUsb();

        if (isUsbCamera) {
            // USB Camera: Use hardcoded values
            setupUsbCameraSettings(view, context);
        } else {
            // Internal Camera: Query Camera2 capabilities
            setupInternalCameraSettings(view, context, cameraManager);
        }

        // Audio bitrate spinner (same for both)
        setupAudioBitrateSpinner(view, context);

        new AlertDialog.Builder(context)
                .setTitle("Settings")
                .setView(view)
                .setPositiveButton("Apply", (dialog, which) -> {
                    Spinner resolutionSpinner = view.findViewById(R.id.settings_resolution_spinner);
                    Spinner fpsSpinner = view.findViewById(R.id.settings_fps_spinner);
                    Spinner audioBitrateSpinner = view.findViewById(R.id.settings_audio_bitrate_spinner);

                    ResolutionItem selectedResolution = (ResolutionItem) resolutionSpinner.getSelectedItem();
                    int fps = (int) fpsSpinner.getSelectedItem();

                    String[] audioBitrates = {"64 kbps", "96 kbps", "128 kbps", "160 kbps", "192 kbps", "256 kbps", "320 kbps"};
                    int[] audioBitrateValues = {64000, 96000, 128000, 160000, 192000, 256000, 320000};
                    int audioBitrate = audioBitrateValues[audioBitrateSpinner.getSelectedItemPosition()];

                    String newUrl = urlInput.getText().toString().trim();

                    if (listener != null) {
                        listener.onSettingsSaved(newUrl, selectedResolution.width, selectedResolution.height,
                                fps, audioBitrate);
                    }
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private static void setupUsbCameraSettings(View view, Context context) {
        Spinner resolutionSpinner = view.findViewById(R.id.settings_resolution_spinner);
        Spinner fpsSpinner = view.findViewById(R.id.settings_fps_spinner);

        // ✅ Get detected max capabilities from StreamProfile
        int maxWidth = StreamProfile.getUsbInputWidth();
        int maxHeight = StreamProfile.getUsbInputHeight();
        int detectedFps = StreamProfile.getUsbInputFps();  // Renamed from maxFps for clarity

        Log.i("SettingsDialog", "USB detected capabilities: " + maxWidth + "x" + maxHeight + "@" + detectedFps);

        // ✅ Build resolution list from adapter's supported sizes
        List<ResolutionItem> availableResolutions = new ArrayList<>();

        // First, try to get supported resolutions from StreamProfile (populated during detection)
        List<ResolutionItem> adapterResolutions = StreamProfile.getUsbSupportedResolutions();

        if (adapterResolutions != null && !adapterResolutions.isEmpty()) {
            // Use dynamic resolutions from adapter
            availableResolutions.addAll(adapterResolutions);
            Log.i("SettingsDialog", "Using " + adapterResolutions.size() + " dynamic resolutions from adapter");
        } else {
            // Fallback to hardcoded resolutions
            for (ResolutionItem item : USB_RESOLUTIONS) {
                if (item.width <= maxWidth && item.height <= maxHeight) {
                    availableResolutions.add(item);
                }
            }
            Log.i("SettingsDialog", "Using fallback hardcoded resolutions");
        }

        // If no resolutions match, add at least the detected resolution
        if (availableResolutions.isEmpty()) {
            String label = maxWidth + "x" + maxHeight;
            if (maxWidth == 1920 && maxHeight == 1080) label += " (1080p)";
            else if (maxWidth == 1280 && maxHeight == 720) label += " (720p)";
            availableResolutions.add(new ResolutionItem(maxWidth, maxHeight, label));
        }

        // Find current resolution index
        int currentResIndex = 0;
        int currentWidth = StreamProfile.getWidth();
        int currentHeight = StreamProfile.getHeight();

        for (int i = 0; i < availableResolutions.size(); i++) {
            ResolutionItem item = availableResolutions.get(i);
            if (item.width == currentWidth && item.height == currentHeight) {
                currentResIndex = i;
                break;
            }
        }

        // Setup resolution spinner
        ArrayAdapter<ResolutionItem> resAdapter = new ArrayAdapter<>(context,
                android.R.layout.simple_spinner_item, availableResolutions);
        resAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        resolutionSpinner.setAdapter(resAdapter);
        resolutionSpinner.setSelection(currentResIndex);

        // ==================== SHOW ALL FRAMERATE OPTIONS (NO CAP) ====================
        List<Integer> availableFps = new ArrayList<>();

        // Get supported FPS from StreamProfile if available
        List<Integer> adapterFps = StreamProfile.getUsbSupportedFps();
        if (adapterFps != null && !adapterFps.isEmpty()) {
            availableFps.addAll(adapterFps);
            Log.i("SettingsDialog", "Using " + adapterFps.size() + " dynamic FPS from adapter: " + adapterFps);
        } else {
            // ✅ SHOW ALL FRAMERATES - NO CAPPING BASED ON DETECTED FPS
            availableFps.add(60);
            availableFps.add(59);
            availableFps.add(50);
            availableFps.add(48);
            availableFps.add(30);
            availableFps.add(29);
            availableFps.add(25);
            availableFps.add(24);
            availableFps.add(23);
            availableFps.add(20);
            availableFps.add(15);
            availableFps.add(10);

            Log.i("SettingsDialog", "Showing all framerate options (no cap): " + availableFps);
        }

        // Remove duplicates and sort descending (highest first)
        List<Integer> uniqueFps = new ArrayList<>();
        for (int fps : availableFps) {
            if (!uniqueFps.contains(fps)) {
                uniqueFps.add(fps);
            }
        }
        java.util.Collections.sort(uniqueFps, (a, b) -> b - a);

        // Find current FPS index (user's last selection)
        int fpsIndex = 0;
        int currentFps = StreamProfile.getFps();
        for (int i = 0; i < uniqueFps.size(); i++) {
            if (uniqueFps.get(i) == currentFps) {
                fpsIndex = i;
                break;
            }
        }

        ArrayAdapter<Integer> fpsAdapter = new ArrayAdapter<>(context,
                android.R.layout.simple_spinner_item, uniqueFps);
        fpsAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        fpsSpinner.setAdapter(fpsAdapter);
        fpsSpinner.setSelection(fpsIndex);
    }

    private static void setupInternalCameraSettings(View view, Context context,
                                                    MyCameraManager cameraManager) {
        if (cameraManager == null) {
            // Fallback to USB settings if camera manager is null
            setupUsbCameraSettings(view, context);
            return;
        }

        Spinner resolutionSpinner = view.findViewById(R.id.settings_resolution_spinner);
        Spinner fpsSpinner = view.findViewById(R.id.settings_fps_spinner);

        // Get supported resolutions from camera
        List<InternalCameraManager.Size> supportedResolutions = cameraManager.getCurrentCameraResolutions();
        List<Integer> supportedFpsRanges = cameraManager.getCurrentCameraFpsRanges();

        // Convert to ResolutionItem list
        List<ResolutionItem> resolutionItems = new ArrayList<>();
        if (supportedResolutions != null && !supportedResolutions.isEmpty()) {
            for (InternalCameraManager.Size size : supportedResolutions) {
                String label = size.width + "x" + size.height;
                // Add quality suffix for common resolutions
                if (size.width == 1920 && size.height == 1080) label += " (1080p)";
                else if (size.width == 1280 && size.height == 720) label += " (720p)";
                else if (size.width == 720 && size.height == 576) label += " (576i)";
                else if (size.width == 720 && size.height == 480) label += " (480p)";
                else if (size.width == 640 && size.height == 480) label += " (480p)";
                else if (size.width == 640 && size.height == 360) label += " (360p)";

                resolutionItems.add(new ResolutionItem(size.width, size.height, label));
            }
        } else {
            // Fallback to common resolutions
            for (ResolutionItem item : USB_RESOLUTIONS) {
                resolutionItems.add(item);
            }
        }

        // Find current resolution index
        int currentResIndex = 0;
        for (int i = 0; i < resolutionItems.size(); i++) {
            ResolutionItem item = resolutionItems.get(i);
            if (item.width == StreamProfile.getWidth() && item.height == StreamProfile.getHeight()) {
                currentResIndex = i;
                break;
            }
        }

        // Setup resolution spinner
        ArrayAdapter<ResolutionItem> resAdapter = new ArrayAdapter<>(context,
                android.R.layout.simple_spinner_item, resolutionItems);
        resAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        resolutionSpinner.setAdapter(resAdapter);
        resolutionSpinner.setSelection(currentResIndex);

        // Setup FPS spinner
        if (supportedFpsRanges != null && !supportedFpsRanges.isEmpty()) {
            int fpsIndex = 0;
            int currentFps = StreamProfile.getFps();

            // Find closest match
            for (int i = 0; i < supportedFpsRanges.size(); i++) {
                if (supportedFpsRanges.get(i) == currentFps) {
                    fpsIndex = i;
                    break;
                }
            }

            // If exact match not found, find closest
            if (supportedFpsRanges.get(fpsIndex) != currentFps) {
                int closestDiff = Integer.MAX_VALUE;
                for (int i = 0; i < supportedFpsRanges.size(); i++) {
                    int diff = Math.abs(supportedFpsRanges.get(i) - currentFps);
                    if (diff < closestDiff) {
                        closestDiff = diff;
                        fpsIndex = i;
                    }
                }
            }

            ArrayAdapter<Integer> fpsAdapter = new ArrayAdapter<>(context,
                    android.R.layout.simple_spinner_item, supportedFpsRanges);
            fpsAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
            fpsSpinner.setAdapter(fpsAdapter);
            fpsSpinner.setSelection(fpsIndex);
        } else {
            // Fallback to standard FPS values
            Integer[] fpsOptions = {15, 24, 30, 60};
            int fpsIndex = 2; // Default to 30
            for (int i = 0; i < fpsOptions.length; i++) {
                if (fpsOptions[i] == StreamProfile.getFps()) {
                    fpsIndex = i;
                    break;
                }
            }

            ArrayAdapter<Integer> fpsAdapter = new ArrayAdapter<>(context,
                    android.R.layout.simple_spinner_item, fpsOptions);
            fpsAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
            fpsSpinner.setAdapter(fpsAdapter);
            fpsSpinner.setSelection(fpsIndex);
        }
    }

    private static void setupAudioBitrateSpinner(View view, Context context) {
        Spinner audioBitrateSpinner = view.findViewById(R.id.settings_audio_bitrate_spinner);
        String[] audioBitrates = {"64 kbps", "96 kbps", "128 kbps", "160 kbps", "192 kbps", "256 kbps", "320 kbps"};
        int[] audioBitrateValues = {64000, 96000, 128000, 160000, 192000, 256000, 320000};

        int audioIndex = 5; // Default 256kbps
        for (int i = 0; i < audioBitrateValues.length; i++) {
            if (audioBitrateValues[i] == StreamProfile.getAudioBitrate()) {
                audioIndex = i;
                break;
            }
        }

        ArrayAdapter<String> audioAdapter = new ArrayAdapter<>(context,
                android.R.layout.simple_spinner_item, audioBitrates);
        audioAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        audioBitrateSpinner.setAdapter(audioAdapter);
        audioBitrateSpinner.setSelection(audioIndex);
    }

    private static Integer[] convertToIntegerArray(int[] intArray) {
        Integer[] integerArray = new Integer[intArray.length];
        for (int i = 0; i < intArray.length; i++) {
            integerArray[i] = intArray[i];
        }
        return integerArray;
    }

    // Helper class for resolution items

    public interface OnSettingsSavedListener {
        void onSettingsSaved(String url, int width, int height, int fps, int audioBitrate);
    }
}
