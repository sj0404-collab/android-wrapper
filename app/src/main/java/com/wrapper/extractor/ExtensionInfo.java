package com.wrapper.extractor;

import org.json.JSONArray;
import org.json.JSONObject;
import java.util.ArrayList;
import java.util.List;

/**
 * Информация о расширении браузера
 */
public class ExtensionInfo {
    private String name;
    private String version;
    private String description;
    private String sourcePath;
    private int type;
    private JSONObject manifest;
    private JSONArray permissions;
    private JSONArray backgroundScripts;
    private String serviceWorker;
    private JSONObject browserAction;
    private JSONArray webAccessibleResources;
    private List<ContentScript> contentScripts = new ArrayList<>();

    // Сконвертированные данные
    private JSONObject androidConfig;
    private List<String> injectedScripts = new ArrayList<>();

    // Getters and Setters
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public String getVersion() { return version; }
    public void setVersion(String version) { this.version = version; }

    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }

    public String getSourcePath() { return sourcePath; }
    public void setSourcePath(String sourcePath) { this.sourcePath = sourcePath; }

    public int getType() { return type; }
    public void setType(int type) { this.type = type; }

    public JSONObject getManifest() { return manifest; }
    public void setManifest(JSONObject manifest) { this.manifest = manifest; }

    public JSONArray getPermissions() { return permissions; }
    public void setPermissions(JSONArray permissions) { this.permissions = permissions; }

    public JSONArray getBackgroundScripts() { return backgroundScripts; }
    public void setBackgroundScripts(JSONArray backgroundScripts) { this.backgroundScripts = backgroundScripts; }

    public String getServiceWorker() { return serviceWorker; }
    public void setServiceWorker(String serviceWorker) { this.serviceWorker = serviceWorker; }

    public JSONObject getBrowserAction() { return browserAction; }
    public void setBrowserAction(JSONObject browserAction) { this.browserAction = browserAction; }

    public JSONArray getWebAccessibleResources() { return webAccessibleResources; }
    public void setWebAccessibleResources(JSONArray webAccessibleResources) { this.webAccessibleResources = webAccessibleResources; }

    public List<ContentScript> getContentScripts() { return contentScripts; }
    public void addContentScript(ContentScript script) { this.contentScripts.add(script); }

    public JSONObject getAndroidConfig() { return androidConfig; }
    public void setAndroidConfig(JSONObject androidConfig) { this.androidConfig = androidConfig; }

    public List<String> getInjectedScripts() { return injectedScripts; }
    public void setInjectedScripts(List<String> injectedScripts) { this.injectedScripts = injectedScripts; }

    /**
     * Получение списка JS файлов для инъекции
     */
    public List<String> getAllJsFiles() {
        List<String> jsFiles = new ArrayList<>();
        for (ContentScript cs : contentScripts) {
            if (cs.getJs() != null) {
                for (int i = 0; i < cs.getJs().length(); i++) {
                    jsFiles.add(cs.getJs().optString(i));
                }
            }
        }
        return jsFiles;
    }

    /**
     * Получение списка CSS файлов
     */
    public List<String> getAllCssFiles() {
        List<String> cssFiles = new ArrayList<>();
        for (ContentScript cs : contentScripts) {
            if (cs.getCss() != null) {
                for (int i = 0; i < cs.getCss().length(); i++) {
                    cssFiles.add(cs.getCss().optString(i));
                }
            }
        }
        return cssFiles;
    }

    /**
     * Получение URL паттернов для матчинга
     */
    public List<String> getMatchPatterns() {
        List<String> patterns = new ArrayList<>();
        for (ContentScript cs : contentScripts) {
            if (cs.getMatches() != null) {
                for (int i = 0; i < cs.getMatches().length(); i++) {
                    patterns.add(cs.getMatches().optString(i));
                }
            }
        }
        return patterns;
    }
}
