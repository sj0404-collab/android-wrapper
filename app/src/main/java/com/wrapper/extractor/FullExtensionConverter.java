package com.wrapper.extractor;

import android.content.Context;
import android.util.Log;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.*;
import java.util.*;
import java.util.zip.*;

/**
 * Полный конвертер расширений браузера в Android модуль
 */
public class FullExtensionConverter {
    private static final String TAG = "FullConverter";
    private final Context context;
    private final File outputDir;

    public FullExtensionConverter(Context context) {
        this.context = context;
        this.outputDir = new File(context.getFilesDir(), "converted");
        if (!outputDir.exists()) outputDir.mkdirs();
    }

    /**
     * Полная конвертация расширения в Android модуль
     */
    public ConvertedModule convert(File extensionFile) {
        ConvertedModule result = new ConvertedModule();
        File tempDir = null;
        
        try {
            // 1. Распаковка
            tempDir = new File(context.getCacheDir(), "ext_" + System.currentTimeMillis());
            unzip(extensionFile, tempDir);
            
            // 2. Определение типа
            int type = detectType(extensionFile.getName());
            
            // 3. Парсинг манифеста
            File manifestFile = findManifest(tempDir);
            if (manifestFile == null) {
                Log.e(TAG, "manifest.json не найден");
                result.success = false;
                result.error = "manifest.json not found";
                return result;
            }
            
            JSONObject manifest = new JSONObject(readFile(manifestFile));
            result.manifest = manifest;
            result.type = type;
            result.name = unescapeLocale(manifest.optString("name", "Unknown"));
            result.version = manifest.optString("version", "1.0");
            result.description = unescapeLocale(manifest.optString("description", ""));
            
            // 4. Создание директории модуля
            String safeName = result.name.replaceAll("[^a-zA-Z0-9]", "_").toLowerCase();
            File moduleDir = new File(outputDir, safeName);
            if (!moduleDir.exists()) moduleDir.mkdirs();
            
            // 5. Конвертация манифеста в module.json
            JSONObject moduleConfig = convertManifest(manifest, type);
            writeFile(new File(moduleDir, "module.json"), moduleConfig.toString(2));
            result.moduleConfig = moduleConfig;
            
            // 6. Копирование ресурсов (иконки, изображения)
            copyResources(tempDir, moduleDir);
            
            // 7. Конвертация скриптов
            JSONArray scripts = new JSONArray();
            
            // Background scripts
            if (manifest.has("background")) {
                JSONObject bgConfig = convertBackground(manifest.getJSONObject("background"), tempDir, moduleDir);
                if (bgConfig != null) scripts.put(bgConfig);
            }
            
            // Content scripts
            if (manifest.has("content_scripts")) {
                JSONArray csConfigs = convertContentScripts(
                    manifest.getJSONArray("content_scripts"), tempDir, moduleDir);
                for (int i = 0; i < csConfigs.length(); i++) {
                    scripts.put(csConfigs.getJSONObject(i));
                }
            }
            
            // Browser action
            if (manifest.has("browser_action") || manifest.has("action")) {
                JSONObject actionConfig = convertBrowserAction(manifest, tempDir, moduleDir);
                if (actionConfig != null) scripts.put(actionConfig);
            }
            
            moduleConfig.put("scripts", scripts);
            
            // 8. Permissions
            JSONArray androidPerms = convertPermissions(
                manifest.optJSONArray("permissions"),
                manifest.optJSONArray("host_permissions"),
                manifest.optJSONArray("optional_permissions")
            );
            moduleConfig.put("permissions", androidPerms);
            
            // 9. Routing rules
            if (manifest.has("declarative_net_request")) {
                JSONObject routingConfig = convertRoutingRules(
                    manifest.getJSONObject("declarative_net_request"), tempDir);
                moduleConfig.put("routing", routingConfig);
            }
            
            // 10. Сохранение обновлённого конфига
            writeFile(new File(moduleDir, "module.json"), moduleConfig.toString(2));
            
            result.moduleDir = moduleDir;
            result.success = true;
            
            Log.d(TAG, "Конвертация завершена: " + result.name);
            
        } catch (Exception e) {
            Log.e(TAG, "Ошибка конвертации", e);
            result.success = false;
            result.error = e.getMessage();
        }
        
        // Очистка temp
        if (tempDir != null && tempDir.exists()) {
            deleteRecursive(tempDir);
        }
        
        return result;
    }

