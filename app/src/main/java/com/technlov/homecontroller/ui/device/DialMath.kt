package com.technlov.homecontroller.ui.device

import kotlin.math.atan2
import kotlin.math.roundToInt

/** The dial's arc starts at the bottom left (135°, clockwise from 3 o'clock) and sweeps 270°. */
internal const val DIAL_START_ANGLE = 135f
internal const val DIAL_SWEEP = 270f

/** Snaps [raw] to the dial's [step] inside [min]..[max]. */
internal fun snapToStep(raw: Double, min: Double, max: Double, step: Double): Double {
    if (step <= 0) return raw.coerceIn(min, max)
    val snapped = min + ((raw - min) / step).roundToInt() * step
    return snapped.coerceIn(min, max)
}

/**
 * The dial value for a touch at ([x], [y]) relative to the centre (y grows downwards): the angle along the arc,
 * snapped to [step]. Touches in the gap at the bottom go to the nearer end.
 */
internal fun dialValueAt(x: Float, y: Float, min: Double, max: Double, step: Double): Double {
    val angle = Math.toDegrees(atan2(y.toDouble(), x.toDouble()))
    var along = (angle - DIAL_START_ANGLE + 720.0) % 360.0
    if (along > DIAL_SWEEP) along = if (along < DIAL_SWEEP + (360.0 - DIAL_SWEEP) / 2) DIAL_SWEEP.toDouble() else 0.0
    return snapToStep(min + along / DIAL_SWEEP * (max - min), min, max, step)
}
