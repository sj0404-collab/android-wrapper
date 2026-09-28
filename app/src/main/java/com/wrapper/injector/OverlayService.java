package com.wrapper.injector;

import android.app.Service;
import android.content.Intent;
import android.graphics.PixelFormat;
import android.graphics.Color;
import android.os.IBinder;
import android.view.Gravity;
import android.view.WindowManager;
import android.webkit.WebView;
import android.webkit.WebSettings;
import android.widget.FrameLayout;

/**
 * Сервис для показа оверлея поверх приложений
 */
public class OverlayService extends Service {
    private WindowManager windowManager;
    private WebView overlayWebView;

    @Override
    public void onCreate() {
        super.onCreate();
        windowManager = (WindowManager) getSystemService(WINDOW_SERVICE);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null) {
            String html = intent.getStringExtra("html");
            int x = intent.getIntExtra("x", 0);
            int y = intent.getIntExtra("y", 100);
            int width = intent.getIntExtra("width", 800);
            int height = intent.getIntExtra("height", 600);

            showOverlay(html, x, y, width, height);
        }
        return START_NOT_STICKY;
    }

    private void showOverlay(String html, int x, int y, int width, int height) {
        // Удаляем предыдущий оверлей
        if (overlayWebView != null) {
            windowManager.removeView(overlayWebView);
            overlayWebView.destroy();
        }

        // Создание WebView для оверлея
        overlayWebView = new WebView(this);
        WebSettings settings = overlayWebView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);

        // Загрузка HTML
        if (html != null) {
            overlayWebView.loadDataWithBaseURL(null, html, "text/html", "UTF-8", null);
        }

        // Параметры окна
        WindowManager.LayoutParams params = new WindowManager.LayoutParams(
            width,
            height,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE |
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT
        );

        params.gravity = Gravity.TOP | Gravity.START;
        params.x = x;
        params.y = y;

        windowManager.addView(overlayWebView, params);
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        if (overlayWebView != null) {
            windowManager.removeView(overlayWebView);
            overlayWebView.destroy();
        }
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
