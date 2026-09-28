package com.wrapper.vpn;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.net.VpnService;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.View;
import android.widget.*;
import androidx.appcompat.app.AppCompatActivity;
import com.wrapper.R;

import java.util.Map;

public class VpnActivity extends AppCompatActivity {
    private static final String TAG = "VpnActivity";
    private static final int VPN_REQUEST_CODE = 100;

    private TextView tvStatus;
    private TextView tvServer;
    private TextView tvPing;
    private TextView tvSpeed;
    private TextView tvJitter;
    private TextView tvTraffic;
    private Button btnConnect;
    private Button btnDisconnect;
    private Spinner spinnerServers;
    private ProgressBar progressBar;

    private boolean vpnRunning = false;
    private String selectedServer = "nl";
    private NetworkMonitor networkMonitor;
    private Handler uiHandler = new Handler(Looper.getMainLooper());

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_vpn);

        initViews();
        setupServers();
        setupNetworkMonitor();
    }

    private void initViews() {
        tvStatus = findViewById(R.id.tv_vpn_status);
        tvServer = findViewById(R.id.tv_vpn_server);
        tvPing = findViewById(R.id.tv_vpn_ping);
        tvSpeed = findViewById(R.id.tv_vpn_speed);
        tvJitter = findViewById(R.id.tv_vpn_jitter);
        tvTraffic = findViewById(R.id.tv_vpn_traffic);
        btnConnect = findViewById(R.id.btn_vpn_connect);
        btnDisconnect = findViewById(R.id.btn_vpn_disconnect);
        spinnerServers = findViewById(R.id.spinner_servers);
        progressBar = findViewById(R.id.progress_vpn);

        btnConnect.setOnClickListener(v -> startVpn());
        btnDisconnect.setOnClickListener(v -> stopVpn());

        updateStatus("Отключено");
        tvPing.setText("Пинг: —");
        tvSpeed.setText("Скорость: —");
        tvJitter.setText("Jitter: —");
        tvTraffic.setText("Трафик: —");
    }

    private void setupNetworkMonitor() {
        networkMonitor = new NetworkMonitor();
        networkMonitor.setUpdateCallback(this::updateNetworkStats);
    }

    private void updateNetworkStats() {
        runOnUiThread(() -> {
            if (vpnRunning && networkMonitor != null) {
                tvPing.setText("Пинг: " + NetworkMonitor.formatPing(networkMonitor.getLastPing()));
                tvSpeed.setText("Скорость: " + NetworkMonitor.formatSpeed(networkMonitor.getLastDownloadSpeed()));
                long jitter = networkMonitor.getLastJitter();
                tvJitter.setText("Jitter: " + (jitter > 0 ? jitter + "ms" : "—"));
            }
        });
    }

    private void setupServers() {
        Map<String, BrowsecVpnService.VpnServer> servers = BrowsecVpnService.getServers();
        String[] serverNames = new String[servers.size()];
        String[] serverCodes = new String[servers.size()];

        int i = 0;
        for (Map.Entry<String, BrowsecVpnService.VpnServer> entry : servers.entrySet()) {
            serverCodes[i] = entry.getKey();
            serverNames[i] = entry.getValue().toString() + " (" + entry.getKey().toUpperCase() + ")";
            i++;
        }

        ArrayAdapter<String> adapter = new ArrayAdapter<>(this,
            android.R.layout.simple_spinner_item, serverNames);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spinnerServers.setAdapter(adapter);

        spinnerServers.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                selectedServer = serverCodes[position];
            }
            @Override
            public void onNothingSelected(AdapterView<?> parent) {}
        });
    }

    private void startVpn() {
        Intent intent = VpnService.prepare(this);
        if (intent != null) {
            startActivityForResult(intent, VPN_REQUEST_CODE);
        } else {
            onActivityResult(VPN_REQUEST_CODE, RESULT_OK, null);
        }
    }

    private void stopVpn() {
        Intent intent = new Intent(this, BrowsecVpnService.class);
        intent.setAction("STOP");
        startService(intent);
        vpnRunning = false;
        networkMonitor.stop();
        updateStatus("Отключено");
        btnConnect.setEnabled(true);
        btnDisconnect.setEnabled(false);
        tvPing.setText("Пинг: —");
        tvSpeed.setText("Скорость: —");
        tvJitter.setText("Jitter: —");
        tvTraffic.setText("Трафик: —");
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == VPN_REQUEST_CODE && resultCode == RESULT_OK) {
            Intent intent = new Intent(this, BrowsecVpnService.class);
            intent.putExtra("country", selectedServer);
            startService(intent);
            vpnRunning = true;
            networkMonitor.start();
            updateStatus("Подключено");
            btnConnect.setEnabled(false);
            btnDisconnect.setEnabled(true);
        }
    }

    private void updateStatus(String status) {
        tvStatus.setText("Статус: " + status);
        BrowsecVpnService.VpnServer server = BrowsecVpnService.getServers().get(selectedServer);
        if (server != null) {
            tvServer.setText("Сервер: " + server.flag + " " + server.name);
        }
        if ("Подключено".equals(status)) {
            tvStatus.setTextColor(0xFF4CAF50);
        } else {
            tvStatus.setTextColor(0xFFF44336);
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (networkMonitor != null) {
            networkMonitor.stop();
        }
        uiHandler.removeCallbacksAndMessages(null);
    }
}
