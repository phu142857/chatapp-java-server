package com.example.chatappjava.ui.chat;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;
import com.example.chatappjava.R;
import java.util.List;

public class MessageAdapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {
    private static final int TYPE_SENT = 1;
    private static final int TYPE_RECEIVED = 2;
    private final List<PrivateChatActivity.MessageItem> messageList;
    private final String myUid;

    public MessageAdapter(List<PrivateChatActivity.MessageItem> messageList, String myUid) {
        this.messageList = messageList;
        this.myUid = myUid;
    }

    @Override
    public int getItemViewType(int position) {
        PrivateChatActivity.MessageItem msg = messageList.get(position);
        return msg.senderUid.equals(myUid) ? TYPE_SENT : TYPE_RECEIVED;
    }

    @NonNull
    @Override
    public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        if (viewType == TYPE_SENT) {
            View view = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_message_sent, parent, false);
            return new SentViewHolder(view);
        } else {
            View view = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_message_received, parent, false);
            return new ReceivedViewHolder(view);
        }
    }

    @Override
    public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position) {
        PrivateChatActivity.MessageItem msg = messageList.get(position);
        if (holder instanceof SentViewHolder) {
            ((SentViewHolder) holder).txtMessage.setText(msg.content);
            ((SentViewHolder) holder).txtTime.setText(msg.timestamp);
        } else if (holder instanceof ReceivedViewHolder) {
            ((ReceivedViewHolder) holder).txtMessage.setText(msg.content);
            ((ReceivedViewHolder) holder).txtTime.setText(msg.timestamp);
        }
    }

    @Override
    public int getItemCount() {
        return messageList.size();
    }

    static class SentViewHolder extends RecyclerView.ViewHolder {
        TextView txtMessage, txtTime;
        SentViewHolder(@NonNull View itemView) {
            super(itemView);
            txtMessage = itemView.findViewById(R.id.txtMessageSent);
            txtTime = itemView.findViewById(R.id.txtTimeSent);
        }
    }

    static class ReceivedViewHolder extends RecyclerView.ViewHolder {
        TextView txtMessage, txtTime;
        ReceivedViewHolder(@NonNull View itemView) {
            super(itemView);
            txtMessage = itemView.findViewById(R.id.txtMessageReceived);
            txtTime = itemView.findViewById(R.id.txtTimeReceived);
        }
    }
}
