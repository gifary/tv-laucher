package dev.gtv.launcher;

import android.view.LayoutInflater;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import java.util.List;

public class AppAdapter extends RecyclerView.Adapter<AppAdapter.VH> {

    public interface Listener {
        void onAppFocused(View tile, AppItem item, int position);
        void onAppClicked(AppItem item);
        void onAppSelectedChanged(AppItem item); // null when nothing is focused
    }

    private final List<AppItem> items;
    private final boolean dock;
    private final Listener listener;
    private int focusedPosition = -1;

    public AppAdapter(List<AppItem> items, boolean dock, Listener listener) {
        this.items = items;
        this.dock = dock;
        this.listener = listener;
    }

    @NonNull
    @Override
    public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        int layout = dock ? R.layout.item_dock : R.layout.item_app;
        View v = LayoutInflater.from(parent.getContext()).inflate(layout, parent, false);
        ImageView icon = v.findViewById(R.id.icon);
        // Apple-TV style rounded icons: clip the (usually square) app icon.
        icon.setClipToOutline(true);
        icon.setOutlineProvider(new android.view.ViewOutlineProvider() {
            @Override
            public void getOutline(View view, android.graphics.Outline outline) {
                outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(),
                        view.getWidth() * 0.235f);
            }
        });
        return new VH(v, dock);
    }

    @Override
    public void onBindViewHolder(@NonNull VH holder, int position) {
        AppItem item = items.get(position);
        holder.icon.setImageDrawable(item.icon);
        if (holder.label != null) {
            holder.label.setText(item.label);
            holder.label.setAlpha(1f);
        }

        holder.itemView.setOnClickListener(v -> listener.onAppClicked(item));
        holder.itemView.setOnKeyListener((v, keyCode, event) -> {
            if (keyCode != KeyEvent.KEYCODE_DPAD_CENTER
                    && keyCode != KeyEvent.KEYCODE_ENTER
                    && keyCode != KeyEvent.KEYCODE_NUMPAD_ENTER
                    && keyCode != KeyEvent.KEYCODE_BUTTON_A) {
                return false;
            }
            if (event.getAction() == KeyEvent.ACTION_DOWN) {
                if (event.getRepeatCount() == 0) v.performClick();
            }
            return true;
        });
        holder.itemView.setOnFocusChangeListener((v, hasFocus) -> {
            if (hasFocus) {
                focusedPosition = holder.getBindingAdapterPosition();
                listener.onAppFocused(v, item, focusedPosition);
            } else {
                v.animate().scaleX(1f).scaleY(1f).setDuration(140).start();
                if (focusedPosition == holder.getBindingAdapterPosition()) {
                    focusedPosition = -1;
                    listener.onAppSelectedChanged(null);
                }
            }
        });

        // Re-apply focused styling if this view is recycled while focused.
        boolean focused = holder.itemView.hasFocus();
        float scale = focused ? (dock ? 1.05f : 1.04f) : 1f;
        holder.itemView.setScaleX(scale);
        holder.itemView.setScaleY(scale);
        if (holder.label != null) holder.label.setAlpha(1f);
    }

    @Override
    public int getItemCount() {
        return items.size();
    }

    public int positionOf(String pkg) {
        for (int i = 0; i < items.size(); i++) {
            if (items.get(i).pkg.equals(pkg)) return i;
        }
        return -1;
    }

    public String pkgAt(int position) {
        return position >= 0 && position < items.size() ? items.get(position).pkg : null;
    }

    static class VH extends RecyclerView.ViewHolder {
        final ImageView icon;
        final TextView label;

        VH(View v, boolean dock) {
            super(v);
            icon = v.findViewById(R.id.icon);
            label = v.findViewById(R.id.label);
        }
    }
}
