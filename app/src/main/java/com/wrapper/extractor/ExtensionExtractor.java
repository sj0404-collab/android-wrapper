package com.wrapper.extractor;

import android.content.Context;
import android.net.Uri;
import android.util.Log;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.*;
import java.util.*;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Парсер и конвертер расширений браузеров (Chrome/Firefox)
 * в нативные Android-модули
 */
public class ExtensionExtractor {
    private static final String TAG = "ExtensionExtractor";
    private final Context context;
    private final File outputDir;

    // Типы расширений
    public static final int TYPE_CHROME = 1;
    public static final int TYPE_FIREFOX = 2;
    public static final int TYPE_EDGE = 3;
    public static final int TYPE_CUSTOM = 4;

    public ExtensionExtractor(Context context) {
        this.context = context;
        this.outputDir = new File(context.getFilesDir(), "extensions");
        if (!outputDir.exists()) outputDir.mkdirs();
    }

    /**
     * Извлечение расширения из .crx/.xpi/.zip файла
     */
    public ExtensionInfo extract(Uri sourceUri) {
        try {
            String fileName = getFileName(sourceUri);
            int type = detectType(fileName);

            InputStream is = context.getContentResolver().openInputStream(sourceUri);
            File extDir = new File(outputDir, getNameWithoutExtension(fileName));
            if (!extDir.exists()) extDir.mkdirs();

            // Распаковка
            unzip(is, extDir);
            is.close();

            // Парсинг манифеста
            ExtensionInfo info = parseManifest(extDir, type);
            info.setSourcePath(extDir.getAbsolutePath());
            info.setType(type);

            Log.d(TAG, "Расширение извлечено: " + info.getName());
            return info;
        } catch (Exception e) {
            Log.e(TAG, "Ошибка извлечения", e);
            return null;
        }
    }

    /**
     * Парсинг manifest.json
     */
    private ExtensionInfo parseManifest(File extDir, int type) {
        ExtensionInfo info = new ExtensionInfo();
        File manifestFile = new File(extDir, "manifest.json");

        if (!manifestFile.exists()) {
            Log.e(TAG, "manifest.json не найден");
            return info;
        }

        try {
            String json = readFile(manifestFile);
            JSONObject manifest = new JSONObject(json);

            info.setName(manifest.optString("name", "Unknown"));
            info.setVersion(manifest.optString("version", "1.0"));
            info.setDescription(manifest.optString("description", ""));

            // Парсинг content_scripts
            if (manifest.has("content_scripts")) {
                JSONArray contentScripts = manifest.getJSONArray("content_scripts");
                for (int i = 0; i < contentScripts.length(); i++) {
                    JSONObject cs = contentScripts.getJSONObject(i);
                    ContentScript script = new ContentScript();
                    script.setMatches(cs.optJSONArray("matches"));
                    script.setJs(cs.optJSONArray("js"));
                    script.setCss(cs.optJSONArray("css"));
                    script.setRunAt(cs.optString("run_at", "document_idle"));
                    info.addContentScript(script);
                }
            }

            // Парсинг background scripts
            if (manifest.has("background")) {
                JSONObject bg = manifest.getJSONObject("background");
                if (bg.has("scripts")) {
                    info.setBackgroundScripts(bg.getJSONArray("scripts"));
                }
                if (bg.has("service_worker")) {
                    info.setServiceWorker(bg.getString("service_worker"));
                }
            }

            // Парсинг permissions
            if (manifest.has("permissions")) {
                JSONArray perms = manifest.getJSONArray("permissions");
                info.setPermissions(perms);
            }

            // Парсинг browser_action / action
            if (manifest.has("browser_action")) {
                info.setBrowserAction(manifest.getJSONObject("browser_action"));
            } else if (manifest.has("action")) {
                info.setBrowserAction(manifest.getJSONObject("action"));
            }

            // Парсинг web_accessible_resources
            if (manifest.has("web_accessible_resources")) {
                info.setWebAccessibleResources(manifest.getJSONArray("web_accessible_resources"));
            }

            // Сохранение манифеста
            info.setManifest(manifest);

        } catch (Exception e) {
            Log.e(TAG, "Ошибка парсинга манифеста", e);
        }

        return info;
    }

    /**
     * Распаковка ZIP архива
     */
    private void unzip(InputStream is, File destDir) throws Exception {
        ZipInputStream zis = new ZipInputStream(new BufferedInputStream(is));
        ZipEntry entry;
        while ((entry = zis.getNextEntry()) != null) {
            File file = new File(destDir, entry.getName());
            if (entry.isDirectory()) {
                file.mkdirs();
            } else {
                file.getParentFile().mkdirs();
                FileOutputStream fos = new FileOutputStream(file);
                byte[] buffer = new byte[8192];
                int len;
                while ((len = zis.read(buffer)) != -1) {
                    fos.write(buffer, 0, len);
                }
                fos.close();
            }
            zis.closeEntry();
        }
        zis.close();
    }

    /**
     * Определение типа расширения по имени файла
     */
    private int detectType(String fileName) {
        if (fileName.endsWith(".crx")) return TYPE_CHROME;
        if (fileName.endsWith(".xpi")) return TYPE_FIREFOX;
        if (fileName.endsWith(".zip")) return TYPE_CUSTOM;
        return TYPE_CUSTOM;
    }

    private String getFileName(Uri uri) {
        String path = uri.getPath();
        if (path == null) return "unknown";
        int lastSlash = path.lastIndexOf('/');
        return lastSlash >= 0 ? path.substring(lastSlash + 1) : path;
    }

    private String getNameWithoutExtension(String fileName) {
        int dot = fileName.lastIndexOf('.');
        return dot >= 0 ? fileName.substring(0, dot) : fileName;
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

    public File getOutputDir() {
        return outputDir;
    }
}
