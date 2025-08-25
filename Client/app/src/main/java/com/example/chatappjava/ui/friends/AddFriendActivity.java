package com.example.chatappjava.ui.friends;

import android.os.Bundle;
import androidx.appcompat.app.AppCompatActivity;

import android.text.TextUtils;
import android.widget.Button;
import android.widget.EditText;
import android.widget.Toast;
import com.example.chatappjava.utils.TokenManager;
import org.json.JSONObject;
import java.io.IOException;
import okhttp3.*;
import com.example.chatappjava.config.server_config;

import com.example.chatappjava.R;

public class AddFriendActivity extends AppCompatActivity {
    private EditText editEmailOrName;
    private Button btnSendRequest;
    // private static final String BASE_URL = "http://10.0.2.2:8000"; // Sửa lại nếu backend khác
    private final OkHttpClient client = new OkHttpClient();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_add_friend);
        editEmailOrName = findViewById(R.id.editEmail);
        btnSendRequest = findViewById(R.id.btnSendRequest);
        btnSendRequest.setOnClickListener(v -> sendFriendRequest());
    }

    private void sendFriendRequest() {
        String input = editEmailOrName.getText().toString().trim();
        if (TextUtils.isEmpty(input)) {
            Toast.makeText(this, "Vui lòng nhập email hoặc display name", Toast.LENGTH_SHORT).show();
            return;
        }
        // Gọi API tìm user
        findUserAndSendRequest(input);
    }

    private void findUserAndSendRequest(String input) {
        String token = TokenManager.get(this);
        if (token == null) {
            Toast.makeText(this, "Bạn chưa đăng nhập", Toast.LENGTH_SHORT).show();
            return;
        }
        // Ưu tiên tìm theo email
        HttpUrl url = HttpUrl.parse(server_config.BASE_URL + "/usersme"); // API lấy info user hiện tại
        if (input.contains("@")) {
            // Tìm theo email
            url = HttpUrl.parse(server_config.BASE_URL + "/finduserbyemail?email=" + input);
        } else {
            // Tìm theo display name
            url = HttpUrl.parse(server_config.BASE_URL + "/finduserbydisplayname?display_name=" + input);
        }
        Request request = new Request.Builder()
                .url(url)
                .addHeader("Authorization", "Bearer " + token)
                .build();
        client.newCall(request).enqueue(new Callback() {
            @Override
            public void onFailure(Call call, IOException e) {
                runOnUiThread(() -> Toast.makeText(AddFriendActivity.this, "Lỗi kết nối server", Toast.LENGTH_SHORT).show());
            }
            @Override
            public void onResponse(Call call, Response response) throws IOException {
                if (!response.isSuccessful()) {
                    runOnUiThread(() -> Toast.makeText(AddFriendActivity.this, "Không tìm thấy người dùng", Toast.LENGTH_SHORT).show());
                    return;
                }
                try {
                    JSONObject obj = new JSONObject(response.body().string());
                    String toUid = obj.getString("uid");
                    sendRequestToUid(toUid, token);
                } catch (Exception e) {
                    runOnUiThread(() -> Toast.makeText(AddFriendActivity.this, "Lỗi xử lý dữ liệu", Toast.LENGTH_SHORT).show());
                }
            }
        });
    }

    private void sendRequestToUid(String toUid, String token) {
        JSONObject json = new JSONObject();
        try {
            json.put("to_uid", toUid);
        } catch (Exception e) {
            runOnUiThread(() -> Toast.makeText(this, "Lỗi dữ liệu", Toast.LENGTH_SHORT).show());
            return;
        }
        RequestBody body = RequestBody.create(json.toString(), MediaType.parse("application/json"));
        Request request = new Request.Builder()
                .url(server_config.BASE_URL + "/friendrequest/send")
                .addHeader("Authorization", "Bearer " + token)
                .post(body)
                .build();
        client.newCall(request).enqueue(new Callback() {
            @Override
            public void onFailure(Call call, IOException e) {
                runOnUiThread(() -> Toast.makeText(AddFriendActivity.this, "Lỗi gửi lời mời", Toast.LENGTH_SHORT).show());
            }
            @Override
            public void onResponse(Call call, Response response) throws IOException {
                if (response.isSuccessful()) {
                    runOnUiThread(() -> Toast.makeText(AddFriendActivity.this, "Đã gửi lời mời kết bạn", Toast.LENGTH_SHORT).show());
                } else {
                    runOnUiThread(() -> Toast.makeText(AddFriendActivity.this, "Không thể gửi lời mời: " + response.message(), Toast.LENGTH_SHORT).show());
                }
            }
        });
    }
} 