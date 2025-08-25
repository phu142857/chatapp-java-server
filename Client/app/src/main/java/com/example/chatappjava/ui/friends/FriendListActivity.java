package com.example.chatappjava.ui.friends;

import android.os.Bundle;
import androidx.appcompat.app.AppCompatActivity;
import android.view.View;
import android.widget.Toast;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import com.example.chatappjava.R;
import com.example.chatappjava.utils.TokenManager;
import com.example.chatappjava.config.server_config;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import okhttp3.*;

public class FriendListActivity extends AppCompatActivity {
    private RecyclerView recyclerView;
    private FriendListAdapter adapter;
    private List<FriendItem> friendList = new ArrayList<>();
    // private static final String BASE_URL = "http://10.0.2.2:8000";
    private final OkHttpClient client = new OkHttpClient();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_friend_list);
        recyclerView = findViewById(R.id.recyclerViewFriends);
        recyclerView.setLayoutManager(new LinearLayoutManager(this));
        adapter = new FriendListAdapter(friendList);
        recyclerView.setAdapter(adapter);
        fetchFriends();
    }

    private void fetchFriends() {
        String token = TokenManager.get(this);
        if (token == null) {
            Toast.makeText(this, "Bạn chưa đăng nhập", Toast.LENGTH_SHORT).show();
            return;
        }
        Request request = new Request.Builder()
                .url(server_config.BASE_URL + "/friends")
                .addHeader("Authorization", "Bearer " + token)
                .build();
        client.newCall(request).enqueue(new Callback() {
            @Override
            public void onFailure(Call call, IOException e) {
                runOnUiThread(() -> Toast.makeText(FriendListActivity.this, "Lỗi kết nối server", Toast.LENGTH_SHORT).show());
            }
            @Override
            public void onResponse(Call call, Response response) throws IOException {
                if (!response.isSuccessful()) {
                    runOnUiThread(() -> Toast.makeText(FriendListActivity.this, "Không lấy được danh sách bạn bè", Toast.LENGTH_SHORT).show());
                    return;
                }
                try {
                    JSONObject obj = new JSONObject(response.body().string());
                    JSONArray arr = obj.getJSONArray("friends");
                    friendList.clear();
                    for (int i = 0; i < arr.length(); i++) {
                        JSONObject item = arr.getJSONObject(i);
                        friendList.add(new FriendItem(
                                item.getString("uid"),
                                item.optString("display_name", ""),
                                item.optString("email", "")
                        ));
                    }
                    runOnUiThread(() -> adapter.notifyDataSetChanged());
                } catch (Exception e) {
                    runOnUiThread(() -> Toast.makeText(FriendListActivity.this, "Lỗi xử lý dữ liệu", Toast.LENGTH_SHORT).show());
                }
            }
        });
    }

    // FriendItem class
    public static class FriendItem {
        public String uid;
        public String displayName;
        public String email;
        public FriendItem(String uid, String displayName, String email) {
            this.uid = uid;
            this.displayName = displayName;
            this.email = email;
        }
    }
} 