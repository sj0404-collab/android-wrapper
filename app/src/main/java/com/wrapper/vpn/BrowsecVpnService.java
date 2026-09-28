package com.wrapper.vpn;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.net.VpnService;
import android.os.Build;
import android.os.IBinder;
import android.os.ParcelFileDescriptor;
import android.util.Log;

import androidx.core.app.NotificationCompat;

import com.wrapper.R;
import com.wrapper.ui.MainActivity;

import java.io.ByteArrayOutputStream;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;

/**
 * VPN-сервис: поднимает VpnService-интерфейс, гоняет трафик через выбранный
 * сервер по HTTP CONNECT и держит foreground-уведомление, чтобы работать
 * в фоне.
 */
public class BrowsecVpnService extends VpnService {

    public static final String ACTION_START = "com.wrapper.vpn.START";
    public static final String ACTION_STOP  = "com.wrapper.vpn.STOP";
    public static final String ACTION_TOGGLE = "com.wrapper.vpn.TOGGLE";

    public static final String EXTRA_COUNTRY = "country";
    public static final String EXTRA_ALL_APPS = "allApps";
    public static final String EXTRA_APPS     = "apps";

    private static final String TAG = "BrowsecVPN";
    private static final String CHANNEL_ID = "vpn_channel";
    private static final String CHANNEL_NAME = "VPN status";
    private static final int NOTIFICATION_ID = 1001;
    private static final int MAX_PACKET = 65535;
    private static final int BUFFER_SIZE = 32767;

    public static final String PREFS = "vpn_state";
    public static final String PREF_SERVER = "server";
    public static final String PREF_ALL_APPS = "all_apps";
    public static final String PREF_APPS = "apps";
    public static final String PREF_WAS_CONNECTED = "was_connected";
    public static final String PREF_KILL_SWITCH = "kill_switch";

    private final IBinder binder = new LocalBinder();

    public class LocalBinder extends android.os.Binder {
        public BrowsecVpnService getService() {
            return BrowsecVpnService.this;
        }
    }

    @Override
    public IBinder onBind(Intent intent) {
        return binder;
    }

    private ParcelFileDescriptor vpnInterface;
    private ExecutorService executor;
    private volatile boolean running;
    private volatile VpnServer currentServer;
    private final Set<String> allowedApps = new HashSet<>();
    private boolean allApps = true;
    private NotificationManager nm;

    private final AtomicLong bytesSent = new AtomicLong();
    private final AtomicLong bytesReceived = new AtomicLong();
    private final AtomicLong lastSpeedBytes = new AtomicLong();
    private volatile long currentSpeedBps;

    private final ConcurrentHashMap<String, Tunnel> tunnels = new ConcurrentHashMap<>();

