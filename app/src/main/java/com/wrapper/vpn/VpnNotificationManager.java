package com.wrapper.vpn;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import androidx.core.app.NotificationCompat;
import com.wrapper.R;
import com.wrapper.ui.MainActivity;

/**
 * Управление уведомлениями VPN
 */
public class VpnNotificationManager {
    private static final String CHANNEL_ID = "vpn_channel";
    private static final int NOTIFICATION_ID = 1001;
    
    private final Context context;
    private final NotificationManager notificationManager;
    
    public VpnNotificationManager(Context context) {
        this.context = context;
        this.notificationManager = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
        createChannel();
    }
    
    private void createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID,
                "VPN Status",
                NotificationManager.IMPORTANCE_LOW
            );
            channel.setDescription("VPN connection status and stats");
            channel.setShowBadge(false);
            notificationManager.createNotificationChannel(channel);
        }
    }
    
    /**
     * Показать уведомление о подключении
     */
    public void showConnected(String serverName, String serverFlag) {
        Intent intent = new Intent(context, MainActivity.class);
        PendingIntent pendingIntent = PendingIntent.getActivity(
            context, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        
        Intent disconnectIntent = new Intent(context, BrowsecVpnService.class);
        disconnectIntent.setAction("STOP");
        PendingIntent disconnectPending = PendingIntent.getService(
            context, 1, disconnectIntent, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        
        Notification notification = new NotificationCompat.Builder(context, CHANNEL_ID)
            .setContentTitle("VPN подключен")
            .setContentText(serverFlag + " " + serverName)
            .setSmallIcon(android.R.drawable.ic_lock_lock)
            .setColor(0xFF4CAF50)
            .setContentIntent(pendingIntent)
            .addAction(android.R.drawable.ic_media_pause, "Отключить", disconnectPending)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build();
        
        notificationManager.notify(NOTIFICATION_ID, notification);
    }
    
    /**
     * Обновить уведомление со статистикой
     */
    public void updateStats(String serverName, String serverFlag, long ping, long downloadSpeed) {
        Intent intent = new Intent(context, MainActivity.class);
        PendingIntent pendingIntent = PendingIntent.getActivity(
            context, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        
        Intent disconnectIntent = new Intent(context, BrowsecVpnService.class);
        disconnectIntent.setAction("STOP");
        PendingIntent disconnectPending = PendingIntent.getService(
            context, 1, disconnectIntent, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        
        String pingStr = NetworkMonitor.formatPing(ping);
        String speedStr = NetworkMonitor.formatSpeed(downloadSpeed);
        
        Notification notification = new NotificationCompat.Builder(context, CHANNEL_ID)
            .setContentTitle(serverFlag + " " + serverName)
            .setContentText("Пинг: " + pingStr + " | Скорость: " + speedStr)
            .setSmallIcon(android.R.drawable.ic_lock_lock)
            .setColor(0xFF4CAF50)
            .setContentIntent(pendingIntent)
            .addAction(android.R.drawable.ic_media_pause, "Отключить", disconnectPending)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOnlyAlertOnce(true)
            .build();
        
        notificationManager.notify(NOTIFICATION_ID, notification);
    }
    
    /**
     * Показать уведомление об отключении
     */
    public void showDisconnected() {
        Intent intent = new Intent(context, MainActivity.class);
        PendingIntent pendingIntent = PendingIntent.getActivity(
            context, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        
        Notification notification = new NotificationCompat.Builder(context, CHANNEL_ID)
            .setContentTitle("VPN отключен")
            .setContentText("Нажмите для подключения")
            .setSmallIcon(android.R.drawable.ic_lock_lock)
            .setColor(0xFFF44336)
            .setContentIntent(pendingIntent)
            .setOngoing(false)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build();
        
        notificationManager.notify(NOTIFICATION_ID, notification);
    }
    
    /**
     * Убрать уведомление
     */
    public void cancel() {
        notificationManager.cancel(NOTIFICATION_ID);
    }
}
