package com.wrapper.vpn.ext;

import android.content.Context;
import android.content.SharedPreferences;

import com.wrapper.vpn.BrowsecVpnService;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Два режима VPN, оба работают через один и тот же нативный туннель
 * (BrowsecVpnService + HTTP CONNECT). Различаются только источником серверов:
 *
 *   BUILTIN    — встроенный список (DNS / Proxy / VPN)
 *   EXTENSION  — серверы, импортированные из браузерного VPN-расширения
 */
public class ServerStore {

    public static final String MODE_BUILTIN = "builtin";
    public static final String MODE_EXTENSION = "extension";

    private static final String PREFS = "vpn_extension";
    private static final String PREF_SERVERS = "ext_servers";
    private static final String PREF_MODE = "mode";
    private static final String PREF_EXT_NAME = "ext_name";
    private static final String PREF_EXT_VERSION = "ext_version";
    private static final String PREF_EXT_IMPORTED_AT = "ext_imported_at";

    private static volatile ServerStore instance;

    private final Context context;
    private final SharedPreferences prefs;
    private final Map<String, BrowsecVpnService.VpnServer> imported = new LinkedHashMap<>();
    private volatile boolean loaded;

    private ServerStore(Context ctx) {
        this.context = ctx.getApplicationContext();
        this.prefs = this.context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    public static ServerStore get(Context context) {
        if (instance == null) {
            synchronized (ServerStore.class) {
                if (instance == null) {
                    instance = new ServerStore(context);
                }
            }
        }
        return instance;
    }

    // ===== Режим =====

    public String getMode() {
        return prefs.getString(PREF_MODE, MODE_BUILTIN);
    }

    public void setMode(String mode) {
        prefs.edit().putString(PREF_MODE, mode).apply();
    }

    public boolean isExtensionMode() {
        return MODE_EXTENSION.equals(getMode());
    }

    /**
     * Активный список серверов для текущего режима.
     */
    public Map<String, BrowsecVpnService.VpnServer> getActiveServers() {
        return getServersForMode(getMode());
    }

    /**
     * Список серверов для указанного режима. Если в режиме расширения
     * импортированных серверов нет, отдаём встроенные — чтобы приложение
     * никогда не оставалось без серверов.
     */
    public Map<String, BrowsecVpnService.VpnServer> getServersForMode(String mode) {
        if (ServerStore.MODE_EXTENSION.equals(mode)) {
            ensureLoaded();
            if (!imported.isEmpty()) {
                return new LinkedHashMap<>(imported);
            }
        }
        return BrowsecVpnService.getServers();
    }

    /**
     * Переключает режим и возвращает код первого сервера нового режима.
     * Если в режиме расширения серверов нет, остаёмся на встроенном.
     */
    public String switchMode(String mode) {
        if (MODE_EXTENSION.equals(mode) && imported.isEmpty()) {
            return BrowsecVpnService.getServers().keySet().iterator().next();
        }
        setMode(mode);
        return getActiveServers().keySet().iterator().next();
    }

    // ===== Импортированные серверы =====

    private void ensureLoaded() {
        if (loaded) return;
        synchronized (this) {
            if (loaded) return;
            String raw = prefs.getString(PREF_SERVERS, "");
            if (raw != null && !raw.isEmpty()) {
                try {
                    JSONArray arr = new JSONArray(raw);
                    for (int i = 0; i < arr.length(); i++) {
                        JSONObject o = arr.getJSONObject(i);
                        String code = o.optString("code");
                        String host = o.optString("host");
                        int port = o.optInt("port", 0);
                        if (code.isEmpty() || host.isEmpty() || port <= 0) continue;
                        imported.put(code, new BrowsecVpnService.VpnServer(
                                code, o.optString("name", host), host, port,
                                o.optString("flag", "📦")));
                    }
                } catch (Exception e) {
                    imported.clear();
                }
            }
            loaded = true;
        }
    }

    public List<BrowsecVpnService.VpnServer> getImportedServers() {
        ensureLoaded();
        return new ArrayList<>(imported.values());
    }

    public int getImportedCount() {
        ensureLoaded();
        return imported.size();
    }

    public boolean hasImported() {
        ensureLoaded();
        return !imported.isEmpty();
    }

    public String getExtensionName() {
        return prefs.getString(PREF_EXT_NAME, "");
    }

    public String getExtensionVersion() {
        return prefs.getString(PREF_EXT_VERSION, "");
    }

    public long getImportedAt() {
        return prefs.getLong(PREF_EXT_IMPORTED_AT, 0L);
    }

    /**
     * Сохраняет результат импорта. Возвращает число записанных серверов.
     */
    public int saveImport(CrxImporter.Result result) {
        ensureLoaded();
        imported.clear();

        JSONArray arr = new JSONArray();
        for (CrxImporter.Server s : result.servers) {
            try {
                JSONObject o = new JSONObject();
                o.put("code", s.code());
                o.put("host", s.host);
                o.put("port", s.port);
                o.put("name", s.name);
                o.put("flag", flagFor(s.country));
                arr.put(o);
            } catch (Exception ignored) {
            }
            imported.put(s.code(), new BrowsecVpnService.VpnServer(
                    s.code(), s.name, s.host, s.port, flagFor(s.country)));
        }

        SharedPreferences.Editor e = prefs.edit();
        e.putString(PREF_SERVERS, arr.toString());
        e.putString(PREF_EXT_NAME, result.extensionName != null ? result.extensionName : "");
        e.putString(PREF_EXT_VERSION, result.extensionVersion != null ? result.extensionVersion : "");
        e.putLong(PREF_EXT_IMPORTED_AT, System.currentTimeMillis());
        e.apply();

        loaded = true;
        return imported.size();
    }

    public void addManual(String host, int port, String name) {
        ensureLoaded();
        String code = "ext_manual_" + port + "_" + Math.abs(host.hashCode() % 100000);
        imported.put(code, new BrowsecVpnService.VpnServer(code, name, host, port, "✍️"));
        persistManual();
    }

    private void persistManual() {
        JSONArray arr = new JSONArray();
        for (BrowsecVpnService.VpnServer v : imported.values()) {
            try {
                JSONObject o = new JSONObject();
                o.put("code", v.code);
                o.put("host", v.host);
                o.put("port", v.port);
                o.put("name", v.name);
                o.put("flag", v.flag);
                arr.put(o);
            } catch (Exception ignored) {
            }
        }
        prefs.edit().putString(PREF_SERVERS, arr.toString()).apply();
    }

    public void clearImported() {
        loaded = true;
        imported.clear();
        SharedPreferences.Editor e = prefs.edit();
        e.remove(PREF_SERVERS);
        e.remove(PREF_EXT_NAME);
        e.remove(PREF_EXT_VERSION);
        e.remove(PREF_EXT_IMPORTED_AT);
        e.apply();
        if (isExtensionMode()) {
            setMode(MODE_BUILTIN);
        }
    }

    private String flagFor(String country) {
        if (country == null) return "📦";
        switch (country.toLowerCase()) {
            case "nl": return "🇳🇱";
            case "us": return "🇺🇸";
            case "de": return "🇩🇪";
            case "fr": return "🇫🇷";
            case "gb": case "uk": return "🇬🇧";
            case "se": return "🇸🇪";
            case "ch": return "🇨🇭";
            case "jp": return "🇯🇵";
            case "sg": return "🇸🇬";
            case "au": return "🇦🇺";
            case "ca": return "🇨🇦";
            case "ru": return "🇷🇺";
            case "fi": return "🇫🇮";
            case "at": return "🇦🇹";
            case "tr": return "🇹🇷";
            default: return "📦";
        }
    }
}
