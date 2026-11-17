package com.parkingmemo.engine

import java.util.Locale

/**
 * Text encoding of a [ParkingRecord] ("key=value" per line), kept in the engine so a
 * round-trip test covers every field. Field test 3 found the app's own JSON code had
 * silently dropped the door bearing and GPS fix, which disabled the door alignment
 * on the phone while every engine test passed.
 */
object RecordCodec {
    private const val VERSION = "1"

    fun encode(r: ParkingRecord): String {
        val m = linkedMapOf<String, String?>()
        m["v"] = VERSION
        m["parkedAtWallMs"] = r.parkedAtWallMs.toString()
        m["siteKey"] = r.siteKey
        m["carPressureHpa"] = r.carPressureHpa?.let(::num)
        m["dhGroundDriveM"] = r.dhGroundDriveM?.let(::num)
        m["dhGroundWalkM"] = r.dhGroundWalkM?.let(::num)
        m["dhEndM"] = r.dhEndM?.let(::num)
        m["endKind"] = r.endKind.name
        m["anchorMode"] = r.anchorMode?.name
        m["carInAnchorFrame"] = r.carInAnchorFrame?.let(::vec)
        m["waypoints"] = r.waypointsFromAnchor.joinToString(";") { vec(it) }
        m["gridAngleDeg"] = r.gridAngleDeg?.let(::num)
        m["pathLengthM"] = num(r.pathLengthM)
        m["segments"] = r.segments.joinToString(";") { "${it.startMs},${it.endMs},${num(it.dhM)},${it.mode.name}" }
        m["floor.label"] = r.floor.label
        m["floor.dhGroundM"] = r.floor.dhGroundM?.let(::num)
        m["floor.confidence"] = num(r.floor.confidence)
        m["floor.basis"] = r.floor.basis
        m["userLabel"] = r.userLabel
        m["doorBearingDeg"] = r.doorBearingDeg?.let(::num)
        m["carGeo"] = r.carGeo?.let { "${num(it.lat)},${num(it.lon)},${num(it.accuracyM)}" }
        m["pillarCode"] = r.pillarCode
        m["photoPath"] = r.photoPath
        return m.entries.filter { it.value != null }
            .joinToString("\n") { "${it.key}=${it.value!!.replace("\n", " ")}" }
    }

    fun decode(text: String): ParkingRecord? = try {
        val m = text.lineSequence()
            .filter { it.contains('=') }
            .associate { it.substringBefore('=') to it.substringAfter('=') }
        fun d(k: String) = m[k]?.toDoubleOrNull()
        fun s(k: String) = m[k]?.takeIf { it.isNotEmpty() }
        ParkingRecord(
            parkedAtWallMs = m.getValue("parkedAtWallMs").toLong(),
            siteKey = s("siteKey"),
            carPressureHpa = d("carPressureHpa"),
            dhGroundDriveM = d("dhGroundDriveM"),
            dhGroundWalkM = d("dhGroundWalkM"),
            dhEndM = d("dhEndM"),
            endKind = WalkEnd.valueOf(m.getValue("endKind")),
            anchorMode = s("anchorMode")?.let { VerticalMode.valueOf(it) },
            carInAnchorFrame = s("carInAnchorFrame")?.let(::parseVec),
            waypointsFromAnchor = s("waypoints")?.split(';')?.map(::parseVec) ?: emptyList(),
            gridAngleDeg = d("gridAngleDeg"),
            pathLengthM = d("pathLengthM") ?: 0.0,
            segments = s("segments")?.split(';')?.map {
                val f = it.split(',')
                VerticalSegment(f[0].toLong(), f[1].toLong(), f[2].toDouble(), VerticalMode.valueOf(f[3]))
            } ?: emptyList(),
            floor = FloorEstimate(s("floor.label"), d("floor.dhGroundM"), d("floor.confidence") ?: 0.0, m["floor.basis"] ?: ""),
            userLabel = s("userLabel"),
            doorBearingDeg = d("doorBearingDeg"),
            carGeo = s("carGeo")?.split(',')?.let { GeoFix(it[0].toDouble(), it[1].toDouble(), it[2].toDouble()) },
            pillarCode = s("pillarCode"),
            photoPath = s("photoPath"),
        )
    } catch (e: Exception) {
        null
    }

    private fun num(x: Double) = "%.6f".format(Locale.US, x).trimEnd('0').trimEnd('.')
    private fun vec(v: Vec2) = "${num(v.x)},${num(v.y)}"
    private fun parseVec(s: String): Vec2 = s.split(',').let { Vec2(it[0].toDouble(), it[1].toDouble()) }
}
