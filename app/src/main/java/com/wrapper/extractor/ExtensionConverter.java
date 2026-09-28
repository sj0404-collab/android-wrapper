package com.wrapper.extractor;

import android.util.Log;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.File;
import java.io.FileWriter;
import java.util.List;

/**
 * Конвертер расширений браузера в Android-модули
 */
public class ExtensionConverter {
    private static final String TAG = "ExtensionConverter";

    /**
     * Конвертация расширения в Android-совместимый модуль
     */
    public JSONObject convertToAndroidModule(ExtensionInfo info) {
        JSONObject module = new JSONObject();

        try {
            // Базовая информация
            module.put("name", info.getName());
            module.put("version", info.getVersion());
            module.put("description", info.getDescription());
            module.put("type", "browser_extension");
            module.put("original_type", getTypeName(info.getType()));

            // Конвертация permissions в Android permissions
            JSONArray androidPerms = convertPermissions(info.getPermissions());
            module.put("permissions", androidPerms);

            // Конвертация content scripts
            JSONArray scriptsConfig = new JSONArray();
            List<String> jsFiles = info.getAllJsFiles();
            List<String> cssFiles = info.getAllCssFiles();
            List<String> patterns = info.getMatchPatterns();

            for (String jsFile : jsFiles) {
                JSONObject script = new JSONObject();
                script.put("file", jsFile);
                script.put("type", "javascript");
                script.put("patterns", new JSONArray(patterns));
                scriptsConfig.put(script);
            }

            for (String cssFile : cssFiles) {
                JSONObject style = new JSONObject();
                style.put("file", cssFile);
                style.put("type", "css");
                style.put("patterns", new JSONArray(patterns));
                scriptsConfig.put(style);
            }

            module.put("scripts", scriptsConfig);

            // Настройки инъекции
            JSONObject injectConfig = new JSONObject();
            injectConfig.put("auto_inject", true);
            injectConfig.put("inject_into", new JSONArray()
                .put("android.webkit.WebView")
                .put("android.app.Activity"));
            injectConfig.put("match_urls", new JSONArray(patterns));
            module.put("inject_config", injectConfig);

            // API мост для связи с нативным кодом
            JSONObject apiBridge = new JSONObject();
            apiBridge.put("enabled", true);
            apiBridge.put("methods", new JSONArray()
                .put("speak")      // TTS
                .put("recognize")  // OCR
                .put("translate")  // Перевод
                .put("overlay")    // Показать оверлей
                .put("storage")    // Хранилище
                .put("http")       // HTTP запросы
            );
            module.put("api_bridge", apiBridge);

            Log.d(TAG, "Модуль сконвертирован: " + info.getName());

        } catch (Exception e) {
            Log.e(TAG, "Ошибка конвертации", e);
        }

        return module;
    }

    /**
     * Конвертация permissions из Chrome в Android
     */
    private JSONArray convertPermissions(JSONArray chromePerms) {
        JSONArray androidPerms = new JSONArray();

        if (chromePerms == null) return androidPerms;

        for (int i = 0; i < chromePerms.length(); i++) {
            String perm = chromePerms.optString(i);
            switch (perm) {
                case "activeTab":
                case "tabs":
                    androidPerms.put("ACCESSIBILITY_SERVICE");
                    break;
                case "storage":
                    androidPerms.put("WRITE_EXTERNAL_STORAGE");
                    break;
                case "notifications":
                    androidPerms.put("POST_NOTIFICATIONS");
                    break;
                case "contextMenus":
                    androidPerms.put("SYSTEM_ALERT_WINDOW");
                    break;
                case "webRequest":
                case "webRequestBlocking":
                    androidPerms.put("INTERNET");
                    break;
                case "camera":
                    androidPerms.put("CAMERA");
                    break;
                case "microphone":
                    androidPerms.put("RECORD_AUDIO");
                    break;
                case "geolocation":
                    androidPerms.put("ACCESS_FINE_LOCATION");
                    break;
                default:
                    // Оставляем как есть для кастомных
                    androidPerms.put(perm);
                    break;
            }
        }

        return androidPerms;
    }

    /**
     * Сохранение сконвертированного модуля
     */
    public boolean saveModule(JSONObject module, File outputDir) {
        try {
            String name = module.optString("name", "unknown").replaceAll("[^a-zA-Z0-9]", "_");
            File moduleDir = new File(outputDir, name);
            if (!moduleDir.exists()) moduleDir.mkdirs();

            // Сохраняем конфигурацию
            File configFile = new File(moduleDir, "module.json");
            FileWriter writer = new FileWriter(configFile);
            writer.write(module.toString(2));
            writer.close();

            Log.d(TAG, "Модуль сохранён: " + configFile.getAbsolutePath());
            return true;
        } catch (Exception e) {
            Log.e(TAG, "Ошибка сохранения модуля", e);
            return false;
        }
    }

    private String getTypeName(int type) {
        switch (type) {
            case ExtensionExtractor.TYPE_CHROME: return "chrome";
            case ExtensionExtractor.TYPE_FIREFOX: return "firefox";
            case ExtensionExtractor.TYPE_EDGE: return "edge";
            default: return "custom";
        }
    }
}
