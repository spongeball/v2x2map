package org.opentrafficmap.receiver

import android.app.Application
import android.location.Location
import android.os.Handler
import android.os.Looper
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.LinkedList
import java.util.Locale

/** One "packet with position" collected during a Range ("Max Range Mode") measurement. */
data class RangeEntry(val type: ItsG5Decoder.MsgType, val distanceMeters: Float, val timestampMs: Long)

/** A live station tracked for the Radar tab — distance/bearing from our current position. */
data class RadarContact(
    val type: ItsG5Decoder.MsgType,
    val distanceMeters: Float,
    val bearingDeg: Float,
    val headingDeg: Double?,
)

/**
 * The nearest in-range traffic light and a live green-wave speed suggestion.
 * [suggestedSpeedKph] is null when the phase is YELLOW/UNKNOWN (too
 * short/uncertain to advise driving through) or the SPATEM carried no
 * timing field at all — [remainingSeconds] already reflects that.
 */
data class AssistantInfo(
    val phase: SpatTemParser.Phase,
    val distanceMeters: Float,
    val remainingSeconds: Double?,
    val suggestedSpeedKph: Double?,
)

/**
 * State shared across the bottom-nav tabs (Map / Radar / Statistics / Range):
 * USB/BT connections, the parsed frame stream, MQTT, recording, and the
 * location subscription. UI-only state (map overlays, own-track polyline,
 * list adapters) stays in the owning Fragment and observes this instead.
 *
 * Survives configuration changes by construction (ViewModel); torn down in
 * [onCleared], which only fires when [MainActivity] is actually finishing —
 * same lifecycle the old single-Activity version relied on via
 * android:configChanges.
 */
class MainViewModel(app: Application) : AndroidViewModel(app) {

    private val mainHandler = Handler(Looper.getMainLooper())
    private val reader = FrameReader()

    // ── USB ──────────────────────────────────────────────────────────────────

    var usb: UsbSerialController? = null
        private set
    val usbState: MutableLiveData<Pair<UsbSerialController.State, String?>> =
        MutableLiveData(UsbSerialController.State.IDLE to null)

    fun startUsb() {
        if (usb != null) return
        usb = UsbSerialController(getApplication(), ::onSerialBytes, ::onUsbState).also { it.start() }
    }

    fun stopUsb() {
        usb?.stop(); usb = null
        usbState.value = UsbSerialController.State.IDLE to null
    }

    private fun onUsbState(state: UsbSerialController.State, info: String?) {
        usbState.postValue(state to info)
        onTransportStateChanged("USB", state.name)
    }

    // ── Bluetooth ────────────────────────────────────────────────────────────

    var bt: BluetoothController? = null
        private set
    val btState: MutableLiveData<Pair<BluetoothController.State, String?>> =
        MutableLiveData(BluetoothController.State.IDLE to null)
    var lastCycle: BluetoothController.CycleConfig? = null
        private set

    fun startBt() {
        if (bt != null) return
        bt = BluetoothController(
            context  = getApplication(),
            onBytes  = ::onSerialBytes,
            onState  = ::onBtState,
            onConfig = { lastCycle = it; SettingsBus.liveCycleConfig = it },
        ).also { it.start() }
        SettingsBus.liveBtController = bt
    }

    fun stopBt() {
        bt?.stop(); bt = null
        SettingsBus.liveBtController = null
        btState.value = BluetoothController.State.IDLE to null
    }

    private fun onBtState(state: BluetoothController.State, info: String?) {
        btState.postValue(state to info)
        onTransportStateChanged("BLE", state.name)
    }

    /** Debug-logs connection transitions and, when enabled, auto-starts recording on first connect. */
    private fun onTransportStateChanged(transport: String, stateName: String) {
        recorder.logEvent("$transport: $stateName")
        if (stateName == "CONNECTED" && !recorder.isRecording && Prefs.autoRecording(getApplication())) {
            toggleRecording()
        }
    }

    fun applyCycle(c: BluetoothController.CycleConfig): Boolean {
        lastCycle = c
        SettingsBus.liveCycleConfig = c
        return bt?.writeConfig(c) ?: false
    }

    // ── MQTT ─────────────────────────────────────────────────────────────────

    private val mqttBridges = mutableListOf<MqttBridge>()
    /** True while at least one configured broker is connected. */
    val mqttConnected = MutableLiveData(false)

