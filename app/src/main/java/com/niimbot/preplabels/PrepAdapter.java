package com.niimbot.preplabels;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import java.util.Collections;
import java.util.List;

public class PrepAdapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {

    public static final int TYPE_ITEM = 0;
    public static final int TYPE_ADD_BUTTON = 1;

    public interface AdapterListener {
        void onItemClick(PrepItem item);
        void onItemDelete(PrepItem item, int position);
        void onAddButtonClick();
    }

    private final List<PrepItem> items;
    private final AdapterListener listener;
    private int deleteModePosition = -1;

    public PrepAdapter(List<PrepItem> items, AdapterListener listener) {
        this.items = items;
        this.listener = listener;
        sortAlphabetical();
    }

    public void sortAlphabetical() {
        Collections.sort(items);
        notifyDataSetChanged();
    }

    public void clearDeleteMode() {
        if (deleteModePosition != -1) {
            int old = deleteModePosition;
            deleteModePosition = -1;
            notifyItemChanged(old);
        }
    }

    @Override
    public int getItemViewType(int position) {
        if (position == items.size()) {
            return TYPE_ADD_BUTTON;
        }
        return TYPE_ITEM;
    }

    @NonNull
    @Override
    public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        if (viewType == TYPE_ADD_BUTTON) {
            View v = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_add_button, parent, false);
            return new AddButtonViewHolder(v);
        } else {
            View v = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_prep_card, parent, false);
            return new ItemViewHolder(v);
        }
    }

    @Override
    public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position) {
        if (holder instanceof ItemViewHolder) {
            ItemViewHolder vh = (ItemViewHolder) holder;
            PrepItem item = items.get(position);

            vh.tvItemName.setText(item.getName());
            vh.tvDuration.setText(item.getDurationLabel());

            boolean isDeleteMode = (deleteModePosition == position);
            vh.btnDeleteX.setVisibility(isDeleteMode ? View.VISIBLE : View.GONE);
            
            vh.innerCard.setScaleX(isDeleteMode ? 1.06f : 1.0f);
            vh.innerCard.setScaleY(isDeleteMode ? 1.06f : 1.0f);

            vh.itemView.setOnClickListener(v -> {
                if (deleteModePosition != -1) {
                    clearDeleteMode();
                } else {
                    listener.onItemClick(item);
                }
            });

            vh.itemView.setOnLongClickListener(v -> {
                deleteModePosition = holder.getAdapterPosition();
                notifyDataSetChanged();
                return true;
            });

            vh.btnDeleteX.setOnClickListener(v -> {
                int pos = holder.getAdapterPosition();
                if (pos >= 0 && pos < items.size()) {
                    PrepItem it = items.get(pos);
                    deleteModePosition = -1;
                    listener.onItemDelete(it, pos);
                }
            });
        } else if (holder instanceof AddButtonViewHolder) {
            AddButtonViewHolder vh = (AddButtonViewHolder) holder;
            vh.btnAddNewItem.setOnClickListener(v -> {
                clearDeleteMode();
                listener.onAddButtonClick();
            });
        }
    }

    @Override
    public int getItemCount() {
        return items.size() + 1; // +1 for the appended Add button at the bottom
    }

    static class ItemViewHolder extends RecyclerView.ViewHolder {
        View innerCard;
        TextView tvItemName;
        TextView tvDuration;
        TextView btnDeleteX;

        public ItemViewHolder(@NonNull View itemView) {
            super(itemView);
            innerCard = itemView.findViewById(R.id.innerCard);
            tvItemName = itemView.findViewById(R.id.tvItemName);
            tvDuration = itemView.findViewById(R.id.tvDuration);
            btnDeleteX = itemView.findViewById(R.id.btnDeleteX);
        }
    }

    static class AddButtonViewHolder extends RecyclerView.ViewHolder {
        Button btnAddNewItem;

        public AddButtonViewHolder(@NonNull View itemView) {
            super(itemView);
            btnAddNewItem = itemView.findViewById(R.id.btnAddNewItem);
        }
    }
}
