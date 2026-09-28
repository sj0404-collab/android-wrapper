package com.wrapper.injector;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Правило инъекции в приложение
 */
public class InjectionRule {
    public static final int ACTION_INJECT_JS = 1;
    public static final int ACTION_OVERLAY = 2;
    public static final int ACTION_MODIFY = 3;
    public static final int ACTION_BLOCK = 4;

    private String appPackage;
    private String appName;
    private int action;
    private String payload;
    private String targetText;
    private String targetViewId;
    private List<Pattern> urlPatterns = new ArrayList<>();
    private int x, y, width, height;
    private boolean enabled = true;

    public InjectionRule() {}

    public InjectionRule(String appPackage, int action, String payload) {
        this.appPackage = appPackage;
        this.action = action;
        this.payload = payload;
    }

    /**
     * Проверка соответствия приложения
     */
    public boolean matches(String packageName) {
        if (!enabled) return false;
        if (appPackage != null && appPackage.equals(packageName)) return true;
        if (appName != null && packageName.contains(appName)) return true;
        return false;
    }

    // Getters and Setters
    public String getAppPackage() { return appPackage; }
    public void setAppPackage(String appPackage) { this.appPackage = appPackage; }

    public String getAppName() { return appName; }
    public void setAppName(String appName) { this.appName = appName; }

    public int getAction() { return action; }
    public void setAction(int action) { this.action = action; }

    public String getPayload() { return payload; }
    public void setPayload(String payload) { this.payload = payload; }

    public String getTargetText() { return targetText; }
    public void setTargetText(String targetText) { this.targetText = targetText; }

    public String getTargetViewId() { return targetViewId; }
    public void setTargetViewId(String targetViewId) { this.targetViewId = targetViewId; }

    public List<Pattern> getUrlPatterns() { return urlPatterns; }
    public void addUrlPattern(String pattern) {
        urlPatterns.add(Pattern.compile(pattern.replace("*", ".*")));
    }

    public int getX() { return x; }
    public void setX(int x) { this.x = x; }

    public int getY() { return y; }
    public void setY(int y) { this.y = y; }

    public int getWidth() { return width; }
    public void setWidth(int width) { this.width = width; }

    public int getHeight() { return height; }
    public void setHeight(int height) { this.height = height; }

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }

    @Override
    public String toString() {
        return "InjectionRule{" + appPackage + " -> action:" + action + "}";
    }
}
