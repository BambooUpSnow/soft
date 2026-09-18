package com.signlanguage.app.ui;

import android.content.res.ColorStateList;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.Fragment;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.slider.Slider;
import com.google.android.material.materialswitch.MaterialSwitch;
import com.signlanguage.app.MainActivity;
import com.signlanguage.app.R;
import com.signlanguage.app.ble.BleManager;

/**
 * 实时监测页：连接状态、手势识别结果、心率血氧、朗读音量。
 */
public class MonitorFragment extends Fragment implements MainActivity.UiListener {

    private TextView tvState;
    private TextView tvDevice;
    private View dotState;
    private TextView tvGesture;
    private TextView tvHr;
    private TextView tvSpo2;
    private TextView tvVitalsNote;
    private TextView tvVolume;
    private Slider sliderVolume;
    private MaterialSwitch switchAutoRead;
    private MaterialButton btnReplay;

    private MainActivity activity;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_monitor, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View v, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(v, savedInstanceState);
        activity = (MainActivity) requireActivity();

        tvState = v.findViewById(R.id.tv_state);
        tvDevice = v.findViewById(R.id.tv_device);
        dotState = v.findViewById(R.id.dot_state);
        tvGesture = v.findViewById(R.id.tv_gesture);
        tvHr = v.findViewById(R.id.tv_hr);
        tvSpo2 = v.findViewById(R.id.tv_spo2);
        tvVitalsNote = v.findViewById(R.id.tv_vitals_note);
        tvVolume = v.findViewById(R.id.tv_volume);
        sliderVolume = v.findViewById(R.id.slider_volume);
        switchAutoRead = v.findViewById(R.id.switch_auto_read);
        btnReplay = v.findViewById(R.id.btn_replay);

        v.findViewById(R.id.btn_pick).setOnClickListener(view -> {
            if (activity.ble().isConnected()) {
                // 已连接时点它 = 断开
                activity.disconnectDevice();
                return;
            }
            new DevicePickerDialog().show(getParentFragmentManager(), "devices");
        });

        v.findViewById(R.id.btn_refresh).setOnClickListener(view -> {
            if (activity.ble().isDemoMode() || activity.ble().isConnected()) {
                activity.requestVitals();
            } else {
                Toast.makeText(requireContext(), R.string.state_idle, Toast.LENGTH_SHORT).show();
            }
        });

        btnReplay.setOnClickListener(view -> {
            String g = activity.getCurrentGesture();
            if (!TextUtils.isEmpty(g)) {
                activity.speak(g);
            }
        });

        // 音量滑块 -> 媒体音量
        sliderVolume.addOnChangeListener((slider, value, fromUser) -> {
            int p = Math.round(value);
            tvVolume.setText(getString(R.string.volume_percent, p));
            if (fromUser) {
                activity.prefs().setVolumePercent(p);
                activity.speech().setVolumePercent(p, true);
            }
        });
        sliderVolume.addOnSliderTouchListener(new Slider.OnSliderTouchListener() {
            @Override
            public void onStartTrackingTouch(@NonNull Slider slider) {
            }

            @Override
            public void onStopTrackingTouch(@NonNull Slider slider) {
                // 松手时用新音量试听一下，让用户马上听到效果
                String g = activity.getCurrentGesture();
                activity.speech().speak(TextUtils.isEmpty(g) ? "音量已调整" : g);
            }
        });

        switchAutoRead.setChecked(activity.prefs().isAutoRead());
        switchAutoRead.setOnCheckedChangeListener((buttonView, isChecked) ->
                activity.prefs().setAutoRead(isChecked));

        activity.registerUiListener(this);
        bindAll();
    }

    @Override
    public void onDestroyView() {
        activity.unregisterUiListener(this);
        super.onDestroyView();
    }

    /** 进入页面时把当前状态刷一遍 */
    private void bindAll() {
        onBleState(activity.ble().getState(), activity.ble().getDeviceName(),
                activity.getLastStateMessage());
        String g = activity.getCurrentGesture();
        if (!TextUtils.isEmpty(g)) {
            onGesture(g, g);
        }
        onVitals(activity.getCurrentHr(), activity.getCurrentSpo2(), "");
        onVolumeChanged(activity.speech().getVolumePercent());
    }

    // ---------------------------------------------------------------- UI 更新

    @Override
    public void onBleState(BleManager.State state, String deviceName, String message) {
        if (tvState == null) {
            return;
        }
        int color;
        int textRes;
        switch (state) {
            case CONNECTED:
                color = R.color.ok;
                textRes = activity.ble().isDemoMode() ? R.string.state_demo : R.string.state_connected;
                break;
            case CONNECTING:
                color = R.color.brand;
                textRes = R.string.state_connecting;
                break;
            default:
                color = R.color.on_surface_variant;
                textRes = R.string.state_idle;
                break;
        }
        tvState.setText(textRes);
        int c = ContextCompat.getColor(requireContext(), color);
        dotState.setBackgroundTintList(ColorStateList.valueOf(c));
        tvState.setTextColor(c);

        // 已连接时按钮变成"断开"
        MaterialButton pick = requireView().findViewById(R.id.btn_pick);
        pick.setText(state == BleManager.State.CONNECTED
                ? R.string.btn_disconnect : R.string.btn_pick_device);

        if (state == BleManager.State.CONNECTED) {
            tvDevice.setText(TextUtils.isEmpty(message)
                    ? getString(R.string.device_not_selected) : message);
        } else {
            tvDevice.setText(TextUtils.isEmpty(message) ? getString(R.string.device_not_selected)
                    : message);
        }
    }

    @Override
    public void onGesture(String gesture, String raw) {
        if (tvGesture == null) {
            return;
        }
        tvGesture.setText(gesture);
        btnReplay.setEnabled(true);
    }

    @Override
    public void onVitals(Integer heartRate, Integer spo2, String raw) {
        if (tvHr == null) {
            return;
        }
        tvHr.setText(heartRate == null ? getString(R.string.value_placeholder)
                : String.valueOf(heartRate));
        tvSpo2.setText(spo2 == null ? getString(R.string.value_placeholder)
                : String.valueOf(spo2));

        boolean fresh = activity.isVitalsFresh();
        if (heartRate == null && spo2 == null) {
            tvVitalsNote.setText(R.string.vitals_no_data);
        } else if (!fresh) {
            tvVitalsNote.setText(R.string.vitals_stale);
        } else {
            tvVitalsNote.setText(getString(R.string.vitals_updated,
                    android.text.format.DateFormat.format("HH:mm:ss", System.currentTimeMillis())));
        }
    }

    @Override
    public void onStatus(String text) {
        // 状态类消息只进日志，不打断界面
    }

    @Override
    public void onVolumeChanged(int percent) {
        if (sliderVolume == null) {
            return;
        }
        sliderVolume.setValue(Math.max(0, Math.min(100, percent)));
        tvVolume.setText(getString(R.string.volume_percent, percent));
    }
}
