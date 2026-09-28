package com.wrapper.extractor;

import java.io.File;

/**
 * Результат конвертации расширения
 */
public class ConvertedModule {
    public String name;
    public String version;
    public String description;
    public int type;
    public boolean success;
    public String error;
    public File moduleDir;
    public org.json.JSONObject moduleConfig;
    public org.json.JSONObject manifest;

    public String getName() { return name; }
    public String getVersion() { return version; }
    public boolean isSuccess() { return success; }
    public File getModuleDir() { return moduleDir; }
}
