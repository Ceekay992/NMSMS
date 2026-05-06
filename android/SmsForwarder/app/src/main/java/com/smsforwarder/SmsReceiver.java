package com.smsforwarder;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.provider.Telephony;
import android.telephony.SmsMessage;
import android.util.Log;

public class SmsReceiver extends BroadcastReceiver {
    private static final String TAG = "SmsReceiver";

    @Override
    public void onReceive(Context context, Intent intent) {
        Log.d(TAG, "收到Intent: " + intent);

        String action = intent.getAction();
        if (Telephony.Sms.Intents.SMS_RECEIVED_ACTION.equals(action)) {
            Bundle bundle = intent.getExtras();
            if (bundle != null) {
                Object[] pdus = (Object[]) bundle.get("pdus");
                if (pdus != null && pdus.length > 0) {
                    StringBuilder messageBody = new StringBuilder();
                    String senderNumber = "";
                    for (Object pdu : pdus) {
                        SmsMessage sms = SmsMessage.createFromPdu((byte[]) pdu);
                        if (sms != null) {
                            if (senderNumber.isEmpty()) {
                                senderNumber = sms.getOriginatingAddress();
                            }
                            messageBody.append(sms.getMessageBody());
                        }
                    }
                    String content = messageBody.toString();
                    Log.d(TAG, "短信内容: " + content);

                    PendingResult result = goAsync();
                    sendSmsAndWake(context, senderNumber, content, result);
                }
            }
        }
    }

    private void sendSmsAndWake(Context context, String phoneNumber, String content, final PendingResult result) {
        new Thread(() -> {
            try {
                Log.d(TAG, "开始发送短信到后端");
                SmsForwarderService.sendCode(context, phoneNumber, content);
                Log.d(TAG, "短信发送成功");
            } catch (Exception e) {
                Log.e(TAG, "短信发送异常", e);
            } finally {
                result.finish();
            }
        }).start();

        try {
            SmsForwarderService.start(context);
            Log.d(TAG, "已唤醒Service");
        } catch (Exception e) {
            Log.e(TAG, "唤醒Service失败", e);
        }
    }
}
