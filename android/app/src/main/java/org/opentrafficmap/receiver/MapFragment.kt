package org.opentrafficmap.receiver

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.location.Location
import android.os.Build
import android.os.Bundle
import android.preference.PreferenceManager
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import androidx.core.graphics.drawable.toBitmap
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.color.MaterialColors
import org.opentrafficmap.receiver.databinding.FragmentMapBinding
import org.osmdroid.config.Configuration
import org.osmdroid.tileprovider.cachemanager.CacheManager
import org.osmdroid.tileprovider.tilesource.ITileSource
import org.osmdroid.tileprovider.tilesource.OnlineTileSourceBase
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.tileprovider.tilesource.XYTileSource
import org.osmdroid.util.GeoPoint
import org.osmdroid.util.MapTileIndex
import org.osmdroid.views.overlay.Polyline
import org.osmdroid.views.overlay.mylocation.GpsMyLocationProvider
import org.osmdroid.views.overlay.mylocation.MyLocationNewOverlay

/**
 * The Map tab: live map, status pills, FABs, the traffic-light assistant
 * card, and the draggable frame-log bottom sheet. Layout and element
 * structure (statusPill/mqttPill/btnSettings/assistantCard/logSheet+
 * logGrabber, single combined connection pill instead of two buttons, FABs
 * anchored to the sheet) are verified against the real shipped app's
 * decompiled `activity_main.xml` and string resources, not guessed from
 * screenshots.
 *
 * The assistant card currently only shows the SPATEM phase as a colored dot
 * + phase label — the actual green-wave speed computation
 * (`assistantSpeed`/`assistantSub` real content) is Phase 4's job, not
 * faked here.
 */
class MapFragment : Fragment() {

    private var _binding: FragmentMapBinding? = null
    private val binding get() = _binding!!

    private val viewModel: MainViewModel by activityViewModels()

    private lateinit var adapter: FrameLogAdapter
    private lateinit var markers: MarkerLayer
    private lateinit var locationOverlay: MyLocationNewOverlay
    private lateinit var logSheetBehavior: BottomSheetBehavior<android.widget.LinearLayout>

    private var defaultFabTint: ColorStateList? = null
    private var logSampleCounter = 0 // High Rate Mode: keeps 1-in-10 log rows

    // Own GPS track — UI-only (osmdroid types), lives here rather than the ViewModel.
    private val ownTrackPoints = mutableListOf<GeoPoint>()
    private var ownTrackLine: Polyline? = null

    // Compass / bearing-up mode — Map-screen-exclusive, not shared with other tabs.
    private var compassMode: Boolean = false

    // ---------------------------------------------------------------- launchers

    private val btPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { r -> if (r.values.all { it }) startBt()
             else Toast.makeText(requireContext(), "BT: permission denied", Toast.LENGTH_SHORT).show() }

    private val notifPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { startReceiverService() }

