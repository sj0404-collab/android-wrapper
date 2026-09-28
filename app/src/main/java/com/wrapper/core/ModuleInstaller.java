package com.wrapper.core;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.provider.DocumentsContract;
import android.util.Log;
import androidx.documentfile.provider.DocumentFile;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * Менеджер загрузки и установки модулей через SAF
 */
public class ModuleInstaller {
    private static final String TAG = "ModuleInstaller";
    private final Context context;
    private final File modulesDir;

    public static final int REQUEST_CODE_OPEN_DOCUMENT = 1001;
    public static final int REQUEST_CODE_OPEN_DIRECTORY = 1002;

    public ModuleInstaller(Context context) {
        this.context = context;
        this.modulesDir = new File(context.getFilesDir(), "modules");
        if (!modulesDir.exists()) {
            modulesDir.mkdirs();
        }
    }

    /**
     * Создание Intent для выбора JSON файла конфигурации
     */
    public Intent createOpenConfigIntent() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("application/json");
        return intent;
    }

    /**
     * Создание Intent для выбора директории с модулями
     */
    public Intent createOpenDirectoryIntent() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
        return intent;
    }

    /**
     * Установка модуля из URI
     */
    public boolean installModule(Uri sourceUri, String moduleName) {
        try {
            File moduleDir = new File(modulesDir, moduleName);
            if (!moduleDir.exists()) {
                moduleDir.mkdirs();
            }

            DocumentFile sourceFile = DocumentFile.fromSingleUri(context, sourceUri);
            if (sourceFile == null || !sourceFile.exists()) {
                Log.e(TAG, "Исходный файл не найден");
                return false;
            }

            String fileName = sourceFile.getName();
            File destFile = new File(moduleDir, fileName);

            InputStream is = context.getContentResolver().openInputStream(sourceUri);
            FileOutputStream fos = new FileOutputStream(destFile);

            byte[] buffer = new byte[8192];
            int bytesRead;
            while ((bytesRead = is.read(buffer)) != -1) {
                fos.write(buffer, 0, bytesRead);
            }

            is.close();
            fos.close();

            Log.d(TAG, "Модуль установлен: " + destFile.getAbsolutePath());
            return true;
        } catch (Exception e) {
            Log.e(TAG, "Ошибка установки модуля", e);
            return false;
        }
    }

    /**
     * Установка всех модулей из директории
     */
    public List<String> installModulesFromDirectory(Uri treeUri) {
        List<String> installedModules = new ArrayList<>();
        DocumentFile directory = DocumentFile.fromTreeUri(context, treeUri);

        if (directory == null || !directory.exists()) {
            Log.e(TAG, "Директория не найдена");
            return installedModules;
        }

        for (DocumentFile file : directory.listFiles()) {
            if (file.isDirectory()) {
                String moduleName = file.getName();
                if (installModuleDirectory(file, moduleName)) {
                    installedModules.add(moduleName);
                }
            } else if (file.getName().endsWith(".json")) {
                String moduleName = file.getName().replace(".json", "");
                if (installModule(file.getUri(), moduleName)) {
                    installedModules.add(moduleName);
                }
            }
        }

        return installedModules;
    }

    private boolean installModuleDirectory(DocumentFile sourceDir, String moduleName) {
        try {
            File moduleDir = new File(modulesDir, moduleName);
            if (!moduleDir.exists()) {
                moduleDir.mkdirs();
            }

            for (DocumentFile file : sourceDir.listFiles()) {
                File destFile = new File(moduleDir, file.getName());
                InputStream is = context.getContentResolver().openInputStream(file.getUri());
                FileOutputStream fos = new FileOutputStream(destFile);

                byte[] buffer = new byte[8192];
                int bytesRead;
                while ((bytesRead = is.read(buffer)) != -1) {
                    fos.write(buffer, 0, bytesRead);
                }

                is.close();
                fos.close();
            }

            Log.d(TAG, "Модуль установлен: " + moduleName);
            return true;
        } catch (Exception e) {
            Log.e(TAG, "Ошибка установки модуля: " + moduleName, e);
            return false;
        }
    }

    /**
     * Получение списка установленных модулей
     */
    public List<String> getInstalledModules() {
        List<String> modules = new ArrayList<>();
        File[] dirs = modulesDir.listFiles();
        if (dirs != null) {
            for (File dir : dirs) {
                if (dir.isDirectory()) {
                    modules.add(dir.getName());
                }
            }
        }
        return modules;
    }

    /**
     * Проверка установлен ли модуль
     */
    public boolean isModuleInstalled(String moduleName) {
        File moduleDir = new File(modulesDir, moduleName);
        return moduleDir.exists() && moduleDir.isDirectory();
    }

    /**
     * Удаление модуля
     */
    public boolean uninstallModule(String moduleName) {
        File moduleDir = new File(modulesDir, moduleName);
        if (moduleDir.exists()) {
            return deleteRecursive(moduleDir);
        }
        return false;
    }

    private boolean deleteRecursive(File file) {
        if (file.isDirectory()) {
            File[] children = file.listFiles();
            if (children != null) {
                for (File child : children) {
                    if (!deleteRecursive(child)) {
                        return false;
                    }
                }
            }
        }
        return file.delete();
    }

    public File getModulesDir() {
        return modulesDir;
    }
}
