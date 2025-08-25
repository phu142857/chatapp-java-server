package com.example.chatappjava.ui.chat;

import android.os.Bundle;
import android.widget.Button;
import android.widget.EditText;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.example.chatappjava.R;
import com.example.chatappjava.ui.friends.FriendListActivity;
import com.example.chatappjava.ui.friends.FriendSelectAdapter;
import com.example.chatappjava.utils.TokenManager;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public class CreateGroupActivity extends AppCompatActivity {
    private EditText editGroupName;
    private RecyclerView recyclerViewFriends;
    private Button btnCreateGroup;
    private FriendSelectAdapter adapter;
    private List<FriendListActivity.FriendItem> friendList = new ArrayList<>();
    private Set<String> selectedUids = new HashSet<>();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_create_group);

        editGroupName = findViewById(R.id.editGroupName);
        recyclerViewFriends = findViewById(R.id.recyclerViewFriends);
        btnCreateGroup = findViewById(R.id.btnCreateGroup);

        recyclerViewFriends.setLayoutManager(new LinearLayoutManager(this));
        adapter = new FriendSelectAdapter(friendList, selectedUids);
        recyclerViewFriends.setAdapter(adapter);

        btnCreateGroup.setOnClickListener(v -> createGroup());

        fetchFriends();
    }

    private void fetchFriends() {
        String token = TokenManager.get(this);
        if (token == null) return;
        okhttp3.OkHttpClient client = new okhttp3.OkHttpClient();
        okhttp3.Request request = new okhttp3.Request.Builder()
                .url(com.example.chatappjava.config.server_config.BASE_URL + "/friends")
                .addHeader("Authorization", "Bearer " + token)
                .build();
        client.newCall(request).enqueue(new okhttp3.Callback() {
            @Override
            public void onFailure(okhttp3.Call call, IOException e) { }
            @Override
            public void onResponse(okhttp3.Call call, okhttp3.Response response) throws IOException {
                if (response.isSuccessful()) {
                    try {
                        org.json.JSONObject obj = new org.json.JSONObject(response.body().string());
                        org.json.JSONArray arr = obj.getJSONArray("friends");
                        friendList.clear();
                        for (int i = 0; i < arr.length(); i++) {
                            org.json.JSONObject item = arr.getJSONObject(i);
                            friendList.add(new FriendListActivity.FriendItem(
                                    item.getString("uid"),
                                    item.optString("display_name", ""),
                                    item.optString("email", "")
                            ));
                        }
                        runOnUiThread(() -> adapter.notifyDataSetChanged());
                    } catch (Exception ignored) {}
                }
            }
        });
    }

    private void createGroup() {
        String groupName = editGroupName.getText().toString().trim();
        if (groupName.isEmpty() || selectedUids.isEmpty()) {
            Toast.makeText(this, "Nhập tên nhóm và chọn thành viên", Toast.LENGTH_SHORT).show();
            return;
        }
        String token = TokenManager.get(this);
        if (token == null) return;
        org.json.JSONObject json = new org.json.JSONObject();
        try {
            json.put("group_name", groupName);
            org.json.JSONArray arr = new org.json.JSONArray();
            for (String uid : selectedUids) arr.put(uid);
            json.put("member_uids", arr);
        } catch (Exception e) {
            Toast.makeText(this, "Lỗi dữ liệu", Toast.LENGTH_SHORT).show();
            return;
        }
        okhttp3.RequestBody body = okhttp3.RequestBody.create(json.toString(), okhttp3.MediaType.parse("application/json"));
        okhttp3.OkHttpClient client = new okhttp3.OkHttpClient();
        okhttp3.Request request = new okhttp3.Request.Builder()
                .url(com.example.chatappjava.config.server_config.BASE_URL + "/groupscreate")
                .addHeader("Authorization", "Bearer " + token)
                .post(body)
                .build();
        client.newCall(request).enqueue(new okhttp3.Callback() {
            @Override
            public void onFailure(okhttp3.Call call, IOException e) {
                runOnUiThread(() -> Toast.makeText(CreateGroupActivity.this, "Lỗi tạo nhóm", Toast.LENGTH_SHORT).show());
            }
            @Override
            public void onResponse(okhttp3.Call call, okhttp3.Response response) throws IOException {
                if (response.isSuccessful()) {
                    runOnUiThread(() -> {
                        Toast.makeText(CreateGroupActivity.this, "Tạo nhóm thành công", Toast.LENGTH_SHORT).show();
                        finish();
                    });
                } else {
                    runOnUiThread(() -> Toast.makeText(CreateGroupActivity.this, "Không tạo được nhóm", Toast.LENGTH_SHORT).show());
                }
            }
        });
    }
} 