package com.parkingmemo.engine

/** What a photo of a pillar or wall sign says: the floor and the pillar/zone code. */
data class SignInfo(val floor: String?, val pillar: String?)

/**
 * Reads parking signs from OCR text lines (text + on-image area). Typical Korean
 * garage signs: "B4 / 나4", "지하 4층", "B4 주차구역", "P2 C-17", "4F".
 *
 * Floor: the most frequent floor label (several pillars in one photo agree).
 * Pillar: the code in the largest text that is not the floor itself, i.e. the
 * pillar closest to the camera.
 */
object SignReader {
    private val floorB = Regex("""(?<![A-Za-z0-9])[Bb]\s?(\d{1,2})(?!\d)""")
    private val floorKo = Regex("""지하\s?(\d{1,2})\s?층""")
    private val floorP = Regex("""(?<![A-Za-z0-9])P\s?(\d{1,2})(?!\d)""")
    private val floorF = Regex("""(?<![A-Za-z0-9])(\d{1,2})\s?F(?![A-Za-z])""")
    private val pillarKo = Regex("""([가-힣])\s?-?\s?(\d{1,3})(?![\d층])""")
    private val pillarLatin = Regex("""(?<![A-Za-z0-9])([ACDEGHJ-NQ-Z])\s?-\s?(\d{1,3})(?!\d)|(?<![A-Za-z0-9])([ACDEGHJ-NQ-Z])(\d{1,3})(?![\dF])""")

    fun read(lines: List<Pair<String, Int>>): SignInfo {
        val floors = mutableMapOf<String, Int>()
        for ((text, _) in lines) floorsIn(text).forEach { floors[it] = (floors[it] ?: 0) + 1 }
        val floor = floors.maxByOrNull { it.value }?.key

        var pillar: String? = null
        var best = -1
        for ((text, area) in lines) {
            val code = pillarIn(text) ?: continue
            if (area > best) { best = area; pillar = code }
        }
        return SignInfo(floor, pillar)
    }

    fun floorsIn(text: String): List<String> {
        val out = mutableListOf<String>()
        floorB.findAll(text).forEach { out += "B${it.groupValues[1].toInt()}" }
        floorKo.findAll(text).forEach { out += "B${it.groupValues[1].toInt()}" }
        floorP.findAll(text).forEach { out += "B${it.groupValues[1].toInt()}" }
        floorF.findAll(text).forEach { out += "${it.groupValues[1].toInt()}F" }
        return out
    }

    fun pillarIn(text: String): String? {
        // Korean zone letters (가, 나, 다, ...) followed by a number, but not "지하 4층".
        pillarKo.findAll(text).forEach { m ->
            val ch = m.groupValues[1]
            if (ch != "층" && ch != "하") return "$ch${m.groupValues[2].toInt()}"
        }
        pillarLatin.find(text)?.let { m ->
            val letter = m.groupValues[1].ifEmpty { m.groupValues[3] }
            val num = m.groupValues[2].ifEmpty { m.groupValues[4] }
            return "$letter-${num.toInt()}"
        }
        return null
    }
}
