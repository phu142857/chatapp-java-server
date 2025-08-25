package com.example.chatappjava.ui.friends;

import android.os.Bundle;
import android.view.View;
import android.widget.Toast;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import com.example.chatappjava.R;
import com.example.chatappjava.config.server_config;
import com.example.chatappjava.utils.TokenManager;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import okhttp3.*;

public class FriendRequestActivity extends AppCompatActivity {
    private RecyclerView recyclerView;
    private FriendRequestAdapter adapter;
    private List<FriendRequestItem> requestList = new ArrayList<>();
    // private static final String BASE_URL = "http://10.0.2.2:8000";
    private final OkHttpClient client = new OkHttpClient();

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_friend_request);
        
        // Set up toolbar navigation icon click listener
        androidx.appcompat.widget.Toolbar toolbar = findViewById(R.id.topAppBar);
        toolbar.setNavigationOnClickListener(v -> finish());
        
        recyclerView = findViewById(R.id.recyclerViewFriendRequests);
        recyclerView.setLayoutManager(new LinearLayoutManager(this));
        adapter = new FriendRequestAdapter(requestList, new FriendRequestAdapter.OnActionListener() {
            @Override
            public void onAccept(String requestId) {
                acceptRequest(requestId);
            }
            @Override
            public void onReject(String requestId) {
                rejectRequest(requestId);
            }
        });
        recyclerView.setAdapter(adapter);
        fetchRequests();
    }

    private void fetchRequests() {
        String token = TokenManager.get(this);
        if (token == null) {
            Toast.makeText(this, "Bạn chưa đăng nhập", Toast.LENGTH_SHORT).show();
            return;
        }
        Request request = new Request.Builder()
                .url(server_config.BASE_URL + "/friendrequest/incoming")
                .addHeader("Authorization", "Bearer " + token)
                .build();
        client.newCall(request).enqueue(new Callback() {
            @Override
            public void onFailure(Call call, IOException e) {
                runOnUiThread(() -> Toast.makeText(FriendRequestActivity.this, "Lỗi kết nối server", Toast.LENGTH_SHORT).show());
            }
            @Override
            public void onResponse(Call call, Response response) throws IOException {
                if (!response.isSuccessful()) {
                    runOnUiThread(() -> Toast.makeText(FriendRequestActivity.this, "Không lấy được danh sách lời mời", Toast.LENGTH_SHORT).show());
                    return;
                }
                try {
                    JSONObject obj = new JSONObject(response.body().string());
                    JSONArray arr = obj.getJSONArray("requests");
                    requestList.clear();
                    for (int i = 0; i < arr.length(); i++) {
                        JSONObject item = arr.getJSONObject(i);
                        requestList.add(new FriendRequestItem(
                                item.getString("id"),
                                item.getString("from_uid"),
                                item.optString("from_display_name", item.getString("from_uid"))
                        ));
                    }
                    runOnUiThread(() -> adapter.notifyDataSetChanged());
                } catch (Exception e) {
                    runOnUiThread(() -> Toast.makeText(FriendRequestActivity.this, "Lỗi xử lý dữ liệu", Toast.LENGTH_SHORT).show());
                }
            }
        });
    }

    private void acceptRequest(String requestId) {
        handleRequestAction(requestId, true);
    }
    private void rejectRequest(String requestId) {
        handleRequestAction(requestId, false);
    }
    private void handleRequestAction(String requestId, boolean accept) {
        String token = TokenManager.get(this);
        if (token == null) return;
        String url = server_config.BASE_URL + (accept ? "/friendrequest/accept" : "/friendrequest/reject");
        JSONObject json = new JSONObject();
        try { json.put("request_id", requestId); } catch (Exception ignored) {}
        RequestBody body = RequestBody.create(json.toString(), MediaType.parse("application/json"));
        Request request = new Request.Builder()
                .url(url)
                .addHeader("Authorization", "Bearer " + token)
                .post(body)
                .build();
        client.newCall(request).enqueue(new Callback() {
            @Override
            public void onFailure(Call call, IOException e) {
                runOnUiThread(() -> Toast.makeText(FriendRequestActivity.this, "Lỗi thao tác", Toast.LENGTH_SHORT).show());
            }
            @Override
            public void onResponse(Call call, Response response) throws IOException {
                if (response.isSuccessful()) {
                    runOnUiThread(() -> {
                        Toast.makeText(FriendRequestActivity.this, accept ? "Đã chấp nhận" : "Đã từ chối", Toast.LENGTH_SHORT).show();
                        fetchRequests();
                    });
                } else {
                    runOnUiThread(() -> Toast.makeText(FriendRequestActivity.this, "Thao tác thất bại", Toast.LENGTH_SHORT).show());
                }
            }
        });
    }

    // FriendRequestItem class
    public static class FriendRequestItem {
        public String id;
        public String fromUid;
        public String fromDisplayName;
        public FriendRequestItem(String id, String fromUid, String fromDisplayName) {
            this.id = id;
            this.fromUid = fromUid;
            this.fromDisplayName = fromDisplayName;
        }
    }
} 