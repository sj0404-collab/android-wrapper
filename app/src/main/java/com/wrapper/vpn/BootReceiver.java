package com.wrapper.vpn;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.net.VpnService;
import android.util.Log;

/**
 * Поднимает VPN после перезагрузки, если он был включён до неё.
 * Работает в фоне: сервис сам стартует в foreground и держит уведомление.
 */
public class BootReceiver extends BroadcastReceiver {

    private static final String TAG = "BootReceiver";

    @Override
    public void onReceive(Context context, Intent intent) {
        String action = intent != null ? intent.getAction() : null;
        if (action == null) return;

        if (!Intent.ACTION_BOOT_COMPLETED.equals(action)
                && !Intent.ACTION_MY_PACKAGE_REPLACED.equals(action)
                && !"android.intent.action.QUICKBOOT_POWERON".equals(action)) {
            return;
        }

        boolean wasConnected = context.getSharedPreferences(BrowsecVpnService.PREFS, Context.MODE_PRIVATE)
                .getBoolean(BrowsecVpnService.PREF_WAS_CONNECTED, false);
        if (!wasConnected) {
            Log.d(TAG, "VPN was off, nothing to restore");
            return;
        }

        // Разрешение VpnService не восстанавливается само — без него startService упадёт.
        if (VpnService.prepare(context) != null) {
            Log.w(TAG, "VPN permission not granted, skipping autostart");
            return;
        }

        Log.d(TAG, "Restoring VPN after " + action);
        Intent start = new Intent(context, BrowsecVpnService.class);
        start.setAction(BrowsecVpnService.ACTION_START);
        context.startForegroundService(start);
    }
}
