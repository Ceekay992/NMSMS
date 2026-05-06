package com.smsforwarder;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.os.PowerManager;
import android.provider.Settings;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

public class MainActivity extends Activity {
    private TextView tvStatus;
    private TextView tvDeviceId;
    private EditText etServerIp;
    private EditText etPort;
    private Button btnSaveConfig;
    private Button btnTestConnection;
    private Button btnBatteryOptimization;
    private Button btnAutoStart;
    private CheckBox cbKeepScreenOn;
    private View statusIndicator;

    private SharedPreferences pref;
    private String deviceId;

    public static final String PREFS_NAME = "SmsForwarderPrefs";
    public static final String KEY_SERVER_IP = "server_ip";
    public static final String KEY_PORT = "port";
    public static final String KEY_DEVICE_ID = "device_id";
    public static final String KEY_KEEP_SCREEN_ON = "keep_screen_on";
    public static final String KEY_FIRST_LAUNCH = "first_launch";

    private static final int REQUEST_SMS_PERMISSIONS = 1001;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        initViews();
        loadPreferences();
        setupListeners();
        checkPermissions();

        if (pref.getBoolean(KEY_FIRST_LAUNCH, true)) {
            showFirstLaunchGuide();
            pref.edit().putBoolean(KEY_FIRST_LAUNCH, false).apply();
        }

