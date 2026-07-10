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
                // Backward compatible: honour explicit lat/lng extras when present (e.g. GPX
                // playback start); otherwise seed from the current REAL location.
                val (lat, lng) = if (intent.hasExtra(EXTRA_LAT) && intent.hasExtra(EXTRA_LNG)) {
                    intent.getDoubleExtra(EXTRA_LAT, _state.value.latitude) to
                        intent.getDoubleExtra(EXTRA_LNG, _state.value.longitude)
                } else {
                    seedFromRealLocation()
                }
                // Explicit user Start of plain mock must win: discard any leftover/persisted
                // playback session so it cannot hijack the coordinate the user just entered.
                // GPX playback goes through EXTRA_PLAYBACK=true and keeps its live cursor.
                if (!intent.getBooleanExtra(EXTRA_PLAYBACK, false)) {
                    _state.update { it.copy(playback = Playback()) }
                }
                startInjecting(lat, lng)
            }
            ACTION_UPDATE -> {
                val lat = intent.getDoubleExtra(EXTRA_LAT, _state.value.latitude)
                val lng = intent.getDoubleExtra(EXTRA_LNG, _state.value.longitude)
                val cur = _state.value
                // Teleport is an explicit user action: it wins over any active playback.
                _state.update { it.copy(playback = it.playback.copy(active = false, paused = false)) }
                // Feature 4: ease to the target instead of a hard single-frame jump — but only
                // while the tick loop is running to animate it; otherwise just set the position.
                if (cur.isRunning) {
                    startGlide(cur.latitude, cur.longitude, lat, lng)
                } else {
                    _state.update { it.copy(latitude = lat, longitude = lng) }
                }
            }
            ACTION_RECENTER -> {
                // Re-seed the injected position to the device's current REAL location WITHOUT
                // stopping mocking. Best-effort: if no non-mock fix is available, leave as-is.
                val real = lastKnownReal()
                if (real != null) {
                    // A recenter is an explicit teleport: it wins over any active playback/glide.
                    _state.update {
                        it.copy(
                            latitude = real.latitude,
                            longitude = real.longitude,
                            glideActive = false,
                            playback = it.playback.copy(active = false, paused = false),
                        )
                    }
                    SessionStore.save(this, _state.value)
                }
            }
            ACTION_STOP -> {
                stopInjecting()
                return START_NOT_STICKY
            }
            else -> {
                // System-initiated restart after a process kill (null intent, START_STICKY):
                // ONLY here do we rehydrate the persisted session, and only when the in-memory
                // state is still pristine (genuinely fresh process) — never clobbering a live,
                // UI-driven session. This is the sole restore path so an explicit Start above
                // can never be hijacked by a stale persisted route.
                if (_state.value == MockState()) {
                    SessionStore.load(this)?.let { restored -> _state.update { restored } }
                }
                if (_state.value.isRunning) {
                    startInjecting(_state.value.latitude, _state.value.longitude)
                }
            }
        }
        return START_STICKY
    }

    /**
     * Seed a plain-mock Start from the current real position: the last known real fix, else the
     * last persisted session position, else a sane default. Requires location permission (the
     * UI checks it before calling Start).
     */
    private fun seedFromRealLocation(): Pair<Double, Double> {
        lastKnownReal()?.let { return it.latitude to it.longitude }
        SessionStore.load(this)?.let { return it.latitude to it.longitude }
        return MockState.DEFAULT_LAT to MockState.DEFAULT_LNG
    }

    private fun lastKnownReal(): Location? {
        val providers = buildList {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) add(LocationManager.FUSED_PROVIDER)
            add(LocationManager.NETWORK_PROVIDER)
            add(LocationManager.PASSIVE_PROVIDER)
            // GPS last: after a prior session its last fix may still be our own injection.
            add(LocationManager.GPS_PROVIDER)
        }
        return providers.firstNotNullOfOrNull { p ->
            try {
                locationManager.getLastKnownLocation(p)
            } catch (e: SecurityException) {
                null
            } catch (e: IllegalArgumentException) {
                null
            }
        }
    }

    private fun startInjecting(lat: Double, lng: Double) {
        startForegroundCompat()

        if (!registerProvider()) return  // error already surfaced to state

        _state.update {
            it.copy(isRunning = true, latitude = lat, longitude = lng, error = null)
        }

        tickJob?.cancel()
        tickJob = scope.launch {
            var lastTick = SystemClock.elapsedRealtime()
            var lastSave = 0L
            // Frame at FRAME_MS (finer than 1s) so glide teleports and movement inject smoothly;
            // motion math is dt-based so the trajectory is unchanged, just updated more often.
            while (isActive) {
                val now = SystemClock.elapsedRealtime()
                val dtSeconds = (now - lastTick) / 1000.0
                lastTick = now
                advancePosition(dtSeconds)
                pushCurrentLocation()
                if (now - lastSave >= SAVE_INTERVAL_MS) {
                    SessionStore.save(this@MockLocationService, _state.value)
                    lastSave = now
                }
                delay(FRAME_MS)
            }
        }
    }

    /** Feature 4: begin a smooth-glide teleport from current to target. */
    private fun startGlide(fromLat: Double, fromLng: Double, toLat: Double, toLng: Double) {
        val dist = PlaybackEngine.segMeters(GeoPt(fromLat, fromLng), GeoPt(toLat, toLng))
        val dur = (dist * GLIDE_MS_PER_M).toLong().coerceIn(GLIDE_MIN_MS, GLIDE_MAX_MS)
        _state.update {
            it.copy(
                glideActive = true,
                glideFromLat = fromLat, glideFromLng = fromLng,
                glideToLat = toLat, glideToLng = toLng,
                glideStartMs = SystemClock.elapsedRealtime(),
                glideDurationMs = dur,
            )
        }
    }

    /** Direction move engine step: shift the held position along the current heading. */
    private fun advancePosition(dtSeconds: Double) {
        val s = _state.value
        // Hub ▶/⏸ master gate: when paused, advance nothing. The tick still injects the held
        // position (pushCurrentLocation runs after this) so the provider stays alive frozen.
        if (s.movementPaused) return
        // Feature 4: an active glide teleport owns the position until it eases to the target.
        if (s.glideActive) {
            val now = SystemClock.elapsedRealtime()
            val dur = s.glideDurationMs.coerceAtLeast(1L)
            val t = ((now - s.glideStartMs).toDouble() / dur).coerceIn(0.0, 1.0)
            if (t >= 1.0) {
                _state.update {
                    it.copy(latitude = s.glideToLat, longitude = s.glideToLng, glideActive = false)
                }
            } else {
                val e = t * t * (3 - 2 * t)  // smoothstep ease-in-out
                _state.update {
                    it.copy(
                        latitude = s.glideFromLat + (s.glideToLat - s.glideFromLat) * e,
                        longitude = s.glideFromLng + (s.glideToLng - s.glideFromLng) * e,
                    )
                }
            }
            return
        }
        // GPX playback, when active, owns the position (different algorithm; only SpeedModel shared).
        if (s.playback.active && !s.playback.paused && s.playback.hasRoute) {
            val (pb, pos) = PlaybackEngine.step(s.playback, s.speedMps, dtSeconds)
            _state.update { it.copy(playback = pb, latitude = pos.lat, longitude = pos.lng) }
            return
        }
        val (lat, lng) = if (s.headingActive) {
            DirectionEngine.stepVector(
                s.latitude, s.longitude, s.headingNorth, s.headingEast, s.speedMps, dtSeconds
            )
        } else {
            if (s.direction == Direction.NONE) return
            DirectionEngine.step(
                s.latitude, s.longitude, s.direction, s.speedMps, dtSeconds
            )
        }
        _state.update { it.copy(latitude = lat, longitude = lng) }
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
            val playing = s.playback.active && !s.playback.paused && s.playback.hasRoute
            val moving = !s.movementPaused &&
                (playing || s.headingActive || s.direction != Direction.NONE)
            bearing = when {
                playing -> PlaybackEngine.currentBearing(s.playback)
                s.headingActive -> DirectionEngine.bearingOf(s.headingNorth, s.headingEast)
                else -> s.direction.bearingDegrees
            }
            speed = if (moving) s.speedMps.toFloat() else 0f
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
        // Ending the session must also end any playback, so a later Start is clean and the
        // persisted active-session flag (pb_active) does not resurrect a session the user ended.
        _state.update {
            it.copy(isRunning = false, playback = it.playback.copy(active = false, paused = false))
        }
        SessionStore.save(this, _state.value)
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
            .setContentTitle(getString(R.string.notif_mock_title))
            .setContentText(
                getString(
                    R.string.notif_mock_text,
                    "%.5f".format(s.latitude),
                    "%.5f".format(s.longitude),
                )
            )
            .setSmallIcon(R.drawable.ic_stat_location)
            .setColor(getColor(R.color.brand_primary))
            .setColorized(true)
            .setOngoing(true)
            .setContentIntent(openIntent)
            .addAction(R.drawable.ic_stat_location, getString(R.string.notif_action_stop), stopIntent)
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
        const val ACTION_RECENTER = "com.dopiz.gpsjoystick.action.RECENTER"
        const val EXTRA_LAT = "lat"
        const val EXTRA_LNG = "lng"
        // Distinguishes a GPX-playback start (keeps the live playback cursor) from a plain-mock
        // Start (which discards any playback so it injects exactly the given coordinate).
        const val EXTRA_PLAYBACK = "playback"

        private const val CHANNEL_ID = "mock_location"
        private const val NOTIFICATION_ID = 1001
        private const val FRAME_MS = 100L
        private const val SAVE_INTERVAL_MS = 1000L
        // Feature 4 glide pacing: ~4ms per meter, clamped to a short natural window.
        private const val GLIDE_MS_PER_M = 4.0
        private const val GLIDE_MIN_MS = 600L
        private const val GLIDE_MAX_MS = 1200L

        private val _state = MutableStateFlow(MockState())
        val state: StateFlow<MockState> = _state.asStateFlow()

        /** Plain-mock Start (user tapped Start). Discards any leftover playback session. */
        fun start(context: Context, lat: Double, lng: Double) {
            val intent = Intent(context, MockLocationService::class.java)
                .setAction(ACTION_START)
                .putExtra(EXTRA_LAT, lat)
                .putExtra(EXTRA_LNG, lng)
            context.startForegroundService(intent)
        }

        /**
         * Plain-mock Start seeded from the current REAL location (no coordinate extras). The
         * service resolves the seed from last-known real fix / persisted session / default.
         */
        fun startAtRealLocation(context: Context) {
            val intent = Intent(context, MockLocationService::class.java)
                .setAction(ACTION_START)
            context.startForegroundService(intent)
        }

        /** GPX-playback Start: keeps the live playback cursor already set via [play]. */
        fun startPlayback(context: Context, lat: Double, lng: Double) {
            val intent = Intent(context, MockLocationService::class.java)
                .setAction(ACTION_START)
                .putExtra(EXTRA_LAT, lat)
                .putExtra(EXTRA_LNG, lng)
                .putExtra(EXTRA_PLAYBACK, true)
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

        /**
         * Re-seed the running mock to the device's current REAL location without stopping the
         * mock. No-op if mocking is not running or no non-mock fix is available (best-effort).
         * The screens task wires this to a "回到真實定位" control; no UI is wired here.
         */
        fun recenterToReal(context: Context) {
            val intent = Intent(context, MockLocationService::class.java)
                .setAction(ACTION_RECENTER)
            context.startService(intent)
        }

        /**
         * Speed/direction mutate the shared in-memory state directly; the running tick loop
         * reads it live, so joystick input takes effect immediately without intent churn.
         * Harmless when not running (just updates the values used on next start).
         */
        fun setSpeed(mps: Double) {
            _state.update { it.copy(speedMps = SpeedModel.clamp(mps)) }
        }

        /**
         * Hub ▶/⏸ master freeze. When true the tick loop holds the current fix and advances
         * nothing (march / glide / GPX playback all frozen); false resumes. The running tick
         * reads this live, so it takes effect on the next frame.
         */
        fun setMovementPaused(paused: Boolean) {
            _state.update { it.copy(movementPaused = paused) }
        }

        fun setDirection(direction: Direction) {
            // Explicit cardinal input overrides any live joystick heading AND any active
            // playback — manual control always wins over a running route.
            _state.update {
                it.copy(
                    direction = direction,
                    headingActive = false,
                    playback = it.playback.copy(active = false, paused = false),
                )
            }
        }

        /** Deflection below this fraction of full travel is treated as centred → stop. */
        private const val JOYSTICK_DEAD_ZONE = 0.12

        /**
         * Joystick input: [north]/[east] is a unit heading vector (screen up = north),
         * [magnitude] is the stick deflection 0..1. The stick sets DIRECTION ONLY — movement
         * speed is ALWAYS the global shared [SpeedModel] value (走/跑/車/自訂), never scaled by
         * deflection. A small dead-zone near centre releases the stick (recenter → stop); any
         * deflection beyond it marches at exactly the current global speed.
         */
        fun setJoystick(north: Double, east: Double, magnitude: Double) {
            if (magnitude < JOYSTICK_DEAD_ZONE) {
                clearJoystick()
                return
            }
            _state.update {
                it.copy(
                    headingActive = true,
                    headingNorth = north,
                    headingEast = east,
                    direction = Direction.NONE,
                    // Driving the joystick is explicit manual control: it wins over playback.
                    playback = it.playback.copy(active = false, paused = false),
                )
            }
        }

        fun clearJoystick() {
            _state.update {
                it.copy(headingActive = false, headingNorth = 0.0, headingEast = 0.0)
            }
        }

        // --- GPX playback (Slice 9/10). Cursor lives in state; the tick loop reads it live. ---

        /** Load a route to play, resetting the cursor to its start. Does not start moving. */
        fun setPlaybackRoute(points: List<GeoPt>, mode: PlaybackMode) {
            _state.update {
                it.copy(
                    playback = it.playback.copy(
                        points = points,
                        mode = mode,
                        segmentIndex = 0,
                        segmentProgress = 0.0,
                        forward = true,
                    )
                )
            }
        }

        fun setPlaybackMode(mode: PlaybackMode) {
            _state.update { it.copy(playback = it.playback.copy(mode = mode)) }
        }

        /** Tap-a-route-point: move the playback start to [index] (clamped), forward, from its start. */
        fun setPlaybackStartIndex(index: Int) {
            _state.update {
                val last = (it.playback.points.size - 1).coerceAtLeast(0)
                it.copy(
                    playback = it.playback.copy(
                        segmentIndex = index.coerceIn(0, last),
                        segmentProgress = 0.0,
                        forward = true,
                    )
                )
            }
        }

        fun play() {
            _state.update { it.copy(playback = it.playback.copy(active = true, paused = false)) }
        }

        fun pausePlayback() {
            _state.update { it.copy(playback = it.playback.copy(paused = true)) }
        }

        fun resumePlayback() {
            _state.update { it.copy(playback = it.playback.copy(active = true, paused = false)) }
        }

        fun stopPlayback() {
            _state.update { it.copy(playback = it.playback.copy(active = false, paused = false)) }
        }

        /**
         * 停止/歸零: stop playback AND rewind the cursor to the route start (point 0, forward), so
         * the next 開始 begins from the GPX's first point rather than the paused position.
         */
        fun resetPlayback() {
            _state.update {
                it.copy(
                    playback = it.playback.copy(
                        active = false,
                        paused = false,
                        segmentIndex = 0,
                        segmentProgress = 0.0,
                        forward = true,
                    )
                )
            }
        }
    }
}
