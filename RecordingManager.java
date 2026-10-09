package com.serenegiant.usbcameratest.managers;

import android.media.MediaCodec;
import android.media.MediaFormat;
import android.media.MediaMuxer;
import android.os.Build;
import android.os.Environment;
import android.provider.MediaStore;
import android.content.ContentValues;
import android.content.Context;
import android.net.Uri;
import android.util.Log;

import java.io.File;
import java.io.FileDescriptor;
import java.io.FileInputStream;
import java.nio.ByteBuffer;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

public class RecordingManager {
    private static final String TAG = "RecordingManager";

    // ✅ Add encoder type enum
    public enum EncoderType {
        INTERNAL,  // InternalCameraManager - strict PTS, filters CODEC_CONFIG
        USB        // UsbH264Encoder - simple passthrough
    }

    private MediaMuxer mediaMuxer;
    private int videoTrackIndex = -1;
    private int audioTrackIndex = -1;
    private boolean isRecording = false;
    private String currentFilePath;
    private long startTime;
    private Context context;

    private MediaFormat videoFormat;
    private MediaFormat audioFormat;
    // Add this field with the others
    private volatile boolean isMuxerReady = false;
    private long lastVideoPts = -1;
    private long lastAudioPts = -1;

    // ✅ Track which encoder is using this
    private EncoderType currentEncoderType = EncoderType.INTERNAL;

    public interface RecordingListener {
        void onRecordingStarted(String filePath);
        void onRecordingStopped(String filePath);
        void onRecordingError(String error);
    }

    private RecordingListener listener;

    public RecordingManager(Context context, RecordingListener listener) {
        this.context = context;
        this.listener = listener;
    }

    // ✅ Add method to set encoder type before recording starts
    public void setEncoderType(EncoderType type) {
        this.currentEncoderType = type;
        Log.i(TAG, "Encoder type set to: " + type);
    }