    private JSONObject convertManifest(JSONObject manifest, int type) {
        JSONObject module = new JSONObject();
        try {
            module.put("name", manifest.optString("name", "Unknown"));
            module.put("version", manifest.optString("version", "1.0"));
            module.put("description", manifest.optString("description", ""));
            module.put("type", "browser_extension");
            module.put("original_type", getTypeName(type));
            module.put("manifest_version", manifest.optInt("manifest_version", 3));
            module.put("enabled", true);
            module.put("converted_at", System.currentTimeMillis());
            
            JSONObject injectConfig = new JSONObject();
            injectConfig.put("auto_inject", true);
            JSONArray injectInto = new JSONArray();
            injectInto.put("android.webkit.WebView");
            injectInto.put("android.app.Activity");
            injectConfig.put("inject_into", injectInto);
            module.put("inject_config", injectConfig);
            
            JSONObject apiBridge = new JSONObject();
            apiBridge.put("enabled", true);
            JSONArray methods = new JSONArray();
            methods.put("speak").put("recognize").put("translate").put("overlay");
            methods.put("storage").put("http").put("notify").put("vpn");
            apiBridge.put("methods", methods);
            module.put("api_bridge", apiBridge);
            
        } catch (Exception e) {
            Log.e(TAG, "Error converting manifest", e);
        }
        return module;
    }

    private JSONObject convertBackground(JSONObject background, File sourceDir, File moduleDir) {
        JSONObject bgConfig = new JSONObject();
        try {
            if (background.has("service_worker")) {
                String swFile = background.getString("service_worker");
                File swSource = new File(sourceDir, swFile);
                if (swSource.exists()) {
                    String content = readFile(swSource);
                    String converted = convertServiceWorker(content);
                    File outFile = new File(moduleDir, "background_service.js");
                    writeFile(outFile, converted);
                    bgConfig.put("type", "service_worker");
                    bgConfig.put("file", "background_service.js");
                    bgConfig.put("original_file", swFile);
                }
            }
            if (background.has("scripts")) {
                JSONArray scripts = background.getJSONArray("scripts");
                JSONArray convertedScripts = new JSONArray();
                for (int i = 0; i < scripts.length(); i++) {
                    String scriptFile = scripts.getString(i);
                    File scriptSource = new File(sourceDir, scriptFile);
                    if (scriptSource.exists()) {
                        String content = readFile(scriptSource);
                        String converted = convertServiceWorker(content);
                        String outName = "background_" + i + ".js";
                        writeFile(new File(moduleDir, outName), converted);
                        JSONObject si = new JSONObject();
                        si.put("file", outName);
                        si.put("original", scriptFile);
                        convertedScripts.put(si);
                    }
                }
                bgConfig.put("type", "scripts");
                bgConfig.put("scripts", convertedScripts);
            }
        } catch (Exception e) {
            Log.e(TAG, "Error converting background", e);
        }
        return bgConfig;
    }

    private String convertServiceWorker(String content) {
        return content
            .replaceAll("chrome\\.storage\\.local\\.get", "AndroidStorage.get")
            .replaceAll("chrome\\.storage\\.local\\.set", "AndroidStorage.set")
            .replaceAll("chrome\\.runtime\\.onMessage\\.addListener", "ServiceBus.onMessage")
            .replaceAll("chrome\\.runtime\\.sendMessage", "ServiceBus.send")
            .replaceAll("chrome\\.tabs\\.query", "ActivityManager.getActivities")
            .replaceAll("chrome\\.proxy\\.settings\\.set", "VpnService.setProxy")
            .replaceAll("chrome\\.webRequest\\.onBeforeRequest\\.addListener", "NetworkInterceptor.onBeforeRequest")
            .replaceAll("chrome\\.alarms\\.create", "AlarmManager.create")
            .replaceAll("chrome\\.notifications\\.create", "NotificationManager.create");
    }

