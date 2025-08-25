package com.example.chatappjava.ui.friends;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.CheckBox;
import android.widget.TextView;
import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;
import com.example.chatappjava.R;
import com.example.chatappjava.ui.friends.FriendListActivity;
import java.util.List;
import java.util.Set;

public class FriendSelectAdapter extends RecyclerView.Adapter<FriendSelectAdapter.FriendViewHolder> {
    private final List<FriendListActivity.FriendItem> friendList;
    private final Set<String> selectedUids;

    public FriendSelectAdapter(List<FriendListActivity.FriendItem> friendList, Set<String> selectedUids) {
        this.friendList = friendList;
        this.selectedUids = selectedUids;
    }

    @NonNull
    @Override
    public FriendViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_friend_select, parent, false);
        return new FriendViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull FriendViewHolder holder, int position) {
        FriendListActivity.FriendItem item = friendList.get(position);
        holder.txtName.setText(item.displayName.isEmpty() ? item.email : item.displayName);
        holder.checkBox.setChecked(selectedUids.contains(item.uid));
        holder.checkBox.setOnCheckedChangeListener((buttonView, isChecked) -> {
            if (isChecked) selectedUids.add(item.uid);
            else selectedUids.remove(item.uid);
        });
        holder.itemView.setOnClickListener(v -> {
            boolean checked = !holder.checkBox.isChecked();
            holder.checkBox.setChecked(checked);
        });
    }

    @Override
    public int getItemCount() {
        return friendList.size();
    }

    static class FriendViewHolder extends RecyclerView.ViewHolder {
        TextView txtName;
        CheckBox checkBox;
        FriendViewHolder(@NonNull View itemView) {
            super(itemView);
            txtName = itemView.findViewById(R.id.txtFriendNameSelect);
            checkBox = itemView.findViewById(R.id.checkBoxSelect);
        }
    }
}
