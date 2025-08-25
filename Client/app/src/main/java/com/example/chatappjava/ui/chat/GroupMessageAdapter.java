package com.example.chatappjava.ui.chat;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;
import com.example.chatappjava.R;
import java.util.List;

import  com.example.chatappjava.ui.chat.GroupChatActivity.GroupMessageItem;

public class GroupMessageAdapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {
    private static final int TYPE_SENT = 1;
    private static final int TYPE_RECEIVED = 2;
    private final List<GroupMessageItem> messageList;
    private final String myUid;

    public GroupMessageAdapter(List<GroupMessageItem> messageList, String myUid) {
        this.messageList = messageList;
        this.myUid = myUid;
    }

    @Override
    public int getItemViewType(int position) {
        GroupMessageItem msg = messageList.get(position);
        return msg.senderUid.equals(myUid) ? TYPE_SENT : TYPE_RECEIVED;
    }

    @NonNull
    @Override
    public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        if (viewType == TYPE_SENT) {
            View view = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_group_message_sent, parent, false);
            return new SentViewHolder(view);
        } else {
            View view = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_group_message_received, parent, false);
            return new ReceivedViewHolder(view);
        }
    }

    @Override
    public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position) {
        GroupMessageItem msg = messageList.get(position);
        if (holder instanceof SentViewHolder) {
            ((SentViewHolder) holder).txtSender.setText("Bạn");
            ((SentViewHolder) holder).txtMessage.setText(msg.content);
            ((SentViewHolder) holder).txtTime.setText(msg.timestamp);
        } else if (holder instanceof ReceivedViewHolder) {
            ((ReceivedViewHolder) holder).txtSender.setText(msg.senderName);
            ((ReceivedViewHolder) holder).txtMessage.setText(msg.content);
            ((ReceivedViewHolder) holder).txtTime.setText(msg.timestamp);
        }
    }

    @Override
    public int getItemCount() {
        return messageList.size();
    }

    static class SentViewHolder extends RecyclerView.ViewHolder {
        TextView txtSender, txtMessage, txtTime;
        SentViewHolder(@NonNull View itemView) {
            super(itemView);
            txtSender = itemView.findViewById(R.id.txtSenderSent);
            txtMessage = itemView.findViewById(R.id.txtMessageSent);
            txtTime = itemView.findViewById(R.id.txtTimeSent);
        }
    }

    static class ReceivedViewHolder extends RecyclerView.ViewHolder {
        TextView txtSender, txtMessage, txtTime;
        ReceivedViewHolder(@NonNull View itemView) {
            super(itemView);
            txtSender = itemView.findViewById(R.id.txtSenderReceived);
            txtMessage = itemView.findViewById(R.id.txtMessageReceived);
            txtTime = itemView.findViewById(R.id.txtTimeReceived);
        }
    }
}

