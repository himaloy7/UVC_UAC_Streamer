package com.serenegiant.usbcameratest.managers;

public interface CameraControls {
    // Zoom
    float getMaxZoom();
    void setZoom(float zoomLevel); // 1.0 = no zoom, higher = zoomed
    float getCurrentZoom();
    
    // Exposure
    void setExposureCompensation(int ev); // EV value (e.g., -12 to +12)
    int getExposureCompensation();
    int getMinExposureCompensation();
    int getMaxExposureCompensation();
    
    // Focus
    void setFocusMode(String mode); // "auto", "continuous", "fixed", "macro", "infinity"
    String[] getSupportedFocusModes();
    void triggerAutoFocus();
    
    // White Balance
    void setWhiteBalanceMode(String mode); // "auto", "incandescent", "fluorescent", "daylight", "cloudy"
    String[] getSupportedWhiteBalanceModes();
    
    // Other features
    boolean isFlashSupported();
    void setFlash(boolean enabled);
    void setManualFocusDistance(float distance); // 0.0 to 1.0
}
