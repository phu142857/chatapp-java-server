package com.example.chatappjava.ui.auth;

import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import androidx.appcompat.app.AppCompatActivity;
import com.example.chatappjava.ui.chat.ChatListActivity;
import com.example.chatappjava.utils.TokenManager;

public class SplashActivity extends AppCompatActivity {

    private static final long SPLASH_DELAY = 2000; // 2 seconds

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // Optionally set splash layout here
        // setContentView(R.layout.activity_splash);

        new Handler().postDelayed(() -> {
            String token = TokenManager.get(this);
            Intent intent;
            if (token != null && !token.isEmpty()) {
                intent = new Intent(this, ChatListActivity.class);
            } else {
                intent = new Intent(this, LoginActivity.class);
            }
            startActivity(intent);
            finish();
        }, SPLASH_DELAY);
    }
}
