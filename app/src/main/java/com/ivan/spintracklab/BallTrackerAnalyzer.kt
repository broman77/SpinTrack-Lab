package com.ivan.spintracklab

import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import java.util.ArrayDeque
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/**
 * Lightweight on-device tracker for a bright moving marker/ball on a TEST rotating disc.
 *
 * The analyzer deliberately stays local and deterministic: it reads only the camera luma plane,
 * searches an annulus around a user-calibrated centre, applies temporal candidate scoring and
 * estimates angle + angular velocity. It does not predict gambling outcomes.
 */
class BallTrackerAnalyzer(
    private val calibrationProvider: () -> DiscCalibration,
    private val configProvider: () -> TrackerConfig,
    private val onState: (TrackingState) -> Unit
) : ImageAnalysis.Analyzer {

    private var lastPoint: TrackPoint? = null
    private var lastAngleRad: Float? = null
    private var angularVelocitySmoothed = 0f
    private var speedSmoothed = 0f
    private val trail = ArrayDeque<TrackPoint>()
    private var fpsSmoothed = 0f
    private var lastFrameNs = 0L
    private var consecutiveHits = 0
    private var consecutiveMisses = 0
    private var calibrationRevision = -1

    override fun analyze(image: ImageProxy) {
        try {
            val yPlane = image.planes.firstOrNull() ?: return
            val buffer = yPlane.buffer
            val width = image.width
            val height = image.height
            val rowStride = yPlane.rowStride
            val pixelStride = yPlane.pixelStride
            val now = image.imageInfo.timestamp
            val rotation = image.imageInfo.rotationDegrees

            if (width <= 0 || height <= 0) return

            val calibration = calibrationProvider()
            val config = configProvider()
            if (calibration.revision != calibrationRevision) {
                resetMotion(calibration.revision)
            }

            updateFps(now)

            val rawCenter = portraitToRaw(
                calibration.centerX,
                calibration.centerY,
                rotation
            )
            val cx = rawCenter.first * width
            val cy = rawCenter.second * height
            val baseRadius = min(width, height).toFloat()
            val outer = baseRadius * config.outerRadiusFraction
            val inner = outer * config.innerRadiusRatio.coerceIn(0.25f, 0.82f)
            val inner2 = inner * inner
            val outer2 = outer * outer

            val previousRaw = lastPoint?.let {
                val p = portraitToRaw(it.x, it.y, rotation)
                Pair(p.first * width, p.second * height)
            }

            // Pass 1: luminance statistics inside the calibrated ring.
            val coarseStep = 4
            var maxY = 0
            var sampleCount = 0
            var mean = 0.0
            var yy = coarseStep
            while (yy < height - coarseStep) {
                var xx = coarseStep
                while (xx < width - coarseStep) {
                    val dx = xx - cx
                    val dy = yy - cy
                    val d2 = dx * dx + dy * dy
                    if (d2 in inner2..outer2) {
                        val index = yy * rowStride + xx * pixelStride
                        if (index in 0 until buffer.limit()) {
                            val lum = buffer.get(index).toInt() and 0xFF
                            maxY = max(maxY, lum)
                            mean += lum
                            sampleCount++
                        }
                    }
                    xx += coarseStep
                }
                yy += coarseStep
            }

            if (sampleCount == 0) {
                emitMiss(now, calibration, config, width, height, rotation)
                return
            }

            mean /= sampleCount
            val contrast = (maxY - mean).toFloat()
            val sensitivity = config.sensitivity.coerceIn(0f, 1f)
            val meanBoost = 54.0 - sensitivity * 36.0
            val maxDrop = 34.0 + sensitivity * 42.0
            val threshold = max(mean + meanBoost, maxY - maxDrop).coerceIn(125.0, 248.0)

            // Pass 2: choose one bright candidate. When already tracking, temporal proximity gets a
            // bonus so static reflections are less likely to steal the track.
            var bestScore = Double.NEGATIVE_INFINITY
            var bestX = -1
            var bestY = -1
            var candidateCount = 0
            yy = coarseStep
            while (yy < height - coarseStep) {
                var xx = coarseStep
                while (xx < width - coarseStep) {
                    val dx = xx - cx
                    val dy = yy - cy
                    val d2 = dx * dx + dy * dy
                    if (d2 in inner2..outer2) {
                        val index = yy * rowStride + xx * pixelStride
                        if (index in 0 until buffer.limit()) {
                            val lum = buffer.get(index).toInt() and 0xFF
                            if (lum >= threshold) {
                                candidateCount++
                                var score = lum.toDouble()
                                if (previousRaw != null && consecutiveMisses < 4) {
                                    val distance = hypot(
                                        (xx - previousRaw.first).toDouble(),
                                        (yy - previousRaw.second).toDouble()
                                    )
                                    val proximity = (1.0 - distance / (outer * 0.48f)).coerceIn(0.0, 1.0)
                                    score += proximity * 78.0
                                }
                                if (score > bestScore) {
                                    bestScore = score
                                    bestX = xx
                                    bestY = yy
                                }
                            }
                        }
                    }
                    xx += coarseStep
                }
                yy += coarseStep
            }

            if (bestX < 0 || candidateCount == 0) {
                emitMiss(now, calibration, config, width, height, rotation)
                return
            }

            // Pass 3: weighted local centroid around the chosen candidate.
            val localRadius = max(12f, outer * 0.105f)
            val localRadius2 = localRadius * localRadius
            val fineStep = 2
            var weightSum = 0.0
            var sumX = 0.0
            var sumY = 0.0
            var hits = 0

            val minX = max(fineStep, (bestX - localRadius).toInt())
            val maxX = min(width - fineStep - 1, (bestX + localRadius).toInt())
            val minY = max(fineStep, (bestY - localRadius).toInt())
            val maxYLocal = min(height - fineStep - 1, (bestY + localRadius).toInt())

            yy = minY
            while (yy <= maxYLocal) {
                var xx = minX
                while (xx <= maxX) {
                    val localDx = xx - bestX
                    val localDy = yy - bestY
                    if (localDx * localDx + localDy * localDy <= localRadius2) {
                        val ringDx = xx - cx
                        val ringDy = yy - cy
                        val ringD2 = ringDx * ringDx + ringDy * ringDy
                        if (ringD2 in inner2..outer2) {
                            val index = yy * rowStride + xx * pixelStride
                            if (index in 0 until buffer.limit()) {
                                val lum = buffer.get(index).toInt() and 0xFF
                                if (lum >= threshold) {
                                    val w = lum - threshold + 1.0
                                    weightSum += w
                                    sumX += xx * w
                                    sumY += yy * w
                                    hits++
                                }
                            }
                        }
                    }
                    xx += fineStep
                }
                yy += fineStep
            }

            if (weightSum <= 0.0 || hits < 2) {
                emitMiss(now, calibration, config, width, height, rotation)
                return
            }

            val rawX = (sumX / weightSum).toFloat()
            val rawY = (sumY / weightSum).toFloat()
            val normalizedPortrait = rawToPortrait(rawX / width, rawY / height, rotation)

            // A light coordinate low-pass filter reduces centroid shimmer without making tracking laggy.
            val previous = lastPoint
            val filterAlpha = if (previous == null || consecutiveMisses > 1) 1f else 0.42f
            val point = TrackPoint(
                x = lerp(previous?.x ?: normalizedPortrait.first, normalizedPortrait.first, filterAlpha),
                y = lerp(previous?.y ?: normalizedPortrait.second, normalizedPortrait.second, filterAlpha),
                timestampNs = now
            )

            val portraitWidth = if (rotation == 90 || rotation == 270) height else width
            val portraitHeight = if (rotation == 90 || rotation == 270) width else height
            val dxPx = (point.x - calibration.centerX) * portraitWidth
            val dyPx = (point.y - calibration.centerY) * portraitHeight
            val radiusPx = hypot(dxPx, dyPx)
            val angleRad = atan2(-dyPx, dxPx)
            val angleDegrees = normalizeDegrees(Math.toDegrees(angleRad.toDouble()).toFloat())

            val dt = previous?.let { (now - it.timestampNs) / 1_000_000_000f } ?: 0f
            var instantSpeed = 0f
            var instantAngularVelocity = 0f
            if (previous != null && dt in 0.005f..0.25f) {
                val prevDx = (point.x - previous.x) * portraitWidth
                val prevDy = (point.y - previous.y) * portraitHeight
                instantSpeed = hypot(prevDx, prevDy) / dt

                val priorAngle = lastAngleRad
                if (priorAngle != null) {
                    val delta = normalizeRadians(angleRad - priorAngle)
                    instantAngularVelocity = delta / dt
                    // Reject physically implausible frame-to-frame spikes from a false candidate.
                    if (kotlin.math.abs(instantAngularVelocity) > 55f) {
                        instantAngularVelocity = angularVelocitySmoothed
                    }
                }
            }

            speedSmoothed = if (speedSmoothed == 0f) instantSpeed else speedSmoothed * 0.72f + instantSpeed * 0.28f
            angularVelocitySmoothed = if (lastAngleRad == null) 0f else {
                angularVelocitySmoothed * 0.78f + instantAngularVelocity * 0.22f
            }

            lastPoint = point
            lastAngleRad = angleRad
            consecutiveHits++
            consecutiveMisses = 0

            trail.addLast(point)
            while (trail.size > 42) trail.removeFirst()
            while (trail.isNotEmpty() && now - trail.first().timestampNs > 1_600_000_000L) {
                trail.removeFirst()
            }

            val hitScore = (hits / 14f).coerceIn(0f, 1f)
            val contrastScore = (contrast / 85f).coerceIn(0f, 1f)
            val lockScore = (consecutiveHits / 8f).coerceIn(0.4f, 1f)
            val confidence = (0.42f * hitScore + 0.42f * contrastScore + 0.16f * lockScore).coerceIn(0f, 1f)
            val radiusFraction = (radiusPx / min(portraitWidth, portraitHeight).toFloat()).coerceIn(0f, 1f)

            onState(
                TrackingState(
                    detected = true,
                    locked = consecutiveHits >= 6,
                    x = point.x.coerceIn(0f, 1f),
                    y = point.y.coerceIn(0f, 1f),
                    centerX = calibration.centerX,
                    centerY = calibration.centerY,
                    outerRadiusFraction = config.outerRadiusFraction,
                    innerRadiusRatio = config.innerRadiusRatio,
                    speedPxPerSecond = speedSmoothed.coerceIn(0f, 12_000f),
                    angleDegrees = angleDegrees,
                    angularVelocityRadPerSecond = angularVelocitySmoothed.coerceIn(-55f, 55f),
                    radiusFraction = radiusFraction,
                    confidence = confidence,
                    fps = fpsSmoothed,
                    frameAspectRatioPortrait = portraitWidth.toFloat() / portraitHeight.toFloat(),
                    trail = if (config.showTrail) trail.toList() else emptyList()
                )
            )
        } finally {
            image.close()
        }
    }

    private fun emitMiss(
        now: Long,
        calibration: DiscCalibration,
        config: TrackerConfig,
        width: Int,
        height: Int,
        rotation: Int
    ) {
        consecutiveMisses++
        consecutiveHits = 0
        if (consecutiveMisses > 4) {
            lastPoint = null
            lastAngleRad = null
            speedSmoothed *= 0.65f
            angularVelocitySmoothed *= 0.65f
        }
        while (trail.isNotEmpty() && now - trail.first().timestampNs > 900_000_000L) {
            trail.removeFirst()
        }
        val portraitWidth = if (rotation == 90 || rotation == 270) height else width
        val portraitHeight = if (rotation == 90 || rotation == 270) width else height
        onState(
            TrackingState(
                detected = false,
                locked = false,
                centerX = calibration.centerX,
                centerY = calibration.centerY,
                outerRadiusFraction = config.outerRadiusFraction,
                innerRadiusRatio = config.innerRadiusRatio,
                angularVelocityRadPerSecond = angularVelocitySmoothed,
                speedPxPerSecond = speedSmoothed,
                fps = fpsSmoothed,
                frameAspectRatioPortrait = portraitWidth.toFloat() / portraitHeight.toFloat(),
                trail = if (config.showTrail) trail.toList() else emptyList()
            )
        )
    }

    private fun updateFps(now: Long) {
        val frameDt = if (lastFrameNs > 0L) (now - lastFrameNs) / 1_000_000_000f else 0f
        if (frameDt > 0.001f) {
            val instantFps = 1f / frameDt
            fpsSmoothed = if (fpsSmoothed == 0f) instantFps else fpsSmoothed * 0.9f + instantFps * 0.1f
        }
        lastFrameNs = now
    }

    private fun resetMotion(revision: Int) {
        calibrationRevision = revision
        lastPoint = null
        lastAngleRad = null
        angularVelocitySmoothed = 0f
        speedSmoothed = 0f
        consecutiveHits = 0
        consecutiveMisses = 0
        trail.clear()
    }

    private fun rawToPortrait(x: Float, y: Float, degrees: Int): Pair<Float, Float> = when (degrees) {
        90 -> Pair(1f - y, x)
        180 -> Pair(1f - x, 1f - y)
        270 -> Pair(y, 1f - x)
        else -> Pair(x, y)
    }

    private fun portraitToRaw(x: Float, y: Float, degrees: Int): Pair<Float, Float> = when (degrees) {
        90 -> Pair(y, 1f - x)
        180 -> Pair(1f - x, 1f - y)
        270 -> Pair(1f - y, x)
        else -> Pair(x, y)
    }

    private fun normalizeRadians(value: Float): Float {
        var result = value
        val twoPi = (2.0 * PI).toFloat()
        val pi = PI.toFloat()
        while (result > pi) result -= twoPi
        while (result < -pi) result += twoPi
        return result
    }

    private fun normalizeDegrees(value: Float): Float {
        var result = value % 360f
        if (result < 0f) result += 360f
        return result
    }

    private fun lerp(a: Float, b: Float, t: Float): Float = a + (b - a) * t
}
