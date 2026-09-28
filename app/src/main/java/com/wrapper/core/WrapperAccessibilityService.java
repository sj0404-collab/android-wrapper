package com.wrapper.core;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.AccessibilityServiceInfo;
import android.content.Intent;
import android.os.Bundle;
import android.util.Log;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import com.wrapper.modules.TtsModule;
import com.wrapper.modules.OcrModule;
import com.wrapper.modules.TranslatorModule;
import com.wrapper.injector.AppInjector;
import com.wrapper.injector.InjectionRule;
import com.wrapper.runtime.ExtensionRuntime;

import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class WrapperAccessibilityService extends AccessibilityService
    implements TtsModule.TtsListener, OcrModule.OcrListener, TranslatorModule.TranslatorListener {

    private static final String TAG = "WrapperService";
    private ConfigParser configParser;
    private ModuleInstaller installer;
    private TtsModule ttsModule;
    private OcrModule ocrModule;
    private TranslatorModule translatorModule;
    private AppInjector injector;
    private ExtensionRuntime runtime;
    private List<RouteRule> routeRules;
    private List<InjectionRule> injectionRules;
    private ExecutorService executor;

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        Log.d(TAG, "Accessibility service connected");

        AccessibilityServiceInfo info = new AccessibilityServiceInfo();
        info.eventTypes = AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
            | AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED
            | AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED
            | AccessibilityEvent.TYPE_VIEW_CLICKED;
        info.feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC;
        info.flags = AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS
            | AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS;
        info.notificationTimeout = 100;
        setServiceInfo(info);

        executor = Executors.newFixedThreadPool(4);
        initModules();
    }

    private void initModules() {
        configParser = new ConfigParser(this);
        installer = new ModuleInstaller(this);

        boolean loaded = configParser.loadFromAssets("config.json");
        if (!loaded) {
            Log.w(TAG, "Could not load config.json from assets, using defaults");
            configParser.loadFromJson("{\"modules\":{\"tts\":{\"enabled\":true},\"ocr\":{\"enabled\":true},\"translator\":{\"enabled\":true},\"router\":{\"enabled\":true}}}");
        }

        routeRules = configParser.getRouteRules();

        runtime = new ExtensionRuntime(this);

        if (configParser.isModuleEnabled("tts")) {
            ttsModule = new TtsModule(this, configParser.getModuleConfig("tts"), this);
        }
        if (configParser.isModuleEnabled("ocr")) {
            ocrModule = new OcrModule(this, configParser.getModuleConfig("ocr"), this);
        }
        if (configParser.isModuleEnabled("translator")) {
            translatorModule = new TranslatorModule(this, configParser.getModuleConfig("translator"), this);
        }

        injector = new AppInjector(this, runtime);
        injector.setTtsModule(ttsModule);
        injector.setOcrModule(ocrModule);
        injector.setTranslatorModule(translatorModule);

        injectionRules = configParser.getInjectionRules();
        injector.setRules(injectionRules);

        Log.d(TAG, "Modules initialized. TTS: " + (ttsModule != null) + " OCR: " + (ocrModule != null) + " Translator: " + (translatorModule != null));
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        if (event == null) return;

        if (event.getEventType() == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            String packageName = event.getPackageName() != null ? event.getPackageName().toString() : "";
            handleAppSwitch(packageName);
        }

        if (ttsModule != null && ttsModule.isInitialized()) {
            if (event.getEventType() == AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED) {
                handleTextForTts(event);
            }
        }
    }

    private void handleAppSwitch(String packageName) {
        if (packageName.isEmpty()) return;

        for (RouteRule rule : routeRules) {
            if (rule.getAppPackage().equals(packageName)) {
                Log.d(TAG, "Rule triggered: " + rule);
                executeRouteRule(rule);
            }
        }

        if (injector != null) {
            InjectionRule injectionRule = injector.findMatchingRule(packageName);
            if (injectionRule != null) {
                Log.d(TAG, "Injection rule triggered for: " + packageName);
                injector.executeRule(injectionRule);
            }
        }
    }

    private void executeRouteRule(RouteRule rule) {
        if (rule.getTarget() == null) return;
        switch (rule.getTarget()) {
            case "module:tts":
                if (ttsModule != null && ttsModule.isInitialized()) {
                    String text = getCurrentWindowText();
                    if (!text.isEmpty()) {
                        ttsModule.speak(text);
                    }
                }
                break;
            case "module:ocr":
                break;
            case "module:translator":
                if (translatorModule != null) {
                    String text = getCurrentWindowText();
                    if (!text.isEmpty()) {
                        translatorModule.translate(text);
                    }
                }
                break;
        }
    }

    private void handleTextForTts(AccessibilityEvent event) {
        if (event.getText() != null && !event.getText().isEmpty()) {
            String text = event.getText().get(0).toString();
            if (!text.isEmpty() && text.length() > 1 && text.length() < 500) {
                executor.submit(() -> {
                    if (ttsModule != null && ttsModule.isInitialized()) {
                        ttsModule.speak(text);
                    }
                });
            }
        }
    }

    public String getCurrentWindowText() {
        AccessibilityNodeInfo rootNode = getRootInActiveWindow();
        if (rootNode == null) return "";

        StringBuilder text = new StringBuilder();
        extractText(rootNode, text);
        rootNode.recycle();
        return text.toString().trim();
    }

    private void extractText(AccessibilityNodeInfo node, StringBuilder builder) {
        if (node == null) return;
        if (node.getText() != null) {
            builder.append(node.getText()).append(" ");
        }
        for (int i = 0; i < node.getChildCount(); i++) {
            AccessibilityNodeInfo child = node.getChild(i);
            if (child != null) {
                extractText(child, builder);
                child.recycle();
            }
        }
    }

    public void performAction(int action) {
        AccessibilityNodeInfo rootNode = getRootInActiveWindow();
        if (rootNode != null) {
            rootNode.performAction(action);
            rootNode.recycle();
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

    @Override
    public void onInterrupt() {
        Log.d(TAG, "Service interrupted");
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        if (executor != null) executor.shutdownNow();
        if (ttsModule != null) ttsModule.shutdown();
        if (ocrModule != null) ocrModule.close();
        if (injector != null) injector.shutdown();
    }
}