    private JSONArray convertContentScripts(JSONArray contentScripts, File sourceDir, File moduleDir) {
        JSONArray configs = new JSONArray();
        for (int i = 0; i < contentScripts.length(); i++) {
            try {
                JSONObject cs = contentScripts.getJSONObject(i);
                JSONObject csConfig = new JSONObject();
                
                JSONArray matches = cs.optJSONArray("matches");
                if (matches != null) {
                    JSONArray patterns = new JSONArray();
                    for (int j = 0; j < matches.length(); j++) {
                        patterns.put(chromePatternToRegex(matches.getString(j)));
                    }
                    csConfig.put("url_patterns", patterns);
                }
                
                JSONArray jsFiles = cs.optJSONArray("js");
                if (jsFiles != null) {
                    JSONArray jsConfigs = new JSONArray();
                    for (int j = 0; j < jsFiles.length(); j++) {
                        String jsFile = jsFiles.getString(j);
                        File jsSource = new File(sourceDir, jsFile);
                        if (jsSource.exists()) {
                            String content = readFile(jsSource);
                            String converted = convertContentScript(content);
                            String outName = "content_" + i + "_" + j + ".js";
                            writeFile(new File(moduleDir, outName), converted);
                            JSONObject ji = new JSONObject();
                            ji.put("file", outName);
                            ji.put("original", jsFile);
                            jsConfigs.put(ji);
                        }
                    }
                    csConfig.put("js", jsConfigs);
                }
                
                JSONArray cssFiles = cs.optJSONArray("css");
                if (cssFiles != null) {
                    JSONArray cssConfigs = new JSONArray();
                    for (int j = 0; j < cssFiles.length(); j++) {
                        String cssFile = cssFiles.getString(j);
                        File cssSource = new File(sourceDir, cssFile);
                        if (cssSource.exists()) {
                            String cssContent = readFile(cssSource);
                            String outName = "content_" + i + "_" + j + ".css";
                            writeFile(new File(moduleDir, outName), cssContent);
                            JSONObject ci = new JSONObject();
                            ci.put("file", outName);
                            ci.put("original", cssFile);
                            cssConfigs.put(ci);
                        }
                    }
                    csConfig.put("css", cssConfigs);
                }
                
                csConfig.put("run_at", cs.optString("run_at", "document_idle"));
                configs.put(csConfig);
            } catch (Exception e) {
                Log.e(TAG, "Error converting content_script #" + i, e);
            }
        }
        return configs;
    }

    private String convertContentScript(String content) {
        return content
            .replaceAll("chrome\\.runtime\\.sendMessage", "AndroidBridge.send")
            .replaceAll("chrome\\.runtime\\.onMessage\\.addListener", "AndroidBridge.onMessage")
            .replaceAll("chrome\\.storage\\.sync\\.get", "AndroidStorage.get")
            .replaceAll("chrome\\.storage\\.sync\\.set", "AndroidStorage.set")
            .replaceAll("chrome\\.storage\\.local\\.get", "AndroidStorage.get")
            .replaceAll("chrome\\.storage\\.local\\.set", "AndroidStorage.set");
    }

