package com.parkingmemo.engine

import java.io.File
import java.util.Locale

/**
 * Developer tool: replay logs exported from the app.
 *
 *   ./gradlew :engine:run --args="park.csv [guide.csv ...]"
 *
 * The parking log is replayed to rebuild the record; each guide log is then
 * replayed against it and summarised once per second.
 */
fun main(args: Array<String>) {
    if (args.isEmpty()) {
        println("usage: <parking-log.csv> [guide-log.csv ...]")
        return
    }
    val sites = SiteBook()
    val res = LogReplay.replayParking(File(args[0]).readLines().asSequence(), sites)
    res.outputs.forEach { println("output: $it") }
    val rec = res.records.lastOrNull()
    if (rec == null) {
        println("no parking record produced")
        return
    }
    println("label=${rec.floor.label} dhGround=${rec.floor.dhGroundM?.fmt()} dhEnd=${rec.dhEndM?.fmt()} " +
        "anchor=${rec.anchorMode} car=${rec.carInAnchorFrame} door=${rec.doorBearingDeg?.fmt()} path=${rec.pathLengthM.fmt()}m")
    for (g in args.drop(1)) {
        println("--- guide replay: $g")
        for ((t, s) in LogReplay.replayGuide(File(g).readLines().asSequence(), rec)) {
            println("t=$t ${s.mode} ${s.ref} d=${s.distanceM?.fmt()} arrow=${s.arrowDeg?.fmt()} h=${s.hRelCarM?.fmt()} align=${s.alignDeg?.fmt()} '${s.text}'")
        }
    }
}

private fun Double.fmt() = "%.1f".format(Locale.US, this)
