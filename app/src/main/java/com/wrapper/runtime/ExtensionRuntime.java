package com.wrapper.runtime;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.webkit.JavascriptInterface;
import android.webkit.WebView;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.*;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class ExtensionRuntime {
    private static final String TAG = "ExtensionRuntime";
    private final Context context;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final ExecutorService executor = Executors.newFixedThreadPool(4);
    private final Map<String, LoadedModule> loadedModules = new HashMap<>();
    private final Map<String, String> sharedStorage = new HashMap<>();

    public ExtensionRuntime(Context context) {
        this.context = context;
    }

    public boolean loadModule(String modulePath) {
        try {
            File configFile = new File(modulePath, "module.json");
            if (!configFile.exists()) {
                Log.e(TAG, "module.json not found: " + modulePath);
                return false;
            }

            String json = readFile(configFile);
            JSONObject config = new JSONObject(json);

            LoadedModule module = new LoadedModule();
            module.name = config.optString("name", "unknown");
            module.config = config;
            module.basePath = modulePath;
            module.scripts = loadScripts(config.optJSONArray("scripts"), modulePath);

            loadedModules.put(module.name, module);
            Log.d(TAG, "Module loaded: " + module.name);
            return true;
        } catch (Exception e) {
            Log.e(TAG, "Error loading module", e);
            return false;
        }
    }

    private List<ScriptFile> loadScripts(JSONArray scriptsConfig, String basePath) {
        List<ScriptFile> scripts = new ArrayList<>();
        if (scriptsConfig == null) return scripts;

        for (int i = 0; i < scriptsConfig.length(); i++) {
            try {
                JSONObject scriptCfg = scriptsConfig.getJSONObject(i);
                String fileName = scriptCfg.getString("file");
                String type = scriptCfg.getString("type");

                File scriptFile = new File(basePath, fileName);
                if (scriptFile.exists()) {
                    ScriptFile sf = new ScriptFile();
                    sf.content = readFile(scriptFile);
                    sf.type = type;
                    sf.patterns = jsonArrayToList(scriptCfg.optJSONArray("patterns"));
                    scripts.add(sf);
                }
            } catch (Exception e) {
                Log.e(TAG, "Error loading script", e);
            }
        }
        return scripts;
    }

    public void injectIntoWebView(WebView webView, String currentUrl) {
        for (LoadedModule module : loadedModules.values()) {
            if (!module.config.optBoolean("enabled", true)) continue;

            for (ScriptFile script : module.scripts) {
                if (shouldInject(script, currentUrl)) {
                    injectScript(webView, script);
                }
            }
        }
    }

    private boolean shouldInject(ScriptFile script, String url) {
        if (script.patterns == null || script.patterns.isEmpty()) {
            return true;
        }
        for (String pattern : script.patterns) {
            if (matchesPattern(url, pattern)) {
                return true;
            }
        }
        return false;
    }

    private boolean matchesPattern(String url, String pattern) {
        String regex = pattern
            .replace(".", "\\.")
            .replace("*", ".*")
            .replace("?", ".");
        return url.matches(regex);
    }

    private void injectScript(WebView webView, ScriptFile script) {
        if ("javascript".equals(script.type)) {
            String wrappedScript = wrapWithApiBridge(script.content);
            webView.evaluateJavascript(wrappedScript, null);
        } else if ("css".equals(script.type)) {
            injectCss(webView, script.content);
        }
    }

    private String wrapWithApiBridge(String script) {
        return "(function() {" +
            "window.AndroidWrapper = {" +
            "  speak: function(text) { Android.speak(text); }," +
            "  recognize: function() { return Android.recognize(); }," +
            "  translate: function(text, cb) { Android.translate(text, cb); }," +
            "  overlay: function(html) { Android.showOverlay(html); }," +
            "  storage: { get: function(k) { return Android.storageGet(k); }, set: function(k,v) { Android.storageSet(k,v); } }," +
            "  http: function(url, cb) { Android.httpRequest(url, cb); }," +
            "  log: function(msg) { Android.log(msg); }" +
            "};" +
            script +
            "})();";
    }

    private void injectCss(WebView webView, String css) {
        String escaped = css.replace("\\", "\\\\").replace("'", "\\'").replace("\n", "\\n");
        String js = "(function() {" +
            "var style = document.createElement('style');" +
            "style.textContent = '" + escaped + "';" +
            "document.head.appendChild(style);" +
            "})();";
        webView.evaluateJavascript(js, null);
    }

    public void setupWebViewBridge(WebView webView) {
        webView.addJavascriptInterface(new WebAppInterface(), "Android");
    }

    public void executeScript(String scriptContent) {
        Log.d(TAG, "Executing script: " + scriptContent.substring(0, Math.min(100, scriptContent.length())));
    }

    public class WebAppInterface {
        @JavascriptInterface
        public void speak(String text) {
            Log.d(TAG, "TTS: " + text);
        }

        @JavascriptInterface
        public String recognize() {
            Log.d(TAG, "OCR recognize");
            return "";
        }

        @JavascriptInterface
        public void translate(String text, final String callback) {
            Log.d(TAG, "Translate: " + text);
            executor.submit(() -> {
                try {
                    String encoded = java.net.URLEncoder.encode(text, "UTF-8");
                    String urlStr = "https://translate.googleapis.com/translate_a/single?client=gtx&sl=auto&tl=ru&dt=t&q=" + encoded;
                    URL url = new URL(urlStr);
                    HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                    conn.setConnectTimeout(10000);
                    conn.setReadTimeout(10000);
                    BufferedReader reader = new BufferedReader(new InputStreamReader(conn.getInputStream()));
                    StringBuilder response = new StringBuilder();
                    String line;
                    while ((line = reader.readLine()) != null) {
                        response.append(line);
                    }
                    reader.close();

                    JSONArray outer = new JSONArray(response.toString());
                    JSONArray sentences = outer.getJSONArray(0);
                    StringBuilder result = new StringBuilder();
                    for (int i = 0; i < sentences.length(); i++) {
                        JSONArray sentence = sentences.getJSONArray(i);
                        if (sentence.length() > 0) {
                            result.append(sentence.getString(0));
                        }
                    }

                    final String translated = result.toString().trim();
                    mainHandler.post(() -> {
                        // Callback would be invoked on the webview
                    });
                } catch (Exception e) {
                    Log.e(TAG, "Translate error", e);
                }
            });
        }

        @JavascriptInterface
        public void showOverlay(String html) {
            Log.d(TAG, "Overlay requested");
        }

        @JavascriptInterface
        public String storageGet(String key) {
            return sharedStorage.get(key);
        }

        @JavascriptInterface
        public void storageSet(String key, String value) {
            sharedStorage.put(key, value);
        }

        @JavascriptInterface
        public void httpRequest(String url, final String callback) {
            executor.submit(() -> {
                try {
                    URL u = new URL(url);
                    HttpURLConnection conn = (HttpURLConnection) u.openConnection();
                    conn.setConnectTimeout(15000);
                    conn.setReadTimeout(15000);
                    conn.setRequestProperty("User-Agent", "AndroidWrapper/2.0");
                    BufferedReader reader = new BufferedReader(new InputStreamReader(conn.getInputStream()));
                    StringBuilder response = new StringBuilder();
                    String line;
                    while ((line = reader.readLine()) != null) {
                        response.append(line);
                    }
                    reader.close();
                    Log.d(TAG, "HTTP response length: " + response.length());
                } catch (Exception e) {
                    Log.e(TAG, "HTTP error", e);
                }
            });
        }

        @JavascriptInterface
        public void log(String message) {
            Log.d(TAG, "JS: " + message);
        }
    }

    public List<String> getLoadedModules() {
        return new ArrayList<>(loadedModules.keySet());
    }

    public void disableModule(String name) {
        LoadedModule module = loadedModules.get(name);
        if (module != null) {
            try {
                module.config.put("enabled", false);
            } catch (Exception e) {
                Log.e(TAG, "Error disabling module", e);
            }
        }
    }

    public void enableModule(String name) {
        LoadedModule module = loadedModules.get(name);
        if (module != null) {
            try {
                module.config.put("enabled", true);
            } catch (Exception e) {
                Log.e(TAG, "Error enabling module", e);
            }
        }
    }

    public void shutdown() {
        executor.shutdownNow();
        loadedModules.clear();
        sharedStorage.clear();
    }

    private String readFile(File file) throws Exception {
        BufferedReader reader = new BufferedReader(new FileReader(file));
        StringBuilder sb = new StringBuilder();
        String line;
        while ((line = reader.readLine()) != null) {
            sb.append(line).append("\n");
        }
        reader.close();
        return sb.toString();
    }

    private List<String> jsonArrayToList(JSONArray array) {
        List<String> list = new ArrayList<>();
        if (array == null) return list;
        for (int i = 0; i < array.length(); i++) {
            list.add(array.optString(i));
        }
        return list;
    }

    private static class LoadedModule {
        String name;
        JSONObject config;
        String basePath;
        List<ScriptFile> scripts;
    }

    private static class ScriptFile {
        String content;
        String type;
        List<String> patterns;
    }
}
