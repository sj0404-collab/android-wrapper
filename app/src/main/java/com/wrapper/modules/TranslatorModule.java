package com.wrapper.modules;

import android.content.Context;
import android.util.Log;
import com.wrapper.core.ModuleConfig;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class TranslatorModule {
    private static final String TAG = "TranslatorModule";
    private ModuleConfig config;
    private TranslatorListener listener;
    private final ExecutorService executor = Executors.newFixedThreadPool(2);

    public interface TranslatorListener {
        void onTranslationComplete(String translatedText);
        void onTranslationError(String error);
    }

    public TranslatorModule(Context context, ModuleConfig config, TranslatorListener listener) {
        this.config = config;
        this.listener = listener;
    }

    public void translate(String text) {
        String sourceLang = config.getString("source_lang", "auto");
        String targetLang = config.getString("target_lang", "ru");
        translate(text, sourceLang, targetLang);
    }

    public void translate(String text, final String sourceLang, final String targetLang) {
        final String encodedText;
        try {
            encodedText = URLEncoder.encode(text, "UTF-8");
        } catch (Exception e) {
            if (listener != null) listener.onTranslationError("Encode error");
            return;
        }

        executor.submit(() -> {
            try {
                String urlStr = String.format(
                    "https://translate.googleapis.com/translate_a/single?client=gtx&sl=%s&tl=%s&dt=t&q=%s",
                    sourceLang, targetLang, encodedText
                );

                URL url = new URL(urlStr);
                HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                conn.setRequestMethod("GET");
                conn.setConnectTimeout(10000);
                conn.setReadTimeout(10000);
                conn.setRequestProperty("User-Agent", "AndroidWrapper/2.0");

                int responseCode = conn.getResponseCode();
                if (responseCode != 200) {
                    if (listener != null) listener.onTranslationError("HTTP " + responseCode);
                    return;
                }

                BufferedReader reader = new BufferedReader(new InputStreamReader(conn.getInputStream()));
                StringBuilder response = new StringBuilder();
                String line;
                while ((line = reader.readLine()) != null) {
                    response.append(line);
                }
                reader.close();

                String translated = parseGoogleTranslateResponse(response.toString());
                Log.d(TAG, "Translation: " + translated);

                if (listener != null) {
                    listener.onTranslationComplete(translated);
                }
            } catch (Exception e) {
                Log.e(TAG, "Translation error", e);
                if (listener != null) {
                    listener.onTranslationError(e.getMessage());
                }
            }
        });
    }

    private String parseGoogleTranslateResponse(String response) {
        try {
            JSONArray outer = new JSONArray(response);
            JSONArray sentences = outer.getJSONArray(0);
            StringBuilder result = new StringBuilder();
            for (int i = 0; i < sentences.length(); i++) {
                JSONArray sentence = sentences.getJSONArray(i);
                if (sentence.length() > 0) {
                    result.append(sentence.getString(0));
                }
            }
            String translated = result.toString().trim();
            if (translated.isEmpty()) {
                return response;
            }
            return translated;
        } catch (Exception e) {
            int start = response.indexOf("\"");
            int end = response.indexOf("\"", start + 1);
            if (start != -1 && end != -1 && end > start) {
                return response.substring(start + 1, end);
            }
            return response;
        }
    }

    public void detectLanguage(String text) {
        executor.submit(() -> {
            try {
                String encodedText = URLEncoder.encode(text, "UTF-8");
                String urlStr = String.format(
                    "https://translate.googleapis.com/translate_a/single?client=gtx&sl=auto&tl=en&dt=t&q=%s",
                    encodedText
                );

                URL url = new URL(urlStr);
                HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                conn.setRequestMethod("GET");
                conn.setConnectTimeout(10000);

                BufferedReader reader = new BufferedReader(new InputStreamReader(conn.getInputStream()));
                StringBuilder response = new StringBuilder();
                String line;
                while ((line = reader.readLine()) != null) {
                    response.append(line);
                }
                reader.close();

                JSONArray outer = new JSONArray(response.toString());
                if (outer.length() > 2) {
                    String detectedLang = outer.getString(2);
                    Log.d(TAG, "Detected language: " + detectedLang);
                }
            } catch (Exception e) {
                Log.e(TAG, "Language detection error", e);
            }
        });
    }

    public void shutdown() {
        executor.shutdownNow();
    }
}
