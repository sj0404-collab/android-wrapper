package com.wrapper.core;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.*;
import java.net.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.regex.Pattern;

public class RouteManager {
    private static final String TAG = "RouteManager";
    private static final String PREFS_NAME = "route_prefs";
    private static final String KEY_ROUTES = "routes";
    private static final String KEY_BYPASS_APPS = "bypass_apps";
    private static final String KEY_BYPASS_DOMAINS = "bypass_domains";

    public enum RouteAction {
        VPN,        // Через VPN
        BYPASS,     // В обход VPN (напрямую)
        BLOCK,      // Заблокировать
        PROXY       // Через прокси
    }

    public enum RouteMode {
        ALL_VPN,        // Весь трафик через VPN
        SELECTIVE,      // Выбранные приложения через VPN
        BYPASS,         // Выбранные приложения в обход
        SMART           // Умная маршрутизация по доменам
    }

    public static class ManagerRouteRule {
        public String id;
        public String appPackage;
        public String domain;
        public Pattern domainPattern;
        public RouteAction action;
        public String proxyHost;
        public int proxyPort;
        public boolean enabled;
        public int priority;
        public String label;

        public ManagerRouteRule() {
            id = UUID.randomUUID().toString();
            enabled = true;
            action = RouteAction.VPN;
            priority = 0;
        }

        @Override
        public String toString() {
            if (domain != null) return label + " " + domain + " -> " + action;
            if (appPackage != null) return label + " " + appPackage + " -> " + action;
            return label + " default -> " + action;
        }
    }

    private final Context context;
    private final SharedPreferences prefs;
    private final List<ManagerRouteRule> rules = new CopyOnWriteArrayList<>();
    private final Set<String> bypassApps = ConcurrentHashMap.newKeySet();
    private final Set<String> bypassDomains = ConcurrentHashMap.newKeySet();
    private final Map<String, RouteAction> domainCache = new ConcurrentHashMap<>();
    private RouteMode currentMode = RouteMode.ALL_VPN;
    private String defaultProxyHost;
    private int defaultProxyPort;

    public RouteManager(Context context) {
        this.context = context;
        this.prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        loadFromPrefs();
    }

    public void setMode(RouteMode mode) {
        this.currentMode = mode;
        domainCache.clear();
        Log.d(TAG, "Route mode: " + mode);
    }

    public RouteMode getMode() {
        return currentMode;
    }

    public void setDefaultProxy(String host, int port) {
        this.defaultProxyHost = host;
        this.defaultProxyPort = port;
    }

    public void addRule(ManagerRouteRule rule) {
        rules.add(rule);
        saveToPrefs();
        domainCache.clear();
    }

    public void removeRule(String ruleId) {
        rules.removeIf(r -> r.id.equals(ruleId));
        saveToPrefs();
        domainCache.clear();
    }

    public void toggleRule(String ruleId) {
        for (ManagerRouteRule rule : rules) {
            if (rule.id.equals(ruleId)) {
                rule.enabled = !rule.enabled;
                break;
            }
        }
        saveToPrefs();
        domainCache.clear();
    }

    public List<ManagerRouteRule> getRules() {
        return new ArrayList<>(rules);
    }

    public void addBypassApp(String packageName) {
        bypassApps.add(packageName);
        saveToPrefs();
    }

    public void removeBypassApp(String packageName) {
        bypassApps.remove(packageName);
        saveToPrefs();
    }

    public Set<String> getBypassApps() {
        return new HashSet<>(bypassApps);
    }

    public void addBypassDomain(String domain) {
        bypassDomains.add(domain.toLowerCase());
        domainCache.clear();
        saveToPrefs();
    }

    public void removeBypassDomain(String domain) {
        bypassDomains.remove(domain.toLowerCase());
        domainCache.clear();
        saveToPrefs();
    }

    public Set<String> getBypassDomains() {
        return new HashSet<>(bypassDomains);
    }

    public RouteAction resolveAction(String appPackage, String host) {
        if (currentMode == RouteMode.ALL_VPN) {
            if (appPackage != null && bypassApps.contains(appPackage)) {
                return RouteAction.BYPASS;
            }
            if (host != null && isDomainBypassed(host)) {
                return RouteAction.BYPASS;
            }
            return matchRules(appPackage, host);
        }

        if (currentMode == RouteMode.BYPASS) {
            if (appPackage != null && bypassApps.contains(appPackage)) {
                return RouteAction.VPN;
            }
            return RouteAction.BYPASS;
        }

        if (currentMode == RouteMode.SELECTIVE) {
            if (appPackage != null && bypassApps.contains(appPackage)) {
                return RouteAction.VPN;
            }
            return RouteAction.BYPASS;
        }

        if (currentMode == RouteMode.SMART) {
            RouteAction domainAction = resolveDomain(host);
            if (domainAction != null) return domainAction;
            return matchRules(appPackage, host);
        }

        return RouteAction.VPN;
    }

    private RouteAction matchRules(String appPackage, String host) {
        List<ManagerRouteRule> sorted = new ArrayList<>(rules);
        sorted.sort((a, b) -> Integer.compare(b.priority, a.priority));

        for (ManagerRouteRule rule : sorted) {
            if (!rule.enabled) continue;

            if (rule.appPackage != null && rule.appPackage.equals(appPackage)) {
                return rule.action;
            }

            if (rule.domain != null && host != null) {
                if (rule.domainPattern != null) {
                    if (rule.domainPattern.matcher(host).matches()) {
                        return rule.action;
                    }
                } else if (host.toLowerCase().contains(rule.domain.toLowerCase())) {
                    return rule.action;
                }
            }
        }

        return RouteAction.VPN;
    }

