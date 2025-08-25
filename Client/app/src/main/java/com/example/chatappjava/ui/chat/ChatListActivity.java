package com.example.chatappjava.ui.chat;

import android.annotation.SuppressLint;
import android.content.Intent;
import android.os.Bundle;
import android.util.Log;
import android.view.Menu;
import android.view.MenuItem;
import android.widget.Toast;
import android.widget.ImageButton;
import android.widget.TextView;
import android.widget.EditText;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.example.chatappjava.R;
import com.example.chatappjava.ui.auth.LoginActivity;
import com.example.chatappjava.ui.settings.SettingsActivity;
import com.example.chatappjava.ui.call.IncomingCallActivity;
import com.example.chatappjava.config.server_config;
import okhttp3.WebSocket;
import okhttp3.WebSocketListener;
import okhttp3.OkHttpClient;
import okhttp3.Response;
import androidx.annotation.Nullable;
import com.example.chatappjava.utils.TokenManager;
import com.example.chatappjava.ui.friends.AddFriendActivity;
import com.example.chatappjava.ui.friends.FriendRequestActivity;

import java.util.ArrayList;
import java.util.List;

import okhttp3.OkHttpClient;
import okhttp3.Request;

import java.io.IOException;
import android.view.View;
import android.text.Editable;
import android.text.TextWatcher;

public class ChatListActivity extends AppCompatActivity {

    private ChatListAdapter adapter;
    private List<ChatItem> chatList;
    private int pendingRequestCount = 0;
    private TextView badgeFriendRequest;
    private EditText editSearch; // Declare editSearch as a member variable

