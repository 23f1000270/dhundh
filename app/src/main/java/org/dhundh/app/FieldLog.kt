package org.dhundh.app

import org.dhundh.core.HazardMessage
import org.dhundh.core.Relevance
import java.io.File
import java.io.FileWriter

/**
 * CSV log for field tests: every packet sent and heard, with signal strength,
 * radio PHY, hop count, distance and how late it arrived. This is how range
 * and latency are measured on a real road (see docs/field-test.md).
 */
class FieldLog(file: File) {
    private val out = FileWriter(file, true).apply {
        if (file.length() == 0L) {
            write("wall_ms,direction,type,hops,seq,msg_time_ms,lat,lon,course,speed,rssi,phy,distance_m,level,reason\n")
        }
    }

    @Synchronized
    fun write(direction: String, m: HazardMessage, rssi: Int?, phy: Int?, verdict: Relevance.Verdict?) {
        out.write(listOf(
            System.currentTimeMillis(), direction, m.type, m.hops, m.sequence, m.timeMs,
            "%.7f".format(m.latitude), "%.7f".format(m.longitude), "%.1f".format(m.courseDeg),
            "%.2f".format(m.speedMps), rssi ?: "", phy ?: "",
            verdict?.distanceM?.let { "%.1f".format(it) } ?: "", verdict?.level ?: "", verdict?.reason ?: "",
        ).joinToString(",") + "\n")
        out.flush()
    }

    @Synchronized
    fun close() = out.close()
}
