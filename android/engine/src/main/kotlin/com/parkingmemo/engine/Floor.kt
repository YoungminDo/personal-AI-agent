package com.parkingmemo.engine

import kotlin.math.abs
import kotlin.math.min
import kotlin.math.roundToInt

data class FloorEstimate(
    val label: String?,
    /** Car height relative to ground level in metres (negative = underground). */
    val dhGroundM: Double?,
    val confidence: Double,
    val basis: String,
)

data class LevelLabel(val siteKey: String, val dhM: Double, val label: String)

/**
 * What the app has learned about each parking site: user-confirmed level names
 * (B05, B07, D10) and how well guidance worked there (finds, for tiering).
 */
class SiteBook(
    val labels: MutableList<LevelLabel> = mutableListOf(),
    val findErrors: MutableMap<String, MutableList<Double>> = mutableMapOf(),
) {
    fun labelFor(site: String?, dhM: Double): String? {
        if (site == null) return null
        return labels.filter { it.siteKey == site && abs(it.dhM - dhM) < 1.5 }
            .minByOrNull { abs(it.dhM - dhM) }?.label
    }

    fun addLabel(site: String, dhM: Double, label: String) {
        labels.removeAll { it.siteKey == site && (it.label == label || abs(it.dhM - dhM) < 1.0) }
        labels += LevelLabel(site, dhM, label)
    }

    /** Floor-to-floor height for a site, learned from two or more labelled levels. */
    fun floorHeight(site: String?): Double {
        val pts = labels.filter { it.siteKey == site }
            .mapNotNull { l -> levelIndex(l.label)?.let { it to l.dhM } }
            .sortedBy { it.first }
        val slopes = mutableListOf<Double>()
        for (i in pts.indices) for (j in i + 1 until pts.size) {
            val di = pts[j].first - pts[i].first
            if (di != 0) slopes += (pts[j].second - pts[i].second) / di
        }
        if (slopes.isEmpty()) return FloorEstimator.DEFAULT_FLOOR_H
        return median(slopes).coerceIn(2.4, 5.0)
    }

    fun addFind(site: String, errorM: Double) {
        findErrors.getOrPut(site) { mutableListOf() } += errorM
    }

    /** A site counts as learned after 3 finds with a median error under 6 m. */
    fun isLearned(site: String?): Boolean {
        val errs = findErrors[site ?: return false] ?: return false
        return errs.size >= 3 && median(errs) < 6.0
    }

    companion object {
        /** B1 -> -1, 1F -> 0, 2F -> 1. Null for unknown formats. */
        fun levelIndex(label: String): Int? = when {
            label.startsWith("B") -> label.drop(1).toIntOrNull()?.let { -it }
            label.endsWith("F") -> label.dropLast(1).toIntOrNull()?.let { it - 1 }
            else -> null
        }
    }
}

object FloorEstimator {
    const val DEFAULT_FLOOR_H = 3.0

    /**
     * Fuses the drive-in estimate (ground -> car while driving) and the walk-out
     * estimate (car -> ground on foot). Both are short-window differences, so
     * weather drift does not enter (P1).
     */
    fun estimate(driveDh: Double?, walkDh: Double?, sites: SiteBook, siteKey: String?): FloorEstimate {
        val dh: Double
        var conf: Double
        val basis: String
        when {
            driveDh != null && walkDh != null && abs(driveDh - walkDh) < 1.2 -> {
                dh = (driveDh + walkDh) / 2.0; conf = 0.9; basis = "주행+도보 일치"
            }
            driveDh != null && walkDh != null -> {
                dh = walkDh; conf = 0.6; basis = "도보 (주행과 불일치)"
            }
            walkDh != null -> { dh = walkDh; conf = 0.75; basis = "도보" }
            driveDh != null -> { dh = driveDh; conf = 0.75; basis = "주행" }
            else -> return FloorEstimate(null, null, 0.0, "지상 기준 없음")
        }
        val learned = sites.labelFor(siteKey, dh)
        if (learned != null) {
            conf = min(0.99, conf + 0.08)
            return FloorEstimate(learned, dh, conf, "$basis + 학습된 층 이름")
        }
        return FloorEstimate(labelFor(dh, sites.floorHeight(siteKey)), dh, conf, basis)
    }

    fun labelFor(dhGroundM: Double, floorH: Double): String {
        val n = (dhGroundM / floorH).roundToInt()
        return if (n < 0) "B${-n}" else "${n + 1}F"
    }
}