    private val locationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) { centreOnMyLocation(); refreshLocationSubscription() }
        else toast(getString(R.string.loc_perm_denied))
    }

    // ---------------------------------------------------------------- lifecycle

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?,
    ): View {
        _binding = FragmentMapBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        setupUi()
        observeViewModel()
    }

    override fun onResume() {
        super.onResume()
        binding.map.onResume()
        refreshLocationSubscription()
    }

    override fun onPause() {
        super.onPause()
        binding.map.onPause()
    }

    /**
     * MainActivity switches tabs with FragmentTransaction.hide()/show(), which
     * does NOT call onPause()/onResume() — without this, osmdroid's MapView
     * would keep rendering/ticking while a different tab is showing.
     */
    override fun onHiddenChanged(hidden: Boolean) {
        super.onHiddenChanged(hidden)
        if (_binding == null) return
        if (hidden) binding.map.onPause() else binding.map.onResume()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        locationOverlay.disableMyLocation()
        _binding = null
    }

    // ---------------------------------------------------------------- setup

    private fun setupUi() {
        val ctx = requireContext()
        Configuration.getInstance().load(ctx.applicationContext, PreferenceManager.getDefaultSharedPreferences(ctx))
        Configuration.getInstance().userAgentValue = ctx.packageName
        Configuration.getInstance().tileFileSystemCacheMaxBytes  = 600L * 1024 * 1024
        Configuration.getInstance().tileFileSystemCacheTrimBytes = 500L * 1024 * 1024

        compassMode = Prefs.compassMode(ctx)
        markers = MarkerLayer(binding.map, ctx)

        binding.map.setTileSource(tileSourceForKey(Prefs.mapLayer(ctx)))
        binding.map.setMultiTouchControls(true)
        binding.map.controller.setZoom(6.0)
        binding.map.controller.setCenter(GeoPoint(51.0, 10.0))
        locationOverlay = MyLocationNewOverlay(GpsMyLocationProvider(ctx), binding.map)
        // Walking-person icon when stationary, directional arrow when a bearing is known —
        // osmdroid switches between the two on its own.
        ContextCompat.getDrawable(ctx, R.drawable.ic_person_pin)?.toBitmap()?.let { person ->
            ContextCompat.getDrawable(ctx, R.drawable.ic_marker_arrow)?.toBitmap()?.let { arrow ->
                locationOverlay.setDirectionArrow(person, arrow)
            }
        }
        binding.map.overlays.add(locationOverlay)

        adapter = FrameLogAdapter(ctx) { f -> FrameDetailSheet.show(ctx, f) }
        binding.log.layoutManager = LinearLayoutManager(ctx)
        binding.log.adapter = adapter
        // A RecyclerView still negotiates nested-scroll with BottomSheetBehavior even
        // empty, which can swallow the sheet's downward collapse drag. The log doesn't
        // need its own fling/over-scroll — let the sheet own all vertical drags instead.
        binding.log.isNestedScrollingEnabled = false

        logSheetBehavior = BottomSheetBehavior.from(binding.logSheet)
        logSheetBehavior.isDraggable = true
        logSheetBehavior.state = BottomSheetBehavior.STATE_COLLAPSED
        // logSheet is match_parent height so fitToContents would expand it to fill the
        // screen; cap it so some map (status pills) stays visible even fully expanded.
        logSheetBehavior.maxHeight = (resources.displayMetrics.heightPixels * 0.65f).toInt()

        defaultFabTint = binding.fabCompass.backgroundTintList

        binding.statusPill.setOnClickListener { onStatusPillClick() }
        binding.assistantCard.setOnClickListener { IntersectionSheet.show(ctx, viewModel.assistantInfo.value) }
        binding.fabLocate.setOnClickListener { onLocateClick() }
        binding.fabLayers.setOnClickListener { showLayerPicker() }
        binding.fabCompass.setOnClickListener { toggleCompassMode() }
        binding.logHeader.setOnClickListener { toggleLogPanel() }

        applyCompassFabTint()
    }

    private fun observeViewModel() {
        viewModel.usbState.observe(viewLifecycleOwner) { updateStatusPill() }
        viewModel.btState.observe(viewLifecycleOwner) { updateStatusPill() }
        viewModel.mqttConnected.observe(viewLifecycleOwner) { updateMqttPill() }
        viewModel.newFrames.observe(viewLifecycleOwner) { frames -> handleNewFrames(frames) }
        viewModel.logStats.observe(viewLifecycleOwner) { (total, rate) ->
            binding.logStats.text = getString(R.string.log_stats, total, rate)
            markers.prune()
        }
        viewModel.assistantInfo.observe(viewLifecycleOwner) { info -> updateAssistantUi(info) }
        viewModel.lastLocation.observe(viewLifecycleOwner) { loc -> onNewLocation(loc) }
    }

    // ------------------------------------------ Reset (called by MainActivity via SettingsBus)

    fun resetMapUi() {
        if (!::markers.isInitialized) return
        markers.clear()
        adapter.clear()
        ownTrackPoints.clear()
        ownTrackLine?.let { binding.map.overlays.remove(it) }
        ownTrackLine = null
        binding.emptyLog.visibility = View.VISIBLE
        binding.map.invalidate()
    }

    /** Called by MainActivity when Settings turns own-track plotting off. */
    fun clearOwnTrack() {
        if (_binding == null) return
        ownTrackPoints.clear()
        ownTrackLine?.let { binding.map.overlays.remove(it) }
        ownTrackLine = null
        binding.map.invalidate()
    }

    // ------------------------------------------ Log panel collapse

    private fun toggleLogPanel() {
        logSheetBehavior.state = if (logSheetBehavior.state == BottomSheetBehavior.STATE_EXPANDED)
            BottomSheetBehavior.STATE_COLLAPSED else BottomSheetBehavior.STATE_EXPANDED
    }

    // ------------------------------------------ Map layers

    private fun showLayerPicker() {
        val ctx = requireContext()
        val keys   = arrayOf("MAPNIK", "DARK", "SATELLITE", "TRANSPORT", "HOT")
        val labels = arrayOf(
            getString(R.string.map_layer_standard),
            getString(R.string.map_layer_dark),
            getString(R.string.map_layer_satellite),
            getString(R.string.map_layer_transport),
            getString(R.string.map_layer_humanitarian)
        )
        val current = keys.indexOf(Prefs.mapLayer(ctx)).coerceAtLeast(0)
        AlertDialog.Builder(ctx)
            .setTitle(R.string.map_layer_title)
            .setSingleChoiceItems(labels, current) { dlg, which ->
                val key = keys[which]
                Prefs.setMapLayer(ctx, key)
                binding.map.setTileSource(tileSourceForKey(key))
                dlg.dismiss()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun tileSourceForKey(key: String): ITileSource = when (key) {
        "DARK" -> XYTileSource(
            "CartoDB_DarkMatter", 0, 19, 256, ".png",
            arrayOf(
                "https://a.basemaps.cartocdn.com/dark_all/",
                "https://b.basemaps.cartocdn.com/dark_all/",
                "https://c.basemaps.cartocdn.com/dark_all/",
                "https://d.basemaps.cartocdn.com/dark_all/"
            )
        )
        "SATELLITE" -> object : OnlineTileSourceBase("Esri_WorldImagery", 0, 19, 256, "",
            arrayOf("https://server.arcgisonline.com/ArcGIS/rest/services/World_Imagery/MapServer/tile/")
        ) {
            override fun getTileURLString(pMapTileIndex: Long): String =
                "https://server.arcgisonline.com/ArcGIS/rest/services/World_Imagery/MapServer/tile/" +
                "${MapTileIndex.getZoom(pMapTileIndex)}/${MapTileIndex.getY(pMapTileIndex)}/${MapTileIndex.getX(pMapTileIndex)}"
        }
        "TRANSPORT" -> XYTileSource(
            "OPNV_Karte", 0, 18, 256, ".png",
            arrayOf("https://tileserver.memomaps.de/tilegen/")
        )
        "HOT" -> XYTileSource(
            "HOT", 0, 19, 256, ".png",
            arrayOf(
                "https://a.tile.openstreetmap.fr/hot/",
                "https://b.tile.openstreetmap.fr/hot/",
                "https://c.tile.openstreetmap.fr/hot/"
            )
        )
        else -> TileSourceFactory.MAPNIK
    }

    // ------------------------------------------ Compass / bearing-up

    private fun toggleCompassMode() {
        compassMode = !compassMode
        Prefs.setCompassMode(requireContext(), compassMode)
        applyCompassFabTint()
        if (!compassMode) {
            binding.map.mapOrientation = 0f
        } else {
            refreshLocationSubscription()
        }
        toast(getString(if (compassMode) R.string.compass_bearing_up else R.string.compass_north_up))
    }

    private fun applyCompassFabTint() {
        if (compassMode) {
            // Use the active theme's own primary color (baseline/wallpaper/custom —
            // whatever Settings > Theme color currently resolves to), not a fixed hex.
            binding.fabCompass.backgroundTintList = ColorStateList.valueOf(
                MaterialColors.getColor(binding.fabCompass, com.google.android.material.R.attr.colorPrimary))
            binding.fabCompass.imageTintList = ColorStateList.valueOf(
                MaterialColors.getColor(binding.fabCompass, com.google.android.material.R.attr.colorOnPrimary))
        } else {
            binding.fabCompass.backgroundTintList = defaultFabTint
            binding.fabCompass.imageTintList = null // falls back to the vector's own android:tint
        }
    }

    // ------------------------------------------ Speed overlay

    private fun updateSpeedOverlay(loc: Location) {
        val bearingText = if (loc.hasBearing()) formatBearing(loc.bearing) else "—°"
        binding.speedOverlay.text = "$bearingText  ${Units.formatSpeedMps(requireContext(), loc.speed)}"
        binding.speedOverlay.visibility = View.VISIBLE
    }

    private fun formatBearing(deg: Float): String {
        val card = arrayOf("N", "NE", "E", "SE", "S", "SW", "W", "NW")
        val idx = ((deg / 45f + 0.5f).toInt() % 8).coerceIn(0, 7)
        return "%03.0f° %s".format(deg, card[idx])
    }

    // ------------------------------------------ Own track

    private fun addOwnTrackPoint(pt: GeoPoint) {
        ownTrackPoints.add(pt)
        if (ownTrackPoints.size > OWN_TRACK_MAX) ownTrackPoints.removeAt(0)
        val line = ownTrackLine ?: Polyline().also { poly ->
            poly.outlinePaint.color       = 0xFF2196F3.toInt()
            poly.outlinePaint.strokeWidth = 5f
            poly.outlinePaint.alpha       = 200
            binding.map.overlays.add(0, poly)
            ownTrackLine = poly
        }
        line.setPoints(ownTrackPoints)
        binding.map.invalidate()
    }

    // ------------------------------------------------------- Foreground service

    private fun ensureServiceRunning() {
        val ctx = requireContext()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED
        ) {
            notifPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            startReceiverService()
        }
    }

    private fun startReceiverService() {
        val ctx = requireContext()
        val intent = Intent(ctx, ReceiverForegroundService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) ctx.startForegroundService(intent)
        else ctx.startService(intent)
    }

    // -------------------------------------------------------------- USB / BT combined pill

    /** Tapping the pill: disconnect whichever transport is active, or offer a choice if idle. */
    private fun onStatusPillClick() {
        val usb = viewModel.usbState.value?.first ?: UsbSerialController.State.IDLE
        val bt  = viewModel.btState.value?.first ?: BluetoothController.State.IDLE
        when {
            usb != UsbSerialController.State.IDLE -> viewModel.stopUsb()
            bt  != BluetoothController.State.IDLE  -> viewModel.stopBt()
            else -> ConnectionDialog.show(requireContext()) { choice ->
                when (choice) {
                    ConnectionDialog.Choice.USB -> { ensureServiceRunning(); viewModel.startUsb() }
                    ConnectionDialog.Choice.BT  -> toggleBt()
                }
            }
        }
    }

    private fun toggleBt() {
        val missing = BluetoothController.runtimePermissions().filter {
            ContextCompat.checkSelfPermission(requireContext(), it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isNotEmpty()) { btPermissionLauncher.launch(missing.toTypedArray()); return }
        startBt()
    }

    private fun startBt() {
        ensureServiceRunning()
        viewModel.startBt()
    }

    private fun updateStatusPill() {
        val usb = viewModel.usbState.value ?: (UsbSerialController.State.IDLE to null)
        val bt  = viewModel.btState.value  ?: (BluetoothController.State.IDLE to null)

        val (text, dotColor) = when {
            usb.first == UsbSerialController.State.CONNECTED ->
                getString(R.string.conn_connected) to StatusColors.GREEN
            bt.first == BluetoothController.State.CONNECTED ->
                getString(R.string.conn_connected) to StatusColors.GREEN
            usb.first == UsbSerialController.State.REQUESTING ->
                (usb.second ?: getString(R.string.usb_connecting)) to StatusColors.ORANGE
            usb.first == UsbSerialController.State.ERROR ->
                (usb.second ?: getString(R.string.usb_error_btn)) to StatusColors.RED
            bt.first == BluetoothController.State.SCANNING || bt.first == BluetoothController.State.CONNECTING ->
                (bt.second ?: getString(R.string.bt_connecting_label)) to StatusColors.ORANGE
            bt.first == BluetoothController.State.ERROR ->
                (bt.second ?: getString(R.string.bt_error_btn)) to StatusColors.RED
            else -> getString(R.string.pill_disconnected) to StatusColors.RED
        }
        binding.statusText.text = text
        binding.statusDot.backgroundTintList = ColorStateList.valueOf(dotColor)
    }

    private fun updateMqttPill() {
        val ctx = requireContext()
        val enabled = Prefs.mqttEnabled(ctx)
        binding.mqttPill.visibility = if (enabled) View.VISIBLE else View.GONE
        if (!enabled) return
        val connected = viewModel.mqttConnected.value == true
        binding.mqttText.text = if (connected) "MQTT" else getString(R.string.mqtt_disconnected)
        binding.mqttDot.backgroundTintList = ColorStateList.valueOf(if (connected) StatusColors.GREEN else StatusColors.RED)
    }

    // ---------------------------------------------------------- Location

    private fun onLocateClick() {
        if (ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.ACCESS_FINE_LOCATION)
            != PackageManager.PERMISSION_GRANTED) {
            locationPermissionLauncher.launch(Manifest.permission.ACCESS_FINE_LOCATION); return
        }
        centreOnMyLocation()
    }

    /**
     * Starts/stops the shared location subscription based on what this screen
     * currently needs. Public — also called by [MainActivity] after a
     * Settings change that affects whether location is needed at all.
     */
    fun refreshLocationSubscription() {
        if (_binding == null) return
        if (!(viewModel.followEnabled || viewModel.ownTrackEnabled || compassMode)) {
            viewModel.stopLocationUpdates()
            return
        }
        if (ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.ACCESS_FINE_LOCATION)
            != PackageManager.PERMISSION_GRANTED) {
            locationPermissionLauncher.launch(Manifest.permission.ACCESS_FINE_LOCATION); return
        }
        viewModel.startLocationUpdates()
    }

    private fun centreOnMyLocation() {
        if (!locationOverlay.isMyLocationEnabled) locationOverlay.enableMyLocation()
        val now = locationOverlay.myLocation
        if (now != null) {
            binding.map.controller.animateTo(now)
            binding.map.controller.setZoom(15.0)
        } else {
            locationOverlay.runOnFirstFix {
                activity?.runOnUiThread {
                    binding.map.controller.animateTo(locationOverlay.myLocation)
                    binding.map.controller.setZoom(15.0)
                }
            }
            toast(getString(R.string.loc_unknown))
        }
    }

    private fun followLocation(loc: Location) {
        binding.map.controller.animateTo(GeoPoint(loc.latitude, loc.longitude))
    }

    private fun onNewLocation(loc: Location) {
        if (viewModel.followEnabled) followLocation(loc)
        if (viewModel.ownTrackEnabled) addOwnTrackPoint(GeoPoint(loc.latitude, loc.longitude))
        adapter.userLocation = loc
        updateSpeedOverlay(loc)
        if (compassMode && loc.hasBearing()) binding.map.mapOrientation = -loc.bearing
    }

    // ---------------------------------------------------------- Frames / log

    private fun handleNewFrames(frames: List<Frame>) {
        for (f in frames) markers.add(f)

        // High Rate Mode: markers still get every frame (CAM updates in place, cheap),
        // but the log is sampled 1-in-10 — a RecyclerView row per frame doesn't scale at ≥1000 pkt/s.
        val forLog = if (viewModel.highRateMode) {
            frames.filter { logSampleCounter++ % 10 == 0 }
        } else frames
        if (forLog.isNotEmpty()) adapter.addFrames(forLog)

        if (binding.emptyLog.visibility != View.GONE) binding.emptyLog.visibility = View.GONE
    }

    private fun updateAssistantUi(info: AssistantInfo?) {
        if (info == null) { binding.assistantCard.visibility = View.GONE; return }
        binding.assistantCard.visibility = View.VISIBLE
        val ctx = requireContext()
        val color = when (info.phase) {
            SpatTemParser.Phase.RED     -> StatusColors.RED
            SpatTemParser.Phase.YELLOW  -> StatusColors.ORANGE
            SpatTemParser.Phase.GREEN   -> StatusColors.GREEN
            SpatTemParser.Phase.UNKNOWN -> StatusColors.GREY
        }
        binding.assistantDot.backgroundTintList = ColorStateList.valueOf(color)

        // Lead line: a speed suggestion when we have one, else just the phase name.
        binding.assistantSpeed.text = info.suggestedSpeedKph?.let {
            Units.formatSpeedMps(ctx, it / 3.6)
        } ?: when (info.phase) {
            SpatTemParser.Phase.RED     -> getString(R.string.log_phase_red)
            SpatTemParser.Phase.YELLOW  -> getString(R.string.log_phase_yellow)
            SpatTemParser.Phase.GREEN   -> getString(R.string.log_phase_green)
            SpatTemParser.Phase.UNKNOWN -> getString(R.string.log_phase_unknown)
        }

        binding.assistantSub.visibility = View.VISIBLE
        binding.assistantSub.text = info.remainingSeconds?.let {
            getString(R.string.assistant_rest, it.toInt())
        } ?: getString(R.string.assistant_no_timer)
    }

    // ------------------------------------------------------- Map cache

    fun downloadVisibleMap() {
        val src = binding.map.tileProvider.tileSource
            as? OnlineTileSourceBase
        if (src == null || !src.tileSourcePolicy.acceptsBulkDownload()) {
            toast(getString(R.string.map_dl_unsupported)); return
        }
        val mgr     = CacheManager(binding.map)
        val box     = binding.map.boundingBox
        val zoomMin = binding.map.zoomLevelDouble.toInt()
        val zoomMax = (zoomMin + 2).coerceAtMost(src.maximumZoomLevel)
        val total   = mgr.possibleTilesInArea(box, zoomMin, zoomMax)
        toast(getString(R.string.map_dl_started))
        try {
            mgr.downloadAreaAsync(requireContext(), box, zoomMin, zoomMax, object : CacheManager.CacheManagerCallback {
                override fun onTaskComplete()     = activity?.runOnUiThread { toast(getString(R.string.map_dl_done)) } ?: Unit
                override fun onTaskFailed(e: Int) = activity?.runOnUiThread { toast("Map dl error: $e") } ?: Unit
                override fun updateProgress(p: Int, cz: Int, zn: Int, zx: Int) {
                    if (p % 50 == 0) activity?.runOnUiThread { toast(getString(R.string.map_dl_progress, p, total)) }
                }
                override fun downloadStarted() {}
                override fun setPossibleTilesInArea(t: Int) {}
            })
        } catch (e: Exception) {
            toast("Map dl: ${e.message ?: e.javaClass.simpleName}")
        }
    }

    private fun toast(s: String) = Toast.makeText(requireContext(), s, Toast.LENGTH_SHORT).show()

    companion object {
        private const val OWN_TRACK_MAX = 2000
        const val TAG = "map"
    }
}
