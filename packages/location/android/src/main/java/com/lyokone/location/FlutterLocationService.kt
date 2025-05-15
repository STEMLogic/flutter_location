package com.lyokone.location

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import io.flutter.plugin.common.MethodChannel
import io.flutter.plugin.common.PluginRegistry

const val kDefaultChannelName: String = "Location background service"
const val kDefaultNotificationTitle: String = "Location background service running"
const val kDefaultNotificationIconName: String = "navigation_empty_icon"

data class NotificationOptions(
    val channelName: String = kDefaultChannelName,
    val title: String = kDefaultNotificationTitle,
    val iconName: String = kDefaultNotificationIconName,
    val subtitle: String? = null,
    val description: String? = null,
    val color: Int? = null,
    val onTapBringToFront: Boolean = false,
)

class BackgroundNotification(
    private val context: Context,
    private val channelId: String,
    private val notificationId: Int,
) {
    private var options: NotificationOptions = NotificationOptions()
    private var builder: NotificationCompat.Builder =
        NotificationCompat.Builder(context, channelId)
            .setPriority(NotificationCompat.PRIORITY_HIGH)

    init {
        updateNotification(options, false)
    }

    private fun getDrawableId(iconName: String): Int {
        return context.resources.getIdentifier(iconName, "drawable", context.packageName)
    }

    private fun buildBringToFrontIntent(): PendingIntent? {
        val intent: Intent? =
            context.packageManager
                .getLaunchIntentForPackage(context.packageName)
                ?.setPackage(null)
                ?.setFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK or
                            Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED
                )

        return if (intent != null) {
            PendingIntent.getActivity(context, 0, intent, PendingIntent.FLAG_IMMUTABLE)
        } else {
            null
        }
    }

    private fun updateChannel(channelName: String) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val notificationManager = NotificationManagerCompat.from(context)
            val channel =
                NotificationChannel(channelId, channelName, NotificationManager.IMPORTANCE_NONE)
                    .apply { lockscreenVisibility = Notification.VISIBILITY_PRIVATE }
            notificationManager.createNotificationChannel(channel)
        }
    }

    private fun updateNotification(options: NotificationOptions, notify: Boolean) {
        val iconId =
            getDrawableId(options.iconName).let {
                if (it != 0) it else getDrawableId(kDefaultNotificationIconName)
            }
        builder =
            builder.setContentTitle(options.title)
                .setSmallIcon(iconId)
                .setContentText(options.subtitle)
                .setSubText(options.description)

        builder =
            if (options.color != null) {
                builder.setColor(options.color).setColorized(true)
            } else {
                builder.setColor(0).setColorized(false)
            }

        builder =
            if (options.onTapBringToFront) {
                builder.setContentIntent(buildBringToFrontIntent())
            } else {
                builder.setContentIntent(null)
            }

        if (notify) {
            val notificationManager = NotificationManagerCompat.from(context)
            if (ActivityCompat.checkSelfPermission(
                    context,
                    Manifest.permission.POST_NOTIFICATIONS
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                return
            }
            notificationManager.notify(notificationId, builder.build())
        }
    }

    fun updateOptions(options: NotificationOptions, isVisible: Boolean) {
        if (options.channelName != this.options.channelName) {
            updateChannel(options.channelName)
        }

        updateNotification(options, isVisible)

        this.options = options
    }

    fun build(): Notification {
        updateChannel(options.channelName)
        return builder.build()
    }
}

class FlutterLocationService : Service(), PluginRegistry.RequestPermissionsResultListener {
    companion object {
        private const val TAG = "FlutterLocationService"
        private const val REQUEST_PERMISSIONS_REQUEST_CODE: Int = 641
        private const val ONGOING_NOTIFICATION_ID = 75418
        private const val CHANNEL_ID = "flutter_location_channel_01"
    }

    private val binder = LocalBinder()
    private var isForeground = false
    private var context: Context? = null
    private var backgroundNotification: BackgroundNotification? = null
    var location: FlutterLocation? = null
        private set
    var result: MethodChannel.Result? = null

    inner class LocalBinder : Binder() {
        fun getService(): FlutterLocationService = this@FlutterLocationService
    }

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "Creating service.")

        location = FlutterLocation(applicationContext, null)
        backgroundNotification =
            BackgroundNotification(applicationContext, CHANNEL_ID, ONGOING_NOTIFICATION_ID)
    }

    override fun onBind(intent: Intent?): IBinder {
        Log.d(TAG, "Binding to location service.")
        return binder
    }

    override fun onUnbind(intent: Intent?): Boolean {
        Log.d(TAG, "Unbinding from location service.")
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        Log.d(TAG, "Destroying service.")
        location = null
        backgroundNotification = null
        super.onDestroy()
    }

    fun checkBackgroundPermissions(): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val locationPermissionState =
                ActivityCompat.checkSelfPermission(
                    context!!,
                    Manifest.permission.ACCESS_BACKGROUND_LOCATION
                )
            return locationPermissionState == PackageManager.PERMISSION_GRANTED
        } else {
            return location?.checkPermissions() ?: false
        }
    }

    fun requestBackgroundPermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            if (!checkBackgroundPermissions()) {
                result?.error(
                    "PERMISSION_DENIED",
                    "Background location permission not granted",
                    null
                )
                result = null
            }
        } else {
            location?.let {
                it.result = this.result
                it.requestPermissions()
                this.result = null
            }
        }
    }

    fun shouldShowRequestBackgroundPermissionRationale(): Boolean {
        // Since we're using Context and not Activity, we can't use
        // ActivityCompat.shouldShowRequestPermissionRationale
        // Instead, we'll check if the background permission is already granted
        return !checkBackgroundPermissions()
    }

    fun isInForegroundMode(): Boolean {
        return isForeground
    }

    fun enableBackgroundMode() {
        if (isForeground) {
            Log.d(TAG, "Service already in foreground mode.")
        } else {
            Log.d(TAG, "Start service in foreground mode.")
            backgroundNotification?.let {
                val notification = it.build()
                startForeground(ONGOING_NOTIFICATION_ID, notification)
                isForeground = true
            }
        }
    }

    fun disableBackgroundMode() {
        Log.d(TAG, "Stop service in foreground.")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            stopForeground(true)
        }
        isForeground = false
    }

    fun changeNotificationOptions(options: NotificationOptions): Map<String, Any>? {
        backgroundNotification?.updateOptions(options, isForeground)
        return if (isForeground) {
            mapOf("channelId" to CHANNEL_ID, "notificationId" to ONGOING_NOTIFICATION_ID)
        } else {
            null
        }
    }

    fun setContext(context: Context?) {
        this.context = context
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<String>,
        grantResults: IntArray,
    ): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q &&
            requestCode == REQUEST_PERMISSIONS_REQUEST_CODE &&
            permissions.size == 2 &&
            permissions[0] == Manifest.permission.ACCESS_FINE_LOCATION &&
            permissions[1] == Manifest.permission.ACCESS_BACKGROUND_LOCATION
        ) {
            val granted =
                grantResults[0] == PackageManager.PERMISSION_GRANTED &&
                        grantResults[1] == PackageManager.PERMISSION_GRANTED

            if (granted) {
                enableBackgroundMode()
                result?.success(1)
                result = null
            } else {
                result?.error("PERMISSION_DENIED", "Background location permission denied", null)
                result = null
            }
        }
        return false
    }
}
