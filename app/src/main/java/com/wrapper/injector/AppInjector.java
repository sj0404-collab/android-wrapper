package com.wrapper.injector;

import android.content.Context;
import android.content.Intent;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import com.wrapper.core.ModuleConfig;
import com.wrapper.modules.TtsModule;
import com.wrapper.modules.OcrModule;
import com.wrapper.modules.TranslatorModule;
import com.wrapper.runtime.ExtensionRuntime;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class AppInjector implements TtsModule.TtsListener, OcrModule.OcrListener, TranslatorModule.TranslatorListener {
    private static final String TAG = "AppInjector";

    private final ExtensionRuntime runtime;
    private final Context context;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final ExecutorService executor = Executors.newFixedThreadPool(4);

    private TtsModule ttsModule;
    private OcrModule ocrModule;
    private TranslatorModule translatorModule;
    private List<InjectionRule> rules = new ArrayList<>();

    public AppInjector(Context context, ExtensionRuntime runtime) {
        this.context = context;
        this.runtime = runtime;
    }

    public void setTtsModule(TtsModule tts) {
        this.ttsModule = tts;
    }

    public void setOcrModule(OcrModule ocr) {
        this.ocrModule = ocr;
    }

    public void setTranslatorModule(TranslatorModule translator) {
        this.translatorModule = translator;
    }

    public void setRules(List<InjectionRule> rules) {
        this.rules = rules != null ? rules : new ArrayList<>();
    }

    public void addRule(InjectionRule rule) {
        rules.add(rule);
    }

    public void removeRule(InjectionRule rule) {
        rules.remove(rule);
    }

    public List<InjectionRule> getRules() {
        return rules;
    }

    public InjectionRule findMatchingRule(String packageName) {
        for (InjectionRule rule : rules) {
            if (rule.matches(packageName)) {
                return rule;
            }
        }
        return null;
    }

    public void executeRule(InjectionRule rule) {
        if (rule == null) return;
        switch (rule.getAction()) {
            case InjectionRule.ACTION_INJECT_JS:
                executeJsInjection(rule);
                break;
            case InjectionRule.ACTION_OVERLAY:
                executeOverlay(rule);
                break;
            case InjectionRule.ACTION_MODIFY:
                executeModify(rule);
                break;
            case InjectionRule.ACTION_BLOCK:
                executeBlock(rule);
                break;
        }
    }

    private void executeJsInjection(InjectionRule rule) {
        Log.d(TAG, "JS injection for " + rule.getAppPackage() + ": " + rule.getPayload());
        if (runtime != null) {
            runtime.executeScript(rule.getPayload());
        }
    }

    private void executeOverlay(InjectionRule rule) {
        Log.d(TAG, "Overlay for " + rule.getAppPackage());
        Intent intent = new Intent(context, OverlayService.class);
        intent.putExtra("html", rule.getPayload());
        intent.putExtra("x", rule.getX());
        intent.putExtra("y", rule.getY());
        intent.putExtra("width", rule.getWidth() > 0 ? rule.getWidth() : 800);
        intent.putExtra("height", rule.getHeight() > 0 ? rule.getHeight() : 600);
        context.startService(intent);
    }

    private void executeModify(InjectionRule rule) {
        Log.d(TAG, "Modify for " + rule.getAppPackage() + ": " + rule.getTargetText());
    }

    private void executeBlock(InjectionRule rule) {
        Log.d(TAG, "Block for " + rule.getAppPackage());
    }

    public void speak(String text) {
        if (ttsModule != null && ttsModule.isInitialized()) {
            ttsModule.speak(text);
        }
    }

    public void recognizeText(android.graphics.Bitmap bitmap) {
        if (ocrModule != null) {
            ocrModule.recognizeText(bitmap);
        }
    }

    public void translate(String text) {
        if (translatorModule != null) {
            translatorModule.translate(text);
        }
    }

    public void showOverlay(String html, int x, int y, int width, int height) {
        Intent intent = new Intent(context, OverlayService.class);
        intent.putExtra("html", html);
        intent.putExtra("x", x);
        intent.putExtra("y", y);
        intent.putExtra("width", width);
        intent.putExtra("height", height);
        context.startService(intent);
    }

    public void processTextFromApp(String packageName, String text) {
        InjectionRule rule = findMatchingRule(packageName);
        if (rule != null) {
            executeRule(rule);
            return;
        }
        if (ttsModule != null && ttsModule.isInitialized() && text.length() > 2 && text.length() < 500) {
            ttsModule.speak(text);
        }
    }

    @Override
    public void onTtsReady() {
        Log.d(TAG, "TTS ready");
    }

    @Override
    public void onTtsError(String error) {
        Log.e(TAG, "TTS error: " + error);
    }

    @Override
    public void onUtteranceDone(String utteranceId) {
        Log.d(TAG, "TTS done: " + utteranceId);
    }

    @Override
    public void onTextRecognized(String text) {
        Log.d(TAG, "OCR recognized: " + text);
        if (translatorModule != null) {
            translatorModule.translate(text);
        }
    }

    @Override
    public void onOcrError(String error) {
        Log.e(TAG, "OCR error: " + error);
    }

    @Override
    public void onTranslationComplete(String translatedText) {
        Log.d(TAG, "Translation: " + translatedText);
        if (ttsModule != null && ttsModule.isInitialized()) {
            ttsModule.speak(translatedText);
        }
    }

    @Override
    public void onTranslationError(String error) {
        Log.e(TAG, "Translation error: " + error);
    }

    public void shutdown() {
        executor.shutdownNow();
        if (ttsModule != null) ttsModule.shutdown();
        if (ocrModule != null) ocrModule.close();
    }
}
