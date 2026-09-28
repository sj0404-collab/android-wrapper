package com.wrapper.ui;

import android.content.*;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.drawable.Drawable;
import android.net.VpnService;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.text.Editable;
import android.text.TextWatcher;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.*;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentActivity;
import androidx.viewpager2.adapter.FragmentStateAdapter;
import androidx.viewpager2.widget.ViewPager2;

import com.google.android.material.tabs.TabLayout;
import com.google.android.material.tabs.TabLayoutMediator;
import com.wrapper.R;
import com.wrapper.vpn.*;

import java.io.*;
import java.net.*;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.concurrent.*;

public class MainActivity extends FragmentActivity {
    private static final String TAG = "MainActivity";
    private static final int VPN_REQUEST = 100;

    private NetworkMonitor networkMonitor;
    private BrowsecVpnService vpnService;
    private boolean vpnBound;

    private TabLayout tabLayout;
    private ViewPager2 viewPager;
    private TextView tvStatus;
    private MainPagerAdapter pagerAdapter;
    private final Handler uiHandler = new Handler(Looper.getMainLooper());
    private final ExecutorService executor = Executors.newFixedThreadPool(8);

    private final List<LogEntry> logs = new CopyOnWriteArrayList<>();
    private final List<AppInfo> allApps = new ArrayList<>();
    private final Set<String> selectedApps = ConcurrentHashMap.newKeySet();
    private volatile boolean vpnRunning;
    private volatile String currentServer = "vpn_nl";
    private volatile String connectionMode = "vpn";

    private final ConcurrentHashMap<String, Long> serverPings = new ConcurrentHashMap<>();
    private volatile boolean isChecking;
    private volatile String checkedIp = "—";
    private volatile long speedResult;

