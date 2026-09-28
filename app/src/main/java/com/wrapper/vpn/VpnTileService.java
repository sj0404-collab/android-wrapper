package com.wrapper.vpn;

import android.annotation.TargetApi;
import android.content.Intent;
import android.net.VpnService;
import android.os.Build;
import android.service.quicksettings.Tile;
import android.service.quicksettings.TileService;
import android.util.Log;
import android.widget.Toast;

import com.wrapper.R;

/**
 * Плитка в шторке быстрых настроек: включает/выключает VPN без открытия приложения.
 * Состояние плитки синхронизировано с реальным состоянием сервиса.
 */
@TargetApi(Build.VERSION_CODES.N)
public class VpnTileService extends TileService {

    private static final String TAG = "VpnTileService";

    @Override
    public void onStartListening() {
        super.onStartListening();
        syncTile();
    }

    @Override
    public void onClick() {
        super.onClick();

        Intent intent = new Intent(this, BrowsecVpnService.class);
        intent.setAction(BrowsecVpnService.ACTION_TOGGLE);

        if (BrowsecVpnService.getServers().isEmpty()) {
            Toast.makeText(this, "No servers", Toast.LENGTH_SHORT).show();
            return;
        }

        if (VpnService.prepare(this) != null) {
            // Нужно подтверждение в системном диалоге — только из Activity
            Intent open = new Intent(this, com.wrapper.ui.MainActivity.class);
            open.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivityAndCollapse(open);
            Toast.makeText(this, "Разрешите VPN в приложении", Toast.LENGTH_LONG).show();
            return;
        }

        try {
            startForegroundService(intent);
        } catch (Exception e) {
            Log.e(TAG, "Tile toggle failed", e);
            Toast.makeText(this, "Ошибка: " + e.getMessage(), Toast.LENGTH_SHORT).show();
            return;
        }

        // Сервис ещё не успел подняться — обновим плитку с задержкой
        new android.os.Handler(getMainLooper()).postDelayed(this::syncTile, 1200);
    }

    private void syncTile() {
        Tile tile = getQsTile();
        if (tile == null) return;

        boolean connected = isVpnRunning();
        tile.setState(connected ? Tile.STATE_ACTIVE : Tile.STATE_INACTIVE);
        tile.setLabel(getString(R.string.app_name));
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            tile.setSubtitle(connected ? "Подключено" : "Отключено");
        }
        tile.updateTile();
    }

    private boolean isVpnRunning() {
        android.app.ActivityManager am = (android.app.ActivityManager) getSystemService(ACTIVITY_SERVICE);
        if (am == null) return false;
        for (android.app.ActivityManager.RunningServiceInfo info : am.getRunningServices(100)) {
            if (BrowsecVpnService.class.getName().equals(info.service.getClassName())) {
                return true;
            }
        }
        return false;
    }
}
