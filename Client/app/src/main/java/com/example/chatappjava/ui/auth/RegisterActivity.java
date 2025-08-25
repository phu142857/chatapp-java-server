package com.example.chatappjava.ui.auth;

import android.content.Intent;
import android.os.Bundle;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import com.example.chatappjava.R;
import com.example.chatappjava.config.server_config;

import org.json.JSONObject;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Scanner;

public class RegisterActivity extends AppCompatActivity {

    private EditText editEmail, editPassword;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_register);

        editEmail = findViewById(R.id.editEmail);
        editPassword = findViewById(R.id.editPassword);
        Button btnRegister = findViewById(R.id.btnSignup);
        TextView textSignIn = findViewById(R.id.textSignIn);

        btnRegister.setOnClickListener(v -> register());

        textSignIn.setOnClickListener(v -> {
            Intent intent = new Intent(RegisterActivity.this, LoginActivity.class);
            startActivity(intent);
        });
    }

    private void register() {
        String email = editEmail.getText().toString().trim();
        String password = editPassword.getText().toString().trim();

        if (email.isEmpty() || password.isEmpty()) {
            runOnUiThread(() -> Toast.makeText(this, "Email and password are required", Toast.LENGTH_SHORT).show());
            return;
        }

        // Use email prefix as display name
        String displayName = email.contains("@") ? email.split("@")[0] : email;

        new Thread(() -> {
            HttpURLConnection conn = null;
            try {
                JSONObject json = new JSONObject();
                json.put("email", email);
                json.put("password", password);
                json.put("display_name", displayName);

                URL url = new URL(server_config.BASE_URL + "/authregister");
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

                    runOnUiThread(() -> {
                        Toast.makeText(this, "Register successful, please login", Toast.LENGTH_SHORT).show();
                        startActivity(new Intent(this, LoginActivity.class));
                        finish();
                    });
                } else {
                    InputStream errorStream = conn.getErrorStream();
                    if (errorStream != null) {
                        Scanner err = new Scanner(errorStream);
                        StringBuilder error = new StringBuilder();
                        while (err.hasNext()) error.append(err.nextLine());
                        err.close();
                        runOnUiThread(() -> Toast.makeText(this, "Register failed: " + error.toString(), Toast.LENGTH_LONG).show());
                    } else {
                        runOnUiThread(() -> Toast.makeText(this, "Register failed: Unknown error", Toast.LENGTH_LONG).show());
                    }
                }

            } catch (Exception e) {
                runOnUiThread(() -> Toast.makeText(this, "Error: " + e.getMessage(), Toast.LENGTH_LONG).show());
            } finally {
                if (conn != null) conn.disconnect();
            }
        }).start();
    }
}
