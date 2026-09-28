package com.wrapper.vpn.ext;

import android.content.Context;
import android.net.Uri;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Импорт серверов из браузерного расширения VPN (.crx / .xpi / .zip).
 *
 * Расширение нужно только как источник адресов серверов. Сам туннель
 * всегда нативный: VpnService + HTTP CONNECT. Браузерный код расширения
 * не исполняется.
 *
 * Что делает:
 *  1. снимает заголовок CRX2/CRX3 (это обычный zip с префиксом)
 *  2. читает manifest.json — ради версии и подтверждения, что это VPN-расширение
 *  3. ищет список серверов в json/js/pac по типовым ключам
 *
 * Если серверы вшиты не статически, а расширение тянет их с API
 * (так работает Browsec), то из файла их не достать — тогда
 * импортируется то, что найдено, а остальное добавляется вручную.
 */
public class CrxImporter {

    private static final String TAG = "CrxImporter";
    private static final int MAX_ENTRIES = 5000;
    private static final int MAX_TEXT_FILE = 6 * 1024 * 1024;
    private static final int MAX_TOTAL_UNCOMPRESSED = 64 * 1024 * 1024;

    /** Ключи, под которыми в конфигах лежат хосты. */
    private static final String[] HOST_KEYS = {
            "host", "hostname", "server", "address", "addr", "ip", "domain", "endpoint", "url"
    };
    /** Ключи с портами. */
    private static final String[] PORT_KEYS = {"port", "proxy_port", "server_port", "https_port"};
    /** Ключи со странами/именами. */
    private static final String[] NAME_KEYS = {"name", "title", "country", "label", "city", "id"};
    /** Ключи, где лежат вложенные списки серверов. */
    private static final String[] LIST_KEYS = {
            "servers", "countries", "locations", "nodes", "proxies", "endpoints", "data", "items"
    };

    /** Эвристика для голого "host:port" в тексте. */
    private static final Pattern HOST_PORT = Pattern.compile(
            "(?i)\\b((?:[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?\\.)+[a-z]{2,24}|\\d{1,3}(?:\\.\\d{1,3}){3})"
                    + "\\s*[:;,=]\\s*(\\d{2,5})\\b");

    /** Эвристика для PAC-скриптов вида PROXY host:port */
    private static final Pattern PAC_PROXY = Pattern.compile(
            "(?i)\\bPROXY\\s+((?:[a-z0-9.-]+\\.[a-z]{2,24}|\\d{1,3}(?:\\.\\d{1,3}){3}))\\s*:\\s*(\\d{2,5})");

    private static final Set<String> TEXT_EXT = new LinkedHashSet<>(Arrays.asList(
            "json", "js", "pac", "txt", "yaml", "yml", "cfg", "conf", "ini", "xml", "html"));

    private final Context context;

    public CrxImporter(Context context) {
        this.context = context.getApplicationContext();
    }

    public static class Result {
        public final List<Server> servers = new ArrayList<>();
        public String extensionName;
        public String extensionVersion;
        public int filesScanned;
        public boolean looksLikeVpnExtension;
        public String note;
    }

    public static class Server {
        public final String host;
        public final int port;
        public final String name;
        public final String country;

        public Server(String host, int port, String name, String country) {
            this.host = host;
            this.port = port;
            this.name = name != null && !name.isEmpty() ? name : host;
            this.country = country;
        }

