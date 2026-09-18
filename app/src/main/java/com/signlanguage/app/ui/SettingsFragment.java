package com.signlanguage.app.ui;

import android.os.Bundle;
import android.text.TextUtils;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.fragment.app.Fragment;

import com.google.android.material.materialswitch.MaterialSwitch;
import com.google.android.material.slider.Slider;
import com.google.android.material.textfield.TextInputEditText;
import com.signlanguage.app.MainActivity;
import com.signlanguage.app.R;
import com.signlanguage.app.util.LogUtil;
import com.signlanguage.app.util.Prefs;

import java.util.ArrayList;
import java.util.List;

/**
 * 设置页：朗读参数、蓝牙连接选项、演示模式、编码切换、自定义指令和通信日志。
 */
public class SettingsFragment extends Fragment implements LogUtil.Listener {

    private MainActivity activity;

    private TextView tvRate;
    private Slider sliderRate;
    private MaterialSwitch switchAutoRead;
    private TextView tvTtsEngine;
    private TextView tvPollInterval;
    private Slider sliderPoll;
    private TextView tvLastDevice;
    private TextInputEditText etCmd;
    private TextView tvLog;
    private ScrollView scrollLog;
    private MaterialSwitch switchLog;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_settings, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View v, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(v, savedInstanceState);
        activity = (MainActivity) requireActivity();
        Prefs prefs = activity.prefs();

        tvRate = v.findViewById(R.id.tv_rate);
        sliderRate = v.findViewById(R.id.slider_rate);
        switchAutoRead = v.findViewById(R.id.switch_auto_read_long);
        tvTtsEngine = v.findViewById(R.id.tv_tts_engine);
        tvPollInterval = v.findViewById(R.id.tv_poll_interval);
        sliderPoll = v.findViewById(R.id.slider_poll);
        tvLastDevice = v.findViewById(R.id.tv_last_device);
        etCmd = v.findViewById(R.id.et_cmd);
        tvLog = v.findViewById(R.id.tv_log);
        scrollLog = v.findViewById(R.id.scroll_log);
        switchLog = v.findViewById(R.id.switch_log);

        // ---------------- 朗读 ----------------
        float rate = prefs.getSpeechRate();
        sliderRate.setValue(clamp(rate, 0.5f, 2.0f));
        tvRate.setText(getString(R.string.rate_value, rate));
        activity.speech().setRate(rate);
        sliderRate.addOnChangeListener((slider, value, fromUser) -> {
            tvRate.setText(getString(R.string.rate_value, value));
            if (fromUser) {
                prefs.setSpeechRate(value);
                activity.speech().setRate(value);
            }
        });

        switchAutoRead.setChecked(prefs.isAutoRead());
        switchAutoRead.setOnCheckedChangeListener((b, c) -> prefs.setAutoRead(c));

        v.findViewById(R.id.btn_test_speak).setOnClickListener(
                view -> activity.speech().speak(getString(R.string.tts_test_text)));

        tvTtsEngine.setText(engineLabel(prefs.getTtsEngine()));
        v.findViewById(R.id.btn_pick_engine).setOnClickListener(view -> pickEngine());

        // ---------------- 连接 ----------------
        MaterialSwitch switchReconnect = v.findViewById(R.id.switch_reconnect);
        switchReconnect.setChecked(prefs.isAutoReconnect());
        switchReconnect.setOnCheckedChangeListener((b, c) -> {
            prefs.setAutoReconnect(c);
            activity.ble().applyPrefs(prefs);
        });

        MaterialSwitch switchPoll = v.findViewById(R.id.switch_poll);
        switchPoll.setChecked(prefs.isPollVitals());
        switchPoll.setOnCheckedChangeListener((b, c) -> {
            prefs.setPollVitals(c);
            activity.ble().applyPrefs(prefs);
        });

        int interval = prefs.getPollIntervalSec();
        sliderPoll.setValue(clamp(interval, 3, 60));
        tvPollInterval.setText(getString(R.string.seconds_value, interval));
        sliderPoll.addOnChangeListener((slider, value, fromUser) -> {
            int s = Math.round(value);
            tvPollInterval.setText(getString(R.string.seconds_value, s));
            if (fromUser) {
                prefs.setPollIntervalSec(s);
                activity.ble().applyPrefs(prefs);
            }
        });

        updateLastDevice();
        v.findViewById(R.id.btn_connect_last).setOnClickListener(view -> {
            String mac = prefs.getLastMac();
            if (TextUtils.isEmpty(mac)) {
                Toast.makeText(requireContext(), R.string.device_not_selected, Toast.LENGTH_SHORT).show();
                return;
            }
            activity.connectDevice(new com.signlanguage.app.ble.BleManager.DeviceInfo(
                    mac, prefs.getLastName(), true));
        });

        // ---------------- 高级 ----------------
        MaterialSwitch switchDemo = v.findViewById(R.id.switch_demo);
        switchDemo.setChecked(activity.ble().isDemoMode());
        switchDemo.setOnCheckedChangeListener((b, c) -> activity.ble().setDemoMode(c));

