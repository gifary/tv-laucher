package dev.gtv.launcher;

import android.content.Context;
import android.util.AttributeSet;
import android.view.KeyEvent;
import android.view.View;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

/**
 * App grid with deterministic D-pad movement.
 *
 * The stock behaviour lets the framework's focus search pick a neighbour by
 * geometry, which skips rows and falls out of the grid. Here every direction
 * is computed from the adapter position: one row, one column, or an explicit
 * hand-off to the caller at an edge.
 */
public class FocusRecyclerView extends RecyclerView {

    public interface EdgeListener {
        void onTopEdge();
        void onBottomEdge();
    }

    private int columns = 5;
    private boolean horizontal = false;
    private EdgeListener edgeListener;

    public FocusRecyclerView(@NonNull Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        setFocusable(false);
        setDescendantFocusability(FOCUS_AFTER_DESCENDANTS);
        setItemAnimator(null);
    }

    public void setColumns(int columns) {
        this.columns = Math.max(1, columns);
    }

    public void setHorizontalList(boolean horizontal) {
        this.horizontal = horizontal;
    }

    public void setEdgeListener(EdgeListener l) {
        this.edgeListener = l;
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        if (event.getAction() == KeyEvent.ACTION_DOWN && handleKey(event.getKeyCode())) {
            return true;
        }
        return super.dispatchKeyEvent(event);
    }

    private boolean handleKey(int keyCode) {
        View focused = getFocusedChild();
        if (focused == null) return false;
        int pos = getChildAdapterPosition(focused);
        if (pos == NO_POSITION) return false;
        int count = getAdapter() == null ? 0 : getAdapter().getItemCount();

        int target = -1;
        switch (keyCode) {
            case KeyEvent.KEYCODE_DPAD_LEFT:
                if (horizontal) target = pos - 1;
                else if (pos % columns > 0) target = pos - 1;
                if (target < 0) return true;              // hard edge: stay put
                break;
            case KeyEvent.KEYCODE_DPAD_RIGHT:
                if (horizontal) target = pos + 1;
                else if (pos % columns < columns - 1) target = pos + 1;
                if (target >= count) return true;         // hard edge: stay put
                break;
            case KeyEvent.KEYCODE_DPAD_UP:
                if (horizontal) {
                    if (edgeListener != null) edgeListener.onTopEdge();
                    return true;
                }
                target = pos - columns;
                if (target < 0) {
                    if (edgeListener != null) edgeListener.onTopEdge();
                    return true;
                }
                break;
            case KeyEvent.KEYCODE_DPAD_DOWN:
                if (horizontal) {
                    if (edgeListener != null) edgeListener.onBottomEdge();
                    return true;
                }
                target = pos + columns;
                if (target >= count) {
                    if (edgeListener != null) edgeListener.onBottomEdge();
                    return true;
                }
                break;
            default:
                return false;
        }
        return focusPosition(target);
    }

    /** Move focus to an adapter position, scrolling only if it is not laid out. */
    public boolean focusPosition(int pos) {
        if (getAdapter() == null || pos < 0 || pos >= getAdapter().getItemCount()) return false;
        View child = getLayoutManager() == null
                ? null : getLayoutManager().findViewByPosition(pos);
        if (child != null) {
            child.requestFocus();
            return true;
        }
        // Align to a whole row so the grid never shows half a cut-off row
        // above the focused one.
        if (!horizontal && getLayoutManager() instanceof GridLayoutManager) {
            int rowStart = pos - (pos % columns);
            ((GridLayoutManager) getLayoutManager())
                    .scrollToPositionWithOffset(rowStart, 0);
        } else {
            scrollToPosition(pos);
        }
        post(() -> {
            View c = getLayoutManager() == null
                    ? null : getLayoutManager().findViewByPosition(pos);
            if (c != null) c.requestFocus();
        });
        return true;
    }
}
