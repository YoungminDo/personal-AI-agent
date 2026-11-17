package com.parkingmemo.app

import android.content.Context
import com.parkingmemo.engine.FloorEstimate
import com.parkingmemo.engine.LevelLabel
import com.parkingmemo.engine.ParkingRecord
import com.parkingmemo.engine.RecordCodec
import com.parkingmemo.engine.SiteBook
import com.parkingmemo.engine.Vec2
import com.parkingmemo.engine.VerticalMode
import com.parkingmemo.engine.VerticalSegment
import com.parkingmemo.engine.WalkEnd
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** On-device storage only (design principle P9): nothing leaves the phone unless the user exports it. */
class Store(private val ctx: Context) {
    // Record text comes from the engine's RecordCodec, so every field is covered by
    // its round-trip test. last_record.json is only read for records saved by 0.1.x.
    private val recordFile = File(ctx.filesDir, "last_record.txt")
    private val legacyRecordFile = File(ctx.filesDir, "last_record.json")
    private val sitesFile = File(ctx.filesDir, "sites.json")
    private val historyFile = File(ctx.filesDir, "history.txt")
    val logsDir: File = File(ctx.filesDir, "logs").apply { mkdirs() }
    val photosDir: File = File(ctx.filesDir, "photos").apply { mkdirs() }

    @Synchronized
    fun saveRecord(r: ParkingRecord, appendHistory: Boolean = true) {
        val text = RecordCodec.encode(r)
        recordFile.writeText(text)
        if (appendHistory) historyFile.appendText(text + "\n---\n")
    }

    @Synchronized
    fun loadRecord(): ParkingRecord? = try {
        when {
            recordFile.exists() -> RecordCodec.decode(recordFile.readText())
            legacyRecordFile.exists() -> RecordJson.fromJson(JSONObject(legacyRecordFile.readText()))
            else -> null
        }
    } catch (e: Exception) {
        null
    }

    @Synchronized
    fun loadSites(): SiteBook = try {
        if (!sitesFile.exists()) SiteBook() else {
            val o = JSONObject(sitesFile.readText())
            val book = SiteBook()
            val labels = o.optJSONArray("labels") ?: JSONArray()
            for (i in 0 until labels.length()) {
                val l = labels.getJSONObject(i)
                book.labels += LevelLabel(l.getString("site"), l.getDouble("dh"), l.getString("label"))
            }
            val finds = o.optJSONObject("finds") ?: JSONObject()
            for (k in finds.keys()) {
                val arr = finds.getJSONArray(k)
                book.findErrors[k] = MutableList(arr.length()) { arr.getDouble(it) }
            }
            book
        }
    } catch (e: Exception) {
        SiteBook()
    }

    @Synchronized
    fun saveSites(b: SiteBook) {
        val labels = JSONArray()
        b.labels.forEach { labels.put(JSONObject().put("site", it.siteKey).put("dh", it.dhM).put("label", it.label)) }
        val finds = JSONObject()
        b.findErrors.forEach { (k, v) -> finds.put(k, JSONArray().apply { v.forEach { put(it) } }) }
        sitesFile.writeText(JSONObject().put("labels", labels).put("finds", finds).toString())
    }

    /** User says "it was actually B2": fix this record and teach the site (D10). */
    @Synchronized
    fun setUserLabel(label: String) {
        val r = loadRecord() ?: return
        val site = r.siteKey
        val dh = r.floor.dhGroundM
        if (site != null && dh != null) {
            val sites = loadSites()
            sites.addLabel(site, dh, label)
            saveSites(sites)
        }
        saveRecord(r.copy(userLabel = label), appendHistory = false)
        historyFile.appendText("correction=$label at=${System.currentTimeMillis()}\n---\n")
    }

    /** Pillar/zone code and sign photo for the current record. */
    @Synchronized
    fun setPillar(code: String?, photoPath: String?) {
        val r = loadRecord() ?: return
        saveRecord(r.copy(pillarCode = code ?: r.pillarCode, photoPath = photoPath ?: r.photoPath), appendHistory = false)
        historyFile.appendText("pillar=${code ?: "-"} at=${System.currentTimeMillis()}\n---\n")
    }

