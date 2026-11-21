package dev.precisionmeasure.app.measurement

import dev.precisionmeasure.geometry.CameraIntrinsics
import dev.precisionmeasure.geometry.CameraPose
import dev.precisionmeasure.geometry.GrayscaleImage

/**
 * Immutable snapshot of one camera keyframe used for multi-view sub-pixel tracking and triangulation.
 */
data class Keyframe(
    val id: Long,
    val timestampNs: Long,
    val grayscale: GrayscaleImage,
    val pose: CameraPose,
    val intrinsics: CameraIntrinsics,
)
