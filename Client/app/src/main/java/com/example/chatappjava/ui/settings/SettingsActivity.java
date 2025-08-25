package com.example.chatappjava.ui.settings;

import android.app.ProgressDialog;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.util.Log;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;
import android.widget.Toast;
import com.example.chatappjava.R;
import com.example.chatappjava.config.server_config;
import com.example.chatappjava.ui.auth.LoginActivity;
import com.example.chatappjava.utils.TokenManager;
import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.imageview.ShapeableImageView;
import com.google.android.material.textfield.TextInputEditText;
import okhttp3.*;

import org.json.JSONObject;

import java.io.File;
import java.io.IOException;

public class SettingsActivity extends AppCompatActivity {
    private static final String TAG = "SettingsActivity";
    private static final MediaType JPEG = MediaType.parse("image/jpeg");

    private ShapeableImageView imgAvatar;
    private TextInputEditText editNewPassword;
    private OkHttpClient client;
    private String token;
    private ProgressDialog progress;

    // Modern activity result launcher
    private final ActivityResultLauncher<String> pickImageLauncher = registerForActivityResult(
        new ActivityResultContracts.GetContent(),
        uri -> {
            if (uri != null) {
                Log.d(TAG, "Image selected: " + uri);
                imgAvatar.setImageURI(uri);
                uploadAvatar(uri);
            } else {
                Log.w(TAG, "No image selected");
                Toast.makeText(this, "No image selected", Toast.LENGTH_SHORT).show();
            }
        }
    );

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_settings);

        MaterialToolbar toolbar = findViewById(R.id.topAppBar);
        toolbar.setNavigationOnClickListener(v -> finish());

        imgAvatar = findViewById(R.id.imgAvatar);
        editNewPassword = findViewById(R.id.editNewPassword);
        MaterialButton btnChangePassword = findViewById(R.id.btnChangePassword);
        MaterialButton btnLogout = findViewById(R.id.btnLogout);
        findViewById(R.id.btnChangeAvatar).setOnClickListener(v -> pickAvatar());

        client = new OkHttpClient();
        token = TokenManager.get(this);

        btnChangePassword.setOnClickListener(v -> changePassword());
        btnLogout.setOnClickListener(v -> logout());

        progress = new ProgressDialog(this);
        progress.setCancelable(false);
    }

    private void logout() {
        TokenManager.clear(this);
        startActivity(new Intent(this, LoginActivity.class)
                .setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK));
        finish();
    }

    private void changePassword() {
        String newPass = editNewPassword.getText().toString().trim();
        if (newPass.isEmpty()) {
            Toast.makeText(this, "Enter new password", Toast.LENGTH_SHORT).show();
            return;
        }
        progress.setMessage("Updating password...");
        progress.show();

        RequestBody body = RequestBody.create(
                "{\"new_password\":\"" + newPass + "\"}",
                MediaType.parse("application/json")
        );

        Request request = new Request.Builder()
                .url(server_config.BASE_URL + "/change_password")
                .addHeader("Authorization", "Bearer " + token)
                .post(body)
                .build();

        client.newCall(request).enqueue(new Callback() {
            @Override public void onFailure(Call call, IOException e) {
                Log.e(TAG, "Password change failed", e);
                runOnUiThread(() -> {
                    progress.dismiss();
                    Toast.makeText(SettingsActivity.this, "Network error", Toast.LENGTH_SHORT).show();
                });
            }
            @Override public void onResponse(Call call, Response response) throws IOException {
                Log.d(TAG, "Password change response: " + response.code());
                runOnUiThread(() -> {
                    progress.dismiss();
                    if (response.isSuccessful()) {
                        Toast.makeText(SettingsActivity.this, "Password updated", Toast.LENGTH_SHORT).show();
                        editNewPassword.setText("");
                    } else {
                        Toast.makeText(SettingsActivity.this, "Failed to update password", Toast.LENGTH_SHORT).show();
                    }
                });
                response.close();
            }
        });
    }

    private void pickAvatar() {
        Log.d(TAG, "Opening image picker");
        pickImageLauncher.launch("image/*");
    }

    private void uploadAvatar(Uri uri) {
        if (token == null) {
            Log.e(TAG, "No token available for upload");
            Toast.makeText(this, "Authentication required", Toast.LENGTH_SHORT).show();
            return;
        }

        Log.d(TAG, "Starting avatar upload for URI: " + uri);
        progress.setMessage("Uploading avatar...");
        progress.show();

        try {
            // Create temporary file with proper extension
            String fileName = "avatar_" + System.currentTimeMillis() + ".jpg";
            File tmp = File.createTempFile("avatar", ".jpg", getCacheDir());
            Log.d(TAG, "Created temp file: " + tmp.getAbsolutePath());

            // Copy image data
            try (java.io.InputStream in = getContentResolver().openInputStream(uri);
                 java.io.FileOutputStream out = new java.io.FileOutputStream(tmp)) {
                byte[] buf = new byte[8192];
                int n;
                long totalBytes = 0;
                while ((n = in.read(buf)) > 0) {
                    out.write(buf, 0, n);
                    totalBytes += n;
                }
                Log.d(TAG, "Copied " + totalBytes + " bytes to temp file");
            }

            // Create multipart request
            RequestBody fileBody = RequestBody.create(tmp, JPEG);
            MultipartBody requestBody = new MultipartBody.Builder()
                    .setType(MultipartBody.FORM)
                    .addFormDataPart("file", fileName, fileBody)
                    .build();

            Request uploadReq = new Request.Builder()
                    .url(server_config.BASE_URL + "/uploadfile")
                    .addHeader("Authorization", "Bearer " + token)
                    .post(requestBody)
                    .build();

            Log.d(TAG, "Sending upload request to: " + uploadReq.url());

            client.newCall(uploadReq).enqueue(new Callback() {
                @Override public void onFailure(Call call, IOException e) {
                    Log.e(TAG, "Upload failed", e);
                    runOnUiThread(() -> {
                        progress.dismiss();
                        Toast.makeText(SettingsActivity.this, "Upload failed: " + e.getMessage(), Toast.LENGTH_LONG).show();
                    });
                }

                @Override public void onResponse(Call call, Response response) throws IOException {
                    String body = response.body() != null ? response.body().string() : null;
                    Log.d(TAG, "Upload response: " + response.code() + " - " + body);

                    try {
                        if (response.isSuccessful() && body != null) {
                            JSONObject obj = new JSONObject(body);
                            if (obj.has("file_url")) {
                                String url = obj.getString("file_url");
                                Log.d(TAG, "Upload successful, file URL: " + url);
                                updateAvatarUrl(url);
                            } else {
                                Log.e(TAG, "Upload response missing file_url: " + body);
                                runOnUiThread(() -> {
                                    progress.dismiss();
                                    Toast.makeText(SettingsActivity.this, "Upload response invalid", Toast.LENGTH_LONG).show();
                                });
                            }
                        } else {
                            Log.e(TAG, "Upload failed with code: " + response.code());
                            runOnUiThread(() -> {
                                progress.dismiss();
                                Toast.makeText(SettingsActivity.this, "Upload failed: " + response.code(), Toast.LENGTH_LONG).show();
                            });
                        }
                    } catch (Exception e) {
                        Log.e(TAG, "Error parsing upload response", e);
                        runOnUiThread(() -> {
                            progress.dismiss();
                            Toast.makeText(SettingsActivity.this, "Upload response error", Toast.LENGTH_LONG).show();
                        });
                    } finally {
                        response.close();
                    }
                }
            });

            // Clean up temp file after upload
            tmp.deleteOnExit();

        } catch (Exception e) {
            Log.e(TAG, "Error preparing upload", e);
            progress.dismiss();
            Toast.makeText(this, "File error: " + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    private void updateAvatarUrl(String url) {
        Log.d(TAG, "Updating avatar URL: " + url);

        // For Realtime Database, we'll store the custom URL scheme
        String avatarUrl;
        if (url.startsWith("realtime_db://")) {
            // Extract user ID from the URL
            // URL format: realtime_db://users/{uid}/avatar
            String[] parts = url.split("/");
            Log.d(TAG, "Parsing realtime_db URL: " + url + ", parts: " + java.util.Arrays.toString(parts));
            if (parts.length >= 4) {
                String uid = parts[3]; // uid is at index 3, not 2
                avatarUrl = server_config.BASE_URL + "/avatar/" + uid;
                Log.d(TAG, "Extracted UID: " + uid + ", generated avatar URL: " + avatarUrl);
            } else {
                Log.w(TAG, "Invalid realtime_db URL format: " + url + ", parts length: " + parts.length);
                avatarUrl = url;
            }
        } else {
            avatarUrl = url;
        }

        RequestBody body = RequestBody.create(
                "{\"avatar_url\":\"" + avatarUrl + "\"}",
                MediaType.parse("application/json")
        );

        Request req = new Request.Builder()
                .url(server_config.BASE_URL + "/update_user")
                .addHeader("Authorization", "Bearer " + token)
                .post(body)
                .build();

        client.newCall(req).enqueue(new Callback() {
            @Override public void onFailure(Call call, IOException e) {
                Log.e(TAG, "Avatar URL update failed", e);
                runOnUiThread(() -> {
                    progress.dismiss();
                    Toast.makeText(SettingsActivity.this, "Failed to update profile", Toast.LENGTH_SHORT).show();
                });
            }

            @Override public void onResponse(Call call, Response response) throws IOException {
                Log.d(TAG, "Avatar URL update response: " + response.code());
                runOnUiThread(() -> {
                    progress.dismiss();
                    if (response.isSuccessful()) {
                        Toast.makeText(SettingsActivity.this, "Avatar updated successfully!", Toast.LENGTH_SHORT).show();
                        // Load the avatar from the new URL
                        loadAvatarFromUrl(avatarUrl);
                    } else {
                        String errorBody = null;
                        try {
                            errorBody = response.body() != null ? response.body().string() : "Unknown error";
                        } catch (IOException e) {
                            throw new RuntimeException(e);
                        }
                        Log.e(TAG, "Avatar update failed: " + errorBody);
                        Toast.makeText(SettingsActivity.this, "Failed to update profile: " + errorBody, Toast.LENGTH_SHORT).show();
                    }
                });
                response.close();
            }
        });
    }

    private void loadAvatarFromUrl(String avatarUrl) {
        // Load avatar image from the server endpoint
        if (avatarUrl.startsWith("http")) {
            // Use a library like Glide or Picasso to load the image
            // For now, we'll just log the URL
            Log.d(TAG, "Avatar URL to load: " + avatarUrl);
            // TODO: Implement image loading with Glide or Picasso
        }
    }

    private void checkUserDataStatus() {
        // Check if user data is intact after avatar upload
        Request req = new Request.Builder()
                .url(server_config.BASE_URL + "/user_data_status")
                .addHeader("Authorization", "Bearer " + token)
                .get()
                .build();

        client.newCall(req).enqueue(new Callback() {
            @Override public void onFailure(Call call, IOException e) {
                Log.e(TAG, "Failed to check user data status", e);
            }

            @Override public void onResponse(Call call, Response response) throws IOException {
                if (response.isSuccessful()) {
                    try {
                        String responseBody = response.body().string();
                        Log.d(TAG, "User data status: " + responseBody);
                        // You can parse this JSON to check data integrity
                    } catch (Exception e) {
                        Log.e(TAG, "Error parsing user data status", e);
                    }
                }
                response.close();
            }
        });
    }

    private void restoreUserDataIfNeeded() {
        // Restore user data from Firestore if needed
        RequestBody body = RequestBody.create("{}", MediaType.parse("application/json"));
        Request req = new Request.Builder()
                .url(server_config.BASE_URL + "/restore_user_data")
                .addHeader("Authorization", "Bearer " + token)
                .post(body)
                .build();

        client.newCall(req).enqueue(new Callback() {
            @Override public void onFailure(Call call, IOException e) {
                Log.e(TAG, "Failed to restore user data", e);
            }

            @Override public void onResponse(Call call, Response response) throws IOException {
                if (response.isSuccessful()) {
                    Log.d(TAG, "User data restored successfully");
                    runOnUiThread(() -> {
                        Toast.makeText(SettingsActivity.this, "User data restored", Toast.LENGTH_SHORT).show();
                    });
                } else {
                    Log.e(TAG, "Failed to restore user data: " + response.code());
                }
                response.close();
            }
        });
    }
}