    private RouteAction resolveDomain(String host) {
        if (host == null) return null;

        RouteAction cached = domainCache.get(host);
        if (cached != null) return cached;

        if (isDomainBypassed(host)) {
            domainCache.put(host, RouteAction.BYPASS);
            return RouteAction.BYPASS;
        }

        for (ManagerRouteRule rule : rules) {
            if (!rule.enabled || rule.domain == null) continue;
            if (rule.domainPattern != null && rule.domainPattern.matcher(host).matches()) {
                domainCache.put(host, rule.action);
                return rule.action;
            }
        }

        domainCache.put(host, RouteAction.VPN);
        return RouteAction.VPN;
    }

    private boolean isDomainBypassed(String host) {
        String lower = host.toLowerCase();
        for (String domain : bypassDomains) {
            if (lower.equals(domain) || lower.endsWith("." + domain)) {
                return true;
            }
        }
        return false;
    }

    public String resolveIp(String host) {
        try {
            return InetAddress.getByName(host).getHostAddress();
        } catch (Exception e) {
            return null;
        }
    }

    public void clearRules() {
        rules.clear();
        bypassApps.clear();
        bypassDomains.clear();
        domainCache.clear();
        saveToPrefs();
    }

    public JSONObject exportConfig() {
        JSONObject config = new JSONObject();
        try {
            config.put("mode", currentMode.name());

            JSONArray rulesArr = new JSONArray();
            for (ManagerRouteRule rule : rules) {
                JSONObject r = new JSONObject();
                r.put("id", rule.id);
                r.put("app", rule.appPackage);
                r.put("domain", rule.domain);
                r.put("action", rule.action.name());
                r.put("enabled", rule.enabled);
                r.put("priority", rule.priority);
                r.put("label", rule.label);
                rulesArr.put(r);
            }
            config.put("rules", rulesArr);

            JSONArray appsArr = new JSONArray();
            for (String app : bypassApps) appsArr.put(app);
            config.put("bypassApps", appsArr);

            JSONArray domainsArr = new JSONArray();
            for (String d : bypassDomains) domainsArr.put(d);
            config.put("bypassDomains", domainsArr);

        } catch (Exception e) {
            Log.e(TAG, "Export error", e);
        }
        return config;
    }

    public void importConfig(JSONObject config) {
        try {
            clearRules();
            currentMode = RouteMode.valueOf(config.optString("mode", "ALL_VPN"));

            JSONArray rulesArr = config.optJSONArray("rules");
            if (rulesArr != null) {
                for (int i = 0; i < rulesArr.length(); i++) {
                    JSONObject r = rulesArr.getJSONObject(i);
                    ManagerRouteRule rule = new ManagerRouteRule();
                    rule.id = r.optString("id", UUID.randomUUID().toString());
                    rule.appPackage = r.optString("app", null);
                    rule.domain = r.optString("domain", null);
                    if (rule.domain != null) {
                        rule.domainPattern = Pattern.compile(
                            rule.domain.replace(".", "\\.").replace("*", ".*"));
                    }
                    rule.action = RouteAction.valueOf(r.optString("action", "VPN"));
                    rule.enabled = r.optBoolean("enabled", true);
                    rule.priority = r.optInt("priority", 0);
                    rule.label = r.optString("label", "");
                    rules.add(rule);
                }
            }

            JSONArray appsArr = config.optJSONArray("bypassApps");
            if (appsArr != null) {
                for (int i = 0; i < appsArr.length(); i++) {
                    bypassApps.add(appsArr.getString(i));
                }
            }

            JSONArray domainsArr = config.optJSONArray("bypassDomains");
            if (domainsArr != null) {
                for (int i = 0; i < domainsArr.length(); i++) {
                    bypassDomains.add(domainsArr.getString(i));
                }
            }

            saveToPrefs();
        } catch (Exception e) {
            Log.e(TAG, "Import error", e);
        }
    }

    private void saveToPrefs() {
        try {
            JSONObject config = exportConfig();
            prefs.edit().putString(KEY_ROUTES, config.toString()).apply();
        } catch (Exception e) {
            Log.e(TAG, "Save error", e);
        }
    }

    private void loadFromPrefs() {
        try {
            String data = prefs.getString(KEY_ROUTES, null);
            if (data != null) {
                importConfig(new JSONObject(data));
            }
        } catch (Exception e) {
            Log.e(TAG, "Load error", e);
        }
    }

    public static ManagerRouteRule createAppRule(String packageName, RouteAction action, String label) {
        ManagerRouteRule rule = new ManagerRouteRule();
        rule.appPackage = packageName;
        rule.action = action;
        rule.label = label;
        return rule;
    }

    public static ManagerRouteRule createDomainRule(String domain, RouteAction action, String label) {
        ManagerRouteRule rule = new ManagerRouteRule();
        rule.domain = domain;
        rule.domainPattern = Pattern.compile(domain.replace(".", "\\.").replace("*", ".*"));
        rule.action = action;
        rule.label = label;
        return rule;
    }

    public static ManagerRouteRule createDefaultRule(RouteAction action) {
        ManagerRouteRule rule = new ManagerRouteRule();
        rule.action = action;
        rule.label = "Default";
        rule.priority = -100;
        return rule;
    }
}
