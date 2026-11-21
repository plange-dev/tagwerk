package de.plangedev.tagwerk;

import android.os.Bundle;

import com.getcapacitor.BridgeActivity;

public class MainActivity extends BridgeActivity {
    @Override
    public void onCreate(Bundle savedInstanceState) {
        // Eigenes Plugin für CalDAV (Nextcloud & Co.)
        registerPlugin(DavHttpPlugin.class);
        super.onCreate(savedInstanceState);
    }
}
