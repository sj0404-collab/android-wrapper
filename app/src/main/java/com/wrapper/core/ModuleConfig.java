package com.wrapper.core;

import org.json.JSONObject;

/**
 * Конфигурация отдельного модуля
 */
public class ModuleConfig {
    private final String name;
    private final JSONObject config;

    public ModuleConfig(String name, JSONObject config) {
        this.name = name;
        this.config = config;
    }

    public String getName() {
        return name;
    }

    public boolean isEnabled() {
        return config.optBoolean("enabled", false);
    }

    public String getString(String key, String defaultValue) {
        return config.optString(key, defaultValue);
    }

    public int getInt(String key, int defaultValue) {
        return config.optInt(key, defaultValue);
    }

    public boolean getBoolean(String key, boolean defaultValue) {
        return config.optBoolean(key, defaultValue);
    }

    public double getDouble(String key, double defaultValue) {
        return config.optDouble(key, defaultValue);
    }

    public JSONObject getJSONObject() {
        return config;
    }
}
