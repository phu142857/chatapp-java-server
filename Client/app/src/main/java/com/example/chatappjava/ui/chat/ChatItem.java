package com.example.chatappjava.ui.chat;

public class ChatItem {
    private String id;
    private String displayName;
    private String lastMessage;
    private boolean isGroup;
    private String avatarUrl;
    private String lastMessageTime;
    private int unreadCount;
    private boolean isOnline;

    public ChatItem(String id, String displayName, String lastMessage, boolean isGroup) {
        this.id = id;
        this.displayName = displayName;
        this.lastMessage = lastMessage;
        this.isGroup = isGroup;
        this.avatarUrl = "";
        this.lastMessageTime = "";
        this.unreadCount = 0;
        this.isOnline = false;
    }

    public ChatItem(String id, String displayName, String lastMessage, boolean isGroup, 
                   String avatarUrl, String lastMessageTime, int unreadCount, boolean isOnline) {
        this.id = id;
        this.displayName = displayName;
        this.lastMessage = lastMessage;
        this.isGroup = isGroup;
        this.avatarUrl = avatarUrl != null ? avatarUrl : "";
        this.lastMessageTime = lastMessageTime != null ? lastMessageTime : "";
        this.unreadCount = unreadCount;
        this.isOnline = isOnline;
    }

    // Existing getters
    public String getId() { return id; }
    public String getDisplayName() { return displayName; }
    public String getLastMessage() { return lastMessage; }
    public boolean isGroup() { return isGroup; }

    // New getters
    public String getAvatarUrl() { return avatarUrl; }
    public String getLastMessageTime() { return lastMessageTime; }
    public int getUnreadCount() { return unreadCount; }
    public boolean isOnline() { return isOnline; }

    // Setters
    public void setAvatarUrl(String avatarUrl) { this.avatarUrl = avatarUrl; }
    public void setDisplayName(String displayName) { this.displayName = displayName; }
    public void setLastMessageTime(String lastMessageTime) { this.lastMessageTime = lastMessageTime; }
    public void setUnreadCount(int unreadCount) { this.unreadCount = unreadCount; }
    public void setOnline(boolean online) { isOnline = online; }
}