    fun startMqtt() {
        stopMqtt()
        val app = getApplication<Application>()
        val nodeId = Prefs.nodeId(app).trim().ifEmpty { app.getString(R.string.default_node_id) }
        Prefs.mqttBrokerList(app).filter { it.isNotBlank() }.forEach { url ->
            MqttBridge(nodeId, normaliseBroker(url.trim()), onConnectionChange = { mainHandler.post(::refreshMqttConnected) }).also {
                it.start()
                mqttBridges.add(it)
            }
        }
    }

    fun stopMqtt() {
        mqttBridges.forEach { it.stop() }
        mqttBridges.clear()
        mqttConnected.value = false
    }

    private fun refreshMqttConnected() {
        mqttConnected.value = mqttBridges.any { it.isConnected() }
    }

    fun setMqttEnabled(on: Boolean) {
        stopMqtt(); if (on) startMqtt()
    }

    private fun normaliseBroker(s: String): String = when {
        s.startsWith("mqtts://") -> "ssl://" + s.removePrefix("mqtts://")
        s.startsWith("mqtt://")  -> "tcp://" + s.removePrefix("mqtt://")
        s.startsWith("ssl://")   -> s
        s.startsWith("tcp://")   -> s
        else                      -> "ssl://$s"
    }

    // ── Recording / audio ────────────────────────────────────────────────────

    val recorder = FrameRecorder(app)
    private val geiger = GeigerCounter(app)

    fun setAudioFeedback(on: Boolean) {
        if (on) geiger.start() else geiger.stop()
    }

    fun toggleRecording(): String {
        val app = getApplication<Application>()
        return if (recorder.isRecording) {
            val stopped = recorder.stop()
            app.getString(R.string.rec_stopped, recorder.frameCount, stopped?.absolutePath ?: "?")
        } else {
            val f = recorder.start()
            if (f != null) app.getString(R.string.rec_started, f.absolutePath) else "recording start failed"
        }
    }

    /** One-off KML export of currently-known station positions, independent of active recording. */
    fun exportKml(): File? = FrameRecorder.exportKml(getApplication(), knownStationsAsKml())

    private fun knownStationsAsKml(): List<KmlPoint> =
        radarStations.entries.map { (sid, s) -> KmlPoint("${s.type.name} #%08x".format(sid.toInt()), s.lat, s.lon) }

    /** Refreshes the active recording's map snapshot. No-op if not recording. */
    private val kmlRefresh = object : Runnable {
        override fun run() {
            if (recorder.isRecording) recorder.writeKmlSnapshot(knownStationsAsKml())
            mainHandler.postDelayed(this, KML_SNAPSHOT_MS)
        }
    }

    // ── Frame stream ─────────────────────────────────────────────────────────

    /** Batches of newly-parsed frames, drained every [LOG_REFRESH_MS]. Fragments append these to their own UI. */
    val newFrames = MutableLiveData<List<Frame>>()
    val logStats = MutableLiveData(0 to 0) // (totalFrames, ratePerMin)

    var followEnabled: Boolean = Prefs.followEnabled(app)
        private set
    var ownTrackEnabled: Boolean = Prefs.ownTrackEnabled(app)
        private set
    var spatLightEnabled: Boolean = Prefs.spatLightEnabled(app)
        private set
    /** Batches map/log updates less often at very high packet rates. See [logRefresh]. */
    var highRateMode: Boolean = Prefs.highRateMode(app)
        private set

    fun setFollowEnabled(on: Boolean) { followEnabled = on }
    fun setOwnTrackEnabled(on: Boolean) { ownTrackEnabled = on }
    fun setHighRateMode(on: Boolean) { highRateMode = on }
    fun setSpatLightEnabled(on: Boolean) {
        spatLightEnabled = on
        if (!on) assistantInfo.value = null else lastLocation.value?.let { recomputeAssistant(it) }
    }

    private val pendingFrames = mutableListOf<Frame>()
    private var totalFrames = 0L
    private val rateWindow = LinkedList<Long>()

    // ── Statistics ───────────────────────────────────────────────────────────

    private val typeCounts = mutableMapOf<ItsG5Decoder.MsgType, Long>()
    private val stationLastSeen = mutableMapOf<Long, Long>()

    enum class RateRange(val bucketCount: Int, val bucketMs: Long) {
        SEC(60, 1_000L), HOUR(60, 60_000L), DAY(24, 3_600_000L), WEEK(7, 86_400_000L),
    }
    private val rateBuckets = RateRange.entries.associateWith { RateBuckets(it.bucketCount, it.bucketMs) }

