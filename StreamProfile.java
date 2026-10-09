package com.serenegiant.usbcameratest.managers;

import android.util.Log;
import java.util.ArrayList;
import java.util.List;

public class StreamProfile {
    private static final String TAG = "StreamProfile";

    // Internal camera constants
    public static final int INTERNAL_WIDTH = 1920;
    public static final int INTERNAL_HEIGHT = 1080;
    public static final int INTERNAL_FPS = 60;
    public static final int INTERNAL_BITRATE = 40_000_000;

    // Audio constants
    public static final int INTERNAL_AUDIO_SAMPLE_RATE = 48000;
    public static final int USB_AUDIO_SAMPLE_RATE = 48000;
    public static final int USB_BITRATE = 40_000_000;
    public static final int AUDIO_BITRATE = 256000;
    public static final int AUDIO_CHANNELS = 2;
    public static final int INTERNAL_AUDIO_FRAME_SIZE = 1024;
    public static final int USB_AUDIO_FRAME_SIZE = 1024;

    // Dynamic USB input (detected from hardware)
    private static int usbInputWidth = 1920;
    private static int usbInputHeight = 1080;
    private static int usbInputFps = 30;
    private static int usbInputBitrate = 40_000_000;

    // ✅ NEW: Store all supported resolutions from adapter
    private static List<ResolutionItem> usbSupportedResolutions = new ArrayList<>();
    private static List<Integer> usbSupportedFps = new ArrayList<>();

    // Output profiles
    private static int internalWidth = 1920;
    private static int internalHeight = 1080;
    private static int internalFps = 30;
    private static int internalBitrate = 40_000_000;

    private static int usbOutputWidth = 1280;
    private static int usbOutputHeight = 720;
    private static int usbOutputFps = 30;
    private static int usbOutputBitrate = 20_000_000;

    private static int currentAudioBitrate = 256000;
    private static String currentCameraType = "internal";

    // ==================== Getters ====================

    public static int getWidth() {
        return currentCameraType.equals("usb") ? usbOutputWidth : internalWidth;
    }

    public static int getHeight() {
        return currentCameraType.equals("usb") ? usbOutputHeight : internalHeight;
    }

    public static int getFps() {
        return currentCameraType.equals("usb") ? usbOutputFps : internalFps;
    }

    public static int getBitrate() {
        return currentCameraType.equals("usb") ? usbOutputBitrate : internalBitrate;
    }

    public static int getAudioBitrate() {
        return currentAudioBitrate;
    }

    // ==================== Setters ====================

    public static void setResolution(int width, int height) {
        if (currentCameraType.equals("usb")) {
            usbOutputWidth = width;
            usbOutputHeight = height;
            Log.i(TAG, "USB output resolution set to: " + width + "x" + height);
        } else {
            internalWidth = width;
            internalHeight = height;
            Log.i(TAG, "Internal output resolution set to: " + width + "x" + height);
        }
    }

    public static void setFps(int fps) {
        if (currentCameraType.equals("usb")) {
            usbOutputFps = fps;
            Log.i(TAG, "USB output FPS set to: " + fps);
        } else {
            internalFps = fps;
            Log.i(TAG, "Internal output FPS set to: " + fps);
        }
    }

    public static void setBitrate(int bitrate) {
        if (currentCameraType.equals("usb")) {
            usbOutputBitrate = bitrate;
        } else {
            internalBitrate = bitrate;
        }
    }

    public static void setAudioBitrate(int bitrate) {
        currentAudioBitrate = bitrate;
    }

    public static void setCurrentCameraType(String type) {
        currentCameraType = type;
        Log.i(TAG, "Current camera type set to: " + type);
    }

    public static String getCurrentCameraType() {
        return currentCameraType;
    }

    // ==================== USB Output Getters ====================

    public static int getUsbOutputWidth() { return usbOutputWidth; }
    public static int getUsbOutputHeight() { return usbOutputHeight; }
    public static int getUsbOutputFps() { return usbOutputFps; }

    // ==================== USB Input (Auto-detected) ====================

    public static void setUsbInputDimensions(int width, int height, int fps) {
        usbInputWidth = width;
        usbInputHeight = height;
        usbInputFps = fps;
        Log.i(TAG, "🔍 USB input detected: " + width + "x" + height + "@" + fps);
    }

    public static void setUsbInputFps(int fps) {
        usbInputFps = fps;
        Log.i(TAG, "USB input FPS updated to: " + fps);
    }

    public static int getUsbInputWidth() { return usbInputWidth; }
    public static int getUsbInputHeight() { return usbInputHeight; }
    public static int getUsbInputFps() { return usbInputFps; }

    // ==================== NEW: Supported Resolutions from Adapter ====================

    public static void setUsbSupportedResolutions(List<ResolutionItem> resolutions) {
        usbSupportedResolutions = new ArrayList<>(resolutions);
        Log.i(TAG, "USB supported resolutions: " + usbSupportedResolutions.size());
        for (ResolutionItem item : usbSupportedResolutions) {
            Log.d(TAG, "  - " + item.width + "x" + item.height);
        }
    }

    public static List<ResolutionItem> getUsbSupportedResolutions() {
        return usbSupportedResolutions;
    }

    public static void setUsbSupportedFps(List<Integer> fpsList) {
        usbSupportedFps = new ArrayList<>(fpsList);
        Log.i(TAG, "USB supported FPS: " + usbSupportedFps);
    }

    public static List<Integer> getUsbSupportedFps() {
        return usbSupportedFps;
    }

    // ==================== Helper Methods ====================

    public static boolean canSupportResolution(int width, int height) {
        return width <= usbInputWidth && height <= usbInputHeight;
    }

    public static boolean canSupportFps(int fps) {
        return fps <= usbInputFps;
    }

    public static void resetOutputToInput() {
        if (currentCameraType.equals("usb")) {
            usbOutputWidth = usbInputWidth;
            usbOutputHeight = usbInputHeight;
            usbOutputFps = usbInputFps;
            Log.i(TAG, "USB output reset to match input: " + usbOutputWidth + "x" + usbOutputHeight + "@" + usbOutputFps);
        }
    }

    // ==================== Resolution Item Class ====================

    public static class ResolutionItem {
        public int width;
        public int height;
        public String displayString;

        public ResolutionItem(int width, int height, String displayString) {
            this.width = width;
            this.height = height;
            this.displayString = displayString;
        }

        @Override
        public String toString() {
            return displayString;
        }
    }
}
