package uk.org.openseizuredetector.activity.main;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Typeface;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.preference.PreferenceManager;
import androidx.recyclerview.widget.ItemTouchHelper;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import uk.org.openseizuredetector.R;

/**
 * Dialog (opened from Settings - General) in which the user chooses the order of the sections
 * shown on the main display in Advanced Mode. A row can be moved with its up/down arrows, or
 * by pressing and holding it and dragging.
 */
public final class SectionOrderDialog {

    private SectionOrderDialog() {
    }

    public static void show(final Context context) {
        final SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context);
        final List<Integer> order = new ArrayList<>(AdvancedSections.getOrder(prefs));
        final float density = context.getResources().getDisplayMetrics().density;

        final RecyclerView list = new RecyclerView(context);
        list.setLayoutManager(new LinearLayoutManager(context));
        // Every move is saved at once (so there is no Save button that has to be reachable).
        final RowAdapter adapter = new RowAdapter(context, order, density, prefs);
        list.setAdapter(adapter);
        int pad = Math.round(8 * density);
        list.setPadding(pad, pad, pad, pad);

        // Fixed list height: with a wrap_content list the dialog could grow taller than the
        // screen and push its buttons out of view.
        int screenH = context.getResources().getDisplayMetrics().heightPixels;
        int listH = Math.min(Math.round(order.size() * 56 * density), Math.round(screenH * 0.45f));
        list.setLayoutParams(new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, listH));

        TextView hint = new TextView(context);
        hint.setText(R.string.advanced_section_order_hint);
        hint.setPadding(pad * 2, pad, pad * 2, 0);

        LinearLayout body = new LinearLayout(context);
        body.setOrientation(LinearLayout.VERTICAL);
        body.addView(hint);
        body.addView(list);

        ItemTouchHelper touchHelper = new ItemTouchHelper(
                new ItemTouchHelper.SimpleCallback(ItemTouchHelper.UP | ItemTouchHelper.DOWN, 0) {
                    @Override
                    public boolean onMove(@NonNull RecyclerView rv, @NonNull RecyclerView.ViewHolder from,
                                          @NonNull RecyclerView.ViewHolder to) {
                        adapter.move(from.getAdapterPosition(), to.getAdapterPosition());
                        return true;
                    }

                    @Override
                    public void clearView(@NonNull RecyclerView rv, @NonNull RecyclerView.ViewHolder vh) {
                        super.clearView(rv, vh);
                        // dragging finished: renumber the rows
                        rv.post(new Runnable() {
                            @Override
                            public void run() {
                                adapter.refreshNumbers();
                            }
                        });
                    }

                    @Override
                    public void onSwiped(@NonNull RecyclerView.ViewHolder viewHolder, int direction) {
                        // swiping is not enabled
                    }
                });
        touchHelper.attachToRecyclerView(list);

        new MaterialAlertDialogBuilder(context)
                .setTitle(R.string.advanced_section_order_title)
                .setView(body)
                .setPositiveButton(android.R.string.ok, null)
                .setNeutralButton(R.string.advanced_section_order_reset,
                        (d, w) -> AdvancedSections.resetOrder(prefs))
                .show();
    }

    private static final class RowAdapter extends RecyclerView.Adapter<RowAdapter.Holder> {
        private final Context mContext;
        private final List<Integer> mOrder;
        private final float mDensity;
        private final SharedPreferences mPrefs;

        RowAdapter(Context context, List<Integer> order, float density, SharedPreferences prefs) {
            mContext = context;
            mOrder = order;
            mDensity = density;
            mPrefs = prefs;
        }

        void move(int from, int to) {
            if (from < 0 || to < 0 || from >= mOrder.size() || to >= mOrder.size()) return;
            Collections.swap(mOrder, from, to);
            notifyItemMoved(from, to);
            AdvancedSections.saveOrder(mPrefs, mOrder);   // saved immediately
        }

        @NonNull
        @Override
        public Holder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            LinearLayout row = new LinearLayout(mContext);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setLayoutParams(new RecyclerView.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            int p = Math.round(8 * mDensity);
            row.setPadding(p, p, p, p);

            TextView title = new TextView(mContext);
            title.setTextSize(16);
            title.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

            TextView up = arrow("\u25B2");
            TextView down = arrow("\u25BC");
            row.addView(title);
            row.addView(up);
            row.addView(down);
            return new Holder(row, title, up, down);
        }

        private TextView arrow(String symbol) {
            TextView t = new TextView(mContext);
            t.setText(symbol);
            t.setTextSize(20);
            t.setTypeface(null, Typeface.BOLD);
            t.setGravity(Gravity.CENTER);
            int min = Math.round(48 * mDensity);
            t.setMinWidth(min);
            t.setMinHeight(min);
            t.setClickable(true);
            return t;
        }

        @Override
        public void onBindViewHolder(@NonNull final Holder h, int position) {
            h.title.setText((position + 1) + ".  " + mContext.getString(AdvancedSections.titleRes(mOrder.get(position))));
            h.up.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    int pos = h.getAdapterPosition();
                    if (pos > 0) {
                        move(pos, pos - 1);
                        refreshNumbers();
                    }
                }
            });
            h.down.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    int pos = h.getAdapterPosition();
                    if (pos >= 0 && pos < mOrder.size() - 1) {
                        move(pos, pos + 1);
                        refreshNumbers();
                    }
                }
            });
        }

        /** Re-binds all rows so the "1. 2. 3." numbers follow the new order. */
        void refreshNumbers() {
            notifyItemRangeChanged(0, mOrder.size());
        }

        @Override
        public int getItemCount() {
            return mOrder.size();
        }

        static final class Holder extends RecyclerView.ViewHolder {
            final TextView title;
            final TextView up;
            final TextView down;

            Holder(View itemView, TextView title, TextView up, TextView down) {
                super(itemView);
                this.title = title;
                this.up = up;
                this.down = down;
            }
        }
    }
}
