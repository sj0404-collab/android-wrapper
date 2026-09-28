package com.wrapper.vpn;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Intent;
import android.net.VpnService;
import android.os.Build;
import android.os.IBinder;
import android.os.ParcelFileDescriptor;
import android.util.Log;
import androidx.core.app.NotificationCompat;
import com.wrapper.R;
import com.wrapper.ui.MainActivity;

import java.io.*;
import java.net.*;
import java.nio.ByteBuffer;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;

public class BrowsecVpnService extends VpnService {
    private static final String TAG = "BrowsecVPN";
    private static final String CHANNEL_ID = "vpn_channel";
    private static final int NOTIFICATION_ID = 1001;
    private static final int MAX_PACKET = 65535;

    private final IBinder binder = new LocalBinder();
    public class LocalBinder extends android.os.Binder {
        public BrowsecVpnService getService() { return BrowsecVpnService.this; }
    }
    @Override
    public IBinder onBind(Intent intent) { return binder; }

    private ParcelFileDescriptor vpnInterface;
    private ExecutorService executor;
    private volatile boolean running;
    private VpnServer currentServer;
    private Set<String> allowedApps = new HashSet<>();
    private boolean allApps = true;
    private NotificationManager nm;

    private final AtomicLong bytesSent = new AtomicLong();
    private final AtomicLong bytesReceived = new AtomicLong();
    private final AtomicLong lastSpeedBytes = new AtomicLong();
    private volatile long currentSpeedBps;

    private final ConcurrentHashMap<String, Tunnel> tunnels = new ConcurrentHashMap<>();

    private static final Map<String, VpnServer> SERVERS = new LinkedHashMap<>();
    static {
        SERVERS.put("dns_google",    new VpnServer("dns_google",    "DNS Google",       "8.8.8.8",         53,  "\uD83C\uDDF3\uD83C\uDDE6"));
        SERVERS.put("dns_cloudflare", new VpnServer("dns_cloudflare", "DNS Cloudflare",   "1.1.1.1",         53,  "\uD83C\uDDF3\uD83C\uDDE6"));
        SERVERS.put("dns_quad9",     new VpnServer("dns_quad9",     "DNS Quad9",        "9.9.9.9",         53,  "\uD83C\uDDF3\uD83C\uDDE6"));
        SERVERS.put("dns_opendns",   new VpnServer("dns_opendns",   "DNS OpenDNS",      "208.67.222.222",  53,  "\uD83C\uDDF3\uD83C\uDDE6"));
        SERVERS.put("dns_adguard",   new VpnServer("dns_adguard",   "DNS AdGuard",      "94.140.14.14",    53,  "\uD83C\uDDF3\uD83C\uDDE6"));

        SERVERS.put("proxy_nl",  new VpnServer("proxy_nl",  "Proxy NL",    "185.199.228.220",  8080, "\uD83C\uDDF3\uD83C\uDDF1"));
        SERVERS.put("proxy_us",  new VpnServer("proxy_us",  "Proxy US",    "45.77.65.140",     3128, "\uD83C\uDDFA\uD83C\uDDF8"));
        SERVERS.put("proxy_de",  new VpnServer("proxy_de",  "Proxy DE",    "138.201.152.200",  8080, "\uD83C\uDDE9\uD83C\uDDEA"));
        SERVERS.put("proxy_fr",  new VpnServer("proxy_fr",  "Proxy FR",    "51.158.165.200",   8080, "\uD83C\uDDEB\uD83C\uDDF7"));
        SERVERS.put("proxy_sg",  new VpnServer("proxy_sg",  "Proxy SG",    "103.253.145.100",  8080, "\uD83C\uDDF8\uD83C\uDDEC"));
        SERVERS.put("proxy_jp",  new VpnServer("proxy_jp",  "Proxy JP",    "150.95.156.120",   8080, "\uD83C\uDDEF\uD83C\uDDF5"));
        SERVERS.put("proxy_ca",  new VpnServer("proxy_ca",  "Proxy CA",    "192.99.151.50",    3128, "\uD83C\uDDE8\uD83C\uDDE6"));
        SERVERS.put("proxy_uk",  new VpnServer("proxy_uk",  "Proxy UK",    "88.99.218.220",    8080, "\uD83C\uDDEC\uD83C\uDDE7"));
        SERVERS.put("proxy_au",  new VpnServer("proxy_au",  "Proxy AU",    "43.225.187.200",   8080, "\uD83C\uDDE6\uD83C\uDDFA"));
        SERVERS.put("proxy_ru",  new VpnServer("proxy_ru",  "Proxy RU",    "95.165.163.110",   8080, "\uD83C\uDDF7\uD83C\uDDFA"));

        SERVERS.put("vpn_nl",  new VpnServer("vpn_nl",  "VPN Netherlands",  "213.108.105.160", 443, "\uD83C\uDDF3\uD83C\uDDF1"));
        SERVERS.put("vpn_de",  new VpnServer("vpn_de",  "VPN Germany",      "185.228.137.130", 443, "\uD83C\uDDE9\uD83C\uDDEA"));
        SERVERS.put("vpn_fr",  new VpnServer("vpn_fr",  "VPN France",       "5.196.74.182",    443, "\uD83C\uDDEB\uD83C\uDDF7"));
        SERVERS.put("vpn_us",  new VpnServer("vpn_us",  "VPN USA",          "93.184.216.34",   443, "\uD83C\uDDFA\uD83C\uDDF8"));
        SERVERS.put("vpn_jp",  new VpnServer("vpn_jp",  "VPN Japan",        "45.33.32.156",    443, "\uD83C\uDDEF\uD83C\uDDF5"));
        SERVERS.put("vpn_sg",  new VpnServer("vpn_sg",  "VPN Singapore",    "104.238.190.160", 443, "\uD83C\uDDF8\uD83C\uDDEC"));
        SERVERS.put("vpn_au",  new VpnServer("vpn_au",  "VPN Australia",     "45.76.118.245",   443, "\uD83C\uDDE6\uD83C\uDDFA"));
        SERVERS.put("vpn_uk",  new VpnServer("vpn_uk",  "VPN UK",           "51.158.68.160",   443, "\uD83C\uDDEC\uD83C\uDDE7"));
        SERVERS.put("vpn_ch",  new VpnServer("vpn_ch",  "VPN Switzerland",  "185.156.172.100", 443, "\uD83C\uDDE8\uD83C\uDDED"));
        SERVERS.put("vpn_se",  new VpnServer("vpn_se",  "VPN Sweden",       "95.143.193.70",   443, "\uD83C\uDDF8\uD83C\uDDEA"));
    }