    @SuppressLint("MissingInflatedId")
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_chat_list);

        RecyclerView recyclerViewChats = findViewById(R.id.recyclerViewChats);
        recyclerViewChats.setLayoutManager(new LinearLayoutManager(this));

        // Search EditText
        editSearch = findViewById(R.id.editSearch);
        editSearch.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {
                // Not used
            }

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
                // Filter the chat list as the user types
                adapter.filter(s.toString());
            }

            @Override
            public void afterTextChanged(Editable s) {
                // Not used
            }
        });

        // Settings button click
        ImageButton btnSettings = findViewById(R.id.btnSettings);
        btnSettings.setOnClickListener(v -> {
            Intent intent = new Intent(this, SettingsActivity.class);
            startActivity(intent);

            
        });

        // Add button click
        ImageButton btnAdd = findViewById(R.id.btnAdd);
        btnAdd.setOnClickListener(v -> {
            String[] options = {"Thêm bạn bè", "Tạo group chat"};
            new AlertDialog.Builder(this)
                .setTitle("Chọn chức năng")
                .setItems(options, (dialog, which) -> {
                    if (which == 0) {
                        // Thêm bạn bè
                        Intent intent = new Intent(this, AddFriendActivity.class);
                        startActivity(intent);
                    } else if (which == 1) {
                        // Tạo group chat
                        Intent intent = new Intent(this, CreateGroupActivity.class);
                        startActivity(intent);
                    }
                })
                .show();
        });

        ImageButton imgFriendRequest = findViewById(R.id.imgFriendRequest);
        badgeFriendRequest = findViewById(R.id.badgeFriendRequest);
        imgFriendRequest.setOnClickListener(v -> {
            Intent intent = new Intent(this, FriendRequestActivity.class);
            startActivity(intent);
        });

        chatList = new ArrayList<>();
        // Xử lý click vào item
        ChatListAdapter.OnItemClickListener chatlist = item -> {
            if (item.isGroup()) {
                Intent intent = new Intent(this, GroupChatActivity.class);
                intent.putExtra("group_id", item.getId());
                intent.putExtra("group_name", item.getDisplayName());
                startActivity(intent);
            } else {
                Intent intent = new Intent(this, PrivateChatActivity.class);
                intent.putExtra("friend_uid", item.getId());
                intent.putExtra("friend_name", item.getDisplayName());
                startActivity(intent);
            }
        };
        adapter = new ChatListAdapter(chatList, chatlist);
        recyclerViewChats.setAdapter(adapter);

        adapter.notifyDataSetChanged();
        fetchFriendRequestCount();
    }

    @Override
    protected void onResume() {
        super.onResume();
        // Gọi lại hàm load/reload chat list ở đây
        reloadChatList();
        fetchFriendRequestCount(); // cũng cập nhật lại badge lời mời
        checkIncomingCalls();
        ensureNotifySocket();
    }

    private void reloadChatList() {
        String token = TokenManager.get(this);
        if (token == null) return;
        OkHttpClient client = new OkHttpClient();
        Request request = new Request.Builder()
            .url(com.example.chatappjava.config.server_config.BASE_URL + "/chatlist")
            .addHeader("Authorization", "Bearer " + token)
            .build();
        client.newCall(request).enqueue(new okhttp3.Callback() {
            @Override
            public void onFailure(@NonNull okhttp3.Call call, @NonNull IOException e) {
                runOnUiThread(() -> Toast.makeText(ChatListActivity.this, "Lỗi kết nối server", Toast.LENGTH_SHORT).show());
            }
            @Override
            public void onResponse(@NonNull okhttp3.Call call, okhttp3.Response response) throws IOException {
                if (response.isSuccessful()) {
                    try {
                        org.json.JSONObject obj = new org.json.JSONObject(response.body().string());
                        org.json.JSONArray arr = obj.getJSONArray("chats");
                        chatList.clear();
                        for (int i = 0; i < arr.length(); i++) {
                            org.json.JSONObject item = arr.getJSONObject(i);
                            
                            // Extract additional information
                            String id = item.getString("id");
                            String displayName = item.getString("displayName");
                            String lastMessage = item.optString("lastMessage", "");
                            boolean isGroup = item.getBoolean("isGroup");
                            
                            // Create ChatItem with basic info first
                            ChatItem chatItem = new ChatItem(id, displayName, lastMessage, isGroup);
                            
                            // Load additional user information if not a group
                            if (!isGroup) {
                                loadUserDetails(chatItem, id, token);
                            } else {
                                // For groups, set default values
                                chatItem.setLastMessageTime(item.optString("lastMessageTime", ""));
                                chatItem.setUnreadCount(item.optInt("unreadCount", 0));
                            }
                            
                            chatList.add(chatItem);
                        }
                        runOnUiThread(() -> {
                            adapter.updateOriginalList(chatList);
                            adapter.filter(editSearch.getText().toString()); // Re-apply current filter
                        });
                    } catch (Exception e) {
                        runOnUiThread(() -> Toast.makeText(ChatListActivity.this, "Lỗi xử lý dữ liệu", Toast.LENGTH_SHORT).show());
                    }
                }
            }
        });
    }

    private void loadUserDetails(ChatItem chatItem, String userId, String token) {
        OkHttpClient client = new OkHttpClient();
        Request request = new Request.Builder()
                .url(com.example.chatappjava.config.server_config.BASE_URL + "/user/" + userId)
                .addHeader("Authorization", "Bearer " + token)
                .build();

        client.newCall(request).enqueue(new okhttp3.Callback() {
            @Override
            public void onFailure(@NonNull okhttp3.Call call, @NonNull IOException e) {
                // Không lấy được user info → dùng avatar mặc định theo UID
                runOnUiThread(() -> {
                    String fallbackAvatar = com.example.chatappjava.config.server_config.BASE_URL + "/avatar/" + userId;
                    chatItem.setAvatarUrl(fallbackAvatar);
                    int position = chatList.indexOf(chatItem);
                    if (position != -1) {
                        adapter.notifyItemChanged(position);
                    }
                });
            }

            @Override
            public void onResponse(@NonNull okhttp3.Call call, okhttp3.Response response) throws IOException {
                if (response.isSuccessful()) {
                    try {
                        org.json.JSONObject userData = new org.json.JSONObject(response.body().string());

                        runOnUiThread(() -> {
                            String avatarUrl = userData.optString("avatar_url", "");
                            if (avatarUrl.isEmpty()) {
                                // Không có avatar → build từ UID
                                avatarUrl = com.example.chatappjava.config.server_config.BASE_URL + "/avatar/" + userId;
                            } else if (avatarUrl.startsWith("realtime_db://")) {
                                // Nếu lưu theo dạng custom → lấy UID
                                // URL format: realtime_db://users/{uid}/avatar
                                String[] parts = avatarUrl.split("/");
                                Log.d("ChatListActivity", "Parsing realtime_db URL: " + avatarUrl + ", parts: " + java.util.Arrays.toString(parts));
                                if (parts.length >= 4) {
                                    String uid = parts[3]; // uid is at index 3, not 2
                                    avatarUrl = com.example.chatappjava.config.server_config.BASE_URL + "/avatar/" + uid;
                                    Log.d("ChatListActivity", "Extracted UID: " + uid + ", generated avatar URL: " + avatarUrl);
                                } else {
                                    Log.w("ChatListActivity", "Invalid realtime_db URL format: " + avatarUrl + ", parts length: " + parts.length);
                                }
                            }
                            chatItem.setAvatarUrl(avatarUrl);

                            String displayName = userData.optString("display_name", "");
                            if (!displayName.isEmpty()) {
                                chatItem.setDisplayName(displayName);
                            }

                            chatItem.setLastMessageTime(userData.optString("last_message_time", ""));
                            chatItem.setUnreadCount(userData.optInt("unread_count", 0));
                            chatItem.setOnline(userData.optBoolean("is_online", false));

                            int position = chatList.indexOf(chatItem);
                            if (position != -1) {
                                adapter.notifyItemChanged(position);
                            }
                        });

                    } catch (Exception e) {
                        // Lỗi parse JSON → fallback avatar
                        runOnUiThread(() -> {
                            String fallbackAvatar = com.example.chatappjava.config.server_config.BASE_URL + "/avatar/" + userId;
                            chatItem.setAvatarUrl(fallbackAvatar);
                            int position = chatList.indexOf(chatItem);
                            if (position != -1) {
                                adapter.notifyItemChanged(position);
                            }
                        });
                    }
                }
                response.close();
            }
        });
    }


    private void updateFriendRequestBadge() {
        if (badgeFriendRequest == null) return;
        if (pendingRequestCount > 0) {
            badgeFriendRequest.setText(String.valueOf(pendingRequestCount));
            badgeFriendRequest.setVisibility(View.VISIBLE);
        } else {
            badgeFriendRequest.setVisibility(View.GONE);
        }
    }

    private void fetchFriendRequestCount() {
        String token = TokenManager.get(this);
        if (token == null) return;
        OkHttpClient client = new OkHttpClient();
        Request request = new Request.Builder()
                .url(com.example.chatappjava.config.server_config.BASE_URL + "/friendrequest/incoming")
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
                        org.json.JSONArray arr = obj.getJSONArray("requests");
                        pendingRequestCount = arr.length();
                        runOnUiThread(() -> updateFriendRequestBadge());
                    } catch (Exception ignored) {}
                }
            }
        });
    }

    private void checkIncomingCalls() {
        String token = TokenManager.get(this);
        if (token == null) return;
        OkHttpClient client = new OkHttpClient();
        Request request = new Request.Builder()
                .url(server_config.BASE_URL + "/call/incoming")
                .addHeader("Authorization", "Bearer " + token)
                .build();
        client.newCall(request).enqueue(new okhttp3.Callback() {
            @Override public void onFailure(@NonNull okhttp3.Call call, @NonNull IOException e) {}
            @Override public void onResponse(@NonNull okhttp3.Call call, @NonNull okhttp3.Response response) throws IOException {
                if (!response.isSuccessful()) { response.close(); return; }
                try {
                    org.json.JSONObject obj = new org.json.JSONObject(response.body().string());
                    org.json.JSONArray arr = obj.getJSONArray("calls");
                    if (arr.length() > 0) {
                        org.json.JSONObject c = arr.getJSONObject(0);
                        String callId = c.getString("call_id");
                        String callerUid = c.optString("caller_uid", null);
                        runOnUiThread(() -> {
                            Intent i = new Intent(ChatListActivity.this, IncomingCallActivity.class);
                            i.putExtra("MY_UID", getMyUidFromToken());
                            i.putExtra("CALLER_UID", callerUid);
                            i.putExtra("CALL_ID", callId);
                            startActivity(i);
                        });
                    }
                } catch (Exception ignored) {
                } finally { response.close(); }
            }
        });
    }

    // --- Realtime incoming call via notify WebSocket ---
    private static WebSocket notifySocket;
    private static final OkHttpClient notifyClient = new OkHttpClient();
    private void ensureNotifySocket() {
        String token = TokenManager.get(this);
        if (token == null) return;
        if (notifySocket != null) return;
        String base = com.example.chatappjava.config.server_config.WS_URL;
        if (!base.endsWith("/")) base += "/";
        String url = base + "notify";
        okhttp3.Request req = new okhttp3.Request.Builder()
                .url(url)
                .addHeader("Authorization", "Bearer " + token)
                .build();
        notifySocket = notifyClient.newWebSocket(req, new WebSocketListener() {
            @Override public void onOpen(WebSocket webSocket, Response response) {}
            @Override public void onMessage(WebSocket webSocket, String text) {
                try {
                    org.json.JSONObject obj = new org.json.JSONObject(text);
                    if ("incoming_call".equals(obj.optString("type"))) {
                        String callId = obj.optString("call_id", null);
                        String callerUid = obj.optString("caller_uid", null);
                        runOnUiThread(() -> {
                            Intent i = new Intent(ChatListActivity.this, IncomingCallActivity.class);
                            i.putExtra("CALL_ID", callId);
                            i.putExtra("CALLER_UID", callerUid);
                            startActivity(i);
                        });
                    }
                } catch (Exception ignored) {}
            }
            @Override public void onFailure(WebSocket webSocket, Throwable t, @Nullable Response response) {
                notifySocket = null; // allow reconnect on next resume
            }
            @Override public void onClosed(WebSocket webSocket, int code, String reason) {
                if (notifySocket == webSocket) notifySocket = null;
            }
        });
    }

    private String getMyUidFromToken() {
        // TokenManager stores Firebase ID token; server verifies it for UID.
        // We do not decode here; rely on server for logic. For building callId, UID is optional.
        // If needed, this could be cached elsewhere; fallback null.
        return null;
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        getMenuInflater().inflate(R.menu.menu_chat_list, menu);
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        int id = item.getItemId();
        if (id == R.id.action_add_friend) {
            Toast.makeText(this, "Add friend clicked", Toast.LENGTH_SHORT).show();
            return true;
        } else if (id == R.id.action_logout) {
            showLogoutDialog();
            return true;
        } else if (id == R.id.action_friend_requests) {
            Intent intent = new Intent(this, FriendRequestActivity.class);
            startActivity(intent);
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    private void showLogoutDialog() {
        new AlertDialog.Builder(this)
                .setTitle("Logout")
                .setMessage("Do you really want to logout?")
                .setPositiveButton("Yes", (dialog, which) -> {
                    TokenManager.clear(this);
                    Intent intent = new Intent(ChatListActivity.this, LoginActivity.class);
                    intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
                    startActivity(intent);
                    finish();
                })
                .setNegativeButton("No", null)
                .show();
    }
}
