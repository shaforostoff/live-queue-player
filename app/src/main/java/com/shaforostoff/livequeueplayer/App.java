package com.shaforostoff.livequeueplayer;

import android.app.Application;

public class App extends Application {

    private MetadataExtractor metadataExtractor;

    @Override
    public void onCreate() {
        super.onCreate();
        metadataExtractor = new MetadataExtractor(getContentResolver());
    }

    public MetadataExtractor getMetadataExtractor() {
        return metadataExtractor;
    }
}
