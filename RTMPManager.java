package com.serenegiant.usbcameratest.managers;

import android.app.Activity;
import android.media.MediaCodec;
import android.media.MediaFormat;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.widget.Toast;

import androidx.annotation.NonNull;

import com.pedro.rtmp.rtmp.RtmpClient;
import com.pedro.rtmp.utils.ConnectCheckerRtmp;

import java.nio.ByteBuffer;
import java.util.LinkedList;
import java.util.Queue;

public class RTMPManager implements ConnectCheckerRtmp {

    private static final String TAG = "RTMPManager";

    private final Activity activity;
    private RtmpClient rtmpClient;
    private final RTMPListener listener;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    private boolean isConnected = false;
    private String currentUrl;

    // ✅ Track if formats are ready
    private boolean videoConfigSent = false;
    private boolean audioConfigSent = false;

    // ✅ Buffer for frames that arrive before config
    private Queue<VideoFrame> videoFrameBuffer = new LinkedList<>();
    private Queue<AudioFrame> audioFrameBuffer = new LinkedList<>();
    private static final int MAX_BUFFER_SIZE = 100;

    // ✅ Store cached configs
    private ByteBuffer cachedSps = null;
    private ByteBuffer cachedPps = null;
    private int cachedAudioSampleRate = 48000;
    private boolean cachedAudioStereo = true;

    public interface RTMPListener {
        void onRtmpStatusChanged(String status);
    }

    public RTMPManager(@NonNull Activity activity, RTMPListener listener) {
        this.activity = activity;
        this.listener = listener;
    }

    // ========================= Video Configuration =========================

    public void setVideoInfo(ByteBuffer spsCopy, ByteBuffer ppsCopy, MediaFormat format) {
        // Cache the SPS/PPS regardless of connection state
        this.cachedSps = spsCopy;
        this.cachedPps = ppsCopy;

        if (rtmpClient != null && isConnected) {
            rtmpClient.setVideoInfo(spsCopy, ppsCopy, null);
            videoConfigSent = true;
            Log.i(TAG, "✅ SPS/PPS sent to RtmpClient");
            flushVideoBuffer();
        } else {
            Log.w(TAG, "Cannot set SPS/PPS - RTMP client not ready, will send when connected");
        }
    }

    public void setVideoInfo(ByteBuffer spsCopy, ByteBuffer ppsCopy) {
        setVideoInfo(spsCopy, ppsCopy, null);
    }

    // ✅ Add this method
    public void setCachedVideoConfig(ByteBuffer sps, ByteBuffer pps) {
        this.cachedSps = sps;
        this.cachedPps = pps;

        if (isConnected && !videoConfigSent && rtmpClient != null) {
            sendCachedVideoConfig();
        }
    }

    // ✅ Add this method
    private void sendCachedVideoConfig() {
        if (cachedSps != null && cachedPps != null && rtmpClient != null && isConnected) {
            rtmpClient.setVideoInfo(cachedSps, cachedPps, null);
            videoConfigSent = true;
            Log.i(TAG, "✅ SPS/PPS sent to RtmpClient (from cache)");
            flushVideoBuffer();
        }
    }

    // ========================= Audio Configuration =========================

    public void setAudioInfo(int sampleRate, boolean isStereo) {
        this.cachedAudioSampleRate = sampleRate;
        this.cachedAudioStereo = isStereo;

        if (rtmpClient != null && isConnected) {
            rtmpClient.setAudioInfo(sampleRate, isStereo);
            audioConfigSent = true;
            Log.i(TAG, "✅ Audio config sent to RtmpClient: " + sampleRate + "Hz, " + (isStereo ? "Stereo" : "Mono"));
            flushAudioBuffer();
        } else {
            Log.w(TAG, "Cannot set audio info - RTMP client not ready");
        }
    }

