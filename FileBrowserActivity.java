package com.serenegiant.usbcameratest;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.provider.DocumentsContract;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import com.serenegiant.usbcameratest.managers.RecordingManager;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;

public class FileBrowserActivity extends AppCompatActivity {

    private static final int REQUEST_PICK_FILE = 1001;

    private TextView currentPathText;
    private ListView fileListView;
    private Button selectButton;
    private Button cancelButton;

    private File currentDir;
    private List<File> fileList = new ArrayList<>();
    private ArrayAdapter<String> adapter;
    private List<Boolean> selectedItems = new ArrayList<>();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_file_browser);

        currentPathText = findViewById(R.id.current_path);
        fileListView = findViewById(R.id.file_list);
        selectButton = findViewById(R.id.select_button);
        cancelButton = findViewById(R.id.cancel_button);

        // Start in the correct recordings directory
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            // Android 10+: Use Movies/USBCamTest
            File moviesDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES);
            currentDir = new File(moviesDir, "USBCamTest");
        } else {
            // Older Android: Use the app private directory
            currentDir = RecordingManager.getAppPrivateDir();
        }

        if (!currentDir.exists()) {
            currentDir.mkdirs();
        }

        adapter = new ArrayAdapter<>(this, android.R.layout.simple_list_item_multiple_choice, new ArrayList<>());
        fileListView.setAdapter(adapter);
        fileListView.setChoiceMode(ListView.CHOICE_MODE_MULTIPLE);

        loadFileList();

        fileListView.setOnItemClickListener((parent, view, position, id) -> {
            File file = fileList.get(position);
            if (file.isDirectory()) {
                currentDir = file;
                loadFileList();
            } else {
                // Toggle selection
                boolean newState = !selectedItems.get(position);
                selectedItems.set(position, newState);
                fileListView.setItemChecked(position, newState);
            }
        });

        selectButton.setOnClickListener(v -> {
            // Get selected files
            List<String> selectedPaths = new ArrayList<>();
            for (int i = 0; i < fileList.size(); i++) {
                if (fileListView.isItemChecked(i)) {
                    selectedPaths.add(fileList.get(i).getAbsolutePath());
                }
            }

            if (selectedPaths.isEmpty()) {
                Toast.makeText(this, "No files selected", Toast.LENGTH_SHORT).show();
                return;
            }

            // Return selected files to MainActivity
            Intent result = new Intent();
            result.putStringArrayListExtra("selected_files", (ArrayList<String>) selectedPaths);
            setResult(Activity.RESULT_OK, result);
            finish();
        });

        cancelButton.setOnClickListener(v -> finish());

        // Button to browse other folders
        findViewById(R.id.browse_other_button).setOnClickListener(v -> {
            Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
            intent.addCategory(Intent.CATEGORY_OPENABLE);
            intent.setType("*/*");
            startActivityForResult(intent, REQUEST_PICK_FILE);
        });
    }

    private void loadFileList() {
        fileList.clear();
        selectedItems.clear();

        File[] files = currentDir.listFiles();
        if (files != null) {
            Arrays.sort(files, new Comparator<File>() {
                @Override
                public int compare(File f1, File f2) {
                    return f1.getName().compareTo(f2.getName());
                }
            });
            fileList.addAll(Arrays.asList(files));
        }

        List<String> names = new ArrayList<>();
        for (File file : fileList) {
            if (file.isDirectory()) {
                names.add("📁 " + file.getName());
            } else {
                names.add("📄 " + file.getName() + " (" + formatFileSize(file.length()) + ")");
            }
            selectedItems.add(false);
        }

        adapter.clear();
        adapter.addAll(names);
        adapter.notifyDataSetChanged();

        currentPathText.setText("Path: " + currentDir.getAbsolutePath());
    }

    private String formatFileSize(long size) {
        if (size < 1024) return size + " B";
        if (size < 1024 * 1024) return String.format("%.1f KB", size / 1024.0);
        return String.format("%.1f MB", size / (1024.0 * 1024.0));
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQUEST_PICK_FILE && resultCode == Activity.RESULT_OK) {
            Uri uri = data.getData();
            if (uri != null) {
                // Convert URI to path if possible
                String path = getPathFromUri(uri);
                if (path != null) {
                    ArrayList<String> paths = new ArrayList<>();
                    paths.add(path);

                    Intent result = new Intent();
                    result.putStringArrayListExtra("selected_files", paths);
                    setResult(Activity.RESULT_OK, result);
                    finish();
                }
            }
        }
    }

    private String getPathFromUri(Uri uri) {
        if (DocumentsContract.isDocumentUri(this, uri)) {
            String docId = DocumentsContract.getDocumentId(uri);
            if ("com.android.externalstorage.documents".equals(uri.getAuthority())) {
                String[] split = docId.split(":");
                if (split.length >= 2) {
                    return "/storage/" + split[0] + "/" + split[1];
                }
            }
        }
        return null;
    }
}
