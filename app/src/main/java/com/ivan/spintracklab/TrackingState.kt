package com.ivan.spintracklab

data class TrackPoint(
    val x: Float,
    val y: Float,
    val timestampNs: Long
)

data class DiscCalibration(
    val centerX: Float = 0.5f,
    val centerY: Float = 0.5f,
    val revision: Int = 0
)

data class TrackerConfig(
    val outerRadiusFraction: Float = 0.46f,
    val innerRadiusRatio: Float = 0.55f,
    val sensitivity: Float = 0.55f,
    val showTrail: Boolean = true
)

data class TrackingState(
    val detected: Boolean = false,
    val locked: Boolean = false,
    val x: Float = 0.5f,
    val y: Float = 0.5f,
    val centerX: Float = 0.5f,
    val centerY: Float = 0.5f,
    val outerRadiusFraction: Float = 0.46f,
    val innerRadiusRatio: Float = 0.55f,
    val speedPxPerSecond: Float = 0f,
    val angleDegrees: Float = 0f,
    val angularVelocityRadPerSecond: Float = 0f,
    val radiusFraction: Float = 0f,
    val confidence: Float = 0f,
    val fps: Float = 0f,
    val frameAspectRatioPortrait: Float = 9f / 16f,
    val trail: List<TrackPoint> = emptyList()
) {
    val directionLabel: String
        get() = when {
            angularVelocityRadPerSecond > 0.18f -> "CCW"
            angularVelocityRadPerSecond < -0.18f -> "CW"
            else -> "—"
        }
}
