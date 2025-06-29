package com.lyokone.location;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.IBinder;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import io.flutter.embedding.engine.FlutterEngine;
import io.flutter.embedding.engine.FlutterEngineCache;
import io.flutter.embedding.engine.plugins.FlutterPlugin;

/**
 * LocationPlugin
 */
public class LocationPlugin implements FlutterPlugin {
    private static final String TAG = "LocationPlugin";
    @Nullable
    private MethodCallHandlerImpl methodCallHandler;
    @Nullable
    private StreamHandlerImpl streamHandlerImpl;
    @Nullable
    private FlutterLocationService locationService;
    @Nullable
    private Context context;

    @Override
    public void onAttachedToEngine(@NonNull FlutterPluginBinding binding) {
        // Only initialize if the shared engine is being used
        FlutterEngine flutterEngine = FlutterEngineCache.getInstance().get("SharedEngine");
        if (flutterEngine != null && flutterEngine == binding.getFlutterEngine()) {
            if (context == null) {
                context = binding.getApplicationContext();

                if (methodCallHandler == null) {
                    methodCallHandler = new MethodCallHandlerImpl();
                    methodCallHandler.setContext(context);
                    methodCallHandler.startListening(binding.getBinaryMessenger());
                }

                if (streamHandlerImpl == null) {
                    streamHandlerImpl = new StreamHandlerImpl();
                    streamHandlerImpl.startListening(binding.getBinaryMessenger());
                }

                // Bind to the location service
                context.bindService(new Intent(context, FlutterLocationService.class), serviceConnection, Context.BIND_AUTO_CREATE);
            }
        }
    }

    @Override
    public void onDetachedFromEngine(@NonNull FlutterPluginBinding binding) {
        if (methodCallHandler != null) {
            methodCallHandler.setLocationService(null);
            methodCallHandler.setLocation(null);
            methodCallHandler.stopListening();
            methodCallHandler = null;
        }

        if (streamHandlerImpl != null) {
            streamHandlerImpl.setLocation(null);
            streamHandlerImpl.stopListening();
            streamHandlerImpl = null;
        }

        if (context != null && locationService != null) {
            context.unbindService(serviceConnection);
            locationService.setContext(null);
            locationService = null;
            context = null;
        }
    }

    private final ServiceConnection serviceConnection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder service) {
            Log.d(TAG, "Service connected: " + name);
            if (service instanceof FlutterLocationService.LocalBinder) {
                initialize(((FlutterLocationService.LocalBinder) service).getService());
            }
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            Log.d(TAG, "Service disconnected:" + name);
        }
    };

    private void initialize(FlutterLocationService service) {
        if (locationService != null) return;
        locationService = service;
        locationService.setContext(context);

        if (methodCallHandler != null) {
            methodCallHandler.setLocation(locationService.getLocation());
            methodCallHandler.setLocationService(locationService);
        }

        if (streamHandlerImpl != null) {
            streamHandlerImpl.setLocation(locationService.getLocation());
        }
    }

}