    private JSONObject convertBrowserAction(JSONObject manifest, File sourceDir, File moduleDir) {
        JSONObject actionConfig = new JSONObject();
        try {
            JSONObject action = manifest.has("browser_action") ?
                manifest.getJSONObject("browser_action") : manifest.optJSONObject("action");
            if (action == null) return null;
            
            actionConfig.put("type", "browser_action");
            if (action.has("default_popup")) {
                String popupFile = action.getString("default_popup");
                File popupSource = new File(sourceDir, popupFile);
                if (popupSource.exists()) {
                    String html = readFile(popupSource);
                    writeFile(new File(moduleDir, "popup.html"), html);
                    actionConfig.put("popup", "popup.html");
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "Error converting browser_action", e);
        }
        return actionConfig;
    }

    private JSONArray convertPermissions(JSONArray permissions, JSONArray hostPerms, JSONArray optional) {
        JSONArray androidPerms = new JSONArray();
        Map<String, String> mapping = new HashMap<>();
        mapping.put("activeTab", "ACCESSIBILITY_SERVICE");
        mapping.put("tabs", "ACCESSIBILITY_SERVICE");
        mapping.put("storage", "WRITE_EXTERNAL_STORAGE");
        mapping.put("notifications", "POST_NOTIFICATIONS");
        mapping.put("contextMenus", "SYSTEM_ALERT_WINDOW");
        mapping.put("webRequest", "INTERNET");
        mapping.put("webRequestBlocking", "INTERNET");
        mapping.put("proxy", "VPN");
        mapping.put("alarms", "WAKE_LOCK");
        mapping.put("background", "FOREGROUND_SERVICE");
        mapping.put("browsingData", "CLEAR_APP_CACHE");
        mapping.put("declarativeNetRequest", "INTERNET");
        mapping.put("scripting", "ACCESSIBILITY_SERVICE");
        mapping.put("management", "GET_TASKS");
        mapping.put("privacy", "VPN");
        
        addMappedPermissions(permissions, mapping, androidPerms);
        addMappedPermissions(hostPerms, mapping, androidPerms);
        addMappedPermissions(optional, mapping, androidPerms);
        
        // Базовые
        Set<String> existing = new HashSet<>();
        for (int i = 0; i < androidPerms.length(); i++) existing.add(androidPerms.optString(i));
        if (!existing.contains("INTERNET")) androidPerms.put("INTERNET");
        if (!existing.contains("ACCESS_NETWORK_STATE")) androidPerms.put("ACCESS_NETWORK_STATE");
        
        return androidPerms;
    }

    private void addMappedPermissions(JSONArray perms, Map<String, String> mapping, JSONArray result) {
        if (perms == null) return;
        Set<String> added = new HashSet<>();
        for (int i = 0; i < perms.length(); i++) {
            try {
                String perm = perms.getString(i);
                String androidPerm = mapping.get(perm);
                String toAdd = androidPerm != null ? androidPerm : perm;
                if (!added.contains(toAdd)) {
                    result.put(toAdd);
                    added.add(toAdd);
                }
            } catch (Exception e) { /* ignore */ }
        }
    }

    private JSONObject convertRoutingRules(JSONObject dnr, File sourceDir) {
        JSONObject routing = new JSONObject();
        try {
            if (dnr.has("rule_resources")) {
                JSONArray rules = dnr.getJSONArray("rule_resources");
                JSONArray allRules = new JSONArray();
                for (int i = 0; i < rules.length(); i++) {
                    JSONObject rr = rules.getJSONObject(i);
                    if (rr.optBoolean("enabled", false)) {
                        String ruleFile = rr.getString("path");
                        File rulesFile = new File(sourceDir, ruleFile);
                        if (rulesFile.exists()) {
                            JSONArray fileRules = new JSONArray(readFile(rulesFile));
                            for (int j = 0; j < fileRules.length(); j++) {
                                allRules.put(fileRules.getJSONObject(j));
                            }
                        }
                    }
                }
                routing.put("rules", allRules);
                routing.put("type", "declarative_net_request");
            }
        } catch (Exception e) {
            Log.e(TAG, "Error converting routing rules", e);
        }
        return routing;
    }

    // ===== Вспомогательные методы =====

    private int detectType(String fileName) {
        String lower = fileName.toLowerCase();
        if (lower.endsWith(".crx")) return ExtensionExtractor.TYPE_CHROME;
        if (lower.endsWith(".xpi")) return ExtensionExtractor.TYPE_FIREFOX;
        return ExtensionExtractor.TYPE_CUSTOM;
    }

    private String getTypeName(int type) {
        switch (type) {
            case ExtensionExtractor.TYPE_CHROME: return "chrome";
            case ExtensionExtractor.TYPE_FIREFOX: return "firefox";
            case ExtensionExtractor.TYPE_EDGE: return "edge";
            default: return "custom";
        }
    }

    private String chromePatternToRegex(String pattern) {
        return pattern.replace("://*.", "://[^/]*")
            .replace("<all_urls>", ".*")
            .replace("*", ".*")
            .replace("?", ".");
    }

    private String unescapeLocale(String text) {
        if (text == null) return "";
        if (text.startsWith("__MSG_") && text.endsWith("__")) {
            // Попытка извлечь имя из locales
            return text.replace("__MSG_", "").replace("__", "").replace("_", " ");
        }
        return text;
    }

    private File findManifest(File dir) {
        File manifest = new File(dir, "manifest.json");
        if (manifest.exists()) return manifest;
        File[] files = dir.listFiles();
        if (files != null) {
            for (File f : files) {
                if (f.isDirectory()) {
                    File result = findManifest(f);
                    if (result != null) return result;
                }
            }
        }
        return null;
    }

    private void copyResources(File sourceDir, File moduleDir) {
        File assetsDir = new File(moduleDir, "assets");
        if (!assetsDir.exists()) assetsDir.mkdirs();
        File[] files = sourceDir.listFiles();
        if (files != null) {
            for (File f : files) {
                if (f.isFile() && !f.getName().equals("manifest.json")) {
                    copyFile(f, new File(assetsDir, f.getName()));
                } else if (f.isDirectory() && !f.getName().equals("_locales")) {
                    copyDirectory(f, new File(assetsDir, f.getName()));
                }
            }
        }
    }

    private void copyDirectory(File source, File dest) {
        if (!dest.exists()) dest.mkdirs();
        File[] files = source.listFiles();
        if (files != null) {
            for (File f : files) {
                if (f.isFile()) copyFile(f, new File(dest, f.getName()));
                else copyDirectory(f, new File(dest, f.getName()));
            }
        }
    }

    private void copyFile(File source, File dest) {
        try {
            dest.getParentFile().mkdirs();
            FileInputStream fis = new FileInputStream(source);
            FileOutputStream fos = new FileOutputStream(dest);
            byte[] buffer = new byte[8192];
            int len;
            while ((len = fis.read(buffer)) != -1) fos.write(buffer, 0, len);
            fis.close();
            fos.close();
        } catch (IOException e) { /* ignore */ }
    }

    private void unzip(File zipFile, File destDir) throws IOException {
        if (!destDir.exists()) destDir.mkdirs();
        ZipInputStream zis = new ZipInputStream(new FileInputStream(zipFile));
        ZipEntry entry;
        while ((entry = zis.getNextEntry()) != null) {
            File outFile = new File(destDir, entry.getName());
            if (entry.isDirectory()) outFile.mkdirs();
            else {
                outFile.getParentFile().mkdirs();
                FileOutputStream fos = new FileOutputStream(outFile);
                byte[] buffer = new byte[8192];
                int len;
                while ((len = zis.read(buffer)) != -1) fos.write(buffer, 0, len);
                fos.close();
            }
            zis.closeEntry();
        }
        zis.close();
    }

    private String readFile(File file) throws IOException {
        BufferedReader reader = new BufferedReader(new FileReader(file));
        StringBuilder sb = new StringBuilder();
        String line;
        while ((line = reader.readLine()) != null) sb.append(line).append("\n");
        reader.close();
        return sb.toString();
    }

    private void writeFile(File file, String content) throws IOException {
        FileWriter writer = new FileWriter(file);
        writer.write(content);
        writer.close();
    }

    private boolean deleteRecursive(File file) {
        if (file.isDirectory()) {
            File[] children = file.listFiles();
            if (children != null) {
                for (File child : children) deleteRecursive(child);
            }
        }
        return file.delete();
    }

    public File getOutputDir() { return outputDir; }
}
