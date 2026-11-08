package org.opentrafficmap.receiver

import android.content.Context
import android.util.Log
import java.io.BufferedOutputStream
import java.io.BufferedWriter
import java.io.File
import java.io.FileOutputStream
import java.io.FileWriter
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** One known station position, for a KML map snapshot. */
data class KmlPoint(val label: String, val lat: Double, val lon: Double)

/**
 * Writes a recording session's bundle: raw frames (PCAP, Wireshark-readable,
 * link type 105 = LINKTYPE_IEEE802_11), a per-frame session log (CSV), an app
 * debug log (recording lifecycle + connection events), and a periodic map
 * snapshot (KML) refreshed by the caller via [writeKmlSnapshot].
 *
 * Output path: Android/data/com.v2x2map/files/  (no extra permission)
 */
class FrameRecorder(private val context: Context) {

    private val tag = "FrameRecorder"
    private var stream: BufferedOutputStream? = null
    private var csv: BufferedWriter? = null
    private var debugLog: BufferedWriter? = null
    private var kmlFile: File? = null
    var file: File? = null
        private set
    var frameCount: Int = 0
        private set

    val isRecording: Boolean get() = stream != null

    fun start(): File? {
        if (stream != null) return file
        val dir = context.getExternalFilesDir(null) ?: context.filesDir
        if (!dir.exists()) dir.mkdirs()
        val ts = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
        val base = "itsg5-$ts"
        val f = File(dir, "$base.pcap")
        return try {
            val out = BufferedOutputStream(FileOutputStream(f))
            writePcapGlobalHeader(out)
            out.flush()
            stream = out
            file = f
            frameCount = 0
            csv = BufferedWriter(FileWriter(File(dir, "$base.csv"))).apply {
                write("seq,sec,usec,type,station_id,lat,lon,speed_mps,heading_deg\n")
            }
            debugLog = BufferedWriter(FileWriter(File(dir, "$base.log")))
            kmlFile = File(dir, "$base.kml")
            logEvent("recording started")
            Log.i(tag, "recording to ${f.absolutePath}")
            f
        } catch (e: Exception) {
            Log.w(tag, "open failed", e)
            null
        }
    }

    fun stop(): File? {
        val s = stream ?: return null
        return try {
            logEvent("recording stopped ($frameCount frames)")
            s.flush(); s.close()
            stream = null
            csv?.flush(); csv?.close(); csv = null
            debugLog?.flush(); debugLog?.close(); debugLog = null
            kmlFile = null
            Log.i(tag, "stopped after $frameCount frames → ${file?.absolutePath}")
            file
        } catch (e: Exception) {
            Log.w(tag, "close failed", e); null
        }
    }

    @Synchronized
    fun append(frame: Frame) {
        val s = stream ?: return
        try {
            writePcapPacket(s, frame)
            writeCsvRow(frame)
            frameCount++
        } catch (e: Exception) {
            Log.w(tag, "write failed", e)
            stop()
        }
    }

    /** Appends a timestamped line to the session debug log. No-op if not recording. */
    @Synchronized
    fun logEvent(message: String) {
        val w = debugLog ?: return
        try {
            w.write("${DEBUG_TS.format(Date())}  $message\n")
            w.flush()
        } catch (e: Exception) {
            Log.w(tag, "debug log write failed", e)
        }
    }

    /** Overwrites the session's KML snapshot with the given stations. No-op if not recording. */
    @Synchronized
    fun writeKmlSnapshot(points: List<KmlPoint>) {
        val f = kmlFile ?: return
        try {
            f.bufferedWriter().use { writeKml(it, points) }
        } catch (e: Exception) {
            Log.w(tag, "KML snapshot write failed", e)
        }
    }

    private fun writeCsvRow(frame: Frame) {
        val w = csv ?: return
        val (lat, lon) = frame.latLon ?: (null to null)
        w.write("${frame.seq},${frame.sec},${frame.usec},${frame.msgType.name}," +
            "${frame.stationId ?: ""},${lat ?: ""},${lon ?: ""}," +
            "${frame.speedMps ?: ""},${frame.headingDeg ?: ""}\n")
    }

    // ── PCAP format ────────────────────────────────────────────────────────

    /** 24-byte global header (little-endian). Link type 105 = LINKTYPE_IEEE802_11. */
    private fun writePcapGlobalHeader(out: BufferedOutputStream) {
        val buf = ByteBuffer.allocate(24).order(ByteOrder.LITTLE_ENDIAN)
        buf.putInt(PCAP_MAGIC)      // magic number (LE)
        buf.putShort(2)             // version major
        buf.putShort(4)             // version minor
        buf.putInt(0)               // GMT offset
        buf.putInt(0)               // timestamp accuracy
        buf.putInt(65535)           // snaplen
        buf.putInt(LINK_IEEE80211)  // link type: raw 802.11 (no radiotap)
        out.write(buf.array())
    }

    /** 16-byte per-packet header + payload. */
    private fun writePcapPacket(out: BufferedOutputStream, frame: Frame) {
        val len = frame.payload.size
        val hdr = ByteBuffer.allocate(16).order(ByteOrder.LITTLE_ENDIAN)
        hdr.putInt(frame.sec.toInt())   // ts_sec
        hdr.putInt(frame.usec.toInt())  // ts_usec
        hdr.putInt(len)                 // incl_len
        hdr.putInt(len)                 // orig_len
        out.write(hdr.array())
        out.write(frame.payload)
    }

    companion object {
        // 0xa1b2c3d4 stored LE → bytes d4 c3 b2 a1 → Wireshark recognises native-endian pcap
        private val PCAP_MAGIC     = 0xa1b2c3d4.toInt()
        private const val LINK_IEEE80211 = 105
        private val DEBUG_TS = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)

        private fun writeKml(w: java.io.Writer, points: List<KmlPoint>) {
            w.write("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n")
            w.write("<kml xmlns=\"http://www.opengis.net/kml/2.2\"><Document><name>V2X2MAP snapshot</name>\n")
            for (p in points) {
                w.write("<Placemark><name>${p.label}</name><Point><coordinates>${p.lon},${p.lat},0</coordinates></Point></Placemark>\n")
            }
            w.write("</Document></kml>\n")
        }

        /** Exports a one-off KML snapshot independent of any active recording. */
        fun exportKml(context: Context, points: List<KmlPoint>): File? {
            val dir = context.getExternalFilesDir(null) ?: context.filesDir
            if (!dir.exists()) dir.mkdirs()
            val ts = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
            val f = File(dir, "map-$ts.kml")
            return try {
                f.bufferedWriter().use { writeKml(it, points) }
                f
            } catch (_: Exception) { null }
        }
    }
}
