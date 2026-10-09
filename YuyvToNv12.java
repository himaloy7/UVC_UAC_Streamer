package com.serenegiant.usbcameratest.managers;

import java.nio.ByteBuffer;

public final class YuyvToNv12 {
    private static final String TAG = "YuyvToNv12";

    // Reusable buffer to avoid GC
    private static byte[] reusableBuffer = new byte[0];

    public static void convertWithScale(ByteBuffer yuyv, byte[] nv12,
                                        int srcWidth, int srcHeight,
                                        int dstWidth, int dstHeight) {
        yuyv.rewind();

        int yuyvSize = yuyv.remaining();

        // Reuse buffer to avoid allocation
        if (reusableBuffer.length < yuyvSize) {
            reusableBuffer = new byte[yuyvSize];
        }
        yuyv.get(reusableBuffer, 0, yuyvSize);

        if (srcWidth == dstWidth && srcHeight == dstHeight) {
            // Fast path - no scaling
            convertNoScale(reusableBuffer, nv12, srcWidth, srcHeight);
        } else {
            // Scaled path
            convertScaled(reusableBuffer, nv12, srcWidth, srcHeight, dstWidth, dstHeight);
        }
    }

    private static void convertNoScale(byte[] yuyv, byte[] nv12, int width, int height) {
        int frameSize = width * height;
        int yIndex = 0;
        int uvIndex = frameSize;
        int limit = frameSize * 2;

        // Extract Y plane
        for (int i = 0; i < limit; i += 4) {
            nv12[yIndex++] = yuyv[i];      // Y0
            nv12[yIndex++] = yuyv[i + 2];  // Y1
        }

        // Extract UV plane
        int rowStride = width * 2;
        for (int row = 0; row < height; row += 2) {
            int rowOffset = row * rowStride;
            int rowEnd = rowOffset + rowStride;
            for (int pos = rowOffset; pos < rowEnd; pos += 4) {
                nv12[uvIndex++] = yuyv[pos + 1]; // U
                nv12[uvIndex++] = yuyv[pos + 3]; // V
            }
        }
    }

    private static void convertScaled(byte[] yuyv, byte[] nv12,
                                      int srcWidth, int srcHeight,
                                      int dstWidth, int dstHeight) {
        int dstFrameSize = dstWidth * dstHeight;
        int yIndex = 0;
        int uvIndex = dstFrameSize;

        // Use integer math for speed (shift by 12 bits = multiply by 4096)
        int xRatio = (srcWidth << 12) / dstWidth;
        int yRatio = (srcHeight << 12) / dstHeight;

        // Scale Y plane
        for (int y = 0; y < dstHeight; y++) {
            int srcY = (y * yRatio) >> 12;
            int srcRowOffset = srcY * srcWidth * 2;

            for (int x = 0; x < dstWidth; x++) {
                int srcX = (x * xRatio) >> 12;
                int srcIndex = srcRowOffset + (srcX << 1);
                nv12[yIndex++] = yuyv[srcIndex]; // Y
            }
        }

        // Scale UV plane (half resolution)
        for (int y = 0; y < dstHeight; y += 2) {
            int srcY = (y * yRatio) >> 12;
            int srcRowOffset = srcY * srcWidth * 2;

            for (int x = 0; x < dstWidth; x += 2) {
                int srcX = (x * xRatio) >> 12;
                int srcIndex = srcRowOffset + (srcX << 1);
                nv12[uvIndex++] = yuyv[srcIndex + 1]; // U
                nv12[uvIndex++] = yuyv[srcIndex + 3]; // V
            }
        }
    }
}
