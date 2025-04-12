package com.linxyi.lsrenamer;

import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
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
import java.io.OutputStream;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends AppCompatActivity {
    private ActivityResultLauncher<Intent> sourceDirLauncher;
    private ActivityResultLauncher<Intent> targetDirLauncher;
    private Uri sourceDirUri;
    private Uri targetDirUri;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private AlertDialog progressDialog;
    private static final String PREFS_NAME = "AppPrefs";
    private static final String KEY_SOURCE_URI = "source_uri";
    private static final String KEY_TARGET_URI = "target_uri";
    private static final int REQUEST_SOURCE_DIR = 1;
    private static final int REQU22EST_TARGET_DIR = 2;

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
                SharedPreferences.Editor editor = getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit();
                if (requestCode == REQUEST_SOURCE_DIR) {
                    sourceDirUri = uri;
                    editor.putString(KEY_SOURCE_URI, uri.toString());
                } else if (requestCode == REQUEST_TARGET_DIR) {
                    targetDirUri = uri;
                    editor.putString(KEY_TARGET_URI, uri.toString());
                }
                editor.apply();
            } catch (SecurityException e) {
                Toast.makeText(this, "权限获取失败：" + e.getMessage(), Toast.LENGTH_LONG).show();
            }
        }
        updateDirectoryDisplay();
    }

    private void loadSavedUris() {
        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        String source = prefs.getString(KEY_SOURCE_URI, null);
        String target = prefs.getString(KEY_TARGET_URI, null);

        if (source != null) {
            sourceDirUri = Uri.parse(source);
            // 保持持久化权限
            getContentResolver().takePersistableUriPermission(
                    sourceDirUri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION |
                            Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            );
            if (isInvalidUri(sourceDirUri)) {
                sourceDirUri = null;
            }
        }
        if (target != null) {
            targetDirUri = Uri.parse(target);
            getContentResolver().takePersistableUriPermission(
                    targetDirUri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION |
                            Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            );
            if (isInvalidUri(targetDirUri)) {
                targetDirUri = null;
            }
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
                                getPathFromUri(sourceDirUri).replace("/tree/primary:", "")) :
                        ""
        );
        ((TextView) findViewById(R.id.textView_target)).setText(
                targetDirUri != null ?
                        String.format(getString(R.string.text_target),
                                getPathFromUri(targetDirUri).replace("/tree/primary:", "")) :
                        ""
        );

    }

    private boolean isInvalidUri(Uri uri) {
        try {
            DocumentFile df = DocumentFile.fromTreeUri(this, uri);
            return df == null || !df.exists();
        } catch (Exception e) {
            return true;
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
        try {
            DocumentFile newFile = targetDir.createFile(getMimeType(newName), newName);
            if (newFile == null) return false;
            try (InputStream in = getContentResolver().openInputStream(source.getUri());
                 OutputStream out = getContentResolver().openOutputStream(newFile.getUri())) {
                byte[] buf = new byte[1024];
                int len;
                while ((len = Objects.requireNonNull(in).read(buf)) > 0) {
                    Objects.requireNonNull(out).write(buf, 0, len);
                }
            }
            source.delete();
            return true;
        } catch (Exception e) {
            Log.e("CopyFile", "Error copying file: " + source.getUri(), e);
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