        public String code() {
            String c = country != null ? country : name;
            String safe = c.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "_");
            if (safe.isEmpty()) safe = "srv";
            if (safe.length() > 20) safe = safe.substring(0, 20);
            return "ext_" + safe + "_" + port;
        }
    }

    public Result importFrom(Uri uri) throws IOException {
        Result result = new Result();
        byte[] payload = readAll(context.getContentResolver().openInputStream(uri));
        if (payload == null || payload.length == 0) {
            throw new IOException("Пустой файл");
        }

        byte[] zipBytes = stripCrxHeader(payload);
        Map<String, byte[]> entries = unzipToMap(zipBytes);

        for (Map.Entry<String, byte[]> e : entries.entrySet()) {
            String name = e.getKey().toLowerCase(Locale.ROOT);
            if (name.equals("manifest.json")) {
                parseManifest(e.getValue(), result);
            }
        }

        // Ищем серверы в текстовых файлах
        Map<String, Server> found = new LinkedHashMap<>();
        for (Map.Entry<String, byte[]> e : entries.entrySet()) {
            String lower = e.getKey().toLowerCase(Locale.ROOT);
            String ext = extensionOf(lower);
            if (!TEXT_EXT.contains(ext)) continue;
            if (e.getValue().length > MAX_TEXT_FILE) continue;

            String text = new String(e.getValue(), Charset.forName("UTF-8"));
            result.filesScanned++;
            collectFromJson(text, found);
            collectFromPac(text, found);
            collectFromRaw(text, found);
        }

        // Секция servers в manifest.json обработана отдельно
        result.servers.addAll(found.values());

        if (result.servers.isEmpty()) {
            result.note = "В расширении не нашлось статического списка серверов. "
                    + "Большинство VPN-расширений (включая Browsec) берут серверы с сервера "
                    + "через API после установки — их в файле нет. "
                    + "Добавь нужные адреса вручную через кнопку «Добавить сервер».";
        } else {
            result.note = "Найдено серверов: " + result.servers.size()
                    + " (просканировано файлов: " + result.filesScanned + ")";
        }
        return result;
    }

    // ===== CRX =====

    /**
     * CRX2: "Cr24" + version(4) + pubkey_len(4) + pubkey + sig_len(4) + sig + ZIP
     * CRX3: "Cr03" + version(4) + header_len(4) + header + ZIP
     */
    private byte[] stripCrxHeader(byte[] data) throws IOException {
        if (data.length < 4) return data;

        boolean isCrx = data[0] == 'C' && data[1] == 'r'
                && data[2] >= '0' && data[2] <= '9' && data[3] >= '0' && data[3] <= '9';

        if (!isCrx) {
            // обычный zip начинается с PK
            if (data[0] == 'P' && data[1] == 'K') return data;
            throw new IOException("Не .crx и не .zip");
        }

        int version = readIntLE(data, 4);
        int offset;
        if (version == 2) {
            int pubLen = readIntLE(data, 8);
            int sigLen = readIntLE(data, 12 + pubLen);
            offset = 16 + pubLen + sigLen;
        } else {
            int headerLen = readIntLE(data, 8);
            offset = 12 + headerLen;
        }

        if (offset <= 0 || offset >= data.length) {
            throw new IOException("Повреждённый заголовок CRX");
        }
        return Arrays.copyOfRange(data, offset, data.length);
    }

    private int readIntLE(byte[] b, int off) throws IOException {
        if (off + 4 > b.length) throw new IOException("Обрезанный заголовок CRX");
        return (b[off] & 0xFF)
                | ((b[off + 1] & 0xFF) << 8)
                | ((b[off + 2] & 0xFF) << 16)
                | ((b[off + 3] & 0xFF) << 24);
    }

    private Map<String, byte[]> unzipToMap(byte[] zipBytes) throws IOException {
        Map<String, byte[]> out = new LinkedHashMap<>();
        int total = 0;
        int count = 0;

        try (ZipInputStream zis = new ZipInputStream(new ByteArrayInputStream(zipBytes))) {
            ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                if (++count > MAX_ENTRIES) {
                    Log.w(TAG, "Слишком много записей, останавливаюсь");
                    break;
                }
                // Защита от zip-slip: пропускаем пути с ..
                String name = entry.getName();
                if (name == null || name.contains("..") || name.startsWith("/")) {
                    Log.w(TAG, "Пропущена подозрительная запись: " + name);
                    zis.closeEntry();
                    continue;
                }
                if (entry.isDirectory()) {
                    zis.closeEntry();
                    continue;
                }

                ByteArrayOutputStream bos = new ByteArrayOutputStream();
                byte[] buf = new byte[8192];
                int read;
                while ((read = zis.read(buf)) != -1) {
                    total += read;
                    if (total > MAX_TOTAL_UNCOMPRESSED) {
                        Log.w(TAG, "Превышен лимит распаковки");
                        zis.closeEntry();
                        return out;
                    }
                    bos.write(buf, 0, read);
                }
                out.put(name, bos.toByteArray());
                zis.closeEntry();
            }
        }
        return out;
    }

    private String extensionOf(String lowerName) {
        int dot = lowerName.lastIndexOf('.');
        return dot >= 0 && dot < lowerName.length() - 1 ? lowerName.substring(dot + 1) : "";
    }

    // ===== Manifest =====

    private void parseManifest(byte[] data, Result result) {
        try {
            JSONObject m = new JSONObject(new String(data, Charset.forName("UTF-8")));
            String name = m.optString("name", "");
            result.extensionName = resolveMsgName(name);
            result.extensionVersion = m.optString("version", "");

            JSONArray perms = m.optJSONArray("permissions");
            List<String> plist = new ArrayList<>();
            if (perms != null) {
                for (int i = 0; i < perms.length(); i++) plist.add(perms.optString(i));
            }
            JSONArray optPerms = m.optJSONArray("optional_permissions");
            if (optPerms != null) {
                for (int i = 0; i < optPerms.length(); i++) plist.add(optPerms.optString(i));
            }
            result.looksLikeVpnExtension = plist.contains("proxy")
                    || name.toLowerCase(Locale.ROOT).contains("vpn")
                    || plist.contains("vpnProvider");

        } catch (Exception e) {
            Log.d(TAG, "manifest.json не разобран: " + e.getMessage());
        }
    }

    /** "__MSG_extension_name__" -> берём из _locales/en/messages.json */
    private String resolveMsgName(String name) {
        if (name != null && name.startsWith("__MSG_") && name.endsWith("__")) {
            return "extension";
        }
        return name == null || name.isEmpty() ? "extension" : name;
    }

    // ===== Поиск серверов =====

    private void collectFromJson(String text, Map<String, Server> out) {
        // Пробуем распарсить как JSON целиком
        try {
            JSONObject root = new JSONObject(text);
            walkJson(root, "", out, 0);
            return;
        } catch (Exception ignored) {
        }
        try {
            JSONArray rootArr = new JSONArray(text);
            walkJsonArr(rootArr, "", out, 0);
            return;
        } catch (Exception ignored) {
        }

        // JSON-фрагменты внутри JS: ищем объекты с host/port
        Matcher objM = Pattern.compile(
                "\\{[^{}]{0,400}\"[^\"]*\\b(?:" + joinAlt(HOST_KEYS) + ")\"[^{}]{0,400}\\}",
                Pattern.CASE_INSENSITIVE).matcher(text);
        while (objM.find()) {
            String frag = objM.group();
            String host = firstString(frag, HOST_KEYS);
            int port = firstInt(frag, PORT_KEYS);
            if (host != null && port > 0) {
                put(out, new Server(host, port, firstString(frag, NAME_KEYS), firstString(frag, NAME_KEYS)));
            }
        }
    }

    private void walkJson(Object node, String country, Map<String, Server> out, int depth) {
        if (node instanceof JSONObject) {
            JSONObject o = (JSONObject) node;

            String host = firstString(o, HOST_KEYS);
            int port = firstInt(o, PORT_KEYS);
            if (host != null && port > 0) {
                String nm = firstString(o, NAME_KEYS);
                put(out, new Server(host, port, nm, country != null ? country : nm));
                return;
            }

            for (String key : LIST_KEYS) {
                Object child = o.opt(key);
                if (child != null) {
                    walkJson(child, country != null ? country : key, out, depth + 1);
                }
            }
            // обход всех полей, если по ключам ничего не нашли
            if (depth < 6) {
                java.util.Iterator<String> it = o.keys();
                while (it.hasNext()) {
                    String k = it.next();
                    if (Arrays.asList(LIST_KEYS).contains(k)) continue;
                    Object v = o.opt(k);
                    if (v instanceof JSONObject || v instanceof JSONArray) {
                        String c = isCountryKey(k) ? k : country;
                        walkJson(v, c, out, depth + 1);
                    }
                }
            }
        } else if (node instanceof JSONArray) {
            walkJsonArr((JSONArray) node, country, out, depth);
        }
    }

    private void walkJsonArr(JSONArray arr, String country, Map<String, Server> out, int depth) {
        if (depth > 6) return;
        for (int i = 0; i < arr.length(); i++) {
            Object v = arr.opt(i);
            if (v instanceof JSONObject || v instanceof JSONArray) {
                walkJson(v, country, out, depth + 1);
            }
        }
    }

    private boolean isCountryKey(String key) {
        String k = key.toLowerCase(Locale.ROOT);
        if (k.length() == 2 && k.charAt(0) >= 'a' && k.charAt(0) <= 'z') return true;
        return k.equals("country") || k.equals("region") || k.equals("location");
    }

    private void collectFromPac(String text, Map<String, Server> out) {
        Matcher m = PAC_PROXY.matcher(text);
        while (m.find()) {
            String host = m.group(1);
            int port;
            try {
                port = Integer.parseInt(m.group(2));
            } catch (NumberFormatException e) {
                continue;
            }
            if (validHost(host) && port > 0 && port <= 65535) {
                put(out, new Server(host, port, host, null));
            }
        }
    }

    private void collectFromRaw(String text, Map<String, Server> out) {
        Matcher m = HOST_PORT.matcher(text);
        while (m.find()) {
            String host = m.group(1);
            int port;
            try {
                port = Integer.parseInt(m.group(2));
            } catch (NumberFormatException e) {
                continue;
            }
            if (validHost(host) && port > 0 && port <= 65535) {
                put(out, new Server(host, port, host, null));
            }
        }
    }

    private void put(Map<String, Server> out, Server s) {
        if (s == null || !validHost(s.host)) return;
        if (s.port <= 0 || s.port > 65535) return;
        out.putIfAbsent(s.host + ":" + s.port, s);
    }

    private boolean validHost(String h) {
        if (h == null || h.isEmpty() || h.length() > 253) return false;
        // отсекаем очевидный мусор: версии, даты, css
        if (h.matches("\\d+\\.\\d+\\.\\d+\\.\\d+")) {
            String[] p = h.split("\\.");
            for (String s : p) {
                try {
                    int v = Integer.parseInt(s);
                    if (v < 0 || v > 255) return false;
                } catch (NumberFormatException e) {
                    return false;
                }
            }
            return true;
        }
        return h.contains(".") && h.matches("(?i)[a-z0-9.-]+");
    }

    private String joinAlt(String[] keys) {
        StringBuilder sb = new StringBuilder();
        for (String k : keys) {
            if (sb.length() > 0) sb.append('|');
            sb.append(Pattern.quote(k));
        }
        return sb.toString();
    }

    private String firstString(JSONObject o, String[] keys) {
        for (String k : keys) {
            String v = o.optString(k, "");
            if (v != null && !v.isEmpty() && !v.startsWith("__MSG")) return v;
        }
        return null;
    }

    private int firstInt(JSONObject o, String[] keys) {
        for (String k : keys) {
            if (o.has(k)) {
                try {
                    return o.getInt(k);
                } catch (Exception ignored) {
                }
            }
        }
        return -1;
    }

    private String firstString(String text, String[] keys) {
        for (String k : keys) {
            Matcher m = Pattern.compile("\"" + Pattern.quote(k) + "\"\\s*:\\s*\"([^\"]{1,120})\"",
                    Pattern.CASE_INSENSITIVE).matcher(text);
            if (m.find()) return m.group(1);
        }
        return null;
    }

    private int firstInt(String text, String[] keys) {
        for (String k : keys) {
            Matcher m = Pattern.compile("\"" + Pattern.quote(k) + "\"\\s*:\\s*\"?(\\d{2,5})",
                    Pattern.CASE_INSENSITIVE).matcher(text);
            if (m.find()) {
                try {
                    return Integer.parseInt(m.group(1));
                } catch (NumberFormatException ignored) {
                }
            }
        }
        return -1;
    }

    private byte[] readAll(InputStream in) throws IOException {
        if (in == null) throw new IOException("Не удалось открыть файл");
        try (InputStream stream = in) {
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int read;
            while ((read = stream.read(buf)) != -1) {
                bos.write(buf, 0, read);
                if (bos.size() > 128 * 1024 * 1024) {
                    throw new IOException("Файл слишком большой");
                }
            }
            return bos.toByteArray();
        }
    }
}
