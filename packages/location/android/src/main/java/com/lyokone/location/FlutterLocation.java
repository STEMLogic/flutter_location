package com.lyokone.location;

import java.util.HashMap;

import org.jetbrains.annotations.NotNull;

import com.google.android.gms.location.FusedLocationProviderClient;
import com.google.android.gms.location.LocationCallback;
import com.google.android.gms.location.LocationRequest;
import com.google.android.gms.location.LocationResult;
import com.google.android.gms.location.LocationServices;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.location.Location;
import android.location.LocationManager;
import android.location.OnNmeaMessageListener;
import android.os.Build;
import android.os.Bundle;
import android.os.Looper;
import android.util.SparseArray;

import androidx.annotation.Nullable;
import androidx.core.app.ActivityCompat;

import io.flutter.plugin.common.EventChannel.EventSink;
import io.flutter.plugin.common.PluginRegistry;
import io.flutter.plugin.common.MethodChannel.Result;

public class FlutterLocation implements PluginRegistry.RequestPermissionsResultListener {
    private static final String TAG = "FlutterLocation";

    private static final int REQUEST_PERMISSIONS_REQUEST_CODE = 34;

    private final Context context;
    protected FusedLocationProviderClient mFusedLocationClient;
    private LocationRequest mLocationRequest;
    public LocationCallback mLocationCallback;

    @androidx.annotation.RequiresApi(Build.VERSION_CODES.N)
    private OnNmeaMessageListener mMessageListener;

    private Double mLastMslAltitude;

    // Parameters of the request
    private long updateIntervalMilliseconds = 5000;
    private long fastestUpdateIntervalMilliseconds = updateIntervalMilliseconds / 2;
    private Integer locationAccuracy = LocationRequest.PRIORITY_HIGH_ACCURACY;
    private float distanceFilter = 0f;

    public EventSink events;

    // Store result until a permission check is resolved
    public Result result;

    // Store result until a location is getting resolved
    public Result getLocationResult;

    private final LocationManager locationManager;

    public SparseArray<Integer> mapFlutterAccuracy = new SparseArray<Integer>() {
        {
            put(0, LocationRequest.PRIORITY_NO_POWER);
            put(1, LocationRequest.PRIORITY_LOW_POWER);
            put(2, LocationRequest.PRIORITY_BALANCED_POWER_ACCURACY);
            put(3, LocationRequest.PRIORITY_HIGH_ACCURACY);
            put(4, LocationRequest.PRIORITY_HIGH_ACCURACY);
            put(5, LocationRequest.PRIORITY_LOW_POWER);
        }
    };

    FlutterLocation(Context context, @Nullable Object activity) {
        this.context = context;
        this.locationManager = (LocationManager) context.getSystemService(Context.LOCATION_SERVICE);
        initializeLocationServices();
    }

    private void initializeLocationServices() {
        mFusedLocationClient = LocationServices.getFusedLocationProviderClient(context);
        createLocationCallback();
        createLocationRequest();
    }

    @Override
    public boolean onRequestPermissionsResult(int requestCode, @NotNull String[] permissions,
                                              @NotNull int[] grantResults) {
        return onRequestPermissionsResultHandler(requestCode, permissions, grantResults);
    }

    public boolean onRequestPermissionsResultHandler(int requestCode, String[] permissions, int[] grantResults) {
        if (requestCode == REQUEST_PERMISSIONS_REQUEST_CODE && permissions.length == 1
                && permissions[0].equals(Manifest.permission.ACCESS_FINE_LOCATION)) {
            if (grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                // Checks if this permission was automatically triggered by a location request
                if (getLocationResult != null || events != null) {
                    startRequestingLocation();
                }
                if (result != null) {
                    result.success(1);
                    result = null;
                }
            } else {
                if (!shouldShowRequestPermissionRationale()) {
                    sendError("PERMISSION_DENIED_NEVER_ASK",
                            "Location permission denied forever - please open app settings", null);
                    if (result != null) {
                        result.success(2);
                        result = null;
                    }
                } else {
                    sendError("PERMISSION_DENIED", "Location permission denied", null);
                    if (result != null) {
                        result.success(0);
                        result = null;
                    }
                }
            }
            return true;
        }
        return false;
    }

    public void changeSettings(Integer newLocationAccuracy, Long updateIntervalMilliseconds,
                               Long fastestUpdateIntervalMilliseconds, Float distanceFilter) {
        this.locationAccuracy = newLocationAccuracy;
        this.updateIntervalMilliseconds = updateIntervalMilliseconds;
        this.fastestUpdateIntervalMilliseconds = fastestUpdateIntervalMilliseconds;
        this.distanceFilter = distanceFilter;

        createLocationCallback();
        createLocationRequest();
        startRequestingLocation();
    }

