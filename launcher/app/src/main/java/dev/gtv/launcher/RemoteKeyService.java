package dev.gtv.launcher;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.AccessibilityServiceInfo;
import android.content.Intent;
import android.util.Log;
import android.view.KeyEvent;
import android.view.accessibility.AccessibilityEvent;

/**
 * Routes dedicated remote shortcut keys before Google TV launches its own app.
 * Unknown buttons are logged with both keycode and scancode so the device's
 * exact mappings can be added to res/values/config.xml.
 */
public class RemoteKeyService extends AccessibilityService {
    private static final String TAG = "RemoteKeyService";
    static final String ACTION_SHOW_INPUTS = "dev.gtv.launcher.SHOW_INPUTS";
    private String[] appPackages;
    private String[] appFallbacks;
    private int[] appKeyCodes, appScanCodes, inputKeyCodes;
    private int[] settingsKeyCodes, settingsScanCodes;
    private boolean routeInputKeys, routeSettingsKeys;

    @Override
    protected void onServiceConnected() {
        appPackages = getResources().getStringArray(R.array.app_keys);
        appFallbacks = getResources().getStringArray(R.array.app_key_fallbacks);
        appKeyCodes = getResources().getIntArray(R.array.app_key_keycodes);
        appScanCodes = getResources().getIntArray(R.array.app_key_scancodes);
        inputKeyCodes = getResources().getIntArray(R.array.input_key_keycodes);
        settingsKeyCodes = getResources().getIntArray(R.array.settings_key_keycodes);
        settingsScanCodes = getResources().getIntArray(R.array.settings_key_scancodes);
        routeInputKeys = getResources().getInteger(R.integer.input_key_service) != 0;
        routeSettingsKeys = getResources().getInteger(R.integer.home_key_service) != 0;

        AccessibilityServiceInfo info = getServiceInfo();
        if (info != null) {
            info.flags |= AccessibilityServiceInfo.FLAG_REQUEST_FILTER_KEY_EVENTS;
            setServiceInfo(info);
        }
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        // Key filtering does not need window or content polling.
    }

    @Override
    public void onInterrupt() {
    }

    @Override
    protected boolean onKeyEvent(KeyEvent event) {
        String appPackage = findAppTarget(event);
        if (appPackage != null) {
            if (event.getAction() == KeyEvent.ACTION_DOWN && event.getRepeatCount() == 0) {
                launchPackage(appPackage, fallbackFor(appPackage));
            }
            return true;
        }

        int keyCode = event.getKeyCode();
        if (routeInputKeys && containsNonZero(inputKeyCodes, keyCode)) {
            if (event.getAction() == KeyEvent.ACTION_DOWN && event.getRepeatCount() == 0) {
                Intent intent = new Intent(this, MainActivity.class)
                        .setAction(ACTION_SHOW_INPUTS)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
                startActivity(intent);
            }
            return true;
        }

        if (routeSettingsKeys
                && (containsNonZero(settingsKeyCodes, keyCode)
                || containsNonZero(settingsScanCodes, event.getScanCode()))) {
            if (event.getAction() == KeyEvent.ACTION_DOWN && event.getRepeatCount() == 0) {
                startActivity(new Intent(android.provider.Settings.ACTION_SETTINGS)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            }
            return true;
        }

        if (event.getAction() == KeyEvent.ACTION_DOWN && event.getRepeatCount() == 0
                && !isNavigationOrVolumeKey(keyCode)) {
            Log.i(TAG, "Unmapped remote key: keyCode=" + keyCode
                    + " scanCode=" + event.getScanCode());
        }
        return false;
    }

    private String findAppTarget(KeyEvent event) {
        int scanCode = event.getScanCode();
        int keyCode = event.getKeyCode();

        for (int i = 0; i < appPackages.length; i++) {
            if (i < appScanCodes.length && scanCode != 0 && appScanCodes[i] == scanCode) {
                return appPackages[i];
            }
        }
        if (keyCode != KeyEvent.KEYCODE_UNKNOWN) {
            for (int i = 0; i < appPackages.length; i++) {
                if (i < appKeyCodes.length && appKeyCodes[i] != 0
                        && appKeyCodes[i] == keyCode) {
                    return appPackages[i];
                }
            }
        }
        return null;
    }

    private boolean containsNonZero(int[] values, int key) {
        if (key == 0) return false;
        for (int value : values) {
            if (value != 0 && value == key) return true;
        }
        return false;
    }

    private boolean isNavigationOrVolumeKey(int keyCode) {
        return keyCode == KeyEvent.KEYCODE_DPAD_UP
                || keyCode == KeyEvent.KEYCODE_DPAD_DOWN
                || keyCode == KeyEvent.KEYCODE_DPAD_LEFT
                || keyCode == KeyEvent.KEYCODE_DPAD_RIGHT
                || keyCode == KeyEvent.KEYCODE_DPAD_CENTER
                || keyCode == KeyEvent.KEYCODE_ENTER
                || keyCode == KeyEvent.KEYCODE_BACK
                || keyCode == KeyEvent.KEYCODE_HOME
                || keyCode == KeyEvent.KEYCODE_VOLUME_UP
                || keyCode == KeyEvent.KEYCODE_VOLUME_DOWN
                || keyCode == KeyEvent.KEYCODE_VOLUME_MUTE
                || keyCode == KeyEvent.KEYCODE_MUTE;
    }

    private String fallbackFor(String packageName) {
        for (int i = 0; i < appPackages.length; i++) {
            if (appPackages[i].equals(packageName) && i < appFallbacks.length
                    && !appFallbacks[i].isEmpty()) {
                return appFallbacks[i];
            }
        }
        return null;
    }

    private void launchPackage(String packageName, String fallbackPackage) {
        Intent launch = getPackageManager().getLeanbackLaunchIntentForPackage(packageName);
        if (launch == null) launch = getPackageManager().getLaunchIntentForPackage(packageName);
        if (launch == null && fallbackPackage != null) {
            launch = getPackageManager().getLeanbackLaunchIntentForPackage(fallbackPackage);
            if (launch == null) {
                launch = getPackageManager().getLaunchIntentForPackage(fallbackPackage);
            }
        }
        if (launch == null) {
            Log.w(TAG, "No launch activity for " + packageName
                    + (fallbackPackage == null ? "" : " or " + fallbackPackage));
            return;
        }
        launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        try {
            startActivity(launch);
        } catch (RuntimeException e) {
            Log.e(TAG, "Could not launch " + packageName, e);
        }
    }
}
