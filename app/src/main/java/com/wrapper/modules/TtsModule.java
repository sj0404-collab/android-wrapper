package com.wrapper.modules;

import android.content.Context;
import android.speech.tts.TextToSpeech;
import android.speech.tts.UtteranceProgressListener;
import android.util.Log;
import com.wrapper.core.ModuleConfig;
import java.util.HashMap;
import java.util.Locale;

/**
 * Модуль Text-to-Speech
 */
public class TtsModule {
    private static final String TAG = "TtsModule";
    private TextToSpeech tts;
    private boolean initialized = false;
    private ModuleConfig config;
    private TtsListener listener;

    public interface TtsListener {
        void onTtsReady();
        void onTtsError(String error);
        void onUtteranceDone(String utteranceId);
    }

    public TtsModule(Context context, ModuleConfig config, TtsListener listener) {
        this.config = config;
        this.listener = listener;
        initTts(context);
    }

    private void initTts(Context context) {
        tts = new TextToSpeech(context, status -> {
            if (status == TextToSpeech.SUCCESS) {
                setupTts();
            } else {
                Log.e(TAG, "TTS инициализация не удалась");
                if (listener != null) {
                    listener.onTtsError("Initialization failed");
                }
            }
        });
    }

    private void setupTts() {
        String langCode = config.getString("language", "ru-RU");
        Locale locale = Locale.forLanguageTag(langCode);
        int result = tts.setLanguage(locale);

        if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
            Log.w(TAG, "Язык не поддерживается: " + langCode);
            tts.setLanguage(Locale.US);
        }

        float speed = (float) config.getDouble("speed", 1.0);
        float pitch = (float) config.getDouble("pitch", 1.0);
        tts.setSpeechRate(speed);
        tts.setPitch(pitch);

        tts.setOnUtteranceProgressListener(new UtteranceProgressListener() {
            @Override
            public void onStart(String utteranceId) {}

            @Override
            public void onDone(String utteranceId) {
                if (listener != null) {
                    listener.onUtteranceDone(utteranceId);
                }
            }

            @Override
            public void onError(String utteranceId) {
                Log.e(TAG, "TTS ошибка: " + utteranceId);
            }
        });

        initialized = true;
        if (listener != null) {
            listener.onTtsReady();
        }
    }

    /**
     * Озвучить текст
     */
    public void speak(String text) {
        speak(text, "utterance_" + System.currentTimeMillis());
    }

    public void speak(String text, String utteranceId) {
        if (!initialized) {
            Log.w(TAG, "TTS не инициализирован");
            return;
        }

        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.LOLLIPOP) {
            HashMap<String, String> params = new HashMap<>();
            params.put(TextToSpeech.Engine.KEY_PARAM_UTTERANCE_ID, utteranceId);
            tts.speak(text, TextToSpeech.QUEUE_ADD, params);
        } else {
            tts.speak(text, TextToSpeech.QUEUE_ADD, null);
        }
    }

    /**
     * Остановить воспроизведение
     */
    public void stop() {
        if (tts != null) {
            tts.stop();
        }
    }

    /**
     * Проверка инициализации
     */
    public boolean isInitialized() {
        return initialized;
    }

    /**
     * Освобождение ресурсов
     */
    public void shutdown() {
        if (tts != null) {
            tts.stop();
            tts.shutdown();
        }
    }
}
