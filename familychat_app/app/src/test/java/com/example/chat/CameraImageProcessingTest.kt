package com.example.chat

import android.view.Surface
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CameraImageProcessingTest {
    @Test
    fun deviceOrientationUsesSurfaceRotationConstants() {
        assertEquals(Surface.ROTATION_0, rotationFromOrientation(0))
        assertEquals(Surface.ROTATION_270, rotationFromOrientation(90))
        assertEquals(Surface.ROTATION_180, rotationFromOrientation(180))
        assertEquals(Surface.ROTATION_90, rotationFromOrientation(270))
        assertEquals(null, rotationFromOrientation(-1))
    }

    @Test
    fun degreeValuesAreNormalizedBeforeCameraXReceivesThem() {
        assertEquals(Surface.ROTATION_0, normalizeSurfaceRotation(0))
        assertEquals(Surface.ROTATION_90, normalizeSurfaceRotation(90))
        assertEquals(Surface.ROTATION_180, normalizeSurfaceRotation(180))
        assertEquals(Surface.ROTATION_270, normalizeSurfaceRotation(270))
        assertEquals(Surface.ROTATION_0, normalizeSurfaceRotation(999))
    }

    @Test
    fun largeLandscapePhotoIsDecodedWithinTarget() {
        val sample = calculateBitmapSampleSize(
            width = 8192,
            height = 6144,
            targetSizePx = 2048,
        )

        assertEquals(4, sample)
        assertTrue(maxOf(8192, 6144) / sample <= 2048)
    }

    @Test
    fun portraitPhotoUsesTheLongestSide() {
        val sample = calculateBitmapSampleSize(
            width = 3000,
            height = 12000,
            targetSizePx = 2048,
        )

        assertEquals(8, sample)
        assertTrue(maxOf(3000, 12000) / sample <= 2048)
    }

    @Test
    fun smallPhotoIsNotDownsampled() {
        assertEquals(
            1,
            calculateBitmapSampleSize(
                width = 1280,
                height = 720,
                targetSizePx = 2048,
            ),
        )
    }
}
