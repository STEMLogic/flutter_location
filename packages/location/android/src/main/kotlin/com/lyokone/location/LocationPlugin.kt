package com.lyokone.location

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import android.util.Log
import io.flutter.embedding.engine.FlutterEngineCache
import io.flutter.embedding.engine.plugins.FlutterPlugin
import io.flutter.embedding.engine.plugins.activity.ActivityAware
import io.flutter.embedding.engine.plugins.activity.ActivityPluginBinding

/**
 * LocationPlugin
 *
 * STEMLogic fork notes:
 * - Only attach against the app's cached "SharedEngine" so Firebase's background
 *   FlutterEngine does not steal/tear down the location service binding.
 * - Bind the location service from the application context so location keeps
 *   working without an Activity (headless / vehicle platforms).
 * - ActivityAware is still used for permission/settings UI when an Activity is
 *   available, but activity detach must not unbind or dispose the service.
 */
class LocationPlugin : FlutterPlugin, ActivityAware {
    private var methodCallHandler: MethodCallHandlerImpl? = null
    private var streamHandlerImpl: StreamHandlerImpl? = null
    private var locationService: FlutterLocationService? = null
    private var activityBinding: ActivityPluginBinding? = null
    private var applicationContext: Context? = null
    private var serviceBound = false

    private val serviceConnection =
        object : ServiceConnection {
            override fun onServiceConnected(
                name: ComponentName?,
                service: IBinder?,
            ) {
                Log.d(TAG, "Service connected: $name")
                if (service is FlutterLocationService.LocalBinder) {
                    initialize(service.getService())
                }
            }

            override fun onServiceDisconnected(name: ComponentName?) {
                Log.d(TAG, "Service disconnected: $name")
            }
        }

    override fun onAttachedToEngine(binding: FlutterPlugin.FlutterPluginBinding) {
        // Only initialize if the shared engine is being used.
        val sharedEngine = FlutterEngineCache.getInstance().get(SHARED_ENGINE_ID)
        if (sharedEngine == null || sharedEngine != binding.flutterEngine) {
            Log.d(TAG, "Skipping attach: binding engine is not SharedEngine")
            return
        }

        if (applicationContext != null) {
            return
        }

        applicationContext = binding.applicationContext

        methodCallHandler =
            MethodCallHandlerImpl().apply {
                startListening(binding.binaryMessenger)
            }
        streamHandlerImpl =
            StreamHandlerImpl().apply {
                startListening(binding.binaryMessenger)
            }

        val context = applicationContext ?: return
        context.bindService(
            Intent(context, FlutterLocationService::class.java),
            serviceConnection,
            Context.BIND_AUTO_CREATE,
        )
        serviceBound = true
    }

    override fun onDetachedFromEngine(binding: FlutterPlugin.FlutterPluginBinding) {
        // Ignore detach from non-shared engines (e.g. Firebase background engine).
        val sharedEngine = FlutterEngineCache.getInstance().get(SHARED_ENGINE_ID)
        if (sharedEngine != null && sharedEngine != binding.flutterEngine) {
            return
        }

        methodCallHandler?.apply {
            setLocationService(null)
            setLocation(null)
            stopListening()
        }
        methodCallHandler = null

        streamHandlerImpl?.apply {
            setLocation(null)
            stopListening()
        }
        streamHandlerImpl = null

        if (serviceBound) {
            applicationContext?.unbindService(serviceConnection)
            serviceBound = false
        }
        locationService = null
        applicationContext = null
    }

    private fun attachToActivity(binding: ActivityPluginBinding) {
        activityBinding = binding
        locationService?.let { service ->
            service.setActivity(binding.activity)
            service.locationActivityResultListener?.let(binding::addActivityResultListener)
            service.locationRequestPermissionsResultListener?.let(binding::addRequestPermissionsResultListener)
            binding.addRequestPermissionsResultListener(service.serviceRequestPermissionsResultListener)
        }
    }

    private fun detachActivity() {
        val service = locationService
        activityBinding?.let { binding ->
            if (service != null) {
                binding.removeRequestPermissionsResultListener(service.serviceRequestPermissionsResultListener)
                service.locationRequestPermissionsResultListener?.let(binding::removeRequestPermissionsResultListener)
                service.locationActivityResultListener?.let(binding::removeActivityResultListener)
            }
        }
        // Keep the service and fused client alive without an Activity.
        locationService?.setActivity(null)
        activityBinding = null
    }

    override fun onAttachedToActivity(binding: ActivityPluginBinding) {
        attachToActivity(binding)
    }

    override fun onDetachedFromActivity() {
        detachActivity()
    }

    override fun onDetachedFromActivityForConfigChanges() {
        detachActivity()
    }

    override fun onReattachedToActivityForConfigChanges(binding: ActivityPluginBinding) {
        attachToActivity(binding)
    }

    private fun initialize(service: FlutterLocationService) {
        if (locationService != null) {
            return
        }
        locationService = service

        activityBinding?.let { binding ->
            service.setActivity(binding.activity)
            service.locationActivityResultListener?.let(binding::addActivityResultListener)
            service.locationRequestPermissionsResultListener?.let(binding::addRequestPermissionsResultListener)
            binding.addRequestPermissionsResultListener(service.serviceRequestPermissionsResultListener)
        }

        methodCallHandler?.setLocation(service.location)
        methodCallHandler?.setLocationService(service)
        streamHandlerImpl?.setLocation(service.location)
    }

    companion object {
        private const val TAG = "LocationPlugin"
        private const val SHARED_ENGINE_ID = "SharedEngine"
    }
}