    fun typeCountsSnapshot(): Map<ItsG5Decoder.MsgType, Long> = typeCounts.toMap()

    fun activeStationCount(): Int {
        val now = System.currentTimeMillis()
        stationLastSeen.entries.removeAll { now - it.value > ACTIVE_STATION_WINDOW_MS }
        return stationLastSeen.size
    }

    /** Oldest-to-newest counts for the given range, re-deriving the current bucket first. */
    fun rateHistory(range: RateRange): List<Int> = rateBuckets.getValue(range).snapshot()

    // ── Range ("Max Range Mode") ─────────────────────────────────────────────

    private val rangeEntries = ArrayDeque<RangeEntry>()
    private val rangeTop5 = mutableListOf<RangeEntry>() // kept sorted descending by distance, max 5
    private var rangeTotalCount = 0

    val rangeRunning = MutableLiveData(false)
    /** Total "packets with position" seen this session — not capped, unlike the bounded [rangeEntries] storage. */
    val rangeCount = MutableLiveData(0)
    val rangeTop5Live = MutableLiveData<List<RangeEntry>>(emptyList())

    fun startRangeMeasuring() {
        rangeEntries.clear()
        rangeTop5.clear()
        rangeTotalCount = 0
        rangeCount.value = 0
        rangeTop5Live.value = emptyList()
        rangeRunning.value = true
    }

    fun stopRangeMeasuring() {
        rangeRunning.value = false
    }

    private fun recordRangeEntry(f: Frame, loc: Location) {
        val (lat, lon) = f.latLon ?: return
        val results = FloatArray(1)
        Location.distanceBetween(loc.latitude, loc.longitude, lat, lon, results)
        val entry = RangeEntry(f.msgType, results[0], System.currentTimeMillis())

        rangeEntries.addLast(entry)
        if (rangeEntries.size > RANGE_MAX_STORED) rangeEntries.removeFirst()
        rangeTotalCount++
        rangeCount.value = rangeTotalCount

        if (rangeTop5.size < 5) {
            rangeTop5.add(entry)
            rangeTop5.sortByDescending { it.distanceMeters }
            rangeTop5Live.value = rangeTop5.toList()
        } else if (entry.distanceMeters > rangeTop5.last().distanceMeters) {
            rangeTop5[4] = entry
            rangeTop5.sortByDescending { it.distanceMeters }
            rangeTop5Live.value = rangeTop5.toList()
        }
    }

    /** Writes every entry collected this session (not just the top 5) to a CSV file. Null if nothing collected. */
    fun exportRangeCsv(): File? {
        if (rangeEntries.isEmpty()) return null
        val app = getApplication<Application>()
        val dir = app.getExternalFilesDir(null) ?: app.filesDir
        val ts = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
        val f = File(dir, "range-$ts.csv")
        return try {
            f.bufferedWriter().use { w ->
                w.write("type,distance_m,timestamp_ms\n")
                rangeEntries.sortedByDescending { it.distanceMeters }.forEach {
                    w.write("${it.type.name},${it.distanceMeters},${it.timestampMs}\n")
                }
            }
            f
        } catch (_: Exception) { null }
    }

    // ── Radar ────────────────────────────────────────────────────────────────

    private data class RadarStation(val type: ItsG5Decoder.MsgType, val lat: Double, val lon: Double,
                                      val headingDeg: Double?, val lastSeenMs: Long)
    private val radarStations = mutableMapOf<Long, RadarStation>()
    val radarContacts = MutableLiveData<List<RadarContact>>(emptyList())

    private fun recomputeRadar(loc: Location) {
        val now = System.currentTimeMillis()
        radarStations.entries.removeAll { now - it.value.lastSeenMs > RADAR_CONTACT_TTL_MS }
        val results = FloatArray(2)
        radarContacts.value = radarStations.values.map { s ->
            Location.distanceBetween(loc.latitude, loc.longitude, s.lat, s.lon, results)
            RadarContact(s.type, results[0], results[1], s.headingDeg)
        }
    }

    private data class SpatRsu(
        val lat: Double, val lon: Double,
        val phase: SpatTemParser.Phase,
        val remainingSecAtCapture: Double?,
        val lastSeenMs: Long,
    )
    private val spatRsus = mutableMapOf<Long, SpatRsu>()
    /** Nearest in-range signal, with a live-recomputed speed suggestion. Null = nothing in range / assistant off. */
    val assistantInfo = MutableLiveData<AssistantInfo?>(null)