    private final ServiceConnection vpnConnection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder service) {
            vpnService = ((BrowsecVpnService.LocalBinder) service).getService();
            vpnBound = true;
            if (networkMonitor != null) networkMonitor.setVpnService(vpnService);
        }
        @Override
        public void onServiceDisconnected(ComponentName name) {
            vpnService = null;
            vpnBound = false;
            if (networkMonitor != null) networkMonitor.setVpnService(null);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        tabLayout = findViewById(R.id.tab_layout);
        viewPager = findViewById(R.id.view_pager);
        tvStatus = findViewById(R.id.tv_vpn_status);

        pagerAdapter = new MainPagerAdapter(this);
        viewPager.setAdapter(pagerAdapter);
        viewPager.setOffscreenPageLimit(4);

        new TabLayoutMediator(tabLayout, viewPager, (tab, position) -> {
            tab.setText(new String[]{
                getString(R.string.tab_dns),
                getString(R.string.tab_proxy),
                getString(R.string.tab_vpn),
                getString(R.string.tab_apps),
                getString(R.string.tab_logs)
            }[position]);
            tab.setIcon(new int[]{
                R.drawable.ic_dns, R.drawable.ic_proxy, R.drawable.ic_vpn,
                R.drawable.ic_apps, R.drawable.ic_logs
            }[position]);
        }).attach();

        addLog("INFO", "App started");
        networkMonitor = new NetworkMonitor();
        networkMonitor.setUpdateCallback(this::updateUI);
        bindService(new Intent(this, BrowsecVpnService.class), vpnConnection, Context.BIND_AUTO_CREATE);

        uiHandler.postDelayed(new Runnable() {
            public void run() {
                updateUI();
                uiHandler.postDelayed(this, 1000);
            }
        }, 1000);

        loadApps();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        uiHandler.removeCallbacksAndMessages(null);
        if (networkMonitor != null) networkMonitor.stop();
        if (vpnBound) {
            unbindService(vpnConnection);
            vpnBound = false;
        }
        executor.shutdownNow();
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == VPN_REQUEST && resultCode == RESULT_OK) {
            addLog("INFO", "VPN permission granted");
            if (connectionMode != null && currentServer != null) {
                startVpnInternal(currentServer, connectionMode.equals("dns"));
            }
        }
    }

    private void connect(String mode, String serverCode) {
        connectionMode = mode;
        currentServer = serverCode;

        BrowsecVpnService.VpnServer srv = BrowsecVpnService.getServers().get(serverCode);
        if (srv == null) {
            addLog("ERROR", "Server not found: " + serverCode);
            return;
        }

        addLog("INFO", mode.toUpperCase() + ": " + srv.name + " (" + srv.host + ":" + srv.port + ")");

        Intent prepareIntent = VpnService.prepare(this);
        if (prepareIntent != null) {
            try {
                startActivityForResult(prepareIntent, VPN_REQUEST);
            } catch (Exception e) {
                addLog("ERROR", "VPN permission error: " + e.getMessage());
            }
        } else {
            startVpnInternal(serverCode, mode.equals("dns"));
        }
    }

    private void startVpnInternal(String serverCode, boolean dnsOnly) {
        BrowsecVpnService.VpnServer srv = BrowsecVpnService.getServers().get(serverCode);
        if (srv == null) return;

        Intent stopIntent = new Intent(this, BrowsecVpnService.class);
        stopIntent.setAction("STOP");
        startService(stopIntent);

        Intent intent = new Intent(this, BrowsecVpnService.class);
        intent.putExtra("country", serverCode);
        intent.putExtra("allApps", selectedApps.isEmpty());
        if (!selectedApps.isEmpty()) {
            intent.putExtra("apps", selectedApps.toArray(new String[0]));
        }

        try {
            startService(intent);
            vpnRunning = true;
            networkMonitor.start();
            updateUI();
            addLog("INFO", "Connected: " + srv.name);
        } catch (Exception e) {
            addLog("ERROR", "Start failed: " + e.getMessage());
        }
    }

    private void disconnect() {
        addLog("INFO", "Disconnecting...");
        Intent intent = new Intent(this, BrowsecVpnService.class);
        intent.setAction("STOP");
        startService(intent);
        vpnRunning = false;
        networkMonitor.stop();
        updateUI();
        addLog("INFO", "Disconnected");
    }

    void checkAllServers(String filter) {
        if (isChecking) return;
        isChecking = true;
        addLog("INFO", "Checking servers: " + filter);

        for (BrowsecVpnService.VpnServer s : BrowsecVpnService.getServers().values()) {
            if (filter.equals("all") || s.code.startsWith(filter)) {
                serverPings.put(s.code, -1L);
            }
        }

        for (BrowsecVpnService.VpnServer s : BrowsecVpnService.getServers().values()) {
            if (!filter.equals("all") && !s.code.startsWith(filter)) continue;
            final BrowsecVpnService.VpnServer fs = s;
            executor.submit(() -> {
                long ping = tcpPing(fs.host, fs.port, 5000);
                serverPings.put(fs.code, ping);
                runOnUiThread(() -> {
                    Fragment f = getSupportFragmentManager().findFragmentByTag("f" + getTabForFilter(filter));
                    if (f instanceof ServerFragment) {
                        ((ServerFragment) f).refreshList();
                    }
                });
            });
        }

        executor.submit(() -> {
            try {
                Thread.sleep(7000);
            } catch (InterruptedException e) {}
            isChecking = false;
            runOnUiThread(() -> {
                String best = null;
                long bestPing = Long.MAX_VALUE;
                for (Map.Entry<String, Long> e : serverPings.entrySet()) {
                    if (e.getValue() > 0 && e.getValue() < bestPing) {
                        bestPing = e.getValue();
                        best = e.getKey();
                    }
                }
                if (best != null) {
                    BrowsecVpnService.VpnServer bs = BrowsecVpnService.getServers().get(best);
                    addLog("INFO", "Best [" + filter + "]: " + bs.flag + " " + bs.name + " (" + bestPing + "ms)");
                }
            });
        });
    }

    private int getTabForFilter(String filter) {
        if (filter.equals("dns")) return 0;
        if (filter.equals("proxy")) return 1;
        if (filter.equals("vpn")) return 2;
        return 0;
    }

    private long tcpPing(String host, int port, int timeout) {
        try {
            long start = System.currentTimeMillis();
            Socket s = new Socket();
            s.connect(new InetSocketAddress(host, port), timeout);
            long elapsed = System.currentTimeMillis() - start;
            s.close();
            return elapsed;
        } catch (Exception e) {
            return -2;
        }
    }

    void checkSpeed() {
        executor.submit(() -> {
            try {
                URL url = new URL("https://speedtest.tele2.net/1MB.zip");
                HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                conn.setConnectTimeout(10000);
                InputStream is = conn.getInputStream();
                byte[] buf = new byte[16384];
                long total = 0, start = System.currentTimeMillis();
                int read;
                while ((read = is.read(buf)) != -1) {
                    total += read;
                    if (System.currentTimeMillis() - start > 8000 || total > 2 * 1024 * 1024) break;
                }
                is.close();
                long elapsed = System.currentTimeMillis() - start;
                speedResult = elapsed > 0 ? (total * 8 * 1000) / elapsed : 0;
                runOnUiThread(() -> {
                    addLog("INFO", "Speed: " + NetworkMonitor.formatSpeed(speedResult));
                    updateUI();
                });
            } catch (Exception e) {
                speedResult = 0;
                runOnUiThread(() -> addLog("ERROR", "Speed test failed"));
            }
        });
    }

    void checkIp() {
        checkedIp = "Checking...";
        executor.submit(() -> {
            String[] urls = {"https://api.ipify.org", "https://ifconfig.me/ip"};
            for (String u : urls) {
                try {
                    HttpURLConnection c = (HttpURLConnection) new URL(u).openConnection();
                    c.setConnectTimeout(5000);
                    c.setRequestProperty("User-Agent", "VPN-Wrapper/2.0");
                    BufferedReader r = new BufferedReader(new InputStreamReader(c.getInputStream()));
                    String ip = r.readLine();
                    r.close();
                    if (ip != null && !ip.isEmpty()) {
                        checkedIp = ip.trim();
                        runOnUiThread(this::updateUI);
                        return;
                    }
                } catch (Exception e) {}
            }
            checkedIp = "Error";
            runOnUiThread(this::updateUI);
        });
    }

    private void updateUI() {
        runOnUiThread(() -> {
            if (tvStatus != null) {
                if (vpnRunning) {
                    String mode = connectionMode.equals("dns") ? "DNS" : connectionMode.equals("proxy") ? "Proxy" : "VPN";
                    tvStatus.setText(mode + " Connected");
                    tvStatus.setTextColor(Color.parseColor("#4CAF50"));
                } else {
                    tvStatus.setText("Disconnected");
                    tvStatus.setTextColor(Color.parseColor("#F44336"));
                }
            }
        });
    }

    private void loadApps() {
        executor.submit(() -> {
            PackageManager pm = getPackageManager();
            List<ApplicationInfo> installed = pm.getInstalledApplications(PackageManager.GET_META_DATA);
            allApps.clear();
            for (ApplicationInfo app : installed) {
                try {
                    AppInfo i = new AppInfo();
                    i.packageName = app.packageName;
                    i.name = pm.getApplicationLabel(app).toString();
                    i.isSystem = (app.flags & ApplicationInfo.FLAG_SYSTEM) != 0;
                    try {
                        i.icon = pm.getApplicationIcon(app);
                    } catch (Exception e) {
                        i.icon = pm.getDefaultActivityIcon();
                    }
                    allApps.add(i);
                } catch (Exception e) {}
            }
            Collections.sort(allApps, (a, b) -> {
                if (a.isSystem != b.isSystem) return a.isSystem ? 1 : -1;
                return a.name.compareToIgnoreCase(b.name);
            });
            runOnUiThread(() -> {
                Fragment f = getSupportFragmentManager().findFragmentByTag("f3");
                if (f instanceof AppsFragment) {
                    ((AppsFragment) f).onAppsLoaded();
                }
            });
        });
    }

    private void addLog(String level, String message) {
        Log.d(TAG, "[" + level + "] " + message);
        LogEntry e = new LogEntry();
        e.level = level;
        e.message = message;
        e.time = new Date();
        logs.add(0, e);
        if (logs.size() > 500) logs.remove(logs.size() - 1);

        Fragment f = getSupportFragmentManager().findFragmentByTag("f4");
        if (f instanceof LogsFragment) {
            runOnUiThread(((LogsFragment) f)::refreshLog);
        }
    }

    private void showAddServerDialog(String filter) {
        View dlg = LayoutInflater.from(this).inflate(R.layout.dialog_add_server, null);
        EditText etName = dlg.findViewById(R.id.et_server_name);
        EditText etHost = dlg.findViewById(R.id.et_server_host);
        EditText etPort = dlg.findViewById(R.id.et_server_port);
        etPort.setText("443");
        new AlertDialog.Builder(this)
            .setTitle("Add " + filter.toUpperCase())
            .setView(dlg)
            .setPositiveButton("Add", (d, w) -> {
                String name = etName.getText().toString().trim();
                String host = etHost.getText().toString().trim();
                String portStr = etPort.getText().toString().trim();
                if (name.isEmpty() || host.isEmpty()) return;
                int port;
                try {
                    port = Integer.parseInt(portStr);
                } catch (Exception e) {
                    port = 443;
                }
                BrowsecVpnService.addCustomServer(name, host, port);
                addLog("INFO", "Added: " + name + " (" + host + ":" + port + ")");
            })
            .setNegativeButton("Cancel", null)
            .show();
    }

    public List<AppInfo> getAllApps() { return allApps; }
    public Set<String> getSelectedApps() { return selectedApps; }
    public List<LogEntry> getLogs() { return logs; }
    public boolean isVpnRunning() { return vpnRunning; }
    public String getCurrentServer() { return currentServer; }
    public String getConnectionMode() { return connectionMode; }
    public ConcurrentHashMap<String, Long> getServerPings() { return serverPings; }
    public String getCheckedIp() { return checkedIp; }
    public long getSpeedResult() { return speedResult; }
    public NetworkMonitor getNetworkMonitor() { return networkMonitor; }

    private static class AppInfo {
        String name, packageName;
        boolean isSystem;
        Drawable icon;
    }

    private static class LogEntry {
        String level, message;
        Date time;
    }

    // ===== Pager Adapter =====

    class MainPagerAdapter extends FragmentStateAdapter {
        MainPagerAdapter(FragmentActivity fa) { super(fa); }
        @Override
        public int getItemCount() { return 5; }

        @NonNull
        @Override
        public Fragment createFragment(int position) {
            switch (position) {
                case 0: return ServerFragment.newInstance("dns");
                case 1: return ServerFragment.newInstance("proxy");
                case 2: return ServerFragment.newInstance("vpn");
                case 3: return new AppsFragment();
                case 4: return new LogsFragment();
                default: return new Fragment();
            }
        }
    }

    // ===== Server Fragment =====

    public static class ServerFragment extends Fragment {
        private String filter;
        private ListView lvServers;
        private TextView tvResult;
        private ServerListAdapter adapter;

        static ServerFragment newInstance(String filter) {
            ServerFragment f = new ServerFragment();
            Bundle b = new Bundle();
            b.putString("filter", filter);
            f.setArguments(b);
            return f;
        }

        @Override
        public void onCreate(Bundle savedInstanceState) {
            super.onCreate(savedInstanceState);
            filter = getArguments() != null ? getArguments().getString("filter", "vpn") : "vpn";
        }

        @Override
        public View onCreateView(LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
            View v = inflater.inflate(R.layout.tab_servers, container, false);
            lvServers = v.findViewById(R.id.lv_servers);
            Button btnCheck = v.findViewById(R.id.btn_check);
            Button btnSpeed = v.findViewById(R.id.btn_speed);
            Button btnIp = v.findViewById(R.id.btn_ip);
            Button btnAdd = v.findViewById(R.id.btn_add_server);
            TextView tvMode = v.findViewById(R.id.tv_mode);
            tvResult = v.findViewById(R.id.tv_result);

            String modeName = filter.equals("dns") ? "DNS Servers" : filter.equals("proxy") ? "Proxy Servers" : "VPN Servers";
            if (tvMode != null) tvMode.setText(modeName);

            btnCheck.setOnClickListener(vv -> {
                if (getActivity() instanceof MainActivity) {
                    ((MainActivity) getActivity()).checkAllServers(filter);
                }
            });
            btnSpeed.setOnClickListener(vv -> {
                if (getActivity() instanceof MainActivity) {
                    ((MainActivity) getActivity()).checkSpeed();
                }
            });
            btnIp.setOnClickListener(vv -> {
                if (getActivity() instanceof MainActivity) {
                    ((MainActivity) getActivity()).checkIp();
                }
            });
            btnAdd.setOnClickListener(vv -> {
                if (getActivity() instanceof MainActivity) {
                    ((MainActivity) getActivity()).showAddServerDialog(filter);
                }
            });

            adapter = new ServerListAdapter();
            lvServers.setAdapter(adapter);

            lvServers.setOnItemClickListener((p, view, pos, id) -> {
                BrowsecVpnService.VpnServer s = adapter.getItem(pos);
                if (getActivity() instanceof MainActivity) {
                    MainActivity act = (MainActivity) getActivity();
                    if (act.isVpnRunning()) act.disconnect();
                    act.connect(filter, s.code);
                }
            });

            lvServers.setOnItemLongClickListener((p, view, pos, id) -> {
                BrowsecVpnService.VpnServer s = adapter.getItem(pos);
                if (getActivity() instanceof MainActivity) {
                    MainActivity act = (MainActivity) getActivity();
                    Long ping = act.getServerPings().get(s.code);
                    String status = ping == null ? "Not checked" : ping == -1 ? "Checking..." : ping == -2 ? "Unreachable" : ping + "ms";
                    new AlertDialog.Builder(getContext())
                        .setTitle(s.flag + " " + s.name)
                        .setMessage("Host: " + s.host + ":" + s.port + "\nPing: " + status)
                        .setPositiveButton("Connect", (d, w) -> {
                            if (act.isVpnRunning()) act.disconnect();
                            act.connect(filter, s.code);
                        })
                        .setNegativeButton("Ping", (d, w) -> {
                            act.getServerPings().put(s.code, -1L);
                            adapter.notifyDataSetChanged();
                            act.executor.submit(() -> {
                                long pg = act.tcpPing(s.host, s.port, 5000);
                                act.getServerPings().put(s.code, pg);
                                getActivity().runOnUiThread(() -> adapter.notifyDataSetChanged());
                            });
                        })
                        .setNeutralButton("Cancel", null)
                        .show();
                }
                return true;
            });

            return v;
        }

        void refreshList() {
            if (adapter != null) adapter.notifyDataSetChanged();
        }

        private class ServerListAdapter extends BaseAdapter {
            private final List<BrowsecVpnService.VpnServer> servers = new ArrayList<>();

            ServerListAdapter() {
                refreshServers();
            }

            void refreshServers() {
                servers.clear();
                for (BrowsecVpnService.VpnServer s : BrowsecVpnService.getServers().values()) {
                    if (filter.equals("all") || s.code.startsWith(filter)) servers.add(s);
                }
            }

            @Override public int getCount() { return servers.size(); }
            @Override public BrowsecVpnService.VpnServer getItem(int p) { return servers.get(p); }
            @Override public long getItemId(int p) { return p; }

            @Override
            public View getView(int pos, View c, ViewGroup p) {
                TextView tv = c != null ? (TextView) c : new TextView(getContext());
                if (c == null) {
                    tv.setPadding(24, 16, 24, 16);
                    tv.setTextSize(13f);
                }
                BrowsecVpnService.VpnServer s = getItem(pos);
                if (getActivity() instanceof MainActivity) {
                    MainActivity act = (MainActivity) getActivity();
                    boolean active = s.code.equals(act.getCurrentServer()) && act.isVpnRunning();
                    String prefix = active ? "● " : "   ";
                    Long ping = act.getServerPings().get(s.code);
                    String pingStr;
                    if (ping == null) pingStr = "";
                    else if (ping == -1) pingStr = " ...";
                    else if (ping == -2) pingStr = " X";
                    else if (ping < 80) pingStr = " " + ping + "ms";
                    else if (ping < 200) pingStr = " " + ping + "ms";
                    else pingStr = " " + ping + "ms";
                    tv.setText(prefix + s.flag + " " + s.name + " (" + s.host + ":" + s.port + ")" + pingStr);
                    tv.setTextColor(s.code.equals(act.getCurrentServer()) ? Color.parseColor("#4CAF50") : Color.parseColor("#c0c0c0"));
                    tv.setBackgroundColor(s.code.equals(act.getCurrentServer()) ? Color.parseColor("#0f1a0f") : Color.parseColor("#0a0a1a"));
                }
                return tv;
            }
        }
    }

    // ===== Apps Fragment =====

    public static class AppsFragment extends Fragment {
        private GridView gvApps;
        private EditText etFilter;
        private TextView tvCount;
        private AppsGridAdapter adapter;
        private List<AppInfo> filtered = new ArrayList<>();

        @Override
        public View onCreateView(LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
            View v = inflater.inflate(R.layout.tab_apps, container, false);
            etFilter = v.findViewById(R.id.et_app_filter);
            Button btnUser = v.findViewById(R.id.btn_select_user);
            Button btnAll = v.findViewById(R.id.btn_select_all);
            Button btnClear = v.findViewById(R.id.btn_clear_selection);
            gvApps = v.findViewById(R.id.gv_apps);
            tvCount = v.findViewById(R.id.tv_selected_count);

            if (getActivity() instanceof MainActivity) {
                MainActivity act = (MainActivity) getActivity();
                filtered = new ArrayList<>(act.getAllApps());
            }

            adapter = new AppsGridAdapter();
            gvApps.setAdapter(adapter);

            etFilter.addTextChangedListener(new TextWatcher() {
                public void beforeTextChanged(CharSequence s, int a, int b, int c) {}
                public void onTextChanged(CharSequence s, int a, int b, int c) {}
                public void afterTextChanged(Editable s) {
                    if (getActivity() instanceof MainActivity) {
                        MainActivity act = (MainActivity) getActivity();
                        String f = s.toString().toLowerCase();
                        filtered.clear();
                        for (AppInfo a : act.getAllApps()) {
                            if (a.name.toLowerCase().contains(f) || a.packageName.toLowerCase().contains(f)) {
                                filtered.add(a);
                            }
                        }
                        adapter.notifyDataSetChanged();
                    }
                }
            });

            btnUser.setOnClickListener(vv -> {
                if (getActivity() instanceof MainActivity) {
                    MainActivity act = (MainActivity) getActivity();
                    act.getSelectedApps().clear();
                    for (AppInfo a : act.getAllApps()) {
                        if (!a.isSystem) act.getSelectedApps().add(a.packageName);
                    }
                    tvCount.setText("Selected: " + act.getSelectedApps().size());
                    adapter.notifyDataSetChanged();
                }
            });
            btnAll.setOnClickListener(vv -> {
                if (getActivity() instanceof MainActivity) {
                    MainActivity act = (MainActivity) getActivity();
                    if (act.getSelectedApps().size() == act.getAllApps().size()) {
                        act.getSelectedApps().clear();
                    } else {
                        for (AppInfo a : act.getAllApps()) act.getSelectedApps().add(a.packageName);
                    }
                    tvCount.setText("Selected: " + act.getSelectedApps().size());
                    adapter.notifyDataSetChanged();
                }
            });
            btnClear.setOnClickListener(vv -> {
                if (getActivity() instanceof MainActivity) {
                    MainActivity act = (MainActivity) getActivity();
                    act.getSelectedApps().clear();
                    tvCount.setText("Selected: 0");
                    adapter.notifyDataSetChanged();
                }
            });

            tvCount.setText("Selected: " + ((MainActivity) getActivity()).getSelectedApps().size());
            return v;
        }

        void onAppsLoaded() {
            if (getActivity() instanceof MainActivity) {
                filtered = new ArrayList<>(((MainActivity) getActivity()).getAllApps());
                if (adapter != null) adapter.notifyDataSetChanged();
            }
        }

        private class AppsGridAdapter extends BaseAdapter {
            public int getCount() { return filtered.size(); }
            public AppInfo getItem(int p) { return filtered.get(p); }
            public long getItemId(int p) { return p; }

            public View getView(int pos, View c, ViewGroup p) {
                ViewHolder h;
                if (c == null) {
                    c = LayoutInflater.from(getContext()).inflate(R.layout.item_app_grid, p, false);
                    h = new ViewHolder();
                    h.iv = c.findViewById(R.id.iv_app_icon);
                    h.tv = c.findViewById(R.id.tv_app_name);
                    h.cb = c.findViewById(R.id.cb_app);
                    c.setTag(h);
                } else {
                    h = (ViewHolder) c.getTag();
                }
                AppInfo a = getItem(pos);
                h.tv.setText(a.name);
                h.tv.setTextColor(a.isSystem ? Color.GRAY : Color.WHITE);
                h.iv.setImageDrawable(a.icon);
                if (getActivity() instanceof MainActivity) {
                    h.cb.setOnCheckedChangeListener(null);
                    h.cb.setChecked(((MainActivity) getActivity()).getSelectedApps().contains(a.packageName));
                    h.cb.setButtonTintList(android.content.res.ColorStateList.valueOf(
                        ((MainActivity) getActivity()).getSelectedApps().contains(a.packageName)
                            ? Color.parseColor("#00d4ff") : Color.parseColor("#606060")));
                    c.setBackgroundColor(((MainActivity) getActivity()).getSelectedApps().contains(a.packageName)
                        ? Color.parseColor("#1a1a40") : Color.parseColor("#0a0a1a"));
                    AppInfo fa = a;
                    h.cb.setOnCheckedChangeListener((b, ck) -> {
                        if (ck) ((MainActivity) getActivity()).getSelectedApps().add(fa.packageName);
                        else ((MainActivity) getActivity()).getSelectedApps().remove(fa.packageName);
                        tvCount.setText("Selected: " + ((MainActivity) getActivity()).getSelectedApps().size());
                        notifyDataSetChanged();
                    });
                }
                return c;
            }

            class ViewHolder {
                ImageView iv;
                TextView tv;
                CheckBox cb;
            }
        }
    }

    // ===== Logs Fragment =====

    public static class LogsFragment extends Fragment {
        private ListView lvLogs;
        private LogEntryAdapter adapter;

        @Override
        public View onCreateView(LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
            View v = inflater.inflate(R.layout.tab_logs, container, false);
            Button btnClear = v.findViewById(R.id.btn_clear_logs);
            Button btnExport = v.findViewById(R.id.btn_export_logs);
            lvLogs = v.findViewById(R.id.lv_logs);

            adapter = new LogEntryAdapter();
            lvLogs.setAdapter(adapter);

            btnClear.setOnClickListener(vv -> {
                if (getActivity() instanceof MainActivity) {
                    ((MainActivity) getActivity()).getLogs().clear();
                    adapter.notifyDataSetChanged();
                }
            });
            btnExport.setOnClickListener(vv -> {
                if (getActivity() instanceof MainActivity) {
                    StringBuilder sb = new StringBuilder();
                    SimpleDateFormat f = new SimpleDateFormat("HH:mm:ss", Locale.getDefault());
                    for (LogEntry e : ((MainActivity) getActivity()).getLogs()) {
                        sb.append(f.format(e.time)).append(" [").append(e.level).append("] ").append(e.message).append("\n");
                    }
                    try {
                        File file = new File(getContext().getFilesDir(), "logs_" + System.currentTimeMillis() + ".txt");
                        FileWriter fw = new FileWriter(file);
                        fw.write(sb.toString());
                        fw.close();
                        ((MainActivity) getActivity()).addLog("INFO", "Logs saved: " + file.getAbsolutePath());
                    } catch (Exception ex) {
                        ((MainActivity) getActivity()).addLog("ERROR", "Export failed: " + ex.getMessage());
                    }
                }
            });

            return v;
        }

        void refreshLog() {
            if (adapter != null) adapter.notifyDataSetChanged();
        }

        private class LogEntryAdapter extends BaseAdapter {
            SimpleDateFormat fmt = new SimpleDateFormat("HH:mm:ss", Locale.getDefault());

            public int getCount() {
                return ((MainActivity) getActivity()).getLogs().size();
            }
            public LogEntry getItem(int p) {
                return ((MainActivity) getActivity()).getLogs().get(p);
            }
            public long getItemId(int p) { return p; }

            public View getView(int pos, View c, ViewGroup p) {
                TextView tv = c != null ? (TextView) c : new TextView(getContext());
                if (c == null) {
                    tv.setPadding(16, 8, 16, 8);
                    tv.setTextSize(11f);
                }
                LogEntry e = getItem(pos);
                tv.setText(fmt.format(e.time) + " [" + e.level + "] " + e.message);
                tv.setTextColor("ERROR".equals(e.level) ? Color.parseColor("#F44336")
                    : "WARN".equals(e.level) ? Color.parseColor("#FF9800")
                    : Color.parseColor("#4CAF50"));
                tv.setBackgroundColor(Color.parseColor("#050510"));
                return tv;
            }
        }
    }
}
