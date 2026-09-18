package com.signlanguage.app.ui;

import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.core.content.FileProvider;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.button.MaterialButton;
import com.signlanguage.app.MainActivity;
import com.signlanguage.app.R;
import com.signlanguage.app.data.HistoryEntry;
import com.signlanguage.app.util.LogUtil;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * 历史记录页：显示识别过的手势（带时间、当时的心率血氧），支持朗读、删除、清空、导出CSV。
 */
public class HistoryFragment extends Fragment {

    private TextView tvCount;
    private TextView tvEmpty;
    private RecyclerView recycler;
    private Adapter adapter;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_history, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View v, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(v, savedInstanceState);
        MainActivity activity = (MainActivity) requireActivity();

        tvCount = v.findViewById(R.id.tv_count);
        tvEmpty = v.findViewById(R.id.tv_empty);
        recycler = v.findViewById(R.id.recycler);
        recycler.setLayoutManager(new LinearLayoutManager(requireContext()));
        adapter = new Adapter(new Adapter.Listener() {
            @Override
            public void onSpeak(HistoryEntry e) {
                activity.speak(e.gesture);
            }

            @Override
            public void onLongClick(HistoryEntry e) {
                showRowMenu(e);
            }
        });
        recycler.setAdapter(adapter);

        v.findViewById(R.id.btn_export).setOnClickListener(view -> export());
        v.findViewById(R.id.btn_clear).setOnClickListener(view -> confirmClear());

