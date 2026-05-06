package com.smsforwarder;

import android.content.Context;
import android.content.SharedPreferences;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import org.json.JSONObject;

public class NetworkUtil {
    private static final int TIMEOUT_MS = 10000;
    private static final int MAX_RETRIES = 3;
    private static final int RETRY_DELAY_MS = 2000;

    public static boolean testConnection(String ip, String port) {
        String url = String.format("http://%s:%s/health", ip, port);
        for (int i = 0; i < MAX_RETRIES; i++) {
            try {
                HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
                conn.setRequestMethod("GET");
                conn.setConnectTimeout(TIMEOUT_MS);
                conn.setReadTimeout(TIMEOUT_MS);
                int responseCode = conn.getResponseCode();
                conn.disconnect();
                if (responseCode == 200) {
                    return true;
                }
            } catch (Exception e) {
                e.printStackTrace();
                if (i < MAX_RETRIES - 1) {
                    try {
                        Thread.sleep(RETRY_DELAY_MS);
                    } catch (InterruptedException ie) {
                        ie.printStackTrace();
                    }
                }
            }
        }
        return false;
    }

    public static boolean sendSms(String ip, String port, String deviceId, String deviceName,
                                   String phoneNumber, String content, long timestamp) {
        String url = String.format("http://%s:%s/api/sms", ip, port);

        for (int i = 0; i < MAX_RETRIES; i++) {
            HttpURLConnection conn = null;
            try {
                conn = (HttpURLConnection) new URL(url).openConnection();
                conn.setRequestMethod("POST");
                conn.setDoOutput(true);
                conn.setDoInput(true);
                conn.setConnectTimeout(TIMEOUT_MS);
                conn.setReadTimeout(TIMEOUT_MS);
                conn.setRequestProperty("Content-Type", "application/json; charset=UTF-8");

                JSONObject json = new JSONObject();
                json.put("deviceId", deviceId);
                json.put("deviceName", deviceName);
                json.put("phoneNumber", phoneNumber);
                json.put("timestamp", timestamp);
                json.put("content", content);

                OutputStream os = conn.getOutputStream();
                os.write(json.toString().getBytes("UTF-8"));
                os.flush();
                os.close();

                int responseCode = conn.getResponseCode();
                if (responseCode == 200) {
                    BufferedReader br = new BufferedReader(new InputStreamReader(conn.getInputStream(), "UTF-8"));
                    StringBuilder response = new StringBuilder();
                    String line;
                    while ((line = br.readLine()) != null) {
                        response.append(line);
                    }
                    br.close();

                    JSONObject result = new JSONObject(response.toString());
                    return result.optBoolean("success", false);
                }
            } catch (Exception e) {
                e.printStackTrace();
                if (i < MAX_RETRIES - 1) {
                    try {
                        Thread.sleep(RETRY_DELAY_MS);
                    } catch (InterruptedException ie) {
                        ie.printStackTrace();
                    }
                }
            } finally {
                if (conn != null) {
                    conn.disconnect();
                }
            }
        }
        return false;
    }

    public static boolean sendHeartbeat(String ip, String port, String deviceId, String deviceName) {
        String url = String.format("http://%s:%s/api/heartbeat", ip, port);

        try {
            HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
            conn.setRequestMethod("POST");
            conn.setDoOutput(true);
            conn.setDoInput(true);
            conn.setConnectTimeout(5000);
            conn.setReadTimeout(5000);
            conn.setRequestProperty("Content-Type", "application/json; charset=UTF-8");

            JSONObject json = new JSONObject();
            json.put("deviceId", deviceId);
            json.put("deviceName", deviceName);

            OutputStream os = conn.getOutputStream();
            os.write(json.toString().getBytes("UTF-8"));
            os.flush();
            os.close();

            int responseCode = conn.getResponseCode();
            conn.disconnect();
            return responseCode == 200;
        } catch (Exception e) {
            e.printStackTrace();
            return false;
        }
    }

    public static void sendSms(Context context, String phoneNumber, String content, String code) {
        SharedPreferences pref = context.getSharedPreferences(MainActivity.PREFS_NAME, Context.MODE_PRIVATE);
        String ip = pref.getString(MainActivity.KEY_SERVER_IP, "");
        String port = pref.getString(MainActivity.KEY_PORT, "8121");
        String deviceId = pref.getString(MainActivity.KEY_DEVICE_ID, "");
        String deviceName = android.os.Build.MODEL;

        if (ip.isEmpty()) return;

        String url = String.format("http://%s:%s/api/sms", ip, port);

        for (int i = 0; i < MAX_RETRIES; i++) {
            HttpURLConnection conn = null;
            try {
                conn = (HttpURLConnection) new URL(url).openConnection();
                conn.setRequestMethod("POST");
                conn.setDoOutput(true);
                conn.setDoInput(true);
                conn.setConnectTimeout(TIMEOUT_MS);
                conn.setReadTimeout(TIMEOUT_MS);
                conn.setRequestProperty("Content-Type", "application/json; charset=UTF-8");

                JSONObject json = new JSONObject();
                json.put("deviceId", deviceId);
                json.put("deviceName", deviceName);
                json.put("phoneNumber", phoneNumber);
                json.put("timestamp", System.currentTimeMillis());
                json.put("content", content);

                OutputStream os = conn.getOutputStream();
                os.write(json.toString().getBytes("UTF-8"));
                os.flush();
                os.close();

                int responseCode = conn.getResponseCode();
                if (responseCode == 200) {
                    BufferedReader br = new BufferedReader(new InputStreamReader(conn.getInputStream(), "UTF-8"));
                    StringBuilder response = new StringBuilder();
                    String line;
                    while ((line = br.readLine()) != null) {
                        response.append(line);
                    }
                    br.close();

                    JSONObject result = new JSONObject(response.toString());
                    if (result.optBoolean("success", false)) {
                        return;
                    }
                }
            } catch (Exception e) {
                e.printStackTrace();
                if (i < MAX_RETRIES - 1) {
                    try {
                        Thread.sleep(RETRY_DELAY_MS);
                    } catch (InterruptedException ie) {
                        ie.printStackTrace();
                    }
                }
            } finally {
                if (conn != null) {
                    conn.disconnect();
                }
            }
        }
    }
}