    private void sendError(String errorCode, String errorMessage, Object errorDetails) {
        if (getLocationResult != null) {
            getLocationResult.error(errorCode, errorMessage, errorDetails);
            getLocationResult = null;
        }
        if (events != null) {
            events.error(errorCode, errorMessage, errorDetails);
            events = null;
        }
    }

    private void createLocationCallback() {
        if (mLocationCallback != null) {
            mFusedLocationClient.removeLocationUpdates(mLocationCallback);
            mLocationCallback = null;
        }
        mLocationCallback = new LocationCallback() {
            @Override
            public void onLocationResult(LocationResult locationResult) {
                super.onLocationResult(locationResult);
                Location location = locationResult.getLastLocation();
                HashMap<String, Object> loc = new HashMap<>();
                loc.put("latitude", location.getLatitude());
                loc.put("longitude", location.getLongitude());
                loc.put("accuracy", (double) location.getAccuracy());
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    loc.put("verticalAccuracy", (double) location.getVerticalAccuracyMeters());
                    loc.put("headingAccuracy", (double) location.getBearingAccuracyDegrees());
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    loc.put("elapsedRealtimeUncertaintyNanos", location.getElapsedRealtimeUncertaintyNanos());
                }

                loc.put("provider", location.getProvider());
                final Bundle extras = location.getExtras();
                if (extras != null) {
                    loc.put("satelliteNumber", location.getExtras().getInt("satellites"));
                }

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR2) {
                    loc.put("elapsedRealtimeNanos", (double) location.getElapsedRealtimeNanos());

                    if (location.isFromMockProvider()) {
                        loc.put("isMock", (double) 1);
                    }
                } else {
                    loc.put("isMock", (double) 0);
                }

                // Using NMEA Data to get MSL level altitude
                if (mLastMslAltitude == null || Build.VERSION.SDK_INT < Build.VERSION_CODES.N) {
                    loc.put("altitude", location.getAltitude());
                } else {
                    loc.put("altitude", mLastMslAltitude);
                }

                loc.put("speed", (double) location.getSpeed());
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    loc.put("speed_accuracy", (double) location.getSpeedAccuracyMetersPerSecond());
                }
                loc.put("heading", (double) location.getBearing());
                loc.put("time", (double) location.getTime());

                if (getLocationResult != null) {
                    getLocationResult.success(loc);
                    getLocationResult = null;
                }
                if (events != null) {
                    events.success(loc);
                } else {
                    if (mFusedLocationClient != null) {
                        mFusedLocationClient.removeLocationUpdates(mLocationCallback);
                    }
                }
            }
        };

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            mMessageListener = (message, timestamp) -> {
                if (message.startsWith("$")) {
                    String[] tokens = message.split(",");
                    String type = tokens[0];

                    // Parse altitude above sea level, Detailed description of NMEA string here
                    // http://aprs.gids.nl/nmea/#gga
                    if (type.startsWith("$GPGGA") && tokens.length > 9) {
                        if (!tokens[9].isEmpty()) {
                            mLastMslAltitude = Double.parseDouble(tokens[9]);
                        }
                    }
                }
            };
        }
    }

    private void createLocationRequest() {
        mLocationRequest = new LocationRequest.Builder(this.updateIntervalMilliseconds)
                .setMinUpdateIntervalMillis(this.fastestUpdateIntervalMilliseconds)
                .setPriority(this.locationAccuracy)
                .setMinUpdateDistanceMeters(this.distanceFilter)
                .build();
    }

    public boolean checkPermissions() {
        int locationPermissionState = ActivityCompat.checkSelfPermission(context,
                Manifest.permission.ACCESS_FINE_LOCATION);
        return locationPermissionState == PackageManager.PERMISSION_GRANTED;
    }

    public void requestPermissions() {
        if (checkPermissions()) {
            result.success(1);
            return;
        }
        if (result != null) {
            result.error("PERMISSION_DENIED", "Location permission not granted", null);
            result = null;
        }
    }

    public boolean shouldShowRequestPermissionRationale() {
        // Since we're using Context and not Activity, we can't use
        // ActivityCompat.shouldShowRequestPermissionRationale
        // Instead, we'll check if the permission is already granted
        return !checkPermissions();
    }

    public boolean checkServiceEnabled() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            return locationManager.isLocationEnabled();
        }

        boolean gps_enabled = locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER);
        boolean network_enabled = locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER);

        return gps_enabled || network_enabled;
    }

    public void startRequestingLocation() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            if (ActivityCompat.checkSelfPermission(context,
                    Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
                return;
            }
            locationManager.addNmeaListener(mMessageListener, null);
        }

        if (mFusedLocationClient != null) {
            mFusedLocationClient
                    .requestLocationUpdates(mLocationRequest, mLocationCallback, Looper.myLooper());
        }
    }
}
