package com.example.chatappjava.ui.friends;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.TextView;
import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;
import com.example.chatappjava.R;
import java.util.List;

public class FriendRequestAdapter extends RecyclerView.Adapter<FriendRequestAdapter.RequestViewHolder> {
    public interface OnActionListener {
        void onAccept(String requestId);
        void onReject(String requestId);
    }
    private final List<FriendRequestActivity.FriendRequestItem> requestList;
    private final OnActionListener listener;

    public FriendRequestAdapter(List<FriendRequestActivity.FriendRequestItem> requestList, OnActionListener listener) {
        this.requestList = requestList;
        this.listener = listener;
    }

    @NonNull
    @Override
    public RequestViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_friend_request, parent, false);
        return new RequestViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull RequestViewHolder holder, int position) {
        FriendRequestActivity.FriendRequestItem item = requestList.get(position);
        holder.txtFromUid.setText(item.fromUid);
        holder.btnAccept.setOnClickListener(v -> listener.onAccept(item.id));
        holder.btnReject.setOnClickListener(v -> listener.onReject(item.id));
    }

    @Override
    public int getItemCount() {
        return requestList.size();
    }

    static class RequestViewHolder extends RecyclerView.ViewHolder {
        TextView txtFromUid;
        Button btnAccept, btnReject;
        RequestViewHolder(@NonNull View itemView) {
            super(itemView);
            txtFromUid = itemView.findViewById(R.id.txtFromUid);
            btnAccept = itemView.findViewById(R.id.btnAccept);
            btnReject = itemView.findViewById(R.id.btnReject);
        }
    }
} 