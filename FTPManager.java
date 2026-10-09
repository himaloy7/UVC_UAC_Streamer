package com.serenegiant.usbcameratest.managers;

import android.os.AsyncTask;
import android.util.Log;

import org.apache.commons.net.ftp.FTP;
import org.apache.commons.net.ftp.FTPClient;
import org.apache.commons.net.ftp.FTPReply;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.util.List;
import java.lang.ref.WeakReference;
import java.util.Comparator;

public class FTPManager {
    private static final String TAG = "FTPManager";

    public interface FTPListener {
        void onUploadStarted(String fileName);
        void onUploadProgress(String fileName, int progress);
        void onUploadCompleted(String fileName);
        void onUploadFailed(String fileName, String error);
        void onAllUploadsCompleted();
    }

    private FTPClient ftpClient;
    private FTPListener listener;

    public FTPManager(FTPListener listener) {
        this.listener = listener;
        this.ftpClient = new FTPClient();
    }

// Replace the anonymous AsyncTask with this:

    private static class UploadTask extends AsyncTask<Void, Void, Boolean> {
        private WeakReference<FTPManager> ftpManagerRef;
        private String server;
        private int port;
        private String username;
        private String password;
        private String remoteDir;
        private List<String> localPaths;

        UploadTask(FTPManager ftpManager, String server, int port,
                   String username, String password, String remoteDir,
                   List<String> localPaths) {
            this.ftpManagerRef = new WeakReference<>(ftpManager);
            this.server = server;
            this.port = port;
            this.username = username;
            this.password = password;
            this.remoteDir = remoteDir;
            this.localPaths = localPaths;
        }

        @Override
        protected Boolean doInBackground(Void... params) {
            FTPManager ftpManager = ftpManagerRef.get();
            if (ftpManager == null) return false;

            try {
                ftpManager.ftpClient.connect(server, port);
                int reply = ftpManager.ftpClient.getReplyCode();
                if (!FTPReply.isPositiveCompletion(reply)) {
                    ftpManager.ftpClient.disconnect();
                    return false;
                }

                if (!ftpManager.ftpClient.login(username, password)) {
                    return false;
                }

                ftpManager.ftpClient.setFileType(FTP.BINARY_FILE_TYPE);
                ftpManager.ftpClient.enterLocalPassiveMode();

                if (!ftpManager.ftpClient.changeWorkingDirectory(remoteDir)) {
                    ftpManager.ftpClient.makeDirectory(remoteDir);
                    ftpManager.ftpClient.changeWorkingDirectory(remoteDir);
                }

                int total = localPaths.size();
                int completed = 0;

                for (String localPath : localPaths) {
                    File localFile = new File(localPath);
                    if (!localFile.exists()) {
                        if (ftpManager.listener != null) {
                            ftpManager.listener.onUploadFailed(localFile.getName(), "File not found");
                        }
                        continue;
                    }

                    if (ftpManager.listener != null) {
                        ftpManager.listener.onUploadStarted(localFile.getName());
                    }

                    try (FileInputStream fis = new FileInputStream(localFile)) {
                        boolean success = ftpManager.ftpClient.storeFile(localFile.getName(), fis);

                        if (success) {
                            completed++;
                            if (ftpManager.listener != null) {
                                ftpManager.listener.onUploadCompleted(localFile.getName());
                            }
                        } else {
                            if (ftpManager.listener != null) {
                                ftpManager.listener.onUploadFailed(localFile.getName(), "Upload failed");
                            }
                        }
                    } catch (IOException e) {
                        if (ftpManager.listener != null) {
                            ftpManager.listener.onUploadFailed(localFile.getName(), e.getMessage());
                        }
                    }
                }

                ftpManager.ftpClient.logout();
                ftpManager.ftpClient.disconnect();

                return completed == total;

            } catch (Exception e) {
                Log.e(TAG, "FTP error: " + e.getMessage());
                return false;
            }
        }

        @Override
        protected void onPostExecute(Boolean result) {
            FTPManager ftpManager = ftpManagerRef.get();
            if (ftpManager != null && ftpManager.listener != null) {
                ftpManager.listener.onAllUploadsCompleted();
            }
        }
    }

    // Then update uploadFiles method:
    public void uploadFiles(final String server, final int port,
                            final String username, final String password,
                            final String remoteDir, final List<String> localPaths) {
        new UploadTask(this, server, port, username, password, remoteDir, localPaths).execute();
    }

    public void disconnect() {
        if (ftpClient != null && ftpClient.isConnected()) {
            try {
                ftpClient.logout();
                ftpClient.disconnect();
            } catch (IOException e) {
                Log.e(TAG, "Error disconnecting: " + e.getMessage());
            }
        }
    }
}
