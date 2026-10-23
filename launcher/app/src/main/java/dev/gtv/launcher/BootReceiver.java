package dev.gtv.launcher;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** Alarms do not survive a reboot (or an app update), so line them up again. */
public class BootReceiver extends BroadcastReceiver {

    private static final String QUICKBOOT = "android.intent.action.QUICKBOOT_POWERON";

    @Override
    public void onReceive(Context ctx, Intent intent) {
        String action = intent.getAction();
        if (Intent.ACTION_BOOT_COMPLETED.equals(action)
                || Intent.ACTION_MY_PACKAGE_REPLACED.equals(action)
                || QUICKBOOT.equals(action)) {
            PrayerScheduler.arm(ctx);
        }
    }
}
