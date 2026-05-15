package com.smsforwarder;

import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.PixelFormat;
import android.graphics.Point;
import android.os.Build;
import android.os.IBinder;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.TextView;

public class FloatWindowService extends Service {
    private static final String TAG = "FloatWindowService";
    private static final String PREFS_NAME = "SmsForwarderPrefs";
    private static final String KEY_FLOAT_ENABLED = "float_window_enabled";
    private static final int FLOAT_SIZE_DP = 30;

    private WindowManager windowManager;
    private TextView floatView;
    private WindowManager.LayoutParams params;
    private boolean isAdded = false;

    private float initialTouchX;
    private float initialTouchY;
    private int initialX;
    private int initialY;

    public static boolean isEnabled(Context context) {
        SharedPreferences pref = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        return pref.getBoolean(KEY_FLOAT_ENABLED, false);
    }

    public static void setEnabled(Context context, boolean enabled) {
        SharedPreferences pref = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        pref.edit().putBoolean(KEY_FLOAT_ENABLED, enabled).apply();
    }

    public static void start(Context context) {
        Intent intent = new Intent(context, FloatWindowService.class);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startForegroundService(intent);
        } else {
            context.startService(intent);
        }
    }

    public static void stop(Context context) {
        context.stopService(new Intent(context, FloatWindowService.class));
    }

    @Override
    public void onCreate() {
        super.onCreate();
        Log.i(TAG, "FloatWindowService onCreate");
        createNotificationChannel();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        Log.i(TAG, "FloatWindowService onStartCommand");
        if (!isAdded) {
            createFloatWindow();
        }
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        Log.i(TAG, "FloatWindowService onDestroy");
        removeFloatWindow();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            android.app.NotificationChannel channel = new android.app.NotificationChannel(
                    "FloatWindow",
                    "悬浮窗保活",
                    android.app.NotificationManager.IMPORTANCE_MIN
            );
            channel.setDescription("保持应用活跃状态，确保息屏后能接收短信");
            channel.setShowBadge(false);

            android.app.NotificationManager nm = getSystemService(android.app.NotificationManager.class);
            nm.createNotificationChannel(channel);

            android.app.Notification notification = new android.app.Notification.Builder(this, "FloatWindow")
                    .setContentTitle("📱 短信转发器")
                    .setContentText("悬浮窗保活中...")
                    .setSmallIcon(android.R.drawable.ic_menu_info_details)
                    .setOngoing(true)
                    .setPriority(android.app.Notification.PRIORITY_MIN)
                    .build();

            startForeground(200, notification);
        }
    }

    private void createFloatWindow() {
        windowManager = (WindowManager) getSystemService(WINDOW_SERVICE);

        // 计算悬浮窗大小
        DisplayMetrics metrics = new DisplayMetrics();
        windowManager.getDefaultDisplay().getMetrics(metrics);
        int floatSize = (int) (FLOAT_SIZE_DP * metrics.density);

        // 创建悬浮窗视图
        floatView = new TextView(this);
        floatView.setText("📬");
        floatView.setTextSize(16);
        floatView.setTextColor(0xFFFFFFFF);
        floatView.setGravity(Gravity.CENTER);
        floatView.setBackgroundResource(android.R.drawable.screen_background_dark);
        floatView.getBackground().setAlpha(120);
        floatView.setAlpha(0.6f);

        // 设置圆角
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            floatView.setBackground(null);
            android.graphics.drawable.GradientDrawable drawable = new android.graphics.drawable.GradientDrawable();
            drawable.setShape(android.graphics.drawable.GradientDrawable.OVAL);
            drawable.setColor(0x80000000);
            floatView.setBackground(drawable);
        }

        // 布局参数
        int layoutFlag;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            layoutFlag = WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY;
        } else {
            layoutFlag = WindowManager.LayoutParams.TYPE_PHONE;
        }

        Point size = new Point();
        windowManager.getDefaultDisplay().getSize(size);

        params = new WindowManager.LayoutParams(
                floatSize,
                floatSize,
                layoutFlag,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT
        );

        params.gravity = Gravity.TOP | Gravity.START;
        params.x = size.x - floatSize - 20;
        params.y = size.y / 3;

        // 触摸事件 - 可拖动
        floatView.setOnTouchListener(new View.OnTouchListener() {
            private static final int MAX_CLICK_DISTANCE = 10;
            private float downX, downY;

            @Override
            public boolean onTouch(View v, MotionEvent event) {
                switch (event.getAction()) {
                    case MotionEvent.ACTION_DOWN:
                        initialTouchX = event.getRawX();
                        initialTouchY = event.getRawY();
                        downX = event.getX();
                        downY = event.getY();
                        initialX = params.x;
                        initialY = params.y;
                        return true;

                    case MotionEvent.ACTION_MOVE:
                        params.x = initialX + (int) (event.getRawX() - initialTouchX);
                        params.y = initialY + (int) (event.getRawY() - initialTouchY);
                        windowManager.updateViewLayout(floatView, params);
                        return true;

                    case MotionEvent.ACTION_UP:
                        float dx = event.getX() - downX;
                        float dy = event.getY() - downY;
                        if (Math.abs(dx) < MAX_CLICK_DISTANCE && Math.abs(dy) < MAX_CLICK_DISTANCE) {
                            // 点击悬浮窗：打开主界面
                            Intent intent = new Intent(FloatWindowService.this, MainActivity.class);
                            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
                            startActivity(intent);
                        }
                        return true;
                }
                return false;
            }
        });

        try {
            windowManager.addView(floatView, params);
            isAdded = true;
            Log.i(TAG, "悬浮窗已添加");
        } catch (Exception e) {
            Log.e(TAG, "添加悬浮窗失败: " + e.getMessage(), e);
        }
    }

    private void removeFloatWindow() {
        if (floatView != null && isAdded && windowManager != null) {
            try {
                windowManager.removeView(floatView);
                isAdded = false;
                Log.i(TAG, "悬浮窗已移除");
            } catch (Exception e) {
                Log.e(TAG, "移除悬浮窗失败: " + e.getMessage(), e);
            }
        }
    }
}