    /** Called from USB/BT background I/O threads. */
    private fun onSerialBytes(chunk: ByteArray) {
        val frames = synchronized(reader) { reader.feed(chunk) }
        if (frames.isEmpty()) return
        mainHandler.post { handleFrames(frames) }
    }

    private fun handleFrames(frames: List<Frame>) {
        val app = getApplication<Application>()
        val mqttFilter = Prefs.mqttFilterTypes(app)
        val rangeFilter = if (rangeRunning.value == true) Prefs.rangeFilterTypes(app) else null
        for (f in frames) {
            totalFrames++
            pendingFrames.add(f)
            geiger.click(f.msgType)
            if (f.msgType.name in mqttFilter) mqttBridges.forEach { it.publish(f.payload) }
            if (recorder.isRecording) recorder.append(f)
            rateWindow.add(System.currentTimeMillis())

            typeCounts[f.msgType] = (typeCounts[f.msgType] ?: 0L) + 1L
            f.stationId?.let { stationLastSeen[it] = System.currentTimeMillis() }
            rateBuckets.values.forEach { it.record() }

            if (rangeFilter != null && f.latLon != null && f.msgType.name in rangeFilter) {
                lastLocation.value?.let { recordRangeEntry(f, it) }
            }

            if (f.stationId != null && f.latLon != null) {
                val (lat, lon) = f.latLon
                radarStations[f.stationId] = RadarStation(f.msgType, lat, lon, f.headingDeg, System.currentTimeMillis())
                lastLocation.value?.let { recomputeRadar(it) }
            }

            if (f.msgType == ItsG5Decoder.MsgType.SPATEM && f.latLon != null) {
                val (lat, lon) = f.latLon
                spatRsus[f.stationId ?: 0L] = SpatRsu(lat, lon,
                    f.spatPhase ?: SpatTemParser.Phase.UNKNOWN, f.spatRemainingSec, System.currentTimeMillis())
                lastLocation.value?.let { recomputeAssistant(it) }
            }
        }
    }

    /**
     * Picks the nearest signal roughly ahead of us (or within 250m regardless of heading
     * if we don't have one), and — when the SPATEM carried a timing field — suggests a
     * speed to arrive exactly as the phase changes. No speed advice for YELLOW/UNKNOWN
     * (too short/uncertain to advise driving through) or when the frame had no timing.
     */
    private fun recomputeAssistant(loc: Location) {
        if (!spatLightEnabled) { assistantInfo.value = null; return }
        val now = System.currentTimeMillis()
        spatRsus.entries.removeAll { now - it.value.lastSeenMs > 30_000 }

        val results = FloatArray(2)
        var best: Pair<SpatRsu, Float>? = null
        for (rsu in spatRsus.values) {
            Location.distanceBetween(loc.latitude, loc.longitude, rsu.lat, rsu.lon, results)
            val dist = results[0]
            if (dist > 400f) continue
            val inHeading = if (loc.hasBearing()) {
                Math.abs(((loc.bearing - results[1] + 180f + 360f) % 360f) - 180f) < 70f
            } else dist < 250f
            if (!inHeading) continue
            if (best == null || dist < best.second) best = rsu to dist
        }
        val (rsu, dist) = best ?: run { assistantInfo.value = null; return }

        val elapsedSec = (now - rsu.lastSeenMs) / 1000.0
        val remaining = rsu.remainingSecAtCapture?.let { (it - elapsedSec).coerceAtLeast(0.0) }
        val speedKph = if (remaining != null && remaining > 0.5 &&
            (rsu.phase == SpatTemParser.Phase.RED || rsu.phase == SpatTemParser.Phase.GREEN)
        ) ((dist / remaining) * 3.6).coerceIn(0.0, 200.0) else null

        assistantInfo.value = AssistantInfo(rsu.phase, dist, remaining, speedKph)
    }

    fun resetAll() {
        pendingFrames.clear()
        totalFrames = 0
        rateWindow.clear()
        spatRsus.clear()
        logStats.value = 0 to 0
        assistantInfo.value = null
        typeCounts.clear()
        stationLastSeen.clear()
        rateBuckets.values.forEach { it.reset() }
        radarStations.clear()
        radarContacts.value = emptyList()
    }

