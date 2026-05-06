package com.linxyi.lsrenamer;

import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.ParcelFileDescriptor;
import android.provider.DocumentsContract;
import android.util.Log;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.EdgeToEdge;
import androidx.activity.result.ActivityResult;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.documentfile.provider.DocumentFile;
import androidx.exifinterface.media.ExifInterface;

import java.io.InputStream;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.nio.channels.FileChannel;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import org.json.JSONArray;

public class MainActivity extends AppCompatActivity {
    private ActivityResultLauncher<Intent> sourceDirLauncher;
    private ActivityResultLauncher<Intent> targetDirLauncher;
    private Uri sourceDirUri;
    private Uri targetDirUri;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private AlertDialog progressDialog;
    private static final String PREFS_NAME = "AppPrefs";
    private static final String KEY_SOURCE_URIS = "source_uris";
    private static final String KEY_TARGET_URIS = "target_uris";
    private static final int MAX_HISTORY = 5;
    private static final int REQUEST_SOURCE_DIR = 1;
    private static final int REQUEST_TARGET_DIR = 2;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        EdgeToEdge.enable(this);
        setContentView(R.layout.activity_main);
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main), (v, insets) -> {
            Insets systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom);
            return insets;
        });
        loadSavedUris();
        updateDirectoryDisplay();

        sourceDirLauncher = registerForActivityResult(
                new ActivityResultContracts.StartActivityForResult(),
                result -> handleDirectoryResult(result, REQUEST_SOURCE_DIR)
        );

        targetDirLauncher = registerForActivityResult(
                new ActivityResultContracts.StartActivityForResult(),
                result -> handleDirectoryResult(result, REQUEST_TARGET_DIR)
        );

        findViewById(R.id.button_source).setOnClickListener(v -> openDirectory(REQUEST_SOURCE_DIR));
        findViewById(R.id.button_target).setOnClickListener(v -> openDirectory(REQUEST_TARGET_DIR));
        findViewById(R.id.button_process).setOnClickListener(v -> startProcessing());

        findViewById(R.id.textView_source).setOnLongClickListener(v -> {
            showHistoryDialog(REQUEST_SOURCE_DIR);
            return true;
        });
        findViewById(R.id.textView_target).setOnLongClickListener(v -> {
            showHistoryDialog(REQUEST_TARGET_DIR);
            return true;
        });
    }

    private void handleDirectoryResult(ActivityResult result, int requestCode) {
        if (result.getResultCode() == RESULT_OK && result.getData() != null) {
            Intent data = result.getData();
            Uri uri = data.getData();
            try {
                getContentResolver().takePersistableUriPermission(
                        Objects.requireNonNull(uri),
                        (Intent.FLAG_GRANT_READ_URI_PERMISSION |
                                Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
                );
                addUriToHistory(requestCode, uri);
            } catch (SecurityException e) {
                Toast.makeText(this, "权限获取失败：" + e.getMessage(), Toast.LENGTH_LONG).show();
            }
        }
        updateDirectoryDisplay();
    }

    private void loadSavedUris() {
        List<String> sourceUris = loadUriHistory(KEY_SOURCE_URIS);
        List<String> targetUris = loadUriHistory(KEY_TARGET_URIS);

        if (!sourceUris.isEmpty()) {
            sourceDirUri = Uri.parse(sourceUris.get(0));
            getContentResolver().takePersistableUriPermission(
                    sourceDirUri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION |
                            Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            );
        }
        if (!targetUris.isEmpty()) {
            targetDirUri = Uri.parse(targetUris.get(0));
            getContentResolver().takePersistableUriPermission(
                    targetDirUri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION |
                            Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            );
        }
    }

    private void openDirectory(int requestCode) {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
        if (requestCode == REQUEST_SOURCE_DIR) {
            sourceDirLauncher.launch(intent);
        } else if (requestCode == REQUEST_TARGET_DIR) {
            targetDirLauncher.launch(intent);
        }
    }

    private void updateDirectoryDisplay() {
        ((TextView) findViewById(R.id.textView_source)).setText(
                sourceDirUri != null ?
                        String.format(getString(R.string.text_source),
                                getDisplayPath(sourceDirUri)) :
                        ""
        );
        ((TextView) findViewById(R.id.textView_target)).setText(
                targetDirUri != null ?
                        String.format(getString(R.string.text_target),
                                getDisplayPath(targetDirUri)) :
                        ""
        );

    }

    private List<String> loadUriHistory(String key) {
        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        List<String> uris = new ArrayList<>();
        String json = prefs.getString(key, null);
        if (json != null) {
            try {
                JSONArray arr = new JSONArray(json);
                for (int i = 0; i < arr.length(); i++) {
                    String uriStr = arr.getString(i);
                    try {
                        Uri uri = Uri.parse(uriStr);
                        if (isInvalidUri(uri)) {
                            uris.add(uriStr);
                        }
                    } catch (Exception ignored) {
                    }
                }
            } catch (Exception ignored) {
            }
        }
        if (uris.isEmpty()) {
            String oldKey = key.equals(KEY_SOURCE_URIS) ? "source_uri" : "target_uri";
            String oldUri = prefs.getString(oldKey, null);
            if (oldUri != null) {
                try {
                    Uri uri = Uri.parse(oldUri);
                    if (isInvalidUri(uri)) {
                        uris.add(oldUri);
                    }
                } catch (Exception ignored) {
                }
                prefs.edit().remove(oldKey).apply();
            }
        }
        return uris;
    }

    private void saveUriHistory(String key, List<String> uris) {
        JSONArray arr = new JSONArray();
        for (String uri : uris) {
            arr.put(uri);
        }
        getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
                .edit().putString(key, arr.toString()).apply();
    }

    private void addUriToHistory(int requestCode, Uri uri) {
        String key = requestCode == REQUEST_SOURCE_DIR ? KEY_SOURCE_URIS : KEY_TARGET_URIS;
        List<String> uris = loadUriHistory(key);
        uris.remove(uri.toString());
        uris.add(0, uri.toString());
        while (uris.size() > MAX_HISTORY) {
            uris.remove(uris.size() - 1);
        }
        saveUriHistory(key, uris);
        if (requestCode == REQUEST_SOURCE_DIR) {
            sourceDirUri = uri;
        } else {
            targetDirUri = uri;
        }
    }

    private String getDisplayPath(Uri uri) {
        String path = getPathFromUri(uri);
        if (path != null) {
            return path.replace("/tree/primary:", "").replace("/tree/", "");
        }
        return "";
    }

    private void showHistoryDialog(int requestCode) {
        String key = requestCode == REQUEST_SOURCE_DIR ? KEY_SOURCE_URIS : KEY_TARGET_URIS;
        List<String> uris = loadUriHistory(key);
        if (uris.isEmpty()) {
            Toast.makeText(this, getString(R.string.no_history), Toast.LENGTH_SHORT).show();
            return;
        }
        String[] displayPaths = new String[uris.size()];
        for (int i = 0; i < uris.size(); i++) {
            displayPaths[i] = getDisplayPath(Uri.parse(uris.get(i)));
        }
        new AlertDialog.Builder(this)
                .setTitle(requestCode == REQUEST_SOURCE_DIR ?
                        getString(R.string.dialog_source_title) :
                        getString(R.string.dialog_target_title))
                .setItems(displayPaths, (dialog, which) -> {
                    String selectedUri = uris.get(which);
                    try {
                        getContentResolver().takePersistableUriPermission(
                                Uri.parse(selectedUri),
                                Intent.FLAG_GRANT_READ_URI_PERMISSION |
                                        Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                        );
                    } catch (SecurityException ignored) {
                    }
                    addUriToHistory(requestCode, Uri.parse(selectedUri));
                    updateDirectoryDisplay();
                })
                .setNegativeButton(getString(android.R.string.cancel), null)
                .show();
    }

    private boolean isInvalidUri(Uri uri) {
        try {
            DocumentFile df = DocumentFile.fromTreeUri(this, uri);
            return df != null && df.exists();
        } catch (Exception e) {
            return false;
        }
    }


    private String getPathFromUri(Uri uri) {
        if (DocumentsContract.isDocumentUri(this, uri)) {
            return DocumentsContract.getDocumentId(uri).split(":")[1];
        }
        return uri.getPath();
    }

    private void startProcessing() {
        if (sourceDirUri == null || targetDirUri == null) {
            Toast.makeText(this, "请先选择源目录和目标目录", Toast.LENGTH_SHORT).show();
            return;
        }

        startFileProcessing();
    }

    private void startFileProcessing() {
        showProgressDialog();

        executor.execute(() -> {
            int processedCount = 0;
            try {
                DocumentFile sourceDir = DocumentFile.fromTreeUri(MainActivity.this, sourceDirUri);
                DocumentFile targetDir = DocumentFile.fromTreeUri(MainActivity.this, targetDirUri);

                if (sourceDir != null && targetDir != null) {
                    for (DocumentFile file : sourceDir.listFiles()) {
                        if (file.isFile()) {
                            String mimeType = file.getType();
                            String fileName = file.getName();
                            if (fileName == null) continue;

                            if (mimeType != null) {
                                if (mimeType.startsWith("image/") && fileName.toLowerCase().endsWith(".jpg")) {
                                    if (processImage(file, targetDir)) processedCount++;
                                } else if (mimeType.startsWith("video/") && fileName.toLowerCase().endsWith(".mp4")) {
                                    if (processVideo(file, targetDir)) processedCount++;
                                }
                            }
                        }
                    }
                }
            } catch (Exception e) {
                Log.e("FileProcessing", "Error processing files", e);
            } finally {
                int finalProcessedCount = processedCount;
                new Handler(Looper.getMainLooper()).post(() -> {
                    dismissProgressDialog();
                    showResultToast(finalProcessedCount);
                });
            }
        });
    }

    private void showProgressDialog() {
        runOnUiThread(() -> {
            progressDialog = new AlertDialog.Builder(MainActivity.this)
                    .setMessage(getString(R.string.processing))
                    .setCancelable(false)
                    .create();
            progressDialog.show();
        });
    }

    private void dismissProgressDialog() {
        if (progressDialog != null && progressDialog.isShowing()) {
            progressDialog.dismiss();
        }
    }

    private void showResultToast(int count) {
        Toast.makeText(MainActivity.this,
                String.format(getString(R.string.process_success), count),
                Toast.LENGTH_SHORT).show();
    }

    private boolean processImage(DocumentFile file, DocumentFile targetDir) {
        try (InputStream is = getContentResolver().openInputStream(file.getUri())) {
            ExifInterface exif = new ExifInterface(Objects.requireNonNull(is));
            String date = exif.getAttribute(ExifInterface.TAG_DATETIME_ORIGINAL);
            if (date == null) date = exif.getAttribute(ExifInterface.TAG_DATETIME);
            String formattedDate = formatDate(date);
            String model = exif.getAttribute(ExifInterface.TAG_MODEL);
            if (model == null || model.trim().isEmpty()) model = "unknown";
            model = model.replace(" ", "_");
            String originalName = getOriginalName(Objects.requireNonNull(file.getName()));
            String newFileName = "IMG_" + model + "_" + formattedDate + "_" + originalName + ".jpg";
            return copyFile(file, targetDir, newFileName);
        } catch (Exception e) {
            Log.e("ProcessImage", "Error processing image: " + file.getUri(), e);
            return false;
        }
    }

    private boolean processVideo(DocumentFile file, DocumentFile targetDir) {
        try {
            String formattedDate = new SimpleDateFormat("yyyyMMdd", Locale.getDefault()).format(new Date());
            String originalName = getOriginalName(Objects.requireNonNull(file.getName()));
            String newFileName = "VID_" + formattedDate + "_" + originalName + ".mp4";
            return copyFile(file, targetDir, newFileName);
        } catch (Exception e) {
            Log.e("ProcessVideo", "Error processing video: " + file.getUri(), e);
            return false;
        }
    }

    private String formatDate(String exifDate) {
        if (exifDate == null) {
            return new SimpleDateFormat("yyyyMMdd", Locale.getDefault()).format(new Date());
        }
        try {
            SimpleDateFormat exifFormat = new SimpleDateFormat("yyyy:MM:dd", Locale.getDefault());
            Date date = exifFormat.parse(exifDate.substring(0, 10));
            return new SimpleDateFormat("yyyyMMdd", Locale.getDefault()).format(Objects.requireNonNull(date));
        } catch (ParseException e) {
            return new SimpleDateFormat("yyyyMMdd", Locale.getDefault()).format(new Date());
        }
    }

    private String getOriginalName(String fileName) {
        int lastDot = fileName.lastIndexOf('.');
        return (lastDot > 0) ? fileName.substring(0, lastDot) : fileName;
    }

    private boolean copyFile(DocumentFile source, DocumentFile targetDir, String newName) {
        Uri srcUri = source.getUri();
        String originalName = source.getName();
        Uri currentSrcUri = srcUri;

        try {
            String srcDocId = DocumentsContract.getTreeDocumentId(sourceDirUri);
            Uri srcParentUri = DocumentsContract.buildDocumentUriUsingTree(sourceDirUri, srcDocId);
            String dstDocId = DocumentsContract.getTreeDocumentId(targetDirUri);
            Uri dstParentUri = DocumentsContract.buildDocumentUriUsingTree(targetDirUri, dstDocId);

            Uri renamedUri = null;
            try {
                renamedUri = DocumentsContract.renameDocument(
                        getContentResolver(), currentSrcUri, newName);
            } catch (Exception ignored) {
            }

            if (renamedUri != null) {
                currentSrcUri = renamedUri;
                try {
                    Uri movedUri = DocumentsContract.moveDocument(
                            getContentResolver(), renamedUri, srcParentUri, dstParentUri);
                    if (movedUri != null) {
                        return true;
                    }
                } catch (Exception ignored) {
                }
                try {
                    assert originalName != null;
                    DocumentsContract.renameDocument(
                            getContentResolver(), renamedUri, originalName);
                    currentSrcUri = srcUri;
                } catch (Exception ignored) {
                }
            }
        } catch (Exception ignored) {
        }

        try {
            DocumentFile newFile = targetDir.createFile(getMimeType(newName), newName);
            if (newFile == null) return false;

            try (ParcelFileDescriptor srcPfd = getContentResolver()
                    .openFileDescriptor(currentSrcUri, "r");
                 ParcelFileDescriptor dstPfd = getContentResolver()
                    .openFileDescriptor(newFile.getUri(), "rw")) {
                if (srcPfd == null || dstPfd == null) return false;

                try (FileInputStream fis = new FileInputStream(srcPfd.getFileDescriptor());
                     FileOutputStream fos = new FileOutputStream(dstPfd.getFileDescriptor())) {
                    FileChannel srcChannel = fis.getChannel();
                    FileChannel dstChannel = fos.getChannel();
                    long size = srcChannel.size();
                    long transferred = 0;
                    while (transferred < size) {
                        transferred += dstChannel.transferFrom(
                                srcChannel, transferred, size - transferred);
                    }
                }
            }

            DocumentsContract.deleteDocument(getContentResolver(), currentSrcUri);
            return true;
        } catch (Exception e) {
            Log.e("CopyFile", "Error copying file: " + currentSrcUri, e);
            return false;
        }
    }

    private String getMimeType(String fileName) {
        if (fileName.toLowerCase().endsWith(".jpg")) return "image/jpeg";
        if (fileName.toLowerCase().endsWith(".mp4")) return "video/mp4";
        return "application/octet-stream";
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        executor.shutdownNow();
        dismissProgressDialog();
    }
}
