package com.example.chatappjava.ui.chat;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import android.widget.ImageView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.example.chatappjava.R;
import com.google.android.material.imageview.ShapeableImageView;

import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.List;

public class ChatListAdapter extends RecyclerView.Adapter<ChatListAdapter.ChatViewHolder> {
    private static final String TAG = "ChatListAdapter";
    
    private List<ChatItem> chatList;
    private List<ChatItem> originalChatList; // New field to hold the full list
    private OnItemClickListener listener;
    private Context context;

    public interface OnItemClickListener {
        void onItemClick(ChatItem item);
    }

    public ChatListAdapter(List<ChatItem> chatList, OnItemClickListener listener) {
        this.chatList = chatList;
        this.originalChatList = new java.util.ArrayList<>(chatList); // Initialize original list
        this.listener = listener;
    }

    @NonNull
    @Override
    public ChatViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        context = parent.getContext();
        View view = LayoutInflater.from(context).inflate(R.layout.item_chat, parent, false);
        return new ChatViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull ChatViewHolder holder, int position) {
        ChatItem item = chatList.get(position);
        holder.bind(item);
    }

    @Override
    public int getItemCount() {
        return chatList.size();
    }

    public void filter(String query) {
        chatList.clear();
        if (query.isEmpty()) {
            chatList.addAll(originalChatList);
        } else {
            query = query.toLowerCase();
            for (ChatItem item : originalChatList) {
                if (item.getDisplayName().toLowerCase().contains(query) ||
                    item.getLastMessage().toLowerCase().contains(query)) {
                    chatList.add(item);
                }
            }
        }
        notifyDataSetChanged();
    }

    // Method to update the originalChatList when the data changes
    public void updateOriginalList(List<ChatItem> newChatList) {
        this.originalChatList.clear();
        this.originalChatList.addAll(newChatList);
        // Also update the filtered list if a search is active (optional, depends on desired behavior)
        // For now, let's assume filtering will be re-applied after reloadChatList
    }

    class ChatViewHolder extends RecyclerView.ViewHolder {
        private ShapeableImageView imgUserAvatar;
        private TextView txtUserName;
        private TextView txtLastMessage;
        private TextView txtTime;
        private ImageView imgOnlineStatus;
        private TextView badgeUnreadCount;

        public ChatViewHolder(@NonNull View itemView) {
            super(itemView);
            imgUserAvatar = itemView.findViewById(R.id.imgUserAvatar);
            txtUserName = itemView.findViewById(R.id.txtUserName);
            txtLastMessage = itemView.findViewById(R.id.txtLastMessage);
            txtTime = itemView.findViewById(R.id.txtTime);
            imgOnlineStatus = itemView.findViewById(R.id.imgOnlineStatus);
            badgeUnreadCount = itemView.findViewById(R.id.badgeUnreadCount);

            itemView.setOnClickListener(v -> {
                int position = getAdapterPosition();
                if (position != RecyclerView.NO_POSITION && listener != null) {
                    listener.onItemClick(chatList.get(position));
                }
            });
        }

        public void bind(ChatItem item) {
            txtUserName.setText(item.getDisplayName());
            txtLastMessage.setText(item.getLastMessage());
            
            // Set time if available
            if (item.getLastMessageTime() != null && !item.getLastMessageTime().isEmpty()) {
                txtTime.setText(formatTime(item.getLastMessageTime()));
                txtTime.setVisibility(View.VISIBLE);
            } else {
                txtTime.setVisibility(View.GONE);
            }

            // Set online status
            if (item.isOnline() && !item.isGroup()) {
                imgOnlineStatus.setVisibility(View.VISIBLE);
            } else {
                imgOnlineStatus.setVisibility(View.GONE);
            }

            // Set unread count badge
            if (item.getUnreadCount() > 0) {
                badgeUnreadCount.setText(String.valueOf(item.getUnreadCount()));
                badgeUnreadCount.setVisibility(View.VISIBLE);
            } else {
                badgeUnreadCount.setVisibility(View.GONE);
            }

            // Load avatar
            loadAvatar(item.getAvatarUrl(), item.isGroup());
        }

        private void loadAvatar(String avatarUrl, boolean isGroup) {
            if (avatarUrl != null && !avatarUrl.isEmpty() && avatarUrl.startsWith("http")) {
                // Load avatar from URL
                new Thread(() -> {
                    try {
                        URL url = new URL(avatarUrl);
                        HttpURLConnection connection = (HttpURLConnection) url.openConnection();
                        
                        // Add authorization header if needed
                        if (avatarUrl.contains("/avatar/")) {
                            // This is our server endpoint, add auth header
                            String token = getAuthToken();
                            if (token != null) {
                                connection.setRequestProperty("Authorization", "Bearer " + token);
                            }
                        }
                        
                        connection.connect();
                        
                        if (connection.getResponseCode() == HttpURLConnection.HTTP_OK) {
                            InputStream inputStream = connection.getInputStream();
                            final Bitmap bitmap = BitmapFactory.decodeStream(inputStream);
                            inputStream.close();
                            
                            if (bitmap != null) {
                                itemView.post(() -> {
                                    imgUserAvatar.setImageBitmap(bitmap);
                                    imgUserAvatar.setBackgroundResource(0);
                                });
                            } else {
                                showDefaultAvatar(isGroup);
                            }
                        } else {
                            Log.w(TAG, "Failed to load avatar: HTTP " + connection.getResponseCode());
                            showDefaultAvatar(isGroup);
                        }
                    } catch (Exception e) {
                        Log.e(TAG, "Error loading avatar: " + e.getMessage());
                        showDefaultAvatar(isGroup);
                    }
                }).start();
            } else {
                showDefaultAvatar(isGroup);
            }
        }
        
        private String getAuthToken() {
            try {
                // Get token from context
                if (context != null) {
                    android.content.SharedPreferences prefs = context.getSharedPreferences("ChatAppPrefs", android.content.Context.MODE_PRIVATE);
                    return prefs.getString("token", "");
                }
            } catch (Exception e) {
                Log.e(TAG, "Error getting auth token: " + e.getMessage());
            }
            return null;
        }

        private void showDefaultAvatar(boolean isGroup) {
            if (isGroup) {
                imgUserAvatar.setImageResource(R.drawable.baseline_group_24);
            } else {
                imgUserAvatar.setImageResource(R.drawable.baseline_person_24);
            }
            imgUserAvatar.setBackgroundResource(R.drawable.avatar_placeholder_background);
        }

        private String formatTime(String timestamp) {
            try {
                // Simple time formatting - you can enhance this
                if (timestamp.length() > 16) {
                    return timestamp.substring(11, 16); // Extract HH:MM from ISO format
                }
                return timestamp;
            } catch (Exception e) {
                return timestamp;
            }
        }
    }
}
