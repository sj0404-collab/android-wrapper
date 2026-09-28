package com.wrapper.core;

import android.content.Context;
import android.net.Uri;
import android.util.Log;
import com.wrapper.injector.InjectionRule;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.List;

public class ConfigParser {
    private static final String TAG = "ConfigParser";
    private final Context context;
    private JSONObject rootConfig;

    public ConfigParser(Context context) {
        this.context = context;
    }

    public boolean loadFromAssets(String path) {
        try {
            InputStream is = context.getAssets().open(path);
            String json = readStream(is);
            rootConfig = new JSONObject(json);
            Log.d(TAG, "Config loaded: " + path);
            return true;
        } catch (Exception e) {
            Log.e(TAG, "Error loading config", e);
            return false;
        }
    }

    public boolean loadFromJson(String json) {
        try {
            rootConfig = new JSONObject(json);
            Log.d(TAG, "Config loaded from JSON string");
            return true;
        } catch (Exception e) {
            Log.e(TAG, "Error loading from JSON", e);
            return false;
        }
    }

    public boolean loadFromUri(Uri uri) {
        try {
            InputStream is = context.getContentResolver().openInputStream(uri);
            String json = readStream(is);
            rootConfig = new JSONObject(json);
            Log.d(TAG, "Config loaded from URI");
            return true;
        } catch (Exception e) {
            Log.e(TAG, "Error loading from URI", e);
            return false;
        }
    }

    public ModuleConfig getModuleConfig(String moduleName) {
        try {
            if (rootConfig == null || !rootConfig.has("modules")) {
                return null;
            }
            JSONObject modules = rootConfig.getJSONObject("modules");
            if (!modules.has(moduleName)) {
                return null;
            }
            return new ModuleConfig(moduleName, modules.getJSONObject(moduleName));
        } catch (Exception e) {
            Log.e(TAG, "Error getting module config: " + moduleName, e);
            return null;
        }
    }

    public List<RouteRule> getRouteRules() {
        List<RouteRule> rules = new ArrayList<>();
        try {
            if (rootConfig == null) return rules;
            JSONObject modules = rootConfig.getJSONObject("modules");
            if (modules.has("router")) {
                JSONObject router = modules.getJSONObject("router");
                if (router.optBoolean("enabled", false) && router.has("rules")) {
                    JSONArray rulesArray = router.getJSONArray("rules");
                    for (int i = 0; i < rulesArray.length(); i++) {
                        JSONObject rule = rulesArray.getJSONObject(i);
                        rules.add(new RouteRule(
                            rule.getString("app"),
                            rule.getString("action"),
                            rule.getString("target")
                        ));
                    }
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "Error getting route rules", e);
        }
        return rules;
    }

    public List<InjectionRule> getInjectionRules() {
        List<InjectionRule> rules = new ArrayList<>();
        try {
            if (rootConfig == null) return rules;
            JSONObject modules = rootConfig.getJSONObject("modules");
            if (modules.has("injections")) {
                JSONArray arr = modules.getJSONArray("injections");
                for (int i = 0; i < arr.length(); i++) {
                    JSONObject obj = arr.getJSONObject(i);
                    InjectionRule rule = new InjectionRule();
                    rule.setAppPackage(obj.optString("app", null));
                    rule.setAction(obj.optInt("action", InjectionRule.ACTION_INJECT_JS));
                    rule.setPayload(obj.optString("payload", ""));
                    rule.setTargetText(obj.optString("target_text", null));
                    rule.setTargetViewId(obj.optString("target_view_id", null));
                    rule.setEnabled(obj.optBoolean("enabled", true));
                    if (obj.has("url_patterns")) {
                        JSONArray patterns = obj.getJSONArray("url_patterns");
                        for (int j = 0; j < patterns.length(); j++) {
                            rule.addUrlPattern(patterns.getString(j));
                        }
                    }
                    rules.add(rule);
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "Error getting injection rules", e);
        }
        return rules;
    }

    public boolean isModuleEnabled(String moduleName) {
        ModuleConfig config = getModuleConfig(moduleName);
        return config != null && config.isEnabled();
    }

    private String readStream(InputStream is) throws Exception {
        BufferedReader reader = new BufferedReader(new InputStreamReader(is));
        StringBuilder sb = new StringBuilder();
        String line;
        while ((line = reader.readLine()) != null) {
            sb.append(line);
        }
        reader.close();
        return sb.toString();
    }
}
