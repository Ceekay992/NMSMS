package com.smsforwarder;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.database.ContentObserver;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.PowerManager;
import android.provider.Telephony;
import android.util.Log;

import androidx.core.app.NotificationCompat;
import androidx.core.content.ContextCompat;

import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

public class SmsForwarderService extends Service {
    private static final String TAG = "SmsForwarderService";
    private static final String CHANNEL_ID = "SmsForwarder";
    private static final int NOTIFICATION_ID = 100;
    private static final long POLL_INTERVAL_MS = 2000L;

    public static final String ACTION_SEND_CODE = "com.smsforwarder.SEND_CODE";
    public static final String EXTRA_SENDER = "sender";
    public static final String EXTRA_BODY = "body";

    public static void start(Context context) {
        Intent intent = new Intent(context, SmsForwarderService.class);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startForegroundService(intent);
        } else {
            context.startService(intent);
        }
    }

    public static void sendCode(Context context, String sender, String body) {
        Intent intent = new Intent(context, SmsForwarderService.class);
        intent.setAction(ACTION_SEND_CODE);
        intent.putExtra(EXTRA_SENDER, sender);
        intent.putExtra(EXTRA_BODY, body);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startForegroundService(intent);
        } else {
            context.startService(intent);
        }
    }

    private PowerManager.WakeLock wakeLock;
    private ScheduledExecutorService scheduler;
    private ScheduledFuture<?> pollTask;
    private ContentObserver smsObserver;
    private HandlerThread observerThread;
    private Handler mainHandler;

    private long lastSmsTimestamp = 0L;
    private Set<String> sentCodes = new HashSet<>();
    private int pollCount = 0;

    @Override
    public void onCreate() {
        super.onCreate();
        Log.i(TAG, "Service onCreate");
        mainHandler = new Handler(getMainLooper());

        createNotificationChannel();
        updateNotification("正在监听...");
        startForeground(NOTIFICATION_ID, buildNotification("正在监听..."));

        acquireWakeLock();
        lastSmsTimestamp = System.currentTimeMillis();

        startSmsObserver();
        startSmsPolling();
    }

    private Notification buildNotification(String text) {
        return new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("📱 短信转发器")
                .setContentText(text)
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setOngoing(true)
                .setPriority(NotificationCompat.PRIORITY_MAX)
                .build();
    }

    private void updateNotification(String text) {
        NotificationManager nm = getSystemService(NotificationManager.class);
        if (nm != null) {
            nm.notify(NOTIFICATION_ID, buildNotification(text));
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_SEND_CODE.equals(intent.getAction())) {
            String sender = intent.getStringExtra(EXTRA_SENDER);
            String body = intent.getStringExtra(EXTRA_BODY);
            if (sender != null && body != null) {
                handleCode("sms", sender, body);
            }
        }
        return START_STICKY;
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        Log.i(TAG, "Service onDestroy");

        if (pollTask != null) {
            pollTask.cancel(false);
        }
        if (smsObserver != null) {
            getContentResolver().unregisterContentObserver(smsObserver);
        }
        if (observerThread != null) {
            observerThread.quitSafely();
        }
        releaseWakeLock();
        if (scheduler != null) {
            scheduler.shutdown();
        }
    }

    private void handleCode(String source, String sender, String body) {
        String code = extractCode(body);
        if (code == null) return;

        String key = code + ":" + (System.currentTimeMillis() / 10000);
        if (sentCodes.contains(key)) {
            Log.d(TAG, "Duplicate code " + code + ", skipping");
            return;
        }
        sentCodes.add(key);
        if (sentCodes.size() > 50) {
            Set<String> newSet = new HashSet<>();
            int count = 0;
            for (String k : sentCodes) {
                if (count++ < 25) continue;
                newSet.add(k);
            }
            sentCodes = newSet;
        }

        Log.i(TAG, "Processing code: " + code + " from " + sender);
        updateNotification("收到验证码: " + code);

        new Thread(() -> {
            try {
                NetworkUtil.sendSms(this, sender, body, code);
                Log.i(TAG, "Code " + code + " sent successfully");
            } catch (Exception e) {
                Log.e(TAG, "Failed to send code: " + e.getMessage(), e);
            } finally {
                new Handler(getMainLooper()).postDelayed(() -> 
                    updateNotification("正在监听..."), 5000);
            }
        }).start();
    }

    private String extractCode(String body) {
        if (body == null) return null;

        String regex = "\\b\\d{4,8}\\b";
        java.util.regex.Pattern pattern = java.util.regex.Pattern.compile(regex);
        java.util.regex.Matcher matcher = pattern.matcher(body);

        String lastCode = null;
        while (matcher.find()) {
            lastCode = matcher.group();
        }
        return lastCode;
    }

    private void startSmsObserver() {
        if (ContextCompat.checkSelfPermission(this, android.Manifest.permission.READ_SMS)
                != PackageManager.PERMISSION_GRANTED) {
            Log.w(TAG, "No READ_SMS permission, skipping ContentObserver");
            return;
        }

        observerThread = new HandlerThread("SmsObserver");
        observerThread.start();

        smsObserver = new ContentObserver(new Handler(observerThread.getLooper())) {
            @Override
            public void onChange(boolean selfChange, Uri uri) {
                Log.d(TAG, "SMS ContentObserver triggered");
                checkNewSms();
            }
        };

        ContentResolver resolver = getContentResolver();
        resolver.registerContentObserver(Telephony.Sms.CONTENT_URI, true, smsObserver);
        Log.i(TAG, "SMS ContentObserver registered");
    }

    private void startSmsPolling() {
        scheduler = Executors.newSingleThreadScheduledExecutor();
        pollTask = scheduler.scheduleWithFixedDelay(
                this::checkNewSms,
                POLL_INTERVAL_MS,
                POLL_INTERVAL_MS,
                TimeUnit.MILLISECONDS
        );
        Log.i(TAG, "SMS polling started (every " + POLL_INTERVAL_MS + "ms)");
    }

    private void checkNewSms() {
        pollCount++;
        if (pollCount % 30 == 0) {
            Log.d(TAG, "Polling alive...");
            updateNotification("正在监听...");
        }

        if (ContextCompat.checkSelfPermission(this, android.Manifest.permission.READ_SMS)
                != PackageManager.PERMISSION_GRANTED) {
            Log.w(TAG, "No READ_SMS permission, skipping poll");
            return;
        }

        try {
            Cursor cursor = getContentResolver().query(
                    Telephony.Sms.Inbox.CONTENT_URI,
                    new String[]{
                            Telephony.Sms._ID,
                            Telephony.Sms.ADDRESS,
                            Telephony.Sms.BODY,
                            Telephony.Sms.DATE
                    },
                    Telephony.Sms.DATE + " > ?",
                    new String[]{String.valueOf(lastSmsTimestamp)},
                    Telephony.Sms.DATE + " ASC"
            );

            if (cursor != null) {
                while (cursor.moveToNext()) {
                    String sender = cursor.getString(cursor.getColumnIndexOrThrow(Telephony.Sms.ADDRESS));
                    String body = cursor.getString(cursor.getColumnIndexOrThrow(Telephony.Sms.BODY));
                    long date = cursor.getLong(cursor.getColumnIndexOrThrow(Telephony.Sms.DATE));

                    if (date > lastSmsTimestamp) {
                        lastSmsTimestamp = date;
                    }

                    if (body != null) {
                        String code = extractCode(body);
                        if (code != null) {
                            Log.i(TAG, "Found code: " + code + " from " + sender);
                            handleCode("sms", sender, body);
                        }
                    }
                }
                cursor.close();
            }
        } catch (Exception e) {
            Log.e(TAG, "SMS poll error: " + e.getMessage(), e);
        }
    }

    private void acquireWakeLock() {
        PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
        wakeLock = pm.newWakeLock(
                PowerManager.PARTIAL_WAKE_LOCK,
                "SmsForwarder::WakeLock"
        );
        wakeLock.acquire();
        Log.i(TAG, "WakeLock acquired");
    }

    private void releaseWakeLock() {
        if (wakeLock != null && wakeLock.isHeld()) {
            wakeLock.release();
            Log.i(TAG, "WakeLock released");
        }
        wakeLock = null;
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID,
                    "短信转发器",
                    NotificationManager.IMPORTANCE_HIGH
            );
            channel.setDescription("保持验证码监听服务运行");

            NotificationManager nm = getSystemService(NotificationManager.class);
            nm.createNotificationChannel(channel);
        }
    }
}
