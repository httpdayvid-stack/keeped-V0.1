package com.keeped.app;

import android.os.Build;
import android.os.Bundle;
import android.webkit.WebSettings;
import com.getcapacitor.BridgeActivity;

public class MainActivity extends BridgeActivity {
    @Override
    public void onCreate(Bundle savedInstanceState) {
        registerPlugin(BlockerPlugin.class);
        super.onCreate(savedInstanceState);
        try {
            if (Build.VERSION.SDK_INT >= 29) {
                getBridge().getWebView().getSettings().setForceDark(WebSettings.FORCE_DARK_OFF);
            }
        } catch (Throwable e) { }
    }
}
