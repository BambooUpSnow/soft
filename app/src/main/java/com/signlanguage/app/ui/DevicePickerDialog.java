package com.signlanguage.app.ui;

import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.DialogFragment;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.button.MaterialButton;
import com.signlanguage.app.MainActivity;
import com.signlanguage.app.R;
import com.signlanguage.app.ble.BleManager;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 选择蓝牙设备：上面是已配对设备（HC-05 一般已经在系统蓝牙里配好了），
 * 下面是扫描到的新设备。
 */
public class DevicePickerDialog extends DialogFragment {

    private final Handler handler = new Handler(Looper.getMainLooper());
    private Adapter adapter;
    private TextView tvEmpty;

    private final Runnable refreshTask = new Runnable() {
        @Override
        public void run() {
            refresh();
            handler.postDelayed(this, 1500);
        }
    };

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.dialog_device_picker, container, false);
    }

    /** DialogFragment 必须实现它才会以弹窗（而不是整页）形式显示 */
    @NonNull
    @Override
    public android.app.Dialog onCreateDialog(@Nullable Bundle savedInstanceState) {
        android.app.Dialog dialog = super.onCreateDialog(savedInstanceState);
        android.view.Window window = dialog.getWindow();
        if (window != null) {
            window.setLayout(ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT);
            window.setGravity(android.view.Gravity.BOTTOM);
            window.setBackgroundDrawableResource(android.R.color.transparent);
            window.setDimAmount(0.4f);
            window.addFlags(android.view.WindowManager.LayoutParams.FLAG_DIM_BEHIND);
        }
        return dialog;
    }

    @Override
    public void onViewCreated(@NonNull View v, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(v, savedInstanceState);
        MainActivity activity = (MainActivity) requireActivity();
        tvEmpty = v.findViewById(R.id.tv_empty);

        RecyclerView rv = v.findViewById(R.id.recycler_devices);
        rv.setLayoutManager(new LinearLayoutManager(requireContext()));
        adapter = new Adapter(device -> {
            activity.connectDevice(device);
            dismissAllowingStateLoss();
        });
        rv.setAdapter(adapter);

        MaterialButton scan = v.findViewById(R.id.btn_scan);
        scan.setOnClickListener(view -> activity.ble().startDiscovery());

        v.findViewById(R.id.btn_close).setOnClickListener(view -> dismissAllowingStateLoss());

        refresh();
        handler.postDelayed(refreshTask, 1500);
    }

    @Override
    public void onDestroyView() {
        handler.removeCallbacks(refreshTask);
        super.onDestroyView();
    }

    private void refresh() {
        MainActivity activity = (MainActivity) getActivity();
        if (activity == null || adapter == null) {
            return;
        }
        // 合并"已配对"和"扫描到"，按 MAC 去重，已配对的优先
        Map<String, BleManager.DeviceInfo> map = new LinkedHashMap<>();
        for (BleManager.DeviceInfo d : activity.ble().getBondedDevices()) {
            map.put(d.address, d);
        }
        for (BleManager.DeviceInfo d : activity.ble().getDiscoveredDevices()) {
            if (!map.containsKey(d.address)) {
                map.put(d.address, d);
            }
        }
        List<BleManager.DeviceInfo> list = new ArrayList<>(map.values());
        adapter.submit(list);
        tvEmpty.setVisibility(list.isEmpty() ? View.VISIBLE : View.GONE);
    }

    // ---------------------------------------------------------------- Adapter

    private interface OnPick {
        void onPick(BleManager.DeviceInfo device);
    }

    private static class Adapter extends RecyclerView.Adapter<Adapter.VH> {

        private final List<BleManager.DeviceInfo> items = new ArrayList<>();
        private final OnPick onPick;

        Adapter(OnPick onPick) {
            this.onPick = onPick;
        }

        void submit(List<BleManager.DeviceInfo> list) {
            items.clear();
            items.addAll(list);
            notifyDataSetChanged();
        }

        @NonNull
        @Override
        public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View v = LayoutInflater.from(parent.getContext())
                    .inflate(R.layout.item_device, parent, false);
            return new VH(v);
        }

        @Override
        public void onBindViewHolder(@NonNull VH h, int position) {
            BleManager.DeviceInfo d = items.get(position);
            h.name.setText(d.name);
            h.addr.setText(d.address);
            h.badge.setText(d.bonded ? R.string.device_bonded : R.string.device_found);
            h.itemView.setOnClickListener(v -> onPick.onPick(d));
        }

        @Override
        public int getItemCount() {
            return items.size();
        }

        static class VH extends RecyclerView.ViewHolder {
            final TextView name;
            final TextView addr;
            final TextView badge;

            VH(@NonNull View v) {
                super(v);
                name = v.findViewById(R.id.tv_name);
                addr = v.findViewById(R.id.tv_addr);
                badge = v.findViewById(R.id.tv_badge);
            }
        }
    }
}
