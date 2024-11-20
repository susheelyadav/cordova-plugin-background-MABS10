/*
 Copyright 2013 Sebastián Katzer

 Licensed to the Apache Software Foundation (ASF) under one
 or more contributor license agreements.  See the NOTICE file
 distributed with this work for additional information
 regarding copyright ownership.  The ASF licenses this file
 to you under the Apache License, Version 2.0 (the
 "License"); you may not use this file except in compliance
 with the License.  You may obtain a copy of the License at

 http://www.apache.org/licenses/LICENSE-2.0

 Unless required by applicable law or agreed to in writing,
 software distributed under the License is distributed on an
 "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 KIND, either express or implied.  See the License for the
 specific language governing permissions and limitations
 under the License.
 */

package de.appplant.cordova.plugin.background;

import android.app.Activity;
import android.content.ComponentName;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.IBinder;

import org.apache.cordova.CallbackContext;
import org.apache.cordova.CordovaPlugin;
import org.json.JSONArray;
import org.json.JSONObject;

import de.appplant.cordova.plugin.background.ForegroundService.ForegroundBinder;

import static android.content.Context.BIND_AUTO_CREATE;
import static de.appplant.cordova.plugin.background.BackgroundModeExt.clearKeyguardFlags;

public class BackgroundMode extends CordovaPlugin {

    // Event types for callbacks
    private enum Event { ACTIVATE, DEACTIVATE, FAILURE }

    // Plugin namespace
    private static final String JS_NAMESPACE = "cordova.plugins.backgroundMode";

    // Flag indicates if the app is in background or foreground
    private boolean inBackground = false;

    // Flag indicates if the plugin is enabled or disabled
    private boolean isDisabled = true;

    // Flag indicates if the service is bind
    private boolean isBind = false;

    // Default settings for the notification
    private static JSONObject defaultSettings = new JSONObject();

    // Service that keeps the app awake
    private ForegroundService service;

    // Used to (un)bind the service to with the activity
    private final ServiceConnection connection = new ServiceConnection()
    {
        @Override
        public void onServiceConnected (ComponentName name, IBinder service)
        {
            try {
                ForegroundBinder binder = (ForegroundBinder) service;
                BackgroundMode.this.service = binder.getService();
            } catch (Exception e) {
                fireEvent(Event.FAILURE, String.format("'onServiceConnected error: %s'", e.getMessage()));
            }
        }

        @Override
        public void onServiceDisconnected (ComponentName name)
        {
            fireEvent(Event.FAILURE, "'Service disconnected unexpectedly'");
        }
    };

    @Override
    public boolean execute(String action, JSONArray args, CallbackContext callback) {
        boolean validAction = true;

        try {
            switch (action) {
                case "configure":
                    configure(args.optJSONObject(0), args.optBoolean(1));
                    break;
                case "enable":
                    enableMode();
                    break;
                case "disable":
                    disableMode();
                    break;
                default:
                    validAction = false;
            }

            if (validAction) {
                callback.success();
            } else {
                callback.error("Invalid action: " + action);
            }
        } catch (Exception e) {
            callback.error("Error executing action: " + e.getMessage());
            fireEvent(Event.FAILURE, String.format("'execute error: %s'", e.getMessage()));
        }

        return validAction;
    }

    @Override
    public void onPause(boolean multitasking) {
        try {
            inBackground = true;
            startService();
        } catch (Exception e) {
            fireEvent(Event.FAILURE, String.format("'onPause error: %s'", e.getMessage()));
        } finally {
            clearKeyguardFlags(cordova.getActivity());
        }
    }

    @Override
    public void onStop() {
        try {
            clearKeyguardFlags(cordova.getActivity());
        } catch (Exception e) {
            fireEvent(Event.FAILURE, String.format("'onStop error: %s'", e.getMessage()));
        }
    }

    @Override
    public void onResume(boolean multitasking) {
        try {
            inBackground = false;
            stopService();
        } catch (Exception e) {
            fireEvent(Event.FAILURE, String.format("'onResume error: %s'", e.getMessage()));
        }
    }

    @Override
    public void onDestroy() {
        try {
            stopService();
        } catch (Exception e) {
            fireEvent(Event.FAILURE, String.format("'onDestroy error: %s'", e.getMessage()));
        } finally {
            android.os.Process.killProcess(android.os.Process.myPid());
        }
    }

    private void enableMode() {
        try {
            isDisabled = false;

            if (inBackground) {
                startService();
            }
        } catch (Exception e) {
            fireEvent(Event.FAILURE, String.format("'enableMode error: %s'", e.getMessage()));
        }
    }

    private void disableMode() {
        try {
            stopService();
            isDisabled = true;
        } catch (Exception e) {
            fireEvent(Event.FAILURE, String.format("'disableMode error: %s'", e.getMessage()));
        }
    }

    private void configure(JSONObject settings, boolean update) {
        try {
            if (update) {
                updateNotification(settings);
            } else {
                setDefaultSettings(settings);
            }
        } catch (Exception e) {
            fireEvent(Event.FAILURE, String.format("'configure error: %s'", e.getMessage()));
        }
    }

    private void setDefaultSettings(JSONObject settings) {
        try {
            defaultSettings = settings;
        } catch (Exception e) {
            fireEvent(Event.FAILURE, String.format("'setDefaultSettings error: %s'", e.getMessage()));
        }
    }

    private void updateNotification(JSONObject settings) {
        try {
            if (isBind) {
                service.updateNotification(settings);
            }
        } catch (Exception e) {
            fireEvent(Event.FAILURE, String.format("'updateNotification error: %s'", e.getMessage()));
        }
    }

    private void startService() {
        Activity context = cordova.getActivity();

        if (isDisabled || isBind)
            return;

        try {
            Intent intent = new Intent(context, ForegroundService.class);
            context.bindService(intent, connection, BIND_AUTO_CREATE);
            fireEvent(Event.ACTIVATE, null);
            context.startService(intent);
        } catch (Exception e) {
            fireEvent(Event.FAILURE, String.format("'startService error: %s'", e.getMessage()));
        }

        isBind = true;
    }

    private void stopService() {
        Activity context = cordova.getActivity();
        Intent intent = new Intent(context, ForegroundService.class);

        if (!isBind)
            return;

        try {
            fireEvent(Event.DEACTIVATE, null);
            context.unbindService(connection);
            context.stopService(intent);
        } catch (Exception e) {
            fireEvent(Event.FAILURE, String.format("'stopService error: %s'", e.getMessage()));
        } finally {
            isBind = false;
        }
    }

    private void fireEvent(Event event, String params) {
        try {
            String eventName = event.name().toLowerCase();
            Boolean active = event == Event.ACTIVATE;

            String str = String.format("%s._setActive(%b)", JS_NAMESPACE, active);
            str = String.format("%s;%s.on('%s', %s)", str, JS_NAMESPACE, eventName, params);
            str = String.format("%s;%s.fireEvent('%s',%s);", str, JS_NAMESPACE, eventName, params);

            final String js = str;

            cordova.getActivity().runOnUiThread(() -> webView.loadUrl("javascript:" + js));
        } catch (Exception e) {
            fireEvent(Event.FAILURE, String.format("'fireEvent error: %s'", e.getMessage()));
        }
    }
}