    private static final Map<String, VpnServer> CUSTOM = new LinkedHashMap<>();

    public static void addCustomServer(String name, String host, int port) {
        CUSTOM.put("custom_" + System.currentTimeMillis(), new VpnServer("custom", name, host, port, "\uD83D\uDD27"));
    }

    public static void removeCustomServer(String code) {
        CUSTOM.remove(code);
    }

    public static Map<String, VpnServer> getServers() {
        Map<String, VpnServer> all = new LinkedHashMap<>(SERVERS);
        all.putAll(CUSTOM);
        return all;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel ch = new NotificationChannel(CHANNEL_ID, "VPN", NotificationManager.IMPORTANCE_LOW);
            ch.setShowBadge(false);
            nm.createNotificationChannel(ch);
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && "STOP".equals(intent.getAction())) {
            stopVpn();
            return START_NOT_STICKY;
        }
        String cc = "vpn_nl";
        if (intent != null) {
            cc = intent.getStringExtra("country");
            if (cc == null) cc = "vpn_nl";
            allApps = intent.getBooleanExtra("allApps", true);
            String[] a = intent.getStringArrayExtra("apps");
            if (a != null) allowedApps = new HashSet<>(Arrays.asList(a));
        }
        startVpn(cc);
        return START_STICKY;
    }

    private void startVpn(String countryCode) {
        if (running) stopInternal();

        currentServer = getServers().get(countryCode);
        if (currentServer == null) {
            currentServer = getServers().get("vpn_nl");
        }
        if (currentServer == null) {
            Log.e(TAG, "No server available");
            return;
        }

        Log.d(TAG, "VPN -> " + currentServer.name + " " + currentServer.host + ":" + currentServer.port);

        try {
            Builder b = new Builder();
            b.setSession("VPN")
             .addAddress("10.0.0.2", 24)
             .addRoute("0.0.0.0", 0)
             .addDnsServer("8.8.8.8")
             .addDnsServer("1.1.1.1")
             .setMtu(1280)
             .setBlocking(false);

            if (allApps) {
                try {
                    b.addDisallowedApplication(getPackageName());
                } catch (Exception e) {
                    Log.w(TAG, "Cannot exclude own app");
                }
            } else {
                for (String pkg : allowedApps) {
                    try {
                        b.addAllowedApplication(pkg);
                    } catch (Exception e) {
                        Log.w(TAG, "Cannot add app: " + pkg);
                    }
                }
            }

            vpnInterface = b.establish();
            if (vpnInterface == null) {
                Log.e(TAG, "establish() returned null");
                return;
            }

            running = true;
            bytesSent.set(0);
            bytesReceived.set(0);
            lastSpeedBytes.set(0);
            showNotif();

            executor = Executors.newFixedThreadPool(8);
            executor.submit(this::readLoop);
            executor.submit(this::speedLoop);
            executor.submit(this::cleanupLoop);
            Log.d(TAG, "VPN started successfully");

        } catch (Exception e) {
            Log.e(TAG, "Start error", e);
            running = false;
        }
    }

    private void readLoop() {
        FileInputStream in = new FileInputStream(vpnInterface.getFileDescriptor());
        FileOutputStream out = new FileOutputStream(vpnInterface.getFileDescriptor());
        ByteBuffer buf = ByteBuffer.allocate(MAX_PACKET);

        while (running) {
            try {
                int len = in.read(buf.array());
                if (len <= 0) {
                    Thread.sleep(1);
                    continue;
                }
                buf.limit(len);
                process(buf, out);
                buf.clear();
            } catch (IOException e) {
                if (running) Log.e(TAG, "read error", e);
                break;
            } catch (InterruptedException e) {
                break;
            }
        }
        try { in.close(); } catch (Exception e) {}
        try { out.close(); } catch (Exception e) {}
    }

    private void process(ByteBuffer pkt, FileOutputStream out) {
        try {
            int b0 = pkt.get(0) & 0xFF;
            if ((b0 >> 4) != 4) return;
            int ihl = (b0 & 0xF) * 4;
            int proto = pkt.get(9) & 0xFF;
            int totalLen = ((pkt.get(2) & 0xFF) << 8) | (pkt.get(3) & 0xFF);

            if (proto == 6) handleTcp(pkt, out, ihl, totalLen);
            else if (proto == 17) handleUdp(pkt, out, ihl, totalLen);
        } catch (Exception e) {}
    }

    private void handleTcp(ByteBuffer pkt, FileOutputStream out, int ihl, int totalLen) {
        try {
            int srcIp = pkt.getInt(12);
            int dstIp = pkt.getInt(16);
            int srcPort = ((pkt.get(ihl) & 0xFF) << 8) | (pkt.get(ihl + 1) & 0xFF);
            int dstPort = ((pkt.get(ihl + 2) & 0xFF) << 8) | (pkt.get(ihl + 3) & 0xFF);
            int tcpHdrLen = ((pkt.get(ihl + 12) >> 4) & 0xF) * 4;
            int payloadOff = ihl + tcpHdrLen;
            int payloadLen = totalLen - payloadOff;

            String key = srcIp + ":" + srcPort + "->" + dstIp + ":" + dstPort;

            if (payloadLen > 0) {
                byte[] payload = new byte[payloadLen];
                pkt.position(payloadOff);
                pkt.get(payload);

                Tunnel t = tunnels.get(key);
                if (t == null || t.socket.isClosed() || !t.active) {
                    t = createTunnel(dstIp, dstPort);
                    if (t == null) return;
                    t.srcIp = srcIp;
                    t.srcPort = srcPort;
                    t.dstIp = dstIp;
                    t.dstPort = dstPort;
                    tunnels.put(key, t);
                    final Tunnel ft = t;
                    executor.submit(() -> relay(ft, out));
                }

                try {
                    t.socket.getOutputStream().write(payload);
                    t.socket.getOutputStream().flush();
                    t.lastActive = System.currentTimeMillis();
                    bytesSent.addAndGet(payloadLen);
                } catch (IOException e) {
                    tunnels.remove(key);
                    try { t.socket.close(); } catch (Exception ex) {}
                }
            }
        } catch (Exception e) {}
    }

    private Tunnel createTunnel(int dstIp, int dstPort) {
        try {
            Socket s = new Socket();
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
            s.getOutputStream().write(connectReq.getBytes());
            s.getOutputStream().flush();

            byte[] respBuf = new byte[4096];
            int totalRead = 0;
            int read;
            while (totalRead < respBuf.length) {
                read = s.getInputStream().read(respBuf, totalRead, respBuf.length - totalRead);
                if (read <= 0) break;
                totalRead += read;
                String soFar = new String(respBuf, 0, totalRead);
                if (soFar.contains("\r\n\r\n")) break;
            }

            String response = new String(respBuf, 0, totalRead);
            if (!response.contains("200")) {
                Log.w(TAG, "CONNECT failed: " + response.substring(0, Math.min(100, response.length())));
                s.close();
                return null;
            }

            Log.d(TAG, "Tunnel established to " + target);
            Tunnel t = new Tunnel();
            t.socket = s;
            return t;

        } catch (IOException e) {
            Log.w(TAG, "Tunnel create failed: " + e.getMessage());
            return null;
        }
    }

    private void relay(Tunnel t, FileOutputStream out) {
        try {
            InputStream is = t.socket.getInputStream();
            byte[] buf = new byte[MAX_PACKET];
            int read;
            while (t.active && running) {
                try {
                    read = is.read(buf);
                    if (read <= 0) break;
                    bytesReceived.addAndGet(read);
                    t.lastActive = System.currentTimeMillis();
                    writeTcp(out, t.dstIp, t.dstPort, t.srcIp, t.srcPort, buf, read);
                } catch (SocketTimeoutException e) {
                    continue;
                }
            }
        } catch (IOException e) {}
        finally {
            t.active = false;
            try { t.socket.close(); } catch (Exception e) {}
        }
    }

    private void writeTcp(FileOutputStream out, int srcIp, int srcPort, int dstIp, int dstPort, byte[] data, int len) {
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
            r.putInt(0);
            r.putInt(0);
            r.put((byte) (5 << 4));
            r.put((byte) 0x10);
            r.putShort((short) 65535);
            r.putShort((short) 0);
            r.putShort((short) 0);
            r.put(data, 0, len);
            out.write(r.array(), 0, total);
            out.flush();
        } catch (IOException e) {}
    }

    private void handleUdp(ByteBuffer pkt, FileOutputStream out, int ihl, int totalLen) {
        try {
            int srcIp = pkt.getInt(12);
            int dstIp = pkt.getInt(16);
            int srcPort = ((pkt.get(ihl) & 0xFF) << 8) | (pkt.get(ihl + 1) & 0xFF);
            int dstPort = ((pkt.get(ihl + 2) & 0xFF) << 8) | (pkt.get(ihl + 3) & 0xFF);
            int off = ihl + 8;
            int len = totalLen - off;

            if (len > 0 && dstPort == 53) {
                byte[] dns = new byte[len];
                pkt.position(off);
                pkt.get(dns);
                final int fSrcIp = srcIp, fDstIp = dstIp, fSrcPort = srcPort, fDstPort = dstPort;
                final byte[] fDns = dns;
                executor.submit(() -> {
                    try {
                        DatagramSocket ds = new DatagramSocket();
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
                        byte[] dr = new byte[rp.getLength() - 2];
                        System.arraycopy(rp.getData(), 2, dr, 0, dr.length);
                        writeUdp(out, fDstIp, fDstPort, fSrcIp, fSrcPort, ihl, dr);
                        ds.close();
                    } catch (Exception e) {}
                });
            }
        } catch (Exception e) {}
    }

    private void writeUdp(FileOutputStream out, int srcIp, int srcPort, int dstIp, int dstPort, int ihl, byte[] data) {
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
        } catch (IOException e) {}
    }

    private String intToIp(int ip) {
        return ((ip >> 24) & 0xFF) + "." + ((ip >> 16) & 0xFF) + "." + ((ip >> 8) & 0xFF) + "." + (ip & 0xFF);
    }

    private void speedLoop() {
        while (running) {
            try {
                Thread.sleep(1000);
                long cur = bytesReceived.get();
                long last = lastSpeedBytes.getAndSet(cur);
                currentSpeedBps = (cur - last) * 8;
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
                    if (!t.active || t.socket.isClosed() || (now - t.lastActive > 120000)) {
                        it.remove();
                        try { t.socket.close(); } catch (Exception e) {}
                    }
                }
            } catch (InterruptedException e) {
                break;
            }
        }
    }

    private void showNotif() {
        Intent i = new Intent(this, MainActivity.class);
        PendingIntent pi = PendingIntent.getActivity(this, 0, i, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Intent si = new Intent(this, BrowsecVpnService.class);
        si.setAction("STOP");
        PendingIntent psi = PendingIntent.getService(this, 1, si, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Notification n = new NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("VPN Connected")
            .setContentText(currentServer.flag + " " + currentServer.name)
            .setSmallIcon(R.drawable.ic_notif)
            .setColor(0xFF4CAF50)
            .setContentIntent(pi)
            .addAction(android.R.drawable.ic_media_pause, "Disconnect", psi)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOnlyAlertOnce(true)
            .build();
        nm.notify(NOTIFICATION_ID, n);
    }

    private void stopInternal() {
        running = false;
        for (Tunnel t : tunnels.values()) {
            t.active = false;
            try { t.socket.close(); } catch (Exception e) {}
        }
        tunnels.clear();
        if (vpnInterface != null) {
            try { vpnInterface.close(); } catch (Exception e) {}
            vpnInterface = null;
        }
        if (executor != null) {
            executor.shutdownNow();
            executor = null;
        }
    }

    private void stopVpn() {
        stopInternal();
        nm.cancel(NOTIFICATION_ID);
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
        volatile boolean active = true;
        volatile long lastActive = System.currentTimeMillis();
    }
}