        MaterialSwitch switchUtf8 = v.findViewById(R.id.switch_utf8);
        switchUtf8.setChecked(Prefs.ENC_UTF8.equals(prefs.getEncoding()));
        switchUtf8.setOnCheckedChangeListener((b, c) -> {
            prefs.setEncoding(c ? Prefs.ENC_UTF8 : Prefs.ENC_GB2312);
            activity.ble().applyPrefs(prefs);
            Toast.makeText(requireContext(),
                    c ? R.string.toast_utf8_on : R.string.toast_gb2312_on,
                    Toast.LENGTH_SHORT).show();
        });

        switchLog.setChecked(prefs.isLogEnabled());
        applyLogVisibility(prefs.isLogEnabled());
        switchLog.setOnCheckedChangeListener((b, c) -> {
            prefs.setLogEnabled(c);
            applyLogVisibility(c);
        });

        v.findViewById(R.id.btn_send).setOnClickListener(view -> {
            String cmd = etCmd.getText() == null ? "" : etCmd.getText().toString().trim();
            if (TextUtils.isEmpty(cmd)) {
                return;
            }
            activity.ble().send(cmd);
            Toast.makeText(requireContext(), getString(R.string.toast_sent, cmd),
                    Toast.LENGTH_SHORT).show();
        });
        v.findViewById(R.id.btn_send_hr).setOnClickListener(view -> {
            etCmd.setText("HR?");
            activity.ble().send("HR?");
        });
        v.findViewById(R.id.btn_send_play).setOnClickListener(view -> {
            etCmd.setText("PLAY0011");
            activity.ble().send("PLAY0011");
        });
        v.findViewById(R.id.btn_clear_log).setOnClickListener(view -> {
            LogUtil.clear();
            renderLog();
        });

        renderLog();
    }

    @Override
    public void onResume() {
        super.onResume();
        LogUtil.setListener(this);
        updateLastDevice();
    }

    @Override
    public void onPause() {
        LogUtil.setListener(null);
        super.onPause();
    }

    private void updateLastDevice() {
        MainActivity a = (MainActivity) getActivity();
        if (a == null || tvLastDevice == null) {
            return;
        }
        String name = a.prefs().getLastName();
        tvLastDevice.setText(TextUtils.isEmpty(name)
                ? getString(R.string.label_last_device_none)
                : getString(R.string.label_last_device, name + " / " + a.prefs().getLastMac()));
    }

    // ---------------------------------------------------------------- 语音引擎

    private void pickEngine() {
        List<String> engines = activity.speech().availableEngines();
        if (engines.isEmpty()) {
            Toast.makeText(requireContext(), R.string.tts_no_engine, Toast.LENGTH_LONG).show();
            return;
        }
        final String[] labels = new String[engines.size() + 1];
        final String[] packages = new String[engines.size() + 1];
        labels[0] = getString(R.string.tts_engine_default);
        packages[0] = "";
        for (int i = 0; i < engines.size(); i++) {
            String[] parts = engines.get(i).split("\\|", 2);
            packages[i + 1] = parts[0];
            labels[i + 1] = parts.length > 1 ? parts[1] + "\n" + parts[0] : parts[0];
        }
        new AlertDialog.Builder(requireContext())
                .setTitle(R.string.label_tts_engine)
                .setItems(labels, (dialog, which) -> {
                    activity.prefs().setTtsEngine(packages[which]);
                    activity.speech().setEngine(packages[which],
                            activity.prefs().getSpeechRate(), 1.0f);
                    tvTtsEngine.setText(engineLabel(packages[which]));
                })
                .show();
    }

    private String engineLabel(String pkg) {
        if (TextUtils.isEmpty(pkg)) {
            return getString(R.string.tts_engine_default);
        }
        return pkg;
    }

    // ---------------------------------------------------------------- 日志

    private void applyLogVisibility(boolean visible) {
        scrollLog.setVisibility(visible ? View.VISIBLE : View.GONE);
        tvLog.setVisibility(visible ? View.VISIBLE : View.GONE);
        if (visible) {
            LogUtil.setListener(this);
            renderLog();
        }
    }

    private void renderLog() {
        if (tvLog == null) {
            return;
        }
        List<String> lines = LogUtil.snapshot();
        if (lines.isEmpty()) {
            tvLog.setText(R.string.log_empty_hint);
            return;
        }
        StringBuilder sb = new StringBuilder();
        for (String l : lines) {
            sb.append(l).append('\n');
        }
        tvLog.setText(sb.toString());
        scrollLog.post(() -> scrollLog.fullScroll(View.FOCUS_DOWN));
    }

    @Override
    public void onLog(String line) {
        if (tvLog == null || !isAdded()) {
            return;
        }
        CharSequence cur = tvLog.getText();
        String text = (cur == null ? "" : cur.toString());
        if (text.equals(getString(R.string.log_empty_hint))) {
            text = "";
        }
        text = text + line + "\n";
        // 只保留最后 300 行，避免 TextView 过长
        String[] arr = text.split("\n");
        if (arr.length > 300) {
            StringBuilder sb = new StringBuilder();
            for (int i = arr.length - 300; i < arr.length; i++) {
                sb.append(arr[i]).append('\n');
            }
            text = sb.toString();
        }
        final String out = text;
        tvLog.post(() -> {
            tvLog.setText(out);
            scrollLog.fullScroll(View.FOCUS_DOWN);
        });
    }

    private static float clamp(float v, float min, float max) {
        return Math.max(min, Math.min(max, v));
    }
}
