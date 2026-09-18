package com.signlanguage.app.speech;

import android.content.Context;
import android.media.AudioAttributes;
import android.media.AudioManager;
import android.os.Bundle;
import android.speech.tts.TextToSpeech;
import android.speech.tts.UtteranceProgressListener;
import android.speech.tts.Voice;
import android.text.TextUtils;

import com.signlanguage.app.util.LogUtil;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * 朗读（Android 系统 TTS）+ 音量控制。
 *
 * <p>音量走 {@link AudioManager#STREAM_MUSIC}（媒体音量），所以这里的滑块和手机的
 * 音量键是同一套，朗读音量改一下手机音量也会跟着变，用户不会困惑。
 *
 * <p>朗读前会请求 AUDIOFOCUS_GAIN_TRANSIENT，避免和正在放的音乐打架。
 */
public class SpeechHelper {

    public interface Listener {
        /** TTS 引擎就绪状态变化 */
        void onReadyChanged(boolean ready);

        /** 开始朗读某段文字 */
        void onSpeakStart(String text);

        /** 朗读结束（或被打断） */
        void onSpeakDone();
    }

    public static final float MIN_RATE = 0.5f;
    public static final float MAX_RATE = 2.0f;

    private final Context appContext;
    private final AudioManager audioManager;
    private final List<Listener> listeners = new ArrayList<>();

    private TextToSpeech tts;
    private volatile boolean ready = false;
    private float rate = 1.0f;
    private float pitch = 1.0f;
    private String pendingText;
    private String enginePackage = "";

    public SpeechHelper(Context context) {
        appContext = context.getApplicationContext();
        audioManager = (AudioManager) appContext.getSystemService(Context.AUDIO_SERVICE);
    }

    // ---------------------------------------------------------------- 初始化

    public void init() {
        if (tts != null) {
            return;
        }
        tts = new TextToSpeech(appContext, status -> {
            if (status == TextToSpeech.SUCCESS) {
                ready = true;
                applyLanguage();
                applyRate();
                tts.setPitch(pitch);
                tts.setOnUtteranceProgressListener(progressListener);
                LogUtil.i("TTS 引擎就绪: " + describeEngine());
                notifyReady(true);
                if (pendingText != null) {
                    String t = pendingText;
                    pendingText = null;
                    speak(t);
                }
            } else {
                ready = false;
                LogUtil.e("TTS 初始化失败，status=" + status + "（手机上可能没装语音引擎）");
                notifyReady(false);
            }
        });
    }

    private final UtteranceProgressListener progressListener = new UtteranceProgressListener() {
        @Override
        public void onStart(String utteranceId) {
            LogUtil.i("开始朗读: " + utteranceId);
        }

        @Override
        public void onDone(String utteranceId) {
            abandonFocus();
            notifyDone();
        }

        @Override
        public void onError(String utteranceId) {
            LogUtil.w("朗读出错: " + utteranceId);
            abandonFocus();
            notifyDone();
        }

        @Override
        public void onError(String utteranceId, int errorCode) {
            LogUtil.w("朗读出错(" + errorCode + "): " + utteranceId);
            abandonFocus();
            notifyDone();
        }

        @Override
        public void onStop(String utteranceId, boolean interrupted) {
            abandonFocus();
            notifyDone();
        }
    };

    private String describeEngine() {
        if (tts == null) {
            return "null";
        }
        try {
            Voice v = tts.getVoice();
            return (v != null ? v.getName() : "default") + " / 语言=" + v;
        } catch (Exception e) {
            return "unknown";
        }
    }

    /** 可用语音引擎列表，返回 "包名|显示名" */
    public List<String> availableEngines() {
        List<String> result = new ArrayList<>();
        if (tts == null) {
            return result;
        }
        try {
            for (TextToSpeech.EngineInfo info : tts.getEngines()) {
                result.add(info.name + "|" + info.label);
            }
        } catch (Exception e) {
            LogUtil.e("读取TTS引擎列表失败", e);
        }
        return result;
    }

    /** 切换语音引擎（部分手机默认引擎没装中文语音包时需要手动换） */
    public void setEngine(String packageName, float rate, float pitch) {
        this.rate = rate;
        this.pitch = pitch;
        enginePackage = packageName == null ? "" : packageName;
        if (tts != null) {
            tts.shutdown();
            tts = null;
            ready = false;
            notifyReady(false);
        }
        if (TextUtils.isEmpty(enginePackage)) {
            init();
        } else {
            tts = new TextToSpeech(appContext, status -> {
                ready = status == TextToSpeech.SUCCESS;
                if (ready) {
                    applyLanguage();
                    applyRate();
                    tts.setPitch(this.pitch);
                    tts.setOnUtteranceProgressListener(progressListener);
                    LogUtil.i("已切换TTS引擎: " + enginePackage);
                } else {
                    LogUtil.e("切换TTS引擎失败: " + enginePackage);
                }
                notifyReady(ready);
            }, enginePackage);
        }
    }

    // ---------------------------------------------------------------- 语言/参数

    private void applyLanguage() {
        if (tts == null) {
            return;
        }
        try {
            int r = tts.setLanguage(Locale.SIMPLIFIED_CHINESE);
            if (r == TextToSpeech.LANG_MISSING_DATA || r == TextToSpeech.LANG_NOT_SUPPORTED) {
                LogUtil.w("当前TTS引擎缺少中文语音包，尝试用系统默认语言");
                int r2 = tts.setLanguage(Locale.getDefault());
                if (r2 == TextToSpeech.LANG_MISSING_DATA
                        || r2 == TextToSpeech.LANG_NOT_SUPPORTED) {
                    LogUtil.e("系统默认语言也不支持，请在 手机设置-辅助功能-文字转语音 里安装中文语音数据");
                }
            }
            try {
                Set<Voice> voices = tts.getVoices();
                if (voices != null) {
                    for (Voice v : voices) {
                        if (v.getLocale() != null
                                && "zh".equalsIgnoreCase(v.getLocale().getLanguage())) {
                            tts.setVoice(v);
                            LogUtil.i("选用中文语音: " + v.getName());
                            break;
                        }
                    }
                }
            } catch (Exception ignored) {
            }
        } catch (Exception e) {
            LogUtil.e("设置TTS语言失败", e);
        }
    }

    private void applyRate() {
        if (tts != null) {
            tts.setSpeechRate(rate);
        }
    }

    /** 语速倍率 0.5-2.0 */
    public void setRate(float r) {
        rate = Math.max(MIN_RATE, Math.min(MAX_RATE, r));
        applyRate();
    }

    public float getRate() {
        return rate;
    }

    public void setPitch(float p) {
        pitch = Math.max(0.5f, Math.min(2.0f, p));
        if (tts != null) {
            tts.setPitch(pitch);
        }
    }

    public boolean isReady() {
        return ready;
    }

    // ---------------------------------------------------------------- 朗读

    /**
     * 朗读一段文字。
     *
     * @param text 中文文本
     * @return true 表示已经交给 TTS
     */
    public boolean speak(String text) {
        if (TextUtils.isEmpty(text)) {
            return false;
        }
        if (tts == null) {
            LogUtil.w("TTS 还没初始化，已缓存待朗读文本: " + text);
            pendingText = text;
            init();
            return false;
        }
        if (!ready) {
            pendingText = text;
            LogUtil.w("TTS 尚未就绪，已缓存待朗读文本: " + text);
            return false;
        }
        requestFocus();
        int mode = TextToSpeech.QUEUE_FLUSH;
        Bundle params = new Bundle();
        // 让朗读走媒体音量通道（默认行为，写出来更明确）
        params.putInt(TextToSpeech.Engine.KEY_PARAM_STREAM, AudioManager.STREAM_MUSIC);
        params.putFloat(TextToSpeech.Engine.KEY_PARAM_VOLUME, 1.0f);
        int r = tts.speak(text, mode, params, text);
        if (r == TextToSpeech.ERROR) {
            LogUtil.e("朗读调用失败: " + text);
            abandonFocus();
            return false;
        }
        for (Listener l : new ArrayList<>(listeners)) {
            l.onSpeakStart(text);
        }
        return true;
    }

    public void stop() {
        if (tts != null) {
            tts.stop();
        }
    }

    public void shutdown() {
        if (tts != null) {
            tts.stop();
            tts.shutdown();
            tts = null;
            ready = false;
        }
    }

    // ---------------------------------------------------------------- 音量

    /** 当前媒体音量百分比 0-100 */
    public int getVolumePercent() {
        if (audioManager == null) {
            return 0;
        }
        int max = Math.max(1, audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC));
        int cur = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC);
        return Math.round(cur * 100f / max);
    }

    /**
     * 设置媒体音量百分比 0-100。
     *
     * @param percent 0-100
     * @param showUi  是否弹出系统音量条
     */
    public void setVolumePercent(int percent, boolean showUi) {
        if (audioManager == null) {
            return;
        }
        int max = Math.max(1, audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC));
        int target = Math.round(Math.max(0, Math.min(100, percent)) * max / 100f);
        try {
            audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, target,
                    showUi ? AudioManager.FLAG_SHOW_UI : 0);
        } catch (SecurityException e) {
            // 部分机型在勿扰模式下会拒绝，退化成弹系统音量条让用户自己调
            LogUtil.w("直接设置媒体音量被拒绝（可能开了免打扰）");
            try {
                audioManager.adjustStreamVolume(AudioManager.STREAM_MUSIC,
                        AudioManager.ADJUST_SAME, AudioManager.FLAG_SHOW_UI);
            } catch (Exception ignored) {
            }
        }
    }

    private void requestFocus() {
        if (audioManager == null) {
            return;
        }
        try {
            audioManager.requestAudioFocus(null, AudioManager.STREAM_MUSIC,
                    AudioManager.AUDIOFOCUS_GAIN_TRANSIENT);
        } catch (Exception ignored) {
        }
    }

    private void abandonFocus() {
        if (audioManager == null) {
            return;
        }
        try {
            audioManager.abandonAudioFocus(null);
        } catch (Exception ignored) {
        }
    }

    // ---------------------------------------------------------------- 监听

    public void addListener(Listener l) {
        if (l != null && !listeners.contains(l)) {
            listeners.add(l);
        }
    }

    public void removeListener(Listener l) {
        listeners.remove(l);
    }

    private void notifyReady(boolean r) {
        for (Listener l : new ArrayList<>(listeners)) {
            l.onReadyChanged(r);
        }
    }

    private void notifyDone() {
        for (Listener l : new ArrayList<>(listeners)) {
            l.onSpeakDone();
        }
    }
}
