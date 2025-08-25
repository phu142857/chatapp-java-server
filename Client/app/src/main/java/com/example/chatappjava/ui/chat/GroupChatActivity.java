package com.example.chatappjava.ui.chat;

import android.os.Bundle;
import android.widget.EditText;
import android.widget.Toast;
import android.widget.ImageButton;

import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.example.chatappjava.R;
import com.example.chatappjava.utils.TokenManager;

import java.util.ArrayList;
import java.util.List;

import okhttp3.OkHttpClient;
import okhttp3.Request;

import java.io.IOException;
import android.os.Handler;


public class GroupChatActivity extends AppCompatActivity {
    private String groupId, groupName;
    private RecyclerView recyclerView;
    private EditText editMessage;
    private ImageButton btnSend;
    private ImageButton btnVideoCall;
    private android.view.View callBanner;
    private com.google.android.material.button.MaterialButton btnJoinCall;
    private GroupMessageAdapter adapter;
    private List<GroupMessageItem> messageList = new ArrayList<>();
    private String myUid;
    private Handler handler = new Handler();
    private Runnable refreshMessages = new Runnable() {
        @Override
        public void run() {
            loadGroupMessages();
            handler.postDelayed(this, 2000); // 2 giây refresh 1 lần
        }
    };
    private boolean shouldScrollToBottom = true;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_group_chat);

        groupId = getIntent().getStringExtra("group_id");
        groupName = getIntent().getStringExtra("group_name");
        recyclerView = findViewById(R.id.recyclerViewGroupMessages);
        editMessage = findViewById(R.id.editGroupMessage);
        btnSend = findViewById(R.id.btnSendGroup);
        btnVideoCall = findViewById(R.id.btnVideoCall);

        // Set up toolbar navigation icon click listener
        androidx.appcompat.widget.Toolbar toolbar = findViewById(R.id.topAppBar);
        toolbar.setNavigationOnClickListener(v -> finish());
        toolbar.setTitle(groupName != null ? groupName : "Group Chat");
        recyclerView.setLayoutManager(new LinearLayoutManager(this));
        callBanner = findViewById(R.id.callBanner);
        btnJoinCall = findViewById(R.id.btnJoinCall);
        fetchMyUidAndInitAdapter();

        btnSend.setOnClickListener(v -> {
            shouldScrollToBottom = true;
            sendGroupMessage();
        });

        btnVideoCall.setOnClickListener(v -> startGroupVideoCall());
        btnJoinCall.setOnClickListener(v -> joinGroupVideoCall());

        loadGroupMessages();
    }

    @Override
    protected void onResume() {
        super.onResume();
        handler.post(refreshMessages);
        // Hide banner on resume by default; only show when caller starts a call
        if (callBanner != null) callBanner.setVisibility(android.view.View.GONE);
    }

    @Override
    protected void onPause() {
        super.onPause();
        handler.removeCallbacks(refreshMessages);
    }

    private void fetchMyUidAndInitAdapter() {
        String token = TokenManager.get(this);
        if (token == null) return;
        OkHttpClient client = new OkHttpClient();
        Request request = new Request.Builder()
                .url(com.example.chatappjava.config.server_config.BASE_URL + "/usersme")
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
                        myUid = obj.getString("uid");
                        runOnUiThread(() -> {
                            adapter = new GroupMessageAdapter(messageList, myUid);
                            recyclerView.setAdapter(adapter);
                            loadGroupMessages();
                        });
                    } catch (Exception ignored) {}
                }
            }
        });
    }

    private void loadGroupMessages() {
        String token = TokenManager.get(this);
        if (token == null) return;
        OkHttpClient client = new OkHttpClient();
        Request request = new Request.Builder()
                .url(com.example.chatappjava.config.server_config.BASE_URL + "/messagesgroup/" + groupId)
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
                        org.json.JSONArray arr = obj.getJSONArray("messages");
                        messageList.clear();
                        for (int i = 0; i < arr.length(); i++) {
                            org.json.JSONObject item = arr.getJSONObject(i);
                            String type = item.optString("message_type", "text");
                            String content = item.getString("message_content");
                            if ("call_signal".equals(type)) {
                                if ("call_started".equals(content)) {
                                    runOnUiThread(() -> { if (callBanner != null) callBanner.setVisibility(android.view.View.VISIBLE); });
                                } else if ("call_ended".equals(content)) {
                                    runOnUiThread(() -> { if (callBanner != null) callBanner.setVisibility(android.view.View.GONE); });
                                }
                                continue;
                            }
                            messageList.add(new GroupMessageItem(
                                    item.getString("sender_uid"),
                                    item.optString("sender_display_name", item.getString("sender_uid")),
                                    content,
                                    item.getString("timestamp")
                            ));
                        }
                        runOnUiThread(() -> {
                            adapter.notifyDataSetChanged();
                            if (shouldScrollToBottom) {
                                recyclerView.scrollToPosition(messageList.size() - 1);
                                shouldScrollToBottom = false;
                            }
                        });
                    } catch (Exception ignored) {}
                }
            }
        });
    }

    private void sendGroupMessage() {
        String content = editMessage.getText().toString().trim();
        if (content.isEmpty()) return;
        String token = TokenManager.get(this);
        if (token == null) return;
        org.json.JSONObject json = new org.json.JSONObject();
        try {
            json.put("group_id", groupId);
            json.put("message_content", content);
        } catch (Exception e) {
            Toast.makeText(this, "Lỗi dữ liệu", Toast.LENGTH_SHORT).show();
            return;
        }
        okhttp3.RequestBody body = okhttp3.RequestBody.create(json.toString(), okhttp3.MediaType.parse("application/json"));
        okhttp3.OkHttpClient client = new okhttp3.OkHttpClient();
        okhttp3.Request request = new okhttp3.Request.Builder()
                .url(com.example.chatappjava.config.server_config.BASE_URL + "/messagessendgroup")
                .addHeader("Authorization", "Bearer " + token)
                .post(body)
                .build();
        client.newCall(request).enqueue(new okhttp3.Callback() {
            @Override
            public void onFailure(okhttp3.Call call, IOException e) {
                runOnUiThread(() -> Toast.makeText(GroupChatActivity.this, "Lỗi gửi tin nhắn", Toast.LENGTH_SHORT).show());
            }
            @Override
            public void onResponse(okhttp3.Call call, okhttp3.Response response) throws IOException {
                if (response.isSuccessful()) {
                    runOnUiThread(() -> {
                        editMessage.setText("");
                        loadGroupMessages();
                    });
                } else {
                    runOnUiThread(() -> Toast.makeText(GroupChatActivity.this, "Không gửi được tin nhắn", Toast.LENGTH_SHORT).show());
                }
            }
        });
    }

    private void startGroupVideoCall() {
        if (groupId == null || groupId.isEmpty()) {
            Toast.makeText(this, "Group ID missing", Toast.LENGTH_SHORT).show();
            return;
        }
        sendGroupCallSignal("call_started");
        // Use groupId as CALL_ID (room) and myUid as USER_ID. For group call, IS_CALLER=true for the initiator.
        android.content.Intent intent = new android.content.Intent(this, com.example.chatappjava.ui.call.VideoCallActivity.class);
        intent.putExtra("USER_ID", myUid);
        intent.putExtra("CALL_ID", "group_" + groupId);
        intent.putExtra("IS_CALLER", true);
        intent.putExtra("GROUP_ID", groupId); // Pass group ID
        startActivity(intent);
        // Show banner so others can join
        if (callBanner != null) callBanner.setVisibility(android.view.View.VISIBLE);
    }

    private void joinGroupVideoCall() {
        if (groupId == null || groupId.isEmpty()) {
            Toast.makeText(this, "Group ID missing", Toast.LENGTH_SHORT).show();
            return;
        }
        android.content.Intent intent = new android.content.Intent(this, com.example.chatappjava.ui.call.VideoCallActivity.class);
        intent.putExtra("USER_ID", myUid);
        intent.putExtra("CALL_ID", "group_" + groupId);
        intent.putExtra("IS_CALLER", false);
        startActivity(intent);
    }

    private void sendGroupCallSignal(String signal) {
        String token = TokenManager.get(this);
        if (token == null || groupId == null) return;
        try {
            org.json.JSONObject json = new org.json.JSONObject();
            json.put("group_id", groupId);
            json.put("message_content", signal);
            json.put("message_type", "call_signal");
            okhttp3.RequestBody body = okhttp3.RequestBody.create(json.toString(), okhttp3.MediaType.parse("application/json"));
            okhttp3.Request req = new okhttp3.Request.Builder()
                    .url(com.example.chatappjava.config.server_config.BASE_URL + "/messagessendgroup")
                    .addHeader("Authorization", "Bearer " + token)
                    .post(body)
                    .build();
            new okhttp3.OkHttpClient().newCall(req).enqueue(new okhttp3.Callback() {
                @Override public void onFailure(okhttp3.Call call, IOException e) { }
                @Override public void onResponse(okhttp3.Call call, okhttp3.Response response) throws IOException { response.close(); }
            });
        } catch (Exception ignored) {}
    }

    public static class GroupMessageItem {
        public String senderUid;
        public String senderName;
        public String content;
        public String timestamp;

        public GroupMessageItem(String senderUid, String senderName, String content, String timestamp) {
            this.senderUid = senderUid;
            this.senderName = senderName;
            this.content = content;
            this.timestamp = timestamp;
        }
    }

}