    private static final Map<String, VpnServer> SERVERS = new LinkedHashMap<>();
    static {
        SERVERS.put("dns_google",    new VpnServer("dns_google",    "DNS Google",       "8.8.8.8",         53,  "🇺🇸"));
        SERVERS.put("dns_cloudflare", new VpnServer("dns_cloudflare", "DNS Cloudflare",   "1.1.1.1",         53,  "🇺🇸"));
        SERVERS.put("dns_quad9",     new VpnServer("dns_quad9",     "DNS Quad9",        "9.9.9.9",         53,  "🇨🇭"));
        SERVERS.put("dns_opendns",   new VpnServer("dns_opendns",   "DNS OpenDNS",      "208.67.222.222",  53,  "🇺🇸"));
        SERVERS.put("dns_adguard",   new VpnServer("dns_adguard",   "DNS AdGuard",      "94.140.14.14",    53,  "🇨🇾"));

        SERVERS.put("proxy_nl",  new VpnServer("proxy_nl",  "Proxy NL",    "185.199.228.220",  8080, "🇳🇱"));
        SERVERS.put("proxy_us",  new VpnServer("proxy_us",  "Proxy US",    "45.77.65.140",     3128, "🇺🇸"));
        SERVERS.put("proxy_de",  new VpnServer("proxy_de",  "Proxy DE",    "138.201.152.200",  8080, "🇩🇪"));
        SERVERS.put("proxy_fr",  new VpnServer("proxy_fr",  "Proxy FR",    "51.158.165.200",   8080, "🇫🇷"));
        SERVERS.put("proxy_sg",  new VpnServer("proxy_sg",  "Proxy SG",    "103.253.145.100",  8080, "🇸🇬"));
        SERVERS.put("proxy_jp",  new VpnServer("proxy_jp",  "Proxy JP",    "150.95.156.120",   8080, "🇯🇵"));
        SERVERS.put("proxy_ca",  new VpnServer("proxy_ca",  "Proxy CA",    "192.99.151.50",    3128, "🇨🇦"));
        SERVERS.put("proxy_uk",  new VpnServer("proxy_uk",  "Proxy UK",    "88.99.218.220",    8080, "🇬🇧"));
        SERVERS.put("proxy_au",  new VpnServer("proxy_au",  "Proxy AU",    "43.225.187.200",   8080, "🇦🇺"));
        SERVERS.put("proxy_ru",  new VpnServer("proxy_ru",  "Proxy RU",    "95.165.163.110",   8080, "🇷🇺"));

        SERVERS.put("vpn_nl",  new VpnServer("vpn_nl",  "VPN Netherlands",  "213.108.105.160", 443, "🇳🇱"));
        SERVERS.put("vpn_de",  new VpnServer("vpn_de",  "VPN Germany",      "185.228.137.130", 443, "🇩🇪"));
        SERVERS.put("vpn_fr",  new VpnServer("vpn_fr",  "VPN France",       "5.196.74.182",    443, "🇫🇷"));
        SERVERS.put("vpn_us",  new VpnServer("vpn_us",  "VPN USA",          "93.184.216.34",   443, "🇺🇸"));
        SERVERS.put("vpn_jp",  new VpnServer("vpn_jp",  "VPN Japan",        "45.33.32.156",    443, "🇯🇵"));
        SERVERS.put("vpn_sg",  new VpnServer("vpn_sg",  "VPN Singapore",    "104.238.190.160", 443, "🇸🇬"));
        SERVERS.put("vpn_au",  new VpnServer("vpn_au",  "VPN Australia",     "45.76.118.245",   443, "🇦🇺"));
        SERVERS.put("vpn_uk",  new VpnServer("vpn_uk",  "VPN UK",           "51.158.68.160",   443, "🇬🇧"));
        SERVERS.put("vpn_ch",  new VpnServer("vpn_ch",  "VPN Switzerland",  "185.156.172.100", 443, "🇨🇭"));
        SERVERS.put("vpn_se",  new VpnServer("vpn_se",  "VPN Sweden",       "95.143.193.70",   443, "🇸🇪"));
    }

    private static final Map<String, VpnServer> CUSTOM = new LinkedHashMap<>();

    public static synchronized void addCustomServer(String name, String host, int port) {
        String code = "custom_" + System.currentTimeMillis();
        CUSTOM.put(code, new VpnServer(code, name, host, port, "🔧"));
    }

    public static synchronized void removeCustomServer(String code) {
        CUSTOM.remove(code);
    }

    public static synchronized Map<String, VpnServer> getServers() {
        Map<String, VpnServer> all = new LinkedHashMap<>(SERVERS);
        all.putAll(CUSTOM);
        return all;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        createChannel();
    }

    private void createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel ch = new NotificationChannel(
                    CHANNEL_ID, CHANNEL_NAME, NotificationManager.IMPORTANCE_LOW);
            ch.setShowBadge(false);
            ch.setDescription("VPN connection status and traffic");
            nm.createNotificationChannel(ch);
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent != null ? intent.getAction() : null;

        if (ACTION_STOP.equals(action)) {
            stopVpn();
            return START_NOT_STICKY;
        }
        if (ACTION_TOGGLE.equals(action)) {
            if (running) {
                stopVpn();
            } else {
                startVpn(resolveServerCode(intent), resolveAllApps(intent), resolveApps(intent));
            }
            return START_STICKY;
        }

