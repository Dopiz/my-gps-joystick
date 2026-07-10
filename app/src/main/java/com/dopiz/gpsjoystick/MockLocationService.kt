package com.dopiz.gpsjoystick

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.location.Criteria
import android.location.Location
import android.location.LocationManager
import android.os.Build
import android.os.IBinder
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Foreground service that injects mock GPS locations through the platform test-provider API.
 *
 * Lifecycle contract:
 *  - START_STICKY so the system restarts it after a process kill; the persistent
 *    notification lets the user restart/stop it manually too.
 *  - Holds the running session as in-memory [MockState] exposed via [state] (companion
 *    StateFlow) so the UI observes without any binding/DI.
 */
class MockLocationService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var tickJob: Job? = null
    private val locationManager by lazy {
        getSystemService(Context.LOCATION_SERVICE) as LocationManager
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                val lat = intent.getDoubleExtra(EXTRA_LAT, _state.value.latitude)
                val lng = intent.getDoubleExtra(EXTRA_LNG, _state.value.longitude)
                startInjecting(lat, lng)
            }
            ACTION_UPDATE -> {
                val lat = intent.getDoubleExtra(EXTRA_LAT, _state.value.latitude)
                val lng = intent.getDoubleExtra(EXTRA_LNG, _state.value.longitude)
                _state.update { it.copy(latitude = lat, longitude = lng) }
            }
            ACTION_STOP -> {
                stopInjecting()
                return START_NOT_STICKY
            }
            else -> {
                // Restarted by the system after a kill (null intent): resume last coords.
                if (_state.value.isRunning) {
                    startInjecting(_state.value.latitude, _state.value.longitude)
                }
            }
        }
        return START_STICKY
    }

    private fun startInjecting(lat: Double, lng: Double) {
        startForegroundCompat()

        if (!registerProvider()) return  // error already surfaced to state

        _state.update {
            it.copy(isRunning = true, latitude = lat, longitude = lng, error = null)
        }

        tickJob?.cancel()
        tickJob = scope.launch {
            while (isActive) {
                pushCurrentLocation()
                delay(TICK_MS)
            }
        }
    }

    /**
     * Registers the GPS test provider. Returns false (and populates [MockState.error])
     * when the app is not selected as the mock-location app — the SecurityException case
     * we must surface clearly rather than swallow.
     */
    private fun registerProvider(): Boolean {
        return try {
            runCatching { locationManager.removeTestProvider(LocationManager.GPS_PROVIDER) }
            @Suppress("DEPRECATION")
            locationManager.addTestProvider(
                LocationManager.GPS_PROVIDER,
                /* requiresNetwork = */ false,
                /* requiresSatellite = */ false,
                /* requiresCell = */ false,
                /* hasMonetaryCost = */ false,
                /* supportsAltitude = */ true,
                /* supportsSpeed = */ true,
                /* supportsBearing = */ true,
                Criteria.POWER_LOW,
                Criteria.ACCURACY_FINE,
            )
            locationManager.setTestProviderEnabled(LocationManager.GPS_PROVIDER, true)
            true
        } catch (e: SecurityException) {
            _state.update {
                it.copy(
                    isRunning = false,
                    error = "此 app 尚未被設為模擬位置 app。請到「開發者選項 → 選擇模擬位置應用程式」選擇本 app。",
                )
            }
            stopSelfCleanup()
            false
        } catch (e: IllegalArgumentException) {
            _state.update {
                it.copy(isRunning = false, error = "無法註冊測試 provider：${e.message}")
            }
            stopSelfCleanup()
            false
        }
    }

    private fun pushCurrentLocation() {
        val s = _state.value
        val loc = Location(LocationManager.GPS_PROVIDER).apply {
            latitude = s.latitude
            longitude = s.longitude
            accuracy = 1f
            altitude = 0.0
            bearing = 0f
            speed = 0f
            time = System.currentTimeMillis()
            elapsedRealtimeNanos = SystemClock.elapsedRealtimeNanos()
            // Fields Google Maps requires on API 26+ before it will accept the fix.
            bearingAccuracyDegrees = 0.1f
            speedAccuracyMetersPerSecond = 0.1f
            verticalAccuracyMeters = 0.1f
        }
        try {
            locationManager.setTestProviderLocation(LocationManager.GPS_PROVIDER, loc)
        } catch (e: SecurityException) {
            _state.update {
                it.copy(
                    isRunning = false,
                    error = "模擬位置權限遺失，請重新於開發者選項選擇本 app 為模擬位置 app。",
                )
            }
            stopInjecting()
        }
    }

    private fun stopInjecting() {
        tickJob?.cancel()
        tickJob = null
        runCatching {
            locationManager.setTestProviderEnabled(LocationManager.GPS_PROVIDER, false)
            locationManager.removeTestProvider(LocationManager.GPS_PROVIDER)
        }
        _state.update { it.copy(isRunning = false) }
        stopSelfCleanup()
    }

    private fun stopSelfCleanup() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }
        stopSelf()
    }

    private fun startForegroundCompat() {
        ensureChannel()
        val notification = buildNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION,
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun ensureChannel() {
        val nm = getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CHANNEL_ID) == null) {
            nm.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    "Mock location",
                    NotificationManager.IMPORTANCE_LOW,
                ).apply { description = "Persistent notification for the running GPS mock" }
            )
        }
    }

    private fun buildNotification(): Notification {
        val openIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val stopIntent = PendingIntent.getService(
            this, 1,
            Intent(this, MockLocationService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val s = _state.value
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("GPS Joystick 執行中")
            .setContentText("模擬座標 ${"%.5f".format(s.latitude)}, ${"%.5f".format(s.longitude)}")
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setOngoing(true)
            .setContentIntent(openIntent)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "停止", stopIntent)
            .build()
    }

    override fun onDestroy() {
        tickJob?.cancel()
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        const val ACTION_START = "com.dopiz.gpsjoystick.action.START"
        const val ACTION_STOP = "com.dopiz.gpsjoystick.action.STOP"
        const val ACTION_UPDATE = "com.dopiz.gpsjoystick.action.UPDATE"
        const val EXTRA_LAT = "lat"
        const val EXTRA_LNG = "lng"

        private const val CHANNEL_ID = "mock_location"
        private const val NOTIFICATION_ID = 1001
        private const val TICK_MS = 1000L

        private val _state = MutableStateFlow(MockState())
        val state: StateFlow<MockState> = _state.asStateFlow()

        fun start(context: Context, lat: Double, lng: Double) {
            val intent = Intent(context, MockLocationService::class.java)
                .setAction(ACTION_START)
                .putExtra(EXTRA_LAT, lat)
                .putExtra(EXTRA_LNG, lng)
            context.startForegroundService(intent)
        }

        fun update(context: Context, lat: Double, lng: Double) {
            val intent = Intent(context, MockLocationService::class.java)
                .setAction(ACTION_UPDATE)
                .putExtra(EXTRA_LAT, lat)
                .putExtra(EXTRA_LNG, lng)
            context.startService(intent)
        }

        fun stop(context: Context) {
            val intent = Intent(context, MockLocationService::class.java)
                .setAction(ACTION_STOP)
            context.startService(intent)
        }
    }
}
