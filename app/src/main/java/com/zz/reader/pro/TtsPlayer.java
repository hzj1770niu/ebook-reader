package com.zz.reader.pro;

import android.content.Context;
import android.os.Build;
import android.os.Bundle;
import android.speech.tts.TextToSpeech;
import android.speech.tts.UtteranceProgressListener;

import java.util.List;
import java.util.Locale;

/** 系统语音朗读封装：逐句排队朗读，回调当前句子序号 */
public class TtsPlayer {

    public interface Listener {
        void onReady(boolean ok, String message);

        void onSpeakingSentence(int index);

        void onFinishedAll();
    }

    private TextToSpeech tts;
    private boolean ready;
    private Listener listener;
    private int currentIndex = -1;
    private int lastIndex = -1;
    private boolean speaking;
    private int readyState = -1;
    private String readyMsg = "";

    public TtsPlayer(Context context) {
        tts = new TextToSpeech(context.getApplicationContext(), status -> {
            if (status == TextToSpeech.SUCCESS) {
                // 手机上的语音引擎对"中文"的语言代码支持不一致，按顺序试三种写法
                int r = tts.setLanguage(Locale.SIMPLIFIED_CHINESE);
                if (r == TextToSpeech.LANG_MISSING_DATA || r == TextToSpeech.LANG_NOT_SUPPORTED) {
                    r = tts.setLanguage(Locale.CHINA);
                }
                if (r == TextToSpeech.LANG_MISSING_DATA || r == TextToSpeech.LANG_NOT_SUPPORTED) {
                    r = tts.setLanguage(Locale.getDefault());
                }
                ready = r != TextToSpeech.LANG_MISSING_DATA && r != TextToSpeech.LANG_NOT_SUPPORTED;
                if (ready) {
                    tts.setOnUtteranceProgressListener(new UtteranceProgressListener() {
                        @Override
                        public void onStart(String utteranceId) {
                            int idx = parseIndex(utteranceId);
                            currentIndex = idx;
                            if (listener != null) listener.onSpeakingSentence(idx);
                        }

                        @Override
                        public void onDone(String utteranceId) {
                            int idx = parseIndex(utteranceId);
                            if (idx == lastIndex) {
                                speaking = false;
                                currentIndex = -1;
                                if (listener != null) listener.onFinishedAll();
                            }
                        }

                        @Override
                        public void onError(String utteranceId) {
                            speaking = false;
                            currentIndex = -1;
                            if (listener != null) listener.onFinishedAll();
                        }
                    });
                    readyState = 1;
                    readyMsg = "语音引擎已就绪";
                    if (listener != null) listener.onReady(true, readyMsg);
                } else {
                    readyState = 0;
                    readyMsg = "手机里没有可用的中文语音引擎，朗读功能暂时用不了。"
                            + "请到系统设置里安装或开启文字转语音（TTS）功能。";
                    if (listener != null) listener.onReady(false, readyMsg);
                }
            } else {
                readyState = 0;
                readyMsg = "语音引擎启动失败，朗读功能用不了";
                if (listener != null) listener.onReady(false, readyMsg);
            }
        });
    }

    /** 界面可能比引擎晚一步注册监听，这里把已经出来的结果补发一次 */
    public void setListener(Listener l) {
        this.listener = l;
        if (l != null && readyState >= 0) l.onReady(readyState == 1, readyMsg);
    }

    public boolean isReady() {
        return ready;
    }

    public boolean isSpeaking() {
        return speaking || (tts != null && tts.isSpeaking());
    }

    public void setSpeed(float speed) {
        if (tts != null) tts.setSpeechRate(speed);
    }

    /**
     * 从 startIndex 开始朗读这批句子；会先清空之前的朗读队列。
     */
    public void speak(List<String> sentences, int startIndex, float speed) {
        if (tts == null) return;
        tts.stop();
        if (sentences == null || sentences.isEmpty()) {
            if (listener != null) listener.onFinishedAll();
            return;
        }
        if (startIndex < 0) startIndex = 0;
        if (startIndex >= sentences.size()) startIndex = sentences.size() - 1;
        tts.setSpeechRate(speed);
        speaking = true;
        currentIndex = startIndex;
        lastIndex = -1;
        for (int i = startIndex; i < sentences.size(); i++) {
            String text = sentences.get(i);
            if (text == null || text.trim().isEmpty()) continue;
            Bundle params = new Bundle();
            int mode = lastIndex < 0 ? TextToSpeech.QUEUE_FLUSH : TextToSpeech.QUEUE_ADD;
            String id = i + "#" + sentences.size();
            tts.speak(text, mode, params, id);
            lastIndex = i;
        }
    }

    public void stop() {
        if (tts != null) tts.stop();
        speaking = false;
        currentIndex = -1;
    }

    public int currentIndex() {
        return currentIndex;
    }

    public void shutdown() {
        if (tts != null) {
            tts.stop();
            tts.shutdown();
            tts = null;
        }
        ready = false;
    }

    private int parseIndex(String utteranceId) {
        if (utteranceId == null) return -1;
        int hash = utteranceId.indexOf('#');
        if (hash < 0) return -1;
        try {
            return Integer.parseInt(utteranceId.substring(0, hash));
        } catch (Exception e) {
            return -1;
        }
    }
}