        String code = resolveServerCode(intent);
        boolean all = resolveAllApps(intent);
        Set<String> apps = resolveApps(intent);
        startVpn(code, all, apps);
        return START_STICKY;
    }

    private String resolveServerCode(Intent intent) {
        if (intent != null) {
            String cc = intent.getStringExtra(EXTRA_COUNTRY);
            if (cc != null && !cc.isEmpty() && getServers().containsKey(cc)) return cc;
        }
        return prefs().getString(PREF_SERVER, "vpn_nl");
    }

    private boolean resolveAllApps(Intent intent) {
        if (intent != null && intent.hasExtra(EXTRA_ALL_APPS)) {
            return intent.getBooleanExtra(EXTRA_ALL_APPS, true);
        }
        return prefs().getBoolean(PREF_ALL_APPS, true);
    }

    private Set<String> resolveApps(Intent intent) {
        Set<String> set = new HashSet<>();
        String[] fromIntent = intent != null ? intent.getStringArrayExtra(EXTRA_APPS) : null;
        if (fromIntent != null && fromIntent.length > 0) {
            set.addAll(Arrays.asList(fromIntent));
            return set;
        }
        String saved = prefs().getString(PREF_APPS, "");
        if (saved != null && !saved.isEmpty()) {
            set.addAll(Arrays.asList(saved.split("\\|")));
        }
        return set;
    }

    private android.content.SharedPreferences prefs() {
        return getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    private void persistState(String code, boolean all, Set<String> apps) {
        android.content.SharedPreferences.Editor e = prefs().edit();
        e.putString(PREF_SERVER, code);
        e.putBoolean(PREF_ALL_APPS, all);
        e.putString(PREF_APPS, Text.join("|", apps));
        e.putBoolean(PREF_WAS_CONNECTED, running);
        e.apply();
    }

    private void startVpn(String countryCode, boolean all, Set<String> apps) {
        if (running) stopInternal();

        VpnServer server = getServers().get(countryCode);
        if (server == null) {
            Log.e(TAG, "Unknown server: " + countryCode + ", falling back");
            server = getServers().get("vpn_nl");
        }
        if (server == null) {
            Log.e(TAG, "No server available");
            stopSelf();
            return;
        }

        currentServer = server;
        allApps = all;
        allowedApps.clear();
        allowedApps.addAll(apps);

        Log.d(TAG, "VPN -> " + server.name + " " + server.host + ":" + server.port);

        try {
            Builder b = new Builder();
            b.setSession(getString(R.string.app_name))
             .addAddress("10.0.0.2", 24)
             .addRoute("0.0.0.0", 0)
             .addDnsServer("8.8.8.8")
             .addDnsServer("1.1.1.1")
             .setMtu(1280)
             .setBlocking(false);

            if (allApps) {
                // весь трафик, кроме собственного приложения — иначе петля
                try {
                    b.addDisallowedApplication(getPackageName());
                } catch (Exception e) {
                    Log.w(TAG, "Cannot exclude own app: " + e.getMessage());
                }
            } else {
                if (apps.isEmpty()) {
                    Log.w(TAG, "Per-app mode with empty selection, blocking all");
                    b.addDisallowedApplication(getPackageName());
                }
                for (String pkg : apps) {
                    try {
                        b.addAllowedApplication(pkg);
                    } catch (Exception e) {
                        Log.w(TAG, "Cannot add app " + pkg + ": " + e.getMessage());
                    }
                }
            }

            vpnInterface = b.establish();
            if (vpnInterface == null) {
                Log.e(TAG, "establish() returned null");
                stopSelf();
                return;
            }

            running = true;
            bytesSent.set(0);
            bytesReceived.set(0);
            lastSpeedBytes.set(0);
            persistState(countryCode, allApps, allowedApps);

            goForeground();

            executor = Executors.newFixedThreadPool(16);
            executor.submit(this::readLoop);
            executor.submit(this::speedLoop);
            executor.submit(this::cleanupLoop);
            executor.submit(this::notifLoop);
            Log.d(TAG, "VPN started");

        } catch (Exception e) {
            Log.e(TAG, "Start error", e);
            running = false;
            stopForeground(true);
            stopSelf();
        }
    }

    private void goForeground() {
        Notification n = buildNotification();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(NOTIFICATION_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
        } else {
            startForeground(NOTIFICATION_ID, n);
        }
    }

    private void notifLoop() {
        while (running) {
            try {
                Thread.sleep(2000);
                if (nm != null && running) {
                    nm.notify(NOTIFICATION_ID, buildNotification());
                }
            } catch (InterruptedException e) {
                break;
            } catch (Exception e) {
                break;
            }
        }
    }

    private Notification buildNotification() {
        VpnServer s = currentServer;
        String title = (s != null ? s.flag + " " + s.name : getString(R.string.app_name));
        String text = NetworkMonitor.formatBytes(bytesSent.get() + bytesReceived.get())
                + " • " + NetworkMonitor.formatSpeed(currentSpeedBps);

        Intent open = new Intent(this, MainActivity.class);
        open.setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent pi = PendingIntent.getActivity(this, 0, open,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        Intent stop = new Intent(this, BrowsecVpnService.class);
        stop.setAction(ACTION_STOP);
        PendingIntent psi = PendingIntent.getService(this, 1, stop,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        return new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle(title)
                .setContentText(text)
                .setSmallIcon(R.drawable.ic_notif)
                .setColor(0xFF4CAF50)
                .setContentIntent(pi)
                .addAction(android.R.drawable.ic_media_pause, getString(R.string.disconnect), psi)
                .setOngoing(true)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setOnlyAlertOnce(true)
                .setShowWhen(false)
                .build();
    }

    // ===== Packet pump =====

    private void readLoop() {
        FileInputStream in = new FileInputStream(vpnInterface.getFileDescriptor());
        FileOutputStream out = new FileOutputStream(vpnInterface.getFileDescriptor());
        byte[] raw = new byte[MAX_PACKET];
        ByteBuffer buf = ByteBuffer.wrap(raw);

        while (running) {
            try {
                int len = in.read(raw);
                if (len <= 0) {
                    Thread.sleep(1);
                    continue;
                }
                buf.clear();
                buf.limit(len);
                process(buf, out);
            } catch (IOException e) {
                if (running) Log.e(TAG, "read error", e);
                break;
            } catch (InterruptedException e) {
                break;
            } catch (Exception e) {
                // keep pumping
            }
        }
        try { in.close(); } catch (Exception ignored) {}
        try { out.close(); } catch (Exception ignored) {}
    }

    private void process(ByteBuffer pkt, FileOutputStream out) {
        try {
            if (pkt.limit() < 20) return;
            int b0 = pkt.get(0) & 0xFF;
            if ((b0 >> 4) != 4) return; // только IPv4
            int ihl = (b0 & 0xF) * 4;
            if (ihl < 20 || pkt.limit() < ihl) return;
            int proto = pkt.get(9) & 0xFF;
            int totalLen = ((pkt.get(2) & 0xFF) << 8) | (pkt.get(3) & 0xFF);

            if (proto == 6) handleTcp(pkt, out, ihl, totalLen);
            else if (proto == 17) handleUdp(pkt, out, ihl, totalLen);
        } catch (Exception ignored) {
        }
    }

    private void handleTcp(ByteBuffer pkt, FileOutputStream out, int ihl, int totalLen) {
        try {
            if (pkt.limit() < ihl + 20) return;
            int srcIp = pkt.getInt(12);
            int dstIp = pkt.getInt(16);
            int srcPort = ((pkt.get(ihl) & 0xFF) << 8) | (pkt.get(ihl + 1) & 0xFF);
            int dstPort = ((pkt.get(ihl + 2) & 0xFF) << 8) | (pkt.get(ihl + 3) & 0xFF);
            int tcpHdrLen = ((pkt.get(ihl + 12) >> 4) & 0xF) * 4;
            int payloadOff = ihl + tcpHdrLen;
            int payloadLen = Math.min(totalLen, pkt.limit()) - payloadOff;

            String key = srcIp + ":" + srcPort + "->" + dstIp + ":" + dstPort;

            if (payloadLen > 0) {
                Tunnel t = tunnels.get(key);
                if (t == null || t.socket == null || t.socket.isClosed() || !t.active) {
                    t = createTunnel(dstIp, dstPort);
                    if (t == null) return;
                    t.srcIp = srcIp;
                    t.srcPort = srcPort;
                    t.dstIp = dstIp;
                    t.dstPort = dstPort;
                    t.sentSeq = ((pkt.get(ihl + 4) & 0xFF) << 24) | ((pkt.get(ihl + 5) & 0xFF) << 16)
                              | ((pkt.get(ihl + 6) & 0xFF) << 8) | (pkt.get(ihl + 7) & 0xFF);
                    tunnels.put(key, t);
                    final Tunnel ft = t;
                    executor.submit(() -> relay(ft, out));
                }

                byte[] payload = new byte[payloadLen];
                pkt.position(payloadOff);
                pkt.get(payload);

                try {
                    t.socket.getOutputStream().write(payload);
                    t.socket.getOutputStream().flush();
                    t.lastActive = System.currentTimeMillis();
                    bytesSent.addAndGet(payloadLen);
                } catch (IOException e) {
                    tunnels.remove(key);
                    try { t.socket.close(); } catch (Exception ignored) {}
                }
            }
        } catch (Exception ignored) {
        }
    }

    private Tunnel createTunnel(int dstIp, int dstPort) {
        Socket s = null;
        try {
            s = new Socket();
            s.connect(new InetSocketAddress(currentServer.host, currentServer.port), 15000);
            s.setTcpNoDelay(true);
            s.setKeepAlive(true);
            s.setSoTimeout(60000);
            protect(s);

            String target = intToIp(dstIp) + ":" + dstPort;
            String connectReq = "CONNECT " + target + " HTTP/1.1\r\n" +
                                "Host: " + target + "\r\n" +
                                "Proxy-Connection: keep-alive\r\n" +
                                "User-Agent: VPN/2.0\r\n\r\n";
            s.getOutputStream().write(connectReq.getBytes("UTF-8"));
            s.getOutputStream().flush();

            ByteArrayOutputStream hdr = new ByteArrayOutputStream();
            byte[] rb = new byte[1024];
            int state = 0;
            while (state < 4) {
                int read = s.getInputStream().read(rb);
                if (read <= 0) break;
                hdr.write(rb, 0, read);
                byte[] so = hdr.toByteArray();
                for (int i = 0; i < so.length; i++) {
                    if (i >= 3 && so[i - 3] == '\r' && so[i - 2] == '\n'
                            && so[i - 1] == '\r' && so[i] == '\n') {
                        state = 4;
                        break;
                    }
                }
            }

            String response = hdr.toString("UTF-8");
            if (!response.startsWith("HTTP/1.") || !response.contains(" 200")) {
                Log.w(TAG, "CONNECT failed: "
                        + response.substring(0, Math.min(120, response.length())));
                s.close();
                return null;
            }

            Tunnel t = new Tunnel();
            t.socket = s;
            return t;

        } catch (IOException e) {
            Log.w(TAG, "Tunnel create failed: " + e.getMessage());
            if (s != null) {
                try { s.close(); } catch (Exception ignored) {}
            }
            return null;
        }
    }

    private void relay(Tunnel t, FileOutputStream out) {
        try {
            java.io.InputStream in = t.socket.getInputStream();
            byte[] buf = new byte[MAX_PACKET];
            while (t.active && running) {
                int read;
                try {
                    read = in.read(buf);
                } catch (SocketTimeoutException e) {
                    continue;
                }
                if (read <= 0) break;
                bytesReceived.addAndGet(read);
                t.lastActive = System.currentTimeMillis();
                writeTcp(out, t.dstIp, t.dstPort, t.srcIp, t.srcPort, t.sentSeq, buf, read);
                t.sentSeq += read;
            }
        } catch (IOException e) {
            // socket closed
        } finally {
            t.active = false;
            try { t.socket.close(); } catch (Exception ignored) {}
        }
    }

    private void writeTcp(FileOutputStream out, int srcIp, int srcPort, int dstIp, int dstPort,
                          int seq, byte[] data, int len) {
        try {
            int total = 40 + len;
            ByteBuffer r = ByteBuffer.allocate(total);
            r.put((byte) 0x45);
            r.put((byte) 0x00);
            r.putShort((short) total);
            r.putShort((short) 0);
            r.putShort((short) 0);
            r.put((byte) 64);
            r.put((byte) 6);
            r.putShort((short) 0);
            r.putInt(srcIp);
            r.putInt(dstIp);
            r.putShort((short) srcPort);
            r.putShort((short) dstPort);
            r.putInt(seq);
            r.putInt(0);
            r.put((byte) (5 << 4));
            r.put((byte) 0x18); // PSH|ACK
            r.putShort((short) 65535);
            r.putShort((short) 0);
            r.putShort((short) 0);
            r.put(data, 0, len);
            out.write(r.array(), 0, total);
            out.flush();
        } catch (IOException ignored) {
        }
    }

    private void handleUdp(ByteBuffer pkt, FileOutputStream out, int ihl, int totalLen) {
        try {
            int srcIp = pkt.getInt(12);
            int dstIp = pkt.getInt(16);
            int srcPort = ((pkt.get(ihl) & 0xFF) << 8) | (pkt.get(ihl + 1) & 0xFF);
            int dstPort = ((pkt.get(ihl + 2) & 0xFF) << 8) | (pkt.get(ihl + 3) & 0xFF);
            int off = ihl + 8;
            int len = Math.min(totalLen, pkt.limit()) - off;

            // DNS пробрасываем напрямую, остальное пока не поддерживаем
            if (len > 0 && dstPort == 53) {
                byte[] dns = new byte[len];
                pkt.position(off);
                pkt.get(dns);
                final int fSrcIp = srcIp, fDstIp = dstIp, fSrcPort = srcPort, fDstPort = dstPort;
                final byte[] fDns = dns;
                executor.submit(() -> {
                    DatagramSocket ds = null;
                    try {
                        ds = new DatagramSocket();
                        protect(ds);
                        ds.setSoTimeout(5000);
                        byte[] q = new byte[2 + fDns.length];
                        q[0] = (byte) ((fDns.length >> 8) & 0xFF);
                        q[1] = (byte) (fDns.length & 0xFF);
                        System.arraycopy(fDns, 0, q, 2, fDns.length);
                        ds.send(new DatagramPacket(q, q.length, InetAddress.getByName("8.8.8.8"), 53));
                        byte[] rb = new byte[512];
                        DatagramPacket rp = new DatagramPacket(rb, rb.length);
                        ds.receive(rp);
                        if (rp.getLength() < 2) return;
                        byte[] dr = new byte[rp.getLength() - 2];
                        System.arraycopy(rp.getData(), 2, dr, 0, dr.length);
                        writeUdp(out, fDstIp, fDstPort, fSrcIp, fSrcPort, ihl, dr);
                    } catch (Exception ignored) {
                    } finally {
                        if (ds != null) ds.close();
                    }
                });
            }
        } catch (Exception ignored) {
        }
    }

    private void writeUdp(FileOutputStream out, int srcIp, int srcPort, int dstIp, int dstPort,
                          int ihl, byte[] data) {
        try {
            int ulen = 8 + data.length;
            int total = ihl + ulen;
            ByteBuffer r = ByteBuffer.allocate(total);
            r.put((byte) 0x45);
            r.put((byte) 0x00);
            r.putShort((short) total);
            r.putShort((short) 0);
            r.putShort((short) 0);
            r.put((byte) 64);
            r.put((byte) 17);
            r.putShort((short) 0);
            r.putInt(srcIp);
            r.putInt(dstIp);
            r.putShort((short) srcPort);
            r.putShort((short) dstPort);
            r.putShort((short) ulen);
            r.putShort((short) 0);
            r.put(data);
            out.write(r.array(), 0, total);
            out.flush();
        } catch (IOException ignored) {
        }
    }

    private String intToIp(int ip) {
        return ((ip >> 24) & 0xFF) + "." + ((ip >> 16) & 0xFF) + "." + ((ip >> 8) & 0xFF) + "." + (ip & 0xFF);
    }

    // ===== Background loops =====

    private void speedLoop() {
        while (running) {
            try {
                Thread.sleep(1000);
                long cur = bytesReceived.get();
                long last = lastSpeedBytes.getAndSet(cur);
                currentSpeedBps = Math.max(0, (cur - last) * 8);
            } catch (InterruptedException e) {
                break;
            }
        }
    }

    private void cleanupLoop() {
        while (running) {
            try {
                Thread.sleep(30000);
                long now = System.currentTimeMillis();
                Iterator<Map.Entry<String, Tunnel>> it = tunnels.entrySet().iterator();
                while (it.hasNext()) {
                    Tunnel t = it.next().getValue();
                    if (!t.active || t.socket == null || t.socket.isClosed() || (now - t.lastActive > 120000)) {
                        it.remove();
                        t.active = false;
                        try { if (t.socket != null) t.socket.close(); } catch (Exception ignored) {}
                    }
                }
            } catch (InterruptedException e) {
                break;
            }
        }
    }

    // ===== Lifecycle =====

    private void stopInternal() {
        running = false;
        for (Tunnel t : tunnels.values()) {
            t.active = false;
            try { if (t.socket != null) t.socket.close(); } catch (Exception ignored) {}
        }
        tunnels.clear();
        if (vpnInterface != null) {
            try { vpnInterface.close(); } catch (Exception ignored) {}
            vpnInterface = null;
        }
        if (executor != null) {
            executor.shutdownNow();
            executor = null;
        }
    }

    private void stopVpn() {
        stopInternal();
        prefs().edit().putBoolean(PREF_WAS_CONNECTED, false).apply();
        stopForeground(true);
        if (nm != null) nm.cancel(NOTIFICATION_ID);
        Log.d(TAG, "VPN stopped");
        stopSelf();
    }

    @Override
    public void onDestroy() {
        stopInternal();
        super.onDestroy();
    }

    @Override
    public void onRevoke() {
        stopVpn();
        super.onRevoke();
    }

    // ===== Public API =====

    public boolean isRunning() { return running; }
    public VpnServer getCurrentServer() { return currentServer; }
    public long getBytesSent() { return bytesSent.get(); }
    public long getBytesReceived() { return bytesReceived.get(); }
    public long getCurrentSpeed() { return currentSpeedBps; }
    public int getConnectionCount() { return tunnels.size(); }

    public static class VpnServer {
        public final String code, name, host, flag;
        public final int port;

        public VpnServer(String c, String n, String h, int p, String f) {
            code = c;
            name = n;
            host = h;
            port = p;
            flag = f;
        }

        @Override
        public String toString() {
            return flag + " " + name;
        }
    }

    private static class Tunnel {
        Socket socket;
        int srcIp, srcPort, dstIp, dstPort;
        int sentSeq;
        volatile boolean active = true;
        volatile long lastActive = System.currentTimeMillis();
    }

    /** Утилита склейки строк без java.util.stream (minSdk 24). */
    static final class Text {
        static String join(String sep, Set<String> items) {
            StringBuilder sb = new StringBuilder();
            for (String s : items) {
                if (sb.length() > 0) sb.append(sep);
                sb.append(s);
            }
            return sb.toString();
        }
    }
}