        refresh();
    }

    @Override
    public void onResume() {
        super.onResume();
        refresh();
    }

    private void refresh() {
        MainActivity activity = (MainActivity) getActivity();
        if (activity == null || adapter == null) {
            return;
        }
        List<HistoryEntry> list = activity.history().all();
        adapter.submit(list);
        tvCount.setText(list.isEmpty() ? getString(R.string.history_count_empty)
                : getString(R.string.history_count, list.size()));
        tvEmpty.setVisibility(list.isEmpty() ? View.VISIBLE : View.GONE);
    }

    // ---------------------------------------------------------------- 操作

    private void showRowMenu(HistoryEntry e) {
        MainActivity activity = (MainActivity) requireActivity();
        String[] items = {
                getString(R.string.action_speak),
                getString(R.string.action_copy),
                getString(R.string.action_delete)
        };
        new AlertDialog.Builder(requireContext())
                .setTitle(e.gesture)
                .setItems(items, (dialog, which) -> {
                    if (which == 0) {
                        activity.speak(e.gesture);
                    } else if (which == 1) {
                        android.content.ClipboardManager cm = (android.content.ClipboardManager)
                                requireContext().getSystemService(android.content.Context.CLIPBOARD_SERVICE);
                        cm.setPrimaryClip(android.content.ClipData.newPlainText("gesture", e.gesture));
                        Toast.makeText(requireContext(), R.string.toast_copied, Toast.LENGTH_SHORT).show();
                    } else {
                        activity.history().delete(e.id);
                        refresh();
                    }
                })
                .show();
    }

    private void confirmClear() {
        MainActivity activity = (MainActivity) requireActivity();
        int n = activity.history().size();
        if (n == 0) {
            Toast.makeText(requireContext(), R.string.history_count_empty, Toast.LENGTH_SHORT).show();
            return;
        }
        new AlertDialog.Builder(requireContext())
                .setTitle(R.string.dialog_clear_title)
                .setMessage(getString(R.string.dialog_clear_msg, n))
                .setNegativeButton(R.string.action_cancel, null)
                .setPositiveButton(R.string.action_clear, (d, w) -> {
                    activity.history().clear();
                    refresh();
                    LogUtil.i("已清空历史记录");
                })
                .show();
    }

    private void export() {
        MainActivity activity = (MainActivity) requireActivity();
        if (activity.history().size() == 0) {
            Toast.makeText(requireContext(), R.string.history_count_empty, Toast.LENGTH_SHORT).show();
            return;
        }
        try {
            File dir = new File(requireContext().getCacheDir(), "export");
            if (!dir.exists() && !dir.mkdirs()) {
                throw new IOException("无法创建导出目录");
            }
            SimpleDateFormat fmt = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.CHINA);
            File out = new File(dir, "gesture_history_" + fmt.format(new Date()) + ".csv");
            try (Writer w = new OutputStreamWriter(new FileOutputStream(out), "UTF-8")) {
                w.write(activity.history().exportCsv());
            }
            Uri uri = FileProvider.getUriForFile(requireContext(),
                    requireContext().getPackageName() + ".fileprovider", out);
            Intent intent = new Intent(Intent.ACTION_SEND);
            intent.setType("text/csv");
            intent.putExtra(Intent.EXTRA_STREAM, uri);
            intent.putExtra(Intent.EXTRA_SUBJECT, getString(R.string.export_subject));
            intent.putExtra(Intent.EXTRA_TEXT,
                    getString(R.string.export_subject) + "（" + activity.history().size() + " 条）");
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            startActivity(Intent.createChooser(intent, getString(R.string.export_chooser)));
            LogUtil.i("已导出 " + out.getName());
        } catch (Exception e) {
            LogUtil.e("导出失败", e);
            Toast.makeText(requireContext(),
                    getString(R.string.toast_export_failed, String.valueOf(e.getMessage())),
                    Toast.LENGTH_LONG).show();
        }
    }

    // ---------------------------------------------------------------- Adapter

    private static class Adapter extends RecyclerView.Adapter<Adapter.VH> {

        interface Listener {
            void onSpeak(HistoryEntry e);

            void onLongClick(HistoryEntry e);
        }

        private final List<HistoryEntry> items = new ArrayList<>();
        private final Listener listener;
        private final SimpleDateFormat fmt = new SimpleDateFormat("MM-dd HH:mm:ss", Locale.CHINA);

        Adapter(Listener listener) {
            this.listener = listener;
        }

        void submit(List<HistoryEntry> list) {
            items.clear();
            items.addAll(list);
            notifyDataSetChanged();
        }

        @NonNull
        @Override
        public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View v = LayoutInflater.from(parent.getContext())
                    .inflate(R.layout.item_history, parent, false);
            return new VH(v);
        }

        @Override
        public void onBindViewHolder(@NonNull VH h, int position) {
            HistoryEntry e = items.get(position);
            h.gesture.setText(e.gesture);
            h.time.setText(fmt.format(new Date(e.time)));

            if (e.hasVitals()) {
                StringBuilder sb = new StringBuilder();
                if (e.heartRate > 0) {
                    sb.append("❤ ").append(e.heartRate).append(" bpm");
                }
                if (e.spo2 > 0) {
                    if (sb.length() > 0) {
                        sb.append("   ");
                    }
                    sb.append("O₂ ").append(e.spo2).append("%");
                }
                h.vitals.setText(sb.toString());
                h.vitals.setVisibility(View.VISIBLE);
            } else {
                h.vitals.setText(R.string.vitals_no_record);
                h.vitals.setVisibility(View.VISIBLE);
            }
            h.vitals.setTextColor(androidx.core.content.ContextCompat.getColor(
                    h.itemView.getContext(), R.color.heart));

            h.speak.setOnClickListener(v -> listener.onSpeak(e));
            h.itemView.setOnLongClickListener(v -> {
                listener.onLongClick(e);
                return true;
            });
            h.itemView.setOnClickListener(v -> listener.onSpeak(e));
            if (TextUtils.isEmpty(e.gesture)) {
                h.gesture.setText("(空)");
            }
        }

        @Override
        public int getItemCount() {
            return items.size();
        }

        static class VH extends RecyclerView.ViewHolder {
            final TextView gesture;
            final TextView vitals;
            final TextView time;
            final MaterialButton speak;

            VH(@NonNull View v) {
                super(v);
                gesture = v.findViewById(R.id.tv_gesture);
                vitals = v.findViewById(R.id.tv_vitals);
                time = v.findViewById(R.id.tv_time);
                speak = v.findViewById(R.id.btn_speak);
            }
        }
    }
}
