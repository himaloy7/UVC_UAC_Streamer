package com.serenegiant.usbcameratest.managers;

import android.content.Context;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.util.Log;

public class DeviceOrientationManager implements SensorEventListener {
    
    private static final String TAG = "DeviceOrientationManager";
    
    private SensorManager sensorManager;
    private Sensor accelerometer;
    private Sensor magnetometer;
    
    private float[] gravity = new float[3];
    private float[] geomagnetic = new float[3];
    private float[] rotationMatrix = new float[9];
    private float[] orientation = new float[3];
    
    private OrientationListener listener;
    private boolean isListening = false;
    
    public interface OrientationListener {
        void onOrientationChanged(float roll, float pitch, float azimuth);
    }
    
    public DeviceOrientationManager(Context context, OrientationListener listener) {
        this.listener = listener;
        sensorManager = (SensorManager) context.getSystemService(Context.SENSOR_SERVICE);
        
        if (sensorManager != null) {
            accelerometer = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER);
            magnetometer = sensorManager.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD);
        }
    }
    
    public void startListening() {
        if (sensorManager != null && accelerometer != null && magnetometer != null && !isListening) {
            sensorManager.registerListener(this, accelerometer, SensorManager.SENSOR_DELAY_UI);
            sensorManager.registerListener(this, magnetometer, SensorManager.SENSOR_DELAY_UI);
            isListening = true;
            Log.i(TAG, "Orientation tracking started");
        }
    }
    
    public void stopListening() {
        if (sensorManager != null && isListening) {
            sensorManager.unregisterListener(this);
            isListening = false;
            Log.i(TAG, "Orientation tracking stopped");
        }
    }
    
    @Override
    public void onSensorChanged(SensorEvent event) {
        if (event.sensor.getType() == Sensor.TYPE_ACCELEROMETER) {
            System.arraycopy(event.values, 0, gravity, 0, 3);
        } else if (event.sensor.getType() == Sensor.TYPE_MAGNETIC_FIELD) {
            System.arraycopy(event.values, 0, geomagnetic, 0, 3);
        }
        
        if (gravity != null && geomagnetic != null && listener != null) {
            if (SensorManager.getRotationMatrix(rotationMatrix, null, gravity, geomagnetic)) {
                SensorManager.getOrientation(rotationMatrix, orientation);
                
                float roll = (float) Math.toDegrees(orientation[2]);  // Roll (rotation around camera axis)
                float pitch = (float) Math.toDegrees(orientation[1]); // Pitch (tilt up/down)
                float azimuth = (float) Math.toDegrees(orientation[0]); // Compass direction
                
                listener.onOrientationChanged(roll, pitch, azimuth);
            }
        }
    }
    
    @Override
    public void onAccuracyChanged(Sensor sensor, int accuracy) {
        // Not needed
    }
}
