package com.novoda.downloadmanager.demo.simple;

import android.app.Application;

import com.facebook.stetho.Stetho;

public class DemoApplication extends Application {

    @Override
    public void onCreate() {
        super.onCreate();
        Stetho.initializeWithDefaults(this);
    }
}
