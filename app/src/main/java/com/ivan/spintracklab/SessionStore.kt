package com.ivan.spintracklab

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class TestSession(
    val startedAtMs: Long,
    val durationMs: Long,
    val averageAbsOmega: Float,
    val maxAbsOmega: Float,
    val averageConfidence: Float,
    val lockedSamples: Int
) {
    fun summary(index: Int): String {
        val date = SimpleDateFormat("dd.MM.yyyy HH:mm", Locale.getDefault()).format(Date(startedAtMs))
        val seconds = durationMs / 1000f
        return "Session #$index  •  $date\n" +
            "%.1f s   avg |ω| %.2f rad/s   max %.2f   confidence %d%%".format(
                Locale.US,
                seconds,
                averageAbsOmega,
                maxAbsOmega,
                (averageConfidence * 100).toInt()
            )
    }
}

class SessionStore(context: Context) {
    private val prefs = context.getSharedPreferences("spintrack_sessions", Context.MODE_PRIVATE)

    fun load(): List<TestSession> {
        val raw = prefs.getString("items", "[]") ?: "[]"
        return try {
            val array = JSONArray(raw)
            buildList {
                for (i in 0 until array.length()) {
                    val obj = array.optJSONObject(i) ?: continue
                    add(
                        TestSession(
                            startedAtMs = obj.optLong("startedAtMs"),
                            durationMs = obj.optLong("durationMs"),
                            averageAbsOmega = obj.optDouble("averageAbsOmega").toFloat(),
                            maxAbsOmega = obj.optDouble("maxAbsOmega").toFloat(),
                            averageConfidence = obj.optDouble("averageConfidence").toFloat(),
                            lockedSamples = obj.optInt("lockedSamples")
                        )
                    )
                }
            }
        } catch (_: Throwable) {
            emptyList()
        }
    }

    fun add(session: TestSession) {
        val items = (listOf(session) + load()).take(20)
        val array = JSONArray()
        items.forEach { s ->
            array.put(
                JSONObject().apply {
                    put("startedAtMs", s.startedAtMs)
                    put("durationMs", s.durationMs)
                    put("averageAbsOmega", s.averageAbsOmega.toDouble())
                    put("maxAbsOmega", s.maxAbsOmega.toDouble())
                    put("averageConfidence", s.averageConfidence.toDouble())
                    put("lockedSamples", s.lockedSamples)
                }
            )
        }
        prefs.edit().putString("items", array.toString()).apply()
    }

    fun clear() {
        prefs.edit().remove("items").apply()
    }
}