    public void setAudioInfo(MediaFormat format) {
        if (format != null) {
            int sampleRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE);
            int channelCount = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT);
            setAudioInfo(sampleRate, channelCount == 2);
        }
    }

    // ✅ Add this method
    public void setCachedAudioConfig(int sampleRate, boolean isStereo) {
        this.cachedAudioSampleRate = sampleRate;
        this.cachedAudioStereo = isStereo;

        if (isConnected && !audioConfigSent && rtmpClient != null) {
            sendCachedAudioConfig();
        }
    }

    // ✅ Add this method
    private void sendCachedAudioConfig() {
        if (cachedAudioSampleRate > 0 && rtmpClient != null && isConnected && !audioConfigSent) {
            // ✅ Set audio info
            rtmpClient.setAudioInfo(cachedAudioSampleRate, cachedAudioStereo);
            audioConfigSent = true;
            Log.i(TAG, "✅ Audio config sent after connection: " + cachedAudioSampleRate + "Hz, " +
                    (cachedAudioStereo ? "Stereo" : "Mono"));

            // Flush buffered audio frames
            flushAudioBuffer();
        } else {
            Log.d(TAG, "sendCachedAudioConfig skipped: rate=" + cachedAudioSampleRate +
                    ", connected=" + isConnected + ", sent=" + audioConfigSent);
        }
    }

    // ========================= Connection Control =========================

    public void connect(String url) {
        if (rtmpClient == null) {
            rtmpClient = new RtmpClient(this);
        }

        if (isConnected) {
            Log.w(TAG, "Already connected to " + currentUrl);
            return;
        }

        // ✅ CRITICAL: Clear buffers FIRST
        videoFrameBuffer.clear();
        audioFrameBuffer.clear();

        // Reset flags
        videoConfigSent = false;
        audioConfigSent = false;

        // ✅ CRITICAL: Set audio info BEFORE connecting if we have cached config
        if (cachedAudioSampleRate > 0 && rtmpClient != null) {
            rtmpClient.setAudioInfo(cachedAudioSampleRate, cachedAudioStereo);
            audioConfigSent = true;
            Log.i(TAG, "✅ Audio config set BEFORE connection: " + cachedAudioSampleRate + "Hz, " +
                    (cachedAudioStereo ? "Stereo" : "Mono"));
        }

        // ✅ Also set video config if available (optional)
        if (cachedSps != null && cachedPps != null && rtmpClient != null) {
            rtmpClient.setVideoInfo(cachedSps, cachedPps, null);
            videoConfigSent = true;
            Log.i(TAG, "✅ Video config set BEFORE connection");
        }

        currentUrl = url;
        Log.i(TAG, "Connecting to: " + url);
        rtmpClient.connect(url);

        Log.i(TAG, "=== Setting audio config BEFORE connect: " + cachedAudioSampleRate + "Hz ===");
    }

    public void disconnect() {
        if (rtmpClient != null && isConnected) {
            rtmpClient.disconnect();
            isConnected = false;
            videoConfigSent = false;
            audioConfigSent = false;
            videoFrameBuffer.clear();
            audioFrameBuffer.clear();
            Log.i(TAG, "Disconnected from RTMP");
        }
    }

    public boolean isConnected() {
        return isConnected;
    }

    // ========================= Sending Methods with Buffering =========================

    public void sendVideo(ByteBuffer buffer, MediaCodec.BufferInfo info) {
        if (rtmpClient == null || !isConnected) {
            return;
        }

        if (!videoConfigSent) {
            synchronized (videoFrameBuffer) {
                if (videoFrameBuffer.size() < MAX_BUFFER_SIZE) {
                    videoFrameBuffer.offer(new VideoFrame(buffer, info));
                    if (videoFrameBuffer.size() % 30 == 0) {
                        Log.d(TAG, "Buffering video frames: " + videoFrameBuffer.size());
                    }
                } else {
                    Log.w(TAG, "Video buffer full, dropping frame");
                }
            }
            return;
        }

        rtmpClient.sendVideo(buffer, info);
        if (info.size > 0) {
            Log.v(TAG, "Sent video packet, size: " + info.size);
        }
    }

    public void sendAudio(ByteBuffer buffer, MediaCodec.BufferInfo info) {
        // ✅ ADD THIS LINE RIGHT HERE (before any other code)
        //Log.i(TAG, "📢 sendAudio called: size=" + info.size + ", flags=" + info.flags + ", connected=" + isConnected + ", audioConfigSent=" + audioConfigSent);
        // Skip codec config frames
        if ((info.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) {
            Log.v(TAG, "Skipping audio config frame");
            return;
        }

        // ✅ If not connected, buffer but don't send
        if (!isConnected) {
            synchronized (audioFrameBuffer) {
                if (audioFrameBuffer.size() < MAX_BUFFER_SIZE) {
                    audioFrameBuffer.offer(new AudioFrame(buffer, info));
                    //Log.d(TAG, "Buffering audio frame (not connected), buffer size: " + audioFrameBuffer.size());
                } else {
                    //Log.w(TAG, "Audio buffer full, dropping frame");
                }
            }
            return;
        }

        // ✅ Now we're connected, ensure audio config is set
        if (rtmpClient == null) {
            Log.w(TAG, "sendAudio: rtmpClient is null");
            return;
        }

        // ✅ If audio config not sent yet, set it NOW before sending any frames
        if (!audioConfigSent && cachedAudioSampleRate > 0) {
            Log.i(TAG, "⚠️ Audio config not sent yet! Setting now before processing audio frame");
            rtmpClient.setAudioInfo(cachedAudioSampleRate, cachedAudioStereo);
            audioConfigSent = true;
            Log.i(TAG, "✅ Audio config set on-demand: " + cachedAudioSampleRate + "Hz");

            // Flush any buffered audio frames after setting config
            flushAudioBuffer();
        }

        // ✅ Log first few bytes to verify format
        if (info.size > 0 && !audioConfigSent) {
            byte[] firstBytes = new byte[Math.min(4, info.size)];
            buffer.position(info.offset);
            buffer.get(firstBytes);
            buffer.position(info.offset);

            // ADTS header starts with 0xFF, 0xF1 (or 0xFF, 0xF9)
            boolean isADTS = (firstBytes[0] == (byte)0xFF && (firstBytes[1] & 0xF0) == 0xF0);
            Log.i(TAG, "🎵 Audio packet format: " + (isADTS ? "ADTS" : "Raw AAC") +
                    ", first bytes: " + bytesToHex(firstBytes));
        }

        // Now send the frame
        rtmpClient.sendAudio(buffer, info);
        //Log.i(TAG, "=== Sending audio frame, config sent=" + audioConfigSent + ", connected=" + isConnected + " ===");
        Log.v(TAG, "🎵 Sent audio packet, size: " + info.size);
    }

    private void flushVideoBuffer() {
        synchronized (videoFrameBuffer) {
            Log.i(TAG, "Flushing " + videoFrameBuffer.size() + " buffered video frames");
            VideoFrame frame;
            int count = 0;
            while ((frame = videoFrameBuffer.poll()) != null) {
                rtmpClient.sendVideo(frame.buffer, frame.info);
                count++;
            }
            Log.i(TAG, "Flushed " + count + " video frames");
        }
    }

    private void flushAudioBuffer() {
        synchronized (audioFrameBuffer) {
            Log.i(TAG, "Flushing " + audioFrameBuffer.size() + " buffered audio frames");
            AudioFrame frame;
            int count = 0;
            while ((frame = audioFrameBuffer.poll()) != null) {
                // ✅ Skip codec config frames
                if ((frame.info.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) {
                    Log.v(TAG, "Skipping audio config frame");
                    continue;
                }

                // Log first few bytes to check format
                if (count < 3) {
                    frame.buffer.position(0);
                    byte[] firstBytes = new byte[Math.min(4, frame.buffer.remaining())];
                    frame.buffer.get(firstBytes);
                    frame.buffer.position(0);
                    Log.i(TAG, "First audio bytes: " + bytesToHex(firstBytes));
                }

                rtmpClient.sendAudio(frame.buffer, frame.info);
                Log.i(TAG, "🎵 Sent audio packet from buffer, size: " + frame.info.size);
                count++;
            }
            Log.i(TAG, "Flushed " + count + " audio frames");
        }
    }

    private String bytesToHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder();
        for (byte b : bytes) {
            sb.append(String.format("%02X ", b));
        }
        return sb.toString();
    }

    // ========================= RTMP Callbacks =========================

    @Override
    public void onConnectionStartedRtmp(String rtmpUrl) {
        Log.i(TAG, "RTMP connection started: " + rtmpUrl);
        notifyStatus("Connecting...");
    }

    @Override
    public void onConnectionSuccessRtmp() {
        isConnected = true;
        Log.i(TAG, "✓ RTMP connected");
        toast("RTMP Connected");
        notifyStatus("Connected");

        // ✅ Send cached video config if available
        if (cachedSps != null && cachedPps != null && !videoConfigSent) {
            rtmpClient.setVideoInfo(cachedSps, cachedPps, null);
            videoConfigSent = true;
            Log.i(TAG, "✅ SPS/PPS sent after connection");
            flushVideoBuffer();
        }

        // ✅ Only send configs if they weren't already sent before connection
        if (!videoConfigSent && cachedSps != null && cachedPps != null) {
            sendCachedVideoConfig();
        } else if (videoConfigSent) {
            Log.d(TAG, "Video config already sent, skipping");
        }

        if (!audioConfigSent && cachedAudioSampleRate > 0) {
            sendCachedAudioConfig();
        } else if (audioConfigSent) {
            Log.d(TAG, "Audio config already sent, skipping");
        }

        // ✅ Flush buffers if they have frames
        if (audioFrameBuffer.size() > 0 && audioConfigSent) {
            flushAudioBuffer();
        }
        if (videoFrameBuffer.size() > 0 && videoConfigSent) {
            flushVideoBuffer();
        }
    }

    @Override
    public void onConnectionFailedRtmp(@NonNull String reason) {
        isConnected = false;
        videoConfigSent = false;
        audioConfigSent = false;
        videoFrameBuffer.clear();
        audioFrameBuffer.clear();
        Log.e(TAG, "✗ RTMP failed: " + reason);
        toast("RTMP Failed: " + reason);
        notifyStatus("Failed");
    }

    @Override
    public void onDisconnectRtmp() {
        isConnected = false;
        videoConfigSent = false;
        audioConfigSent = false;
        videoFrameBuffer.clear();
        audioFrameBuffer.clear();
        Log.i(TAG, "RTMP disconnected");
        toast("RTMP Disconnected");
        notifyStatus("Disconnected");
    }

    @Override
    public void onAuthErrorRtmp() {
        Log.e(TAG, "RTMP auth error");
        toast("RTMP Auth Error");
        notifyStatus("Auth Error");
    }

    @Override
    public void onAuthSuccessRtmp() {
        Log.i(TAG, "RTMP auth success");
        toast("RTMP Auth Success");
        notifyStatus("Auth Success");
    }

    @Override
    public void onNewBitrateRtmp(long bitrate) {
        Log.d(TAG, "RTMP bitrate: " + bitrate);
    }

    // ========================= Helpers =========================

    private void toast(String msg) {
        mainHandler.post(() ->
                Toast.makeText(activity, msg, Toast.LENGTH_SHORT).show()
        );
    }

    private void notifyStatus(String status) {
        if (listener != null) {
            mainHandler.post(() -> listener.onRtmpStatusChanged(status));
        }
    }

    public void release() {
        disconnect();
        if (rtmpClient != null) {
            rtmpClient = null;
        }
    }

    // ========================= Frame Buffer Classes =========================

    private static class VideoFrame {
        ByteBuffer buffer;
        MediaCodec.BufferInfo info;

        VideoFrame(ByteBuffer buffer, MediaCodec.BufferInfo info) {
            this.buffer = ByteBuffer.allocate(info.size);
            int oldPosition = buffer.position();
            buffer.position(info.offset);
            buffer.limit(info.offset + info.size);
            this.buffer.put(buffer);
            this.buffer.flip();

            this.info = new MediaCodec.BufferInfo();
            this.info.set(0, info.size, info.presentationTimeUs, info.flags);

            buffer.position(oldPosition);
            buffer.limit(buffer.capacity());
        }
    }

    private static class AudioFrame {
        ByteBuffer buffer;
        MediaCodec.BufferInfo info;

        AudioFrame(ByteBuffer buffer, MediaCodec.BufferInfo info) {
            this.buffer = ByteBuffer.allocate(info.size);
            int oldPosition = buffer.position();
            buffer.position(info.offset);
            buffer.limit(info.offset + info.size);
            this.buffer.put(buffer);
            this.buffer.flip();

            this.info = new MediaCodec.BufferInfo();
            this.info.set(0, info.size, info.presentationTimeUs, info.flags);

            buffer.position(oldPosition);
            buffer.limit(buffer.capacity());
        }
    }
    public boolean isAudioConfigSet() {
        return audioConfigSent;
    }

    public int getAudioSampleRate() {
        return cachedAudioSampleRate;
    }
    // Add this method to RTMPManager
    public void preConfigureAudio(int sampleRate, boolean isStereo) {
        this.cachedAudioSampleRate = sampleRate;
        this.cachedAudioStereo = isStereo;

        // If RTMP client exists but not connected, set it now
        if (rtmpClient != null && !isConnected) {
            rtmpClient.setAudioInfo(sampleRate, isStereo);
            audioConfigSent = true;
            Log.i(TAG, "✅ Pre-configured RTMP client with audio: " + sampleRate + "Hz");
        }
    }
    public void flushAudioBufferForRecording() {
        synchronized (audioFrameBuffer) {
            Log.i(TAG, "Flushing " + audioFrameBuffer.size() + " buffered audio frames for recording");
            audioFrameBuffer.clear();
        }
    }
}