    private String generateFilePath() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            return generateMediaStorePath();
        } else {
            return generateLegacyFilePath();
        }
    }

    private String generateLegacyFilePath() {
        File appDir = getAppPrivateDir();

        int retryCount = 0;
        while (!appDir.exists() && retryCount < 3) {
            boolean created = appDir.mkdirs();
            Log.i(TAG, "Created recording directory attempt " + (retryCount + 1) +
                    ": " + created + " at " + appDir.getAbsolutePath());
            retryCount++;
        }

        SimpleDateFormat sdf = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US);
        String timestamp = sdf.format(new Date());

        String resolution = "unknown";
        if (videoFormat != null) {
            int width = 0;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                width = videoFormat.getInteger(MediaFormat.KEY_WIDTH, 0);
            }
            int height = 0;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                height = videoFormat.getInteger(MediaFormat.KEY_HEIGHT, 0);
            }
            if (width > 0 && height > 0) {
                resolution = width + "x" + height;
            }
        }

        String fileName = "USBCamTest_" + timestamp + "_" + resolution + ".mp4";
        return new File(appDir, fileName).getAbsolutePath();
    }

    private String generateMediaStorePath() {
        SimpleDateFormat sdf = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US);
        String timestamp = sdf.format(new Date());

        String resolution = "unknown";
        if (videoFormat != null) {
            int width = 0;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                width = videoFormat.getInteger(MediaFormat.KEY_WIDTH, 0);
            }
            int height = 0;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                height = videoFormat.getInteger(MediaFormat.KEY_HEIGHT, 0);
            }
            if (width > 0 && height > 0) {
                resolution = width + "x" + height;
            }
        }

        String fileName = "USBCamTest_" + timestamp + "_" + resolution + ".mp4";

        ContentValues values = new ContentValues();
        values.put(MediaStore.Video.Media.DISPLAY_NAME, fileName);
        values.put(MediaStore.Video.Media.MIME_TYPE, "video/mp4");
        values.put(MediaStore.Video.Media.RELATIVE_PATH, Environment.DIRECTORY_MOVIES + "/USBCamTest");

        Uri uri = context.getContentResolver().insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values);

        if (uri != null) {
            return uri.toString();
        } else {
            return generateLegacyFilePath();
        }
    }

    public static File getAppPrivateDir() {
        File baseDir = new File(Environment.getExternalStorageDirectory(),
                "Android/data/com.serenegiant.usbcameratest/files");
        return new File(baseDir, "USBCamTest");
    }

    public void startRecording(MediaFormat videoFormat, MediaFormat audioFormat) {
        if (isRecording) {
            Log.w(TAG, "Already recording");
            return;
        }

        if (videoFormat == null || audioFormat == null) {
            Log.e(TAG, "Video or audio format is null");
            if (listener != null) {
                listener.onRecordingError("Invalid formats");
            }
            return;
        }

        // Log the actual video format details
        Log.i(TAG, "Video format details:");
        Log.i(TAG, "  MIME: " + videoFormat.getString(MediaFormat.KEY_MIME));
        Log.i(TAG, "  Size: " + videoFormat.getInteger(MediaFormat.KEY_WIDTH) + "x" +
                videoFormat.getInteger(MediaFormat.KEY_HEIGHT));
        Log.i(TAG, "  Bitrate: " + videoFormat.getInteger(MediaFormat.KEY_BIT_RATE));
        Log.i(TAG, "  Frame rate: " + videoFormat.getInteger(MediaFormat.KEY_FRAME_RATE));

        int profile = 0;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            profile = videoFormat.getInteger(MediaFormat.KEY_PROFILE, -1);
        }
        int level = 0;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            level = videoFormat.getInteger(MediaFormat.KEY_LEVEL, -1);
        }
        Log.i(TAG, "  Profile: " + profile + ", Level: " + level);

        ByteBuffer csd0 = videoFormat.getByteBuffer("csd-0");
        ByteBuffer csd1 = videoFormat.getByteBuffer("csd-1");
        Log.i(TAG, "  CSD0 size: " + (csd0 != null ? csd0.remaining() : 0));
        Log.i(TAG, "  CSD1 size: " + (csd1 != null ? csd1.remaining() : 0));

        this.videoFormat = videoFormat;
        this.audioFormat = audioFormat;
        lastVideoPts = -1;
        lastAudioPts = -1;

        String path = generateFilePath();

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && path.startsWith("content://")) {
                Uri uri = Uri.parse(path);
                mediaMuxer = new MediaMuxer(context.getContentResolver().openFileDescriptor(uri, "w").getFileDescriptor(),
                        MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
                currentFilePath = path;
            } else {
                mediaMuxer = new MediaMuxer(path, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
                currentFilePath = path;
            }

            // ✅ Force frame rate in video format for muxer
            // Don't check API level - KEY_FRAME_RATE exists on all API levels
            videoFormat.setInteger(MediaFormat.KEY_FRAME_RATE, StreamProfile.getFps());

            videoTrackIndex = mediaMuxer.addTrack(videoFormat);
            Log.i(TAG, "Added video track, index: " + videoTrackIndex);
            Log.i(TAG, "Video track format: " + videoFormat);

            if (audioFormat != null) {
                audioTrackIndex = mediaMuxer.addTrack(audioFormat);
                Log.i(TAG, "Added audio track, index: " + audioTrackIndex);
            } else {
                audioTrackIndex = -1;
                Log.w(TAG, "Audio format is null, recording video only");
            }

            Log.i(TAG, "=== Recording started ===");
            Log.i(TAG, "Video: " + videoFormat.getInteger(MediaFormat.KEY_WIDTH) + "x" +
                    videoFormat.getInteger(MediaFormat.KEY_HEIGHT));
            Log.i(TAG, "Audio: " + audioFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE) + "Hz, " +
                    audioFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT) + "ch, " +
                    audioFormat.getInteger(MediaFormat.KEY_BIT_RATE) + "bps");

            mediaMuxer.start();
            isMuxerReady = true;  // ← ADD THIS LINE
            isRecording = true;
            startTime = System.currentTimeMillis();

            Log.i(TAG, "Recording started: " + currentFilePath);
            if (listener != null) {
                listener.onRecordingStarted(currentFilePath);
            }

        } catch (Exception e) {
            Log.e(TAG, "Failed to start recording: " + e.getMessage(), e);
            if (listener != null) {
                listener.onRecordingError("Failed to create file: " + e.getMessage());
            }
            release();
        }
    }

    public void writeVideoFrame(ByteBuffer byteBuffer, MediaCodec.BufferInfo bufferInfo) {
        if (!isMuxerReady || mediaMuxer == null || videoTrackIndex < 0) {
            return;
        }

        try {
            // ✅ For USB encoder, write everything as-is (like old version)
            if (currentEncoderType == EncoderType.USB) {
                // Simple passthrough - no filtering
                byteBuffer.position(bufferInfo.offset);
                byteBuffer.limit(bufferInfo.offset + bufferInfo.size);
                mediaMuxer.writeSampleData(videoTrackIndex, byteBuffer, bufferInfo);
                return;
            }

            // ✅ For internal encoder, use strict mode
            // Skip codec config frames
            if ((bufferInfo.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) {
                return;
            }

            // Ensure correct buffer positioning
            byteBuffer.position(bufferInfo.offset);
            byteBuffer.limit(bufferInfo.offset + bufferInfo.size);

            // Enforce monotonic PTS
            if (bufferInfo.presentationTimeUs <= lastVideoPts) {
                bufferInfo.presentationTimeUs = lastVideoPts + 1;
            }
            lastVideoPts = bufferInfo.presentationTimeUs;

            mediaMuxer.writeSampleData(videoTrackIndex, byteBuffer, bufferInfo);

        } catch (Exception e) {
            Log.e(TAG, "Error writing video frame: " + e.getMessage(), e);
        }
    }

    public void writeAudioFrame(ByteBuffer byteBuffer, MediaCodec.BufferInfo bufferInfo) {
        if (!isMuxerReady || mediaMuxer == null || audioTrackIndex < 0) {
            return;
        }

        try {
            // ✅ For USB encoder, write everything as-is (like old version)
            if (currentEncoderType == EncoderType.USB) {
                // Simple passthrough - no filtering
                byteBuffer.position(bufferInfo.offset);
                byteBuffer.limit(bufferInfo.offset + bufferInfo.size);
                mediaMuxer.writeSampleData(audioTrackIndex, byteBuffer, bufferInfo);
                return;
            }

            // ✅ For internal encoder, use strict mode
            // Skip codec config frames
            if ((bufferInfo.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) {
                return;
            }

            // Ensure correct buffer positioning
            byteBuffer.position(bufferInfo.offset);
            byteBuffer.limit(bufferInfo.offset + bufferInfo.size);

            // Enforce monotonic PTS
            if (bufferInfo.presentationTimeUs <= lastAudioPts) {
                bufferInfo.presentationTimeUs = lastAudioPts + 1;
            }
            lastAudioPts = bufferInfo.presentationTimeUs;

            mediaMuxer.writeSampleData(audioTrackIndex, byteBuffer, bufferInfo);

        } catch (Exception e) {
            Log.e(TAG, "Error writing audio frame: " + e.getMessage());
        }
    }

    public void stopRecording() {
        if (!isRecording) {
            return;
        }

        isRecording = false;
        isMuxerReady = false;  // ← ADD THIS LINE

        try {
            if (mediaMuxer != null) {
                mediaMuxer.stop();
                mediaMuxer.release();
                mediaMuxer = null;
            }

            long duration = System.currentTimeMillis() - startTime;

            long fileSize = 0;
            if (currentFilePath != null && !currentFilePath.startsWith("content://")) {
                File file = new File(currentFilePath);
                fileSize = (file.exists()) ? file.length() : 0;
            } else if (currentFilePath != null && currentFilePath.startsWith("content://")) {
                try {
                    Uri uri = Uri.parse(currentFilePath);
                    FileDescriptor fd = context.getContentResolver().openFileDescriptor(uri, "r").getFileDescriptor();
                    FileInputStream fis = new FileInputStream(fd);
                    fileSize = fis.getChannel().size();
                    fis.close();
                } catch (Exception e) {
                    Log.w(TAG, "Cannot get content URI file size: " + e.getMessage());
                }
            }

            Log.i(TAG, "Recording stopped: " + currentFilePath);
            Log.i(TAG, "Duration: " + duration + "ms, Size: " + fileSize + " bytes");

            if (listener != null) {
                listener.onRecordingStopped(currentFilePath);
            }

        } catch (Exception e) {
            Log.e(TAG, "Error stopping recording: " + e.getMessage());
            if (listener != null) {
                listener.onRecordingError("Error stopping: " + e.getMessage());
            }
        }

        videoTrackIndex = -1;
        audioTrackIndex = -1;
        currentFilePath = null;
        videoFormat = null;
        audioFormat = null;

        // ✅ Reset to default encoder type
        currentEncoderType = EncoderType.INTERNAL;
    }

    private String getRealPathFromUri(String uriString) {
        if (uriString == null) return null;
        if (!uriString.startsWith("content://")) return uriString;

        try {
            Uri uri = Uri.parse(uriString);
            String[] projection = {MediaStore.Video.Media.DATA};
            android.database.Cursor cursor = context.getContentResolver().query(uri, projection, null, null, null);
            if (cursor != null) {
                int column_index = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DATA);
                cursor.moveToFirst();
                String path = cursor.getString(column_index);
                cursor.close();
                return path;
            }
        } catch (Exception e) {
            Log.e(TAG, "Error getting real path: " + e.getMessage());
        }
        return uriString;
    }

    public void release() {
        if (isRecording) {
            stopRecording();
        }
    }

    public boolean isRecording() {
        return isRecording;
    }

    public String getCurrentFilePath() {
        return currentFilePath;
    }
}