    /**
     * One plain-text file with the record, what the sites learned and the most recent
     * sensor logs, so it can be attached straight to a chat (zip files often cannot).
     */
    fun exportText(maxLogs: Int = 6): File {
        val out = File(ctx.cacheDir, "parking-memo-logs.txt")
        out.bufferedWriter().use { w ->
            fun section(name: String, f: File) {
                if (!f.exists()) return
                w.write("=== $name ===\n")
                f.bufferedReader().use { it.copyTo(w) }
                w.write("\n")
            }
            w.write("# parking-memo export ${System.currentTimeMillis()}\n")
            section("last_record", recordFile)
            section("sites", sitesFile)
            section("history", historyFile)
            logsDir.listFiles()?.sortedBy { it.name }?.takeLast(maxLogs)?.forEach { section("log ${it.name}", it) }
        }
        return out
    }
}

/** Reads records saved by 0.1.x as JSON (no longer written). */
object RecordJson {
    fun toJson(r: ParkingRecord): JSONObject = JSONObject().apply {
        put("parkedAtWallMs", r.parkedAtWallMs)
        putOpt("siteKey", r.siteKey)
        putOpt("carPressureHpa", r.carPressureHpa)
        putOpt("dhGroundDriveM", r.dhGroundDriveM)
        putOpt("dhGroundWalkM", r.dhGroundWalkM)
        putOpt("dhEndM", r.dhEndM)
        put("endKind", r.endKind.name)
        putOpt("anchorMode", r.anchorMode?.name)
        r.carInAnchorFrame?.let { put("carInAnchorFrame", vec(it)) }
        put("waypoints", JSONArray().apply { r.waypointsFromAnchor.forEach { put(vec(it)) } })
        putOpt("gridAngleDeg", r.gridAngleDeg)
        put("pathLengthM", r.pathLengthM)
        put("segments", JSONArray().apply {
            r.segments.forEach { s ->
                put(JSONObject().put("startMs", s.startMs).put("endMs", s.endMs).put("dhM", s.dhM).put("mode", s.mode.name))
            }
        })
        put("floor", JSONObject().apply {
            putOpt("label", r.floor.label)
            putOpt("dhGroundM", r.floor.dhGroundM)
            put("confidence", r.floor.confidence)
            put("basis", r.floor.basis)
        })
        putOpt("userLabel", r.userLabel)
    }

    fun fromJson(o: JSONObject): ParkingRecord {
        val f = o.getJSONObject("floor")
        val wps = o.optJSONArray("waypoints") ?: JSONArray()
        val segs = o.optJSONArray("segments") ?: JSONArray()
        return ParkingRecord(
            parkedAtWallMs = o.getLong("parkedAtWallMs"),
            siteKey = o.optS("siteKey"),
            carPressureHpa = o.optD("carPressureHpa"),
            dhGroundDriveM = o.optD("dhGroundDriveM"),
            dhGroundWalkM = o.optD("dhGroundWalkM"),
            dhEndM = o.optD("dhEndM"),
            endKind = WalkEnd.valueOf(o.getString("endKind")),
            anchorMode = o.optS("anchorMode")?.let { VerticalMode.valueOf(it) },
            carInAnchorFrame = o.optJSONArray("carInAnchorFrame")?.let { Vec2(it.getDouble(0), it.getDouble(1)) },
            waypointsFromAnchor = List(wps.length()) { val a = wps.getJSONArray(it); Vec2(a.getDouble(0), a.getDouble(1)) },
            gridAngleDeg = o.optD("gridAngleDeg"),
            pathLengthM = o.optDouble("pathLengthM", 0.0),
            segments = List(segs.length()) {
                val s = segs.getJSONObject(it)
                VerticalSegment(s.getLong("startMs"), s.getLong("endMs"), s.getDouble("dhM"), VerticalMode.valueOf(s.getString("mode")))
            },
            floor = FloorEstimate(f.optS("label"), f.optD("dhGroundM"), f.optDouble("confidence", 0.0), f.optString("basis", "")),
            userLabel = o.optS("userLabel"),
        )
    }

    private fun vec(v: Vec2) = JSONArray().put(v.x).put(v.y)
    private fun JSONObject.optD(k: String): Double? = if (has(k) && !isNull(k)) getDouble(k) else null
    private fun JSONObject.optS(k: String): String? = if (has(k) && !isNull(k)) getString(k) else null
}
