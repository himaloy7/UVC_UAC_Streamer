package com.serenegiant.usbcameratest;

import static androidx.constraintlayout.helper.widget.MotionEffect.TAG;

import android.app.ProgressDialog;
import android.content.SharedPreferences;
import android.content.res.ColorStateList;
import android.os.Bundle;
import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import org.apache.commons.net.ftp.FTP;
import org.apache.commons.net.ftp.FTPClient;
import org.apache.commons.net.ftp.FTPReply;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.util.ArrayList;

public class FTPSettingsActivity extends AppCompatActivity {

    private EditText serverEdit, portEdit, usernameEdit, passwordEdit, remoteDirEdit;
    private Button saveButton, testButton, uploadButton; // Add upload button
    private ArrayList<String> selectedFiles;
    private ProgressDialog progressDialog;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_ftp_settings);

        // Get selected files from intent
        selectedFiles = getIntent().getStringArrayListExtra("selected_files");
        if (selectedFiles != null) {
            Log.d("FTPSettings", "Received " + selectedFiles.size() + " files for upload");
        }

        serverEdit = findViewById(R.id.ftp_server);
        portEdit = findViewById(R.id.ftp_port);
        usernameEdit = findViewById(R.id.ftp_username);
        passwordEdit = findViewById(R.id.ftp_password);
        remoteDirEdit = findViewById(R.id.ftp_remote_dir);
        saveButton = findViewById(R.id.save_button);
        testButton = findViewById(R.id.test_button);

        // USE THE XML BUTTON (ORANGE)
        uploadButton = findViewById(R.id.upload_button);

        loadSettings();

        saveButton.setOnClickListener(v -> saveSettings());
        testButton.setOnClickListener(v -> testConnection());

        uploadButton.setOnClickListener(v -> {
            if (selectedFiles == null || selectedFiles.isEmpty()) {
                Toast.makeText(this, "No files selected for upload", Toast.LENGTH_SHORT).show();
                return;
            }
            startUpload();
        });
    }

    private void startUpload() {
        String server = serverEdit.getText().toString().trim();
        String portStr = portEdit.getText().toString().trim();
        String username = usernameEdit.getText().toString().trim();
        String password = passwordEdit.getText().toString().trim();
        String remoteDir = remoteDirEdit.getText().toString().trim();

        if (server.isEmpty()) {
            Toast.makeText(this, "Please enter server address", Toast.LENGTH_SHORT).show();
            return;
        }

        int port = 21;
        try {
            port = Integer.parseInt(portStr);
        } catch (NumberFormatException e) {
            // Use default 21
        }

        if (selectedFiles == null || selectedFiles.isEmpty()) {
            Toast.makeText(this, "No files selected for upload", Toast.LENGTH_SHORT).show();
            return;
        }

        // Disable buttons during upload
        setButtonsEnabled(false);

        // Create and show progress dialog
        ProgressDialog progressDialog = new ProgressDialog(this);
        progressDialog.setMessage("Preparing upload...");
        progressDialog.setProgressStyle(ProgressDialog.STYLE_HORIZONTAL);
        progressDialog.setMax(selectedFiles.size());
        progressDialog.setCancelable(false);
        progressDialog.show();

        // Show initial toast
        Toast.makeText(this, "Starting upload of " + selectedFiles.size() + " files...", Toast.LENGTH_SHORT).show();

        // Run upload in background thread
        int finalPort = port;
        new Thread(() -> {
            boolean allSuccess = true;
            FTPClient ftpClient = new FTPClient();
            int successCount = 0;

            try {
                // Connect to server
                ftpClient.connect(server, finalPort);
                int reply = ftpClient.getReplyCode();

                if (!FTPReply.isPositiveCompletion(reply)) {
                    ftpClient.disconnect();
                    runOnUiThread(() -> {
                        progressDialog.dismiss();
                        Toast.makeText(this, "FTP server refused connection", Toast.LENGTH_LONG).show();
                        setButtonsEnabled(true);
                    });
                    return;
                }

                // Login
                if (!ftpClient.login(username, password)) {
                    runOnUiThread(() -> {
                        progressDialog.dismiss();
                        Toast.makeText(this, "FTP login failed", Toast.LENGTH_LONG).show();
                        setButtonsEnabled(true);
                    });
                    return;
                }

                // Set passive mode and binary transfer
                ftpClient.enterLocalPassiveMode();
                ftpClient.setFileType(FTP.BINARY_FILE_TYPE);
                ftpClient.setFileTransferMode(FTP.STREAM_TRANSFER_MODE);

                // Create remote directory if it doesn't exist
                if (remoteDir != null && !remoteDir.isEmpty()) {
                    String[] dirs = remoteDir.split("/");
                    String currentPath = "";
                    for (String dir : dirs) {
                        if (!dir.isEmpty()) {
                            currentPath += "/" + dir;
                            if (!ftpClient.changeWorkingDirectory(currentPath)) {
                                ftpClient.makeDirectory(currentPath);
                                ftpClient.changeWorkingDirectory(currentPath);
                            }
                        }
                    }
                }

                // Upload each file
                int currentFile = 0;
                for (String filePath : selectedFiles) {
                    currentFile++;
                    File file = new File(filePath);

                    // Update progress dialog on UI thread
                    final int progress = currentFile;
                    final String fileName = file.getName();
                    runOnUiThread(() -> {
                        progressDialog.setProgress(progress);
                        progressDialog.setMessage("Uploading: " + fileName + " (" + progress + "/" + selectedFiles.size() + ")");
                    });

                    if (!file.exists()) {
                        Log.e(TAG, "File not found: " + filePath);
                        allSuccess = false;
                        continue;
                    }

                    String remoteFileName = file.getName();

                    // Show progress in log
                    Log.i(TAG, "Uploading: " + remoteFileName + " (" + formatFileSize(file.length()) + ")");

                    try (FileInputStream inputStream = new FileInputStream(file)) {
                        boolean uploaded = ftpClient.storeFile(remoteFileName, inputStream);

                        if (uploaded) {
                            successCount++;
                            Log.i(TAG, "✓ Uploaded: " + remoteFileName);
                        } else {
                            allSuccess = false;
                            Log.e(TAG, "✗ Failed to upload: " + remoteFileName);
                        }
                    } catch (Exception e) {
                        allSuccess = false;
                        Log.e(TAG, "Error uploading " + filePath + ": " + e.getMessage());
                    }
                }

                // Logout and disconnect
                ftpClient.logout();
                ftpClient.disconnect();

                final int finalSuccessCount = successCount;
                final boolean finalAllSuccess = allSuccess;

                // Update UI on main thread
                runOnUiThread(() -> {
                    // Dismiss progress dialog
                    if (progressDialog != null && progressDialog.isShowing()) {
                        progressDialog.dismiss();
                    }

                    String message = finalSuccessCount + " of " + selectedFiles.size() + " files uploaded successfully";
                    if (finalAllSuccess) {
                        Toast.makeText(this, "✅ " + message, Toast.LENGTH_LONG).show();
                    } else {
                        Toast.makeText(this, "⚠️ " + message, Toast.LENGTH_LONG).show();
                    }
                    setButtonsEnabled(true);
                });

            } catch (Exception e) {
                Log.e(TAG, "FTP error: " + e.getMessage());
                runOnUiThread(() -> {
                    if (progressDialog != null && progressDialog.isShowing()) {
                        progressDialog.dismiss();
                    }
                    Toast.makeText(this, "FTP error: " + e.getMessage(), Toast.LENGTH_LONG).show();
                    setButtonsEnabled(true);
                });

                try {
                    if (ftpClient.isConnected()) {
                        ftpClient.disconnect();
                    }
                } catch (IOException ex) {
                    // Ignore
                }
            }
        }).start();
    }

    private void setButtonsEnabled(boolean enabled) {
        saveButton.setEnabled(enabled);
        testButton.setEnabled(enabled);
        uploadButton.setEnabled(enabled);
    }

    private String formatFileSize(long size) {
        if (size < 1024) return size + " B";
        if (size < 1024 * 1024) return String.format("%.1f KB", size / 1024.0);
        return String.format("%.1f MB", size / (1024.0 * 1024.0));
    }

    private void loadSettings() {
        SharedPreferences prefs = getSharedPreferences("ftp_settings", MODE_PRIVATE);
        serverEdit.setText(prefs.getString("server", ""));
        portEdit.setText(String.valueOf(prefs.getInt("port", 21)));
        usernameEdit.setText(prefs.getString("username", ""));
        passwordEdit.setText(prefs.getString("password", ""));
        remoteDirEdit.setText(prefs.getString("remote_dir", "/"));
    }

    private void saveSettings() {
        SharedPreferences prefs = getSharedPreferences("ftp_settings", MODE_PRIVATE);
        SharedPreferences.Editor editor = prefs.edit();
        editor.putString("server", serverEdit.getText().toString());
        editor.putInt("port", Integer.parseInt(portEdit.getText().toString()));
        editor.putString("username", usernameEdit.getText().toString());
        editor.putString("password", passwordEdit.getText().toString());
        editor.putString("remote_dir", remoteDirEdit.getText().toString());
        editor.apply();

        Toast.makeText(this, "Settings saved", Toast.LENGTH_SHORT).show();
        finish();
    }

    private void testConnection() {
        // Simple test - you can implement actual test
        Toast.makeText(this, "Test connection (not implemented)", Toast.LENGTH_SHORT).show();
    }

}
