package com.rk.wacloning;

import android.os.Build;
import android.os.Bundle;
import android.webkit.WebView;

/** Second independent WhatsApp Web session. Android 9+ assigns this process its own WebView data directory. */
public class SecondProfileActivity extends MainActivity {
    private static boolean suffixConfigured = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P && !suffixConfigured) {
            try {
                WebView.setDataDirectorySuffix("rk_wa_profile_2");
                suffixConfigured = true;
            } catch (IllegalStateException ignored) {
                // This process should only initialize WebView here, before MainActivity creates it.
            }
        }
        super.onCreate(savedInstanceState);
    }
}