    /** Drains the frame buffer into [newFrames] every [LOG_REFRESH_MS] (or [HIGH_RATE_REFRESH_MS] in High Rate Mode). */
    private val logRefresh = object : Runnable {
        override fun run() {
            if (pendingFrames.isNotEmpty()) {
                val batch = pendingFrames.toList()
                pendingFrames.clear()
                newFrames.value = batch
            }
            mainHandler.postDelayed(this, if (highRateMode) HIGH_RATE_REFRESH_MS else LOG_REFRESH_MS)
        }
    }

    private val rateRefresh = object : Runnable {
        override fun run() {
            val cutoff = System.currentTimeMillis() - 60_000
            while (rateWindow.isNotEmpty() && rateWindow.first() < cutoff) rateWindow.removeFirst()
            val rate = rateWindow.size
            logStats.value = totalFrames.toInt() to rate
            ReceiverForegroundService.instance?.updateStats(totalFrames.toInt(), rate)
            mainHandler.postDelayed(this, 1_000)
        }
    }

    // ── Location ─────────────────────────────────────────────────────────────

    private val fused: FusedLocationProviderClient =
        LocationServices.getFusedLocationProviderClient(getApplication<Application>())
    val lastLocation = MutableLiveData<Location>()
    @Volatile var lastSpeedMps: Float = 0f
        private set

    private val locationCallback = object : LocationCallback() {
        override fun onLocationResult(result: LocationResult) {
            val loc = result.lastLocation ?: return
            lastSpeedMps = if (loc.hasSpeed()) loc.speed else 0f
            lastLocation.value = loc
            recomputeAssistant(loc)
            recomputeRadar(loc)
        }
    }

    @Suppress("MissingPermission")
    fun startLocationUpdates() {
        val req = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 1000L)
            .setMinUpdateIntervalMillis(500L).build()
        try { fused.requestLocationUpdates(req, locationCallback, Looper.getMainLooper()) }
        catch (_: SecurityException) {}
    }

    fun stopLocationUpdates() {
        try { fused.removeLocationUpdates(locationCallback) } catch (_: Exception) {}
    }

    // ── lifecycle ────────────────────────────────────────────────────────────

    init {
        SettingsBus.liveRecorder = recorder
        if (Prefs.audioFeedback(app)) geiger.start()
        if (Prefs.mqttEnabled(app) && Prefs.mqttBrokerList(app).isNotEmpty()) startMqtt()
        mainHandler.post(rateRefresh)
        mainHandler.post(logRefresh)
        mainHandler.postDelayed(kmlRefresh, KML_SNAPSHOT_MS)
    }

    override fun onCleared() {
        usb?.stop(); bt?.stop(); stopMqtt()
        recorder.stop()
        geiger.stop()
        stopLocationUpdates()
        SettingsBus.liveRecorder = null
        SettingsBus.liveBtController = null
        mainHandler.removeCallbacksAndMessages(null)
    }

    companion object {
        private const val LOG_REFRESH_MS = 300L
        private const val HIGH_RATE_REFRESH_MS = 1_000L
        private const val ACTIVE_STATION_WINDOW_MS = 30_000L
        private const val RANGE_MAX_STORED = 20_000 // caps memory on a long-forgotten-running session
        private const val RADAR_CONTACT_TTL_MS = 30_000L
        private const val KML_SNAPSHOT_MS = 120_000L
    }
}

/** Fixed-size ring of counts, one bucket per [bucketMs] — e.g. 60×1s for a 60-second history. */
class RateBuckets(private val count: Int, private val bucketMs: Long) {
    private val buckets = IntArray(count)
    private var current = 0
    private var bucketStartMs = System.currentTimeMillis()

    fun record() {
        advance()
        buckets[current]++
    }

    fun reset() {
        buckets.fill(0)
        current = 0
        bucketStartMs = System.currentTimeMillis()
    }

    /** Oldest-to-newest. */
    fun snapshot(): List<Int> {
        advance()
        return (0 until count).map { buckets[(current + 1 + it) % count] }
    }

    private fun advance() {
        val now = System.currentTimeMillis()
        var elapsed = now - bucketStartMs
        if (elapsed > bucketMs.toDouble() * count) {
            // We've been away longer than the whole window — everything is stale.
            reset()
            return
        }
        while (elapsed >= bucketMs) {
            current = (current + 1) % count
            buckets[current] = 0
            bucketStartMs += bucketMs
            elapsed -= bucketMs
        }
    }
}
