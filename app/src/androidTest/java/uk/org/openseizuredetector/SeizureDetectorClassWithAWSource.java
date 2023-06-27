package uk.org.openseizuredetector;


import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

import android.app.Application;
import android.app.Service;
import android.companion.AssociationRequest;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.net.ConnectivityManager;
import android.net.NetworkCapabilities;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import androidx.preference.PreferenceManager;
import android.util.Log;

import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.*;
import org.junit.After;
import org.junit.Before;

import org.junit.runner.RunWith;
import org.junit.BeforeClass;
import org.junit.AfterClass;
import org.robolectric.Robolectric;
import org.robolectric.android.controller.ServiceController;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.Objects;
import java.util.Random;




public class SeizureDetectorClassWithAWSource {
    Context context;
    Application application;
    Looper looper;
    Handler handler;
    OsdUtil util;
    SdServer sdServer;
    Intent sdServerIntent;
    ServiceController<SdServer> controller;
    SdServiceConnection sdServiceConnection;

    @Before
    public void initOsdUtil(){
        application = ApplicationProvider.getApplicationContext();
        context = application.getApplicationContext();
        looper = context.getMainLooper();
        handler = new Handler(looper);
        util = new OsdUtil(context,handler);
        sdServerIntent = new Intent(context,SdServer.class)
                .setData(Constants.GLOBAL_CONSTANTS.mStartUri);
        sdServiceConnection = new SdServiceConnection(context);
    }

    @Test
    public void assertServerInitiatedWithAW(){
        Assertions.assertTrue(Objects.nonNull(sdServiceConnection));
        Assertions.assertFalse(util.isServerRunning());
        SharedPreferences SP = PreferenceManager
                .getDefaultSharedPreferences(context);
        SP.edit()
                .remove("DataSource")
                .putString("DataSource","AndroidWear")
                .apply();

        Assertions.assertEquals("AndroidWear", SP.getString("DataSource","Undefined"));
        util.startServer();

        Assertions.assertTrue(util.isServerRunning());


    }
    @After
    public void removeUtil(){
        if (Objects.nonNull(util))
            if (util.isServerRunning()) {
                if (Objects.nonNull(sdServiceConnection)) {
                    if (sdServiceConnection.mBound)
                        util.unbindFromServer(context, sdServiceConnection);
                }
                util.stopServer();
                Assertions.assertFalse(util.isServerRunning());
            }
        sdServiceConnection = null;
        util = null;
        handler = null;
        looper = null;
        context = null;
    }

}
