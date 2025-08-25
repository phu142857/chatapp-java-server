package com.example.chatappjava.ui.chat;

import android.annotation.SuppressLint;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.example.chatappjava.R;
import com.example.chatappjava.ui.call.VideoCallActivity;
import com.example.chatappjava.utils.TokenManager;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

public class PrivateChatActivity extends AppCompatActivity {
    private String friendUid, friendName;
    private RecyclerView recyclerView;
    private EditText editMessage;
    private ImageButton btnSend;
    private MessageAdapter adapter;
    private List<MessageItem> messageList = new ArrayList<>();
    private static final String BASE_URL = com.example.chatappjava.config.server_config.BASE_URL;
    private final OkHttpClient client = new OkHttpClient();
    private String myUid;
    private Handler handler = new Handler();
    private Runnable refreshMessages = new Runnable() {
        @Override
        public void run() {
            loadMessages();
            handler.postDelayed(this, 2000); // 2 giây refresh 1 lần
        }
    };
    private boolean shouldScrollToBottom = true;
    private ImageButton btnVideoCall;

    @SuppressLint("MissingInflatedId")
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_private_chat);
        friendUid = getIntent().getStringExtra("friend_uid");
        friendName = getIntent().getStringExtra("friend_name");
        recyclerView = findViewById(R.id.recyclerViewMessages);
        editMessage = findViewById(R.id.editMessage);
        btnSend = findViewById(R.id.btnSend);

        // Set up toolbar navigation icon click listener and title
        androidx.appcompat.widget.Toolbar toolbar = findViewById(R.id.topAppBar);
        toolbar.setNavigationOnClickListener(v -> finish());
        toolbar.setTitle(friendName != null ? friendName : "Private Chat");

        recyclerView.setLayoutManager(new LinearLayoutManager(this));
        fetchMyUidAndInitAdapter();
        btnSend.setOnClickListener(v -> {
            shouldScrollToBottom = true;
            sendMessage();
        });
        btnVideoCall = findViewById(R.id.btnVideoCall);
        btnVideoCall.setOnClickListener(v -> {
            String callId = myUid != null && friendUid != null ?
                    (myUid.compareTo(friendUid) < 0 ? myUid + "_" + friendUid : friendUid + "_" + myUid) : "";
            Intent intent = new Intent(this, VideoCallActivity.class);
            intent.putExtra("USER_ID", myUid);
            intent.putExtra("CALL_ID", callId);
            intent.putExtra("IS_CALLER", true);
            startActivity(intent);
        });
        loadMessages();
    }

    @Override
    protected void onResume() {
        super.onResume();
        handler.post(refreshMessages);
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
                            adapter = new MessageAdapter(messageList, myUid);
                            recyclerView.setAdapter(adapter);
                            loadMessages();
                        });
                    } catch (Exception ignored) {}
                }
            }
        });
    }

    private void loadMessages() {
        String token = TokenManager.get(this);
        if (token == null) return;
        Request request = new Request.Builder()
                .url(BASE_URL + "/messagesprivate/" + friendUid)
                .addHeader("Authorization", "Bearer " + token)
                .build();
        client.newCall(request).enqueue(new Callback() {
            @Override
            public void onFailure(Call call, IOException e) {
                runOnUiThread(() -> Toast.makeText(PrivateChatActivity.this, "Lỗi tải tin nhắn", Toast.LENGTH_SHORT).show());
            }
            @Override
            public void onResponse(Call call, Response response) throws IOException {
                if (response.isSuccessful()) {
                    try {
                        JSONObject obj = new JSONObject(response.body().string());
                        JSONArray arr = obj.getJSONArray("messages");
                        messageList.clear();
                        for (int i = 0; i < arr.length(); i++) {
                            JSONObject item = arr.getJSONObject(i);
                            messageList.add(new MessageItem(
                                    item.getString("sender_uid"),
                                    item.getString("message_content"),
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
                    } catch (Exception e) {
                        runOnUiThread(() -> Toast.makeText(PrivateChatActivity.this, "Lỗi xử lý dữ liệu", Toast.LENGTH_SHORT).show());
                    }
                }
            }
        });
    }

    private void sendMessage() {
        String content = editMessage.getText().toString().trim();
        if (content.isEmpty()) return;
        String token = TokenManager.get(this);
        if (token == null) return;
        JSONObject json = new JSONObject();
        try {
            json.put("receiver_uid", friendUid);
            json.put("message_content", content);
        } catch (Exception e) {
            Toast.makeText(this, "Lỗi dữ liệu", Toast.LENGTH_SHORT).show();
            return;
        }
        RequestBody body = RequestBody.create(json.toString(), MediaType.parse("application/json"));
        Request request = new Request.Builder()
                .url(BASE_URL + "/messagessendprivate")
                .addHeader("Authorization", "Bearer " + token)
                .post(body)
                .build();
        client.newCall(request).enqueue(new Callback() {
            @Override
            public void onFailure(Call call, IOException e) {
                runOnUiThread(() -> Toast.makeText(PrivateChatActivity.this, "Lỗi gửi tin nhắn", Toast.LENGTH_SHORT).show());
            }
            @Override
            public void onResponse(Call call, Response response) throws IOException {
                if (response.isSuccessful()) {
                    runOnUiThread(() -> {
                        editMessage.setText("");
                        loadMessages();
                    });
                } else {
                    runOnUiThread(() -> Toast.makeText(PrivateChatActivity.this, "Không gửi được tin nhắn", Toast.LENGTH_SHORT).show());
                }
            }
        });
    }

    // MessageItem class
    public static class MessageItem {
        public String senderUid;
        public String content;
        public String timestamp;
        public MessageItem(String senderUid, String content, String timestamp) {
            this.senderUid = senderUid;
            this.content = content;
            this.timestamp = timestamp;
        }
    }
}