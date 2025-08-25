package com.example.chatappjava.ui.friends;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;
import com.example.chatappjava.R;
import java.util.List;

public class FriendListAdapter extends RecyclerView.Adapter<FriendListAdapter.FriendViewHolder> {
    private final List<FriendListActivity.FriendItem> friendList;

    public FriendListAdapter(List<FriendListActivity.FriendItem> friendList) {
        this.friendList = friendList;
    }

    @NonNull
    @Override
    public FriendViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_friend, parent, false);
        return new FriendViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull FriendViewHolder holder, int position) {
        FriendListActivity.FriendItem item = friendList.get(position);
        holder.txtName.setText(item.displayName.isEmpty() ? item.email : item.displayName);
        holder.txtEmail.setText(item.email);
    }

    @Override
    public int getItemCount() {
        return friendList.size();
    }

    static class FriendViewHolder extends RecyclerView.ViewHolder {
        TextView txtName, txtEmail;
        FriendViewHolder(@NonNull View itemView) {
            super(itemView);
            txtName = itemView.findViewById(R.id.txtFriendName);
            txtEmail = itemView.findViewById(R.id.txtFriendEmail);
        }
    }
} 