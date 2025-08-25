package com.example.chatappjava.ui.auth;

import android.content.Intent;
import android.os.Bundle;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import com.example.chatappjava.R;
import com.example.chatappjava.ui.chat.PrivateChatActivity;
import com.example.chatappjava.utils.TokenManager;

import org.json.JSONObject;

import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Scanner;
import com.example.chatappjava.config.server_config;

public class LoginActivity extends AppCompatActivity {

    private EditText editEmail, editPassword;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_login);

        editEmail = findViewById(R.id.editEmail);
        editPassword = findViewById(R.id.editPassword);
        Button btnLogin = findViewById(R.id.btnLogin);
        TextView textSignUp = findViewById(R.id.textSignUp);

        btnLogin.setOnClickListener(v -> login());

        textSignUp.setOnClickListener(v -> {
            Intent intent = new Intent(LoginActivity.this,   RegisterActivity.class);
            startActivity(intent);
        });
    }

    private void login() {
        String email = editEmail.getText().toString();
        String password = editPassword.getText().toString();

        new Thread(() -> {
            HttpURLConnection conn = null;
            try {
                JSONObject json = new JSONObject();
                json.put("email", email);
                json.put("password", password);

                URL url = new URL(server_config.BASE_URL + "/login");
                conn = (HttpURLConnection) url.openConnection();
                conn.setRequestMethod("POST");
                conn.setRequestProperty("Content-Type", "application/json");
                conn.setDoOutput(true);

                OutputStream os = conn.getOutputStream();
                os.write(json.toString().getBytes("UTF-8"));
                os.flush();
                os.close();

                int responseCode = conn.getResponseCode();

                if (responseCode == HttpURLConnection.HTTP_OK) {
                    Scanner in = new Scanner(conn.getInputStream());
                    StringBuilder response = new StringBuilder();
                    while (in.hasNext()) response.append(in.nextLine());
                    in.close();

                    JSONObject resp = new JSONObject(response.toString());
                    String idToken = resp.getString("idToken");

                    TokenManager.save(this, idToken);

                    runOnUiThread(() -> {
                        Toast.makeText(this, "Login successful", Toast.LENGTH_SHORT).show();
                        // Small delay to avoid token-issued-in-future race on some devices
                        try { Thread.sleep(500); } catch (InterruptedException ignored) {}
                        startActivity(new Intent(this, com.example.chatappjava.ui.chat.ChatListActivity.class));
                        finish();
                    });
                } else {
                    Scanner err = new Scanner(conn.getErrorStream());
                    StringBuilder error = new StringBuilder();
                    while (err.hasNext()) error.append(err.nextLine());
                    err.close();

                    runOnUiThread(() -> Toast.makeText(this, "Login failed: " + responseCode, Toast.LENGTH_SHORT).show());
                }

            } catch (Exception e) {
                runOnUiThread(() -> Toast.makeText(this, "Error: " + e.getMessage(), Toast.LENGTH_LONG).show());
            } finally {
                if (conn != null) conn.disconnect();
            }
        }).start();
    }


}