        SmsForwarderService.start(this);
        updateScreenKeepOn();
    }

    private void showFirstLaunchGuide() {
        new AlertDialog.Builder(this)
                .setTitle("⚠️ 必须完成的设置")
                .setMessage("为了让APP在息屏后也能接收验证码，必须完成以下操作：\n\n"
                        + "① 点击「加入电池白名单」并完成\n"
                        + "② 点击「设置自启动」并完成\n"
                        + "③ 打开最近任务，长按SmsForwarder，点击「锁定」\n\n"
                        + "如果息屏后仍然无法接收，请打开「保持屏幕常亮」！")
                .setPositiveButton("我知道了", null)
                .show();
    }

    private void initViews() {
        tvStatus = findViewById(R.id.tvStatus);
        tvDeviceId = findViewById(R.id.tvDeviceId);
        etServerIp = findViewById(R.id.etServerIp);
        etPort = findViewById(R.id.etPort);
        btnSaveConfig = findViewById(R.id.btnSaveConfig);
        btnTestConnection = findViewById(R.id.btnTestConnection);
        btnBatteryOptimization = findViewById(R.id.btnBatteryOptimization);
        btnAutoStart = findViewById(R.id.btnAutoStart);
        cbKeepScreenOn = findViewById(R.id.cbKeepScreenOn);
        statusIndicator = findViewById(R.id.statusIndicator);
    }

    private void loadPreferences() {
        pref = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);

        deviceId = pref.getString(KEY_DEVICE_ID, null);
        if (deviceId == null) {
            deviceId = java.util.UUID.randomUUID().toString();
            pref.edit().putString(KEY_DEVICE_ID, deviceId).apply();
        }

        String savedIp = pref.getString(KEY_SERVER_IP, "");
        String savedPort = pref.getString(KEY_PORT, "8121");
        boolean keepScreenOn = pref.getBoolean(KEY_KEEP_SCREEN_ON, false);

        etServerIp.setText(savedIp);
        etPort.setText(savedPort);
        cbKeepScreenOn.setChecked(keepScreenOn);
        tvDeviceId.setText("设备ID: " + deviceId.substring(0, Math.min(8, deviceId.length())).toUpperCase());
    }

    private void setupListeners() {
        btnSaveConfig.setOnClickListener(v -> saveConfig());
        btnTestConnection.setOnClickListener(v -> testConnection());
        btnBatteryOptimization.setOnClickListener(v -> requestBatteryOptimization());
        btnAutoStart.setOnClickListener(v -> openAutoStartSetting());

        cbKeepScreenOn.setOnCheckedChangeListener((buttonView, isChecked) -> {
            pref.edit().putBoolean(KEY_KEEP_SCREEN_ON, isChecked).apply();
            updateScreenKeepOn();
        });
    }

    private void updateScreenKeepOn() {
        boolean keepOn = pref.getBoolean(KEY_KEEP_SCREEN_ON, false);
        if (keepOn) {
            getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        } else {
            getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        }
    }

    private void checkPermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            if (checkSelfPermission(Manifest.permission.RECEIVE_SMS) != PackageManager.PERMISSION_GRANTED ||
                checkSelfPermission(Manifest.permission.READ_SMS) != PackageManager.PERMISSION_GRANTED) {

                requestPermissions(new String[]{
                    Manifest.permission.RECEIVE_SMS,
                    Manifest.permission.READ_SMS
                }, REQUEST_SMS_PERMISSIONS);
            } else {
                checkConnectionStatus();
            }
        } else {
            checkConnectionStatus();
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQUEST_SMS_PERMISSIONS) {
            boolean allGranted = true;
            for (int result : grantResults) {
                if (result != PackageManager.PERMISSION_GRANTED) {
                    allGranted = false;
                    break;
                }
            }
            if (allGranted) {
                Toast.makeText(this, "权限已授予", Toast.LENGTH_SHORT).show();
                checkConnectionStatus();
            } else {
                Toast.makeText(this, "需要短信权限才能正常工作", Toast.LENGTH_LONG).show();
            }
        }
    }

    private void saveConfig() {
        String ip = etServerIp.getText().toString().trim();
        String port = etPort.getText().toString().trim();

        if (ip.isEmpty()) {
            Toast.makeText(this, "请输入服务器IP地址", Toast.LENGTH_SHORT).show();
            return;
        }

        pref.edit().putString(KEY_SERVER_IP, ip).putString(KEY_PORT, port).apply();
        Toast.makeText(this, "配置已保存", Toast.LENGTH_SHORT).show();
        checkConnectionStatus();
    }

    private void testConnection() {
        String ip = etServerIp.getText().toString().trim();
        String port = etPort.getText().toString().trim();

        if (ip.isEmpty()) {
            Toast.makeText(this, "请先保存配置", Toast.LENGTH_SHORT).show();
            return;
        }

        updateStatus(false, true);

        new Thread(() -> {
            boolean success = NetworkUtil.testConnection(ip, port);
            runOnUiThread(() -> {
                if (success) {
                    updateStatus(true, false);
                    Toast.makeText(MainActivity.this, "连接成功", Toast.LENGTH_SHORT).show();
                } else {
                    updateStatus(false, false);
                    Toast.makeText(MainActivity.this, "连接失败", Toast.LENGTH_SHORT).show();
                }
            });
        }).start();
    }

    private void checkConnectionStatus() {
        String ip = pref.getString(KEY_SERVER_IP, "");
        String port = pref.getString(KEY_PORT, "8121");

        if (ip.isEmpty()) {
            updateStatus(false, false);
            return;
        }

        updateStatus(false, true);

        new Thread(() -> {
            boolean success = NetworkUtil.testConnection(ip, port);
            runOnUiThread(() -> {
                if (success) {
                    updateStatus(true, false);
                } else {
                    updateStatus(false, false);
                }
            });
        }).start();
    }

    private void updateStatus(boolean connected, boolean connecting) {
        if (connecting) {
            tvStatus.setText("连接中...");
            statusIndicator.setBackgroundColor(0xFFFF9800);
        } else if (connected) {
            tvStatus.setText("已连接");
            statusIndicator.setBackgroundColor(0xFF4CAF50);
        } else {
            tvStatus.setText("未连接");
            statusIndicator.setBackgroundColor(0xFFF44336);
        }
    }

    private void requestBatteryOptimization() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
            if (pm != null && !pm.isIgnoringBatteryOptimizations(getPackageName())) {
                Intent intent = new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS);
                intent.setData(android.net.Uri.parse("package:" + getPackageName()));
                startActivity(intent);
            } else {
                Toast.makeText(this, "应用已加入电池优化白名单", Toast.LENGTH_SHORT).show();
            }
        }
    }

    private void openAutoStartSetting() {
        try {
            Intent intent = new Intent();
            String manufacturer = android.os.Build.MANUFACTURER.toLowerCase();

            if (manufacturer.contains("xiaomi") || manufacturer.contains("redmi")) {
                intent.setComponent(new android.content.ComponentName(
                        "com.miui.securitycenter",
                        "com.miui.permcenter.autostart.AutoStartManagementActivity"
                ));
                startActivity(intent);
            } else if (manufacturer.contains("huawei") || manufacturer.contains("honor")) {
                intent.setComponent(new android.content.ComponentName(
                        "com.huawei.systemmanager",
                        "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity"
                ));
                startActivity(intent);
            } else if (manufacturer.contains("oppo")) {
                intent.setComponent(new android.content.ComponentName(
                        "com.coloros.safecenter",
                        "com.coloros.safecenter.startupapp.StartupAppListActivity"
                ));
                startActivity(intent);
            } else if (manufacturer.contains("vivo")) {
                intent.setComponent(new android.content.ComponentName(
                        "com.iqoo.secure",
                        "com.iqoo.secure.ui.phoneoptimize.AddWhiteListActivity"
                ));
                startActivity(intent);
            } else if (manufacturer.contains("samsung")) {
                intent.setComponent(new android.content.ComponentName(
                        "com.samsung.android.lool",
                        "com.samsung.android.sm.ui.battery.BatteryActivity"
                ));
                startActivity(intent);
            } else if (manufacturer.contains("meizu")) {
                intent.setAction("com.meizu.safe.security.SHOW_APPSEC");
                intent.putExtra("packageName", getPackageName());
                startActivity(intent);
            } else {
                Toast.makeText(this, "请在应用设置中手动开启自启动权限", Toast.LENGTH_LONG).show();
                Intent openAppSetting = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS);
                openAppSetting.setData(android.net.Uri.parse("package:" + getPackageName()));
                startActivity(openAppSetting);
            }
        } catch (Exception e) {
            Toast.makeText(this, "请在应用设置中手动开启自启动权限", Toast.LENGTH_LONG).show();
            Intent openAppSetting = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS);
            openAppSetting.setData(android.net.Uri.parse("package:" + getPackageName()));
            startActivity(openAppSetting);
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        checkConnectionStatus();
        updateScreenKeepOn();
    }
}
