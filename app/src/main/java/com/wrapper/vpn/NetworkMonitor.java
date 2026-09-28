package com.wrapper.vpn;

import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import java.io.*;
import java.net.*;
import java.util.*;
import java.util.concurrent.*;

public class NetworkMonitor {
    private static final String TAG = "NetMonitor";

    private ScheduledExecutorService scheduler;
    private volatile boolean monitoring = false;

    private volatile long lastPing = -1;
    private volatile long lastDownloadSpeed = 0;
    private volatile long lastJitter = 0;

    private final Handler uiHandler = new Handler(Looper.getMainLooper());
    private Runnable updateCallback;
    private Runnable speedUpdateCallback;

    private final List<Long> pingHistory = new ArrayList<>();
    private static final int PING_HISTORY_SIZE = 10;

    private static final String[] PING_HOSTS = {
        "8.8.8.8", "1.1.1.1", "208.67.222.222"
    };
    private static final int PING_PORT = 443;
    private static final int PING_TIMEOUT = 3000;

    private BrowsecVpnService vpnService;

    public void setUpdateCallback(Runnable callback) {
        this.updateCallback = callback;
    }

    public void setSpeedUpdateCallback(Runnable callback) {
        this.speedUpdateCallback = callback;
    }

    public void setVpnService(BrowsecVpnService service) {
        this.vpnService = service;
    }

    public void start() {
        if (monitoring) return;
        monitoring = true;
        pingHistory.clear();
        scheduler = Executors.newScheduledThreadPool(2);

        scheduler.scheduleAtFixedRate(() -> {
            measurePing();
            notifyUpdate();
        }, 0, 1, TimeUnit.SECONDS);

        scheduler.scheduleAtFixedRate(() -> {
            measureSpeed();
            notifyUpdate();
        }, 0, 1, TimeUnit.SECONDS);

        Log.d(TAG, "NetworkMonitor started");
    }

    public void stop() {
        monitoring = false;
        if (scheduler != null) {
            scheduler.shutdownNow();
            scheduler = null;
        }
        lastPing = -1;
        lastDownloadSpeed = 0;
        lastJitter = 0;
        pingHistory.clear();
        Log.d(TAG, "NetworkMonitor stopped");
    }

    private void notifyUpdate() {
        if (updateCallback != null) {
            uiHandler.post(updateCallback);
        }
    }

    private void measurePing() {
        long totalPing = 0;
        int successCount = 0;

        for (String host : PING_HOSTS) {
            long ping = tcpPing(host, PING_PORT);
            if (ping > 0) {
                totalPing += ping;
                successCount++;
            }
        }

        if (successCount > 0) {
            long avgPing = totalPing / successCount;
            lastPing = avgPing;

            synchronized (pingHistory) {
                pingHistory.add(avgPing);
                if (pingHistory.size() > PING_HISTORY_SIZE) {
                    pingHistory.remove(0);
                }
                if (pingHistory.size() >= 2) {
                    long jitterSum = 0;
                    for (int i = 1; i < pingHistory.size(); i++) {
                        jitterSum += Math.abs(pingHistory.get(i) - pingHistory.get(i - 1));
                    }
                    lastJitter = jitterSum / (pingHistory.size() - 1);
                }
            }
        } else {
            lastPing = -1;
        }
    }

    private long tcpPing(String host, int port) {
        Socket socket = null;
        try {
            socket = new Socket();
            long start = System.currentTimeMillis();
            socket.connect(new InetSocketAddress(host, port), PING_TIMEOUT);
            return System.currentTimeMillis() - start;
        } catch (Exception e) {
            return -1;
        } finally {
            try { if (socket != null) socket.close(); } catch (IOException ignored) {}
        }
    }

    private void measureSpeed() {
        if (vpnService != null && vpnService.isRunning()) {
            long speed = vpnService.getCurrentSpeed();
            lastDownloadSpeed = speed;
        } else {
            measureSpeedDirect();
        }
    }

    private void measureSpeedDirect() {
        new Thread(() -> {
            try {
                long totalBytes = 0;
                long totalTime = 0;

                String[] testUrls = {
                    "http://speedtest.tele2.net/1MB.zip",
                    "http://proof.ovh.net/files/1Mb.dat"
                };

                for (String urlStr : testUrls) {
                    try {
                        long start = System.currentTimeMillis();
                        URL url = new URL(urlStr);
                        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                        conn.setConnectTimeout(5000);
                        conn.setReadTimeout(8000);
                        conn.setRequestMethod("GET");
                        conn.setDoInput(true);

                        InputStream is = conn.getInputStream();
                        byte[] buffer = new byte[16384];
                        int read;
                        long bytesRead = 0;
                        while ((read = is.read(buffer)) != -1) {
                            bytesRead += read;
                            if (bytesRead > 512 * 1024) break;
                            if (System.currentTimeMillis() - start > 5000) break;
                        }
                        is.close();
                        conn.disconnect();

                        long elapsed = System.currentTimeMillis() - start;
                        if (elapsed > 100 && bytesRead > 0) {
                            totalBytes += bytesRead;
                            totalTime += elapsed;
                        }
                    } catch (Exception e) {
                        // skip
                    }
                }

                if (totalTime > 0) {
                    lastDownloadSpeed = (totalBytes * 8 * 1000) / totalTime;
                }
            } catch (Exception e) {
                // ignore
            }
        }).start();
    }

    public static String formatSpeed(long bps) {
        if (bps <= 0) return "—";
        if (bps < 1000) return bps + " bps";
        if (bps < 1000000) return String.format("%.1f Kbps", bps / 1000.0);
        return String.format("%.2f Mbps", bps / 1000000.0);
    }

    public static String formatPing(long ms) {
        if (ms < 0) return "—";
        if (ms < 80) return ms + "ms 🟢";
        if (ms < 200) return ms + "ms 🟡";
        return ms + "ms 🔴";
    }

    public static String formatBytes(long bytes) {
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) return String.format("%.1f KB", bytes / 1024.0);
        if (bytes < 1024 * 1024 * 1024) return String.format("%.1f MB", bytes / (1024.0 * 1024));
        return String.format("%.2f GB", bytes / (1024.0 * 1024 * 1024));
    }

    public long getLastPing() { return lastPing; }
    public long getLastDownloadSpeed() { return lastDownloadSpeed; }
    public long getLastJitter() { return lastJitter; }
    public boolean isMonitoring() { return monitoring; }
}
