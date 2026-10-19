package com.vista.photoeditor.ui.components

import com.vista.photoeditor.shareType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class VideoLabelsTest {

    private val day = 24 * 60 * 60 * 1000L

    @Test
    fun shortDuration() = assertEquals("0:42", DateLabels.duration(42_000))

    @Test
    fun durationRoundsToNearestSecond() {
        assertEquals("0:01", DateLabels.duration(600))
        assertEquals("0:00", DateLabels.duration(400))
    }

    @Test
    fun minutesArePadded() = assertEquals("12:05", DateLabels.duration((12 * 60 + 5) * 1000L))

    @Test
    fun hoursAppearOnlyWhenNeeded() = assertEquals("1:02:33", DateLabels.duration((3600 + 2 * 60 + 33) * 1000L))

    @Test
    fun negativeDurationIsZero() = assertEquals("0:00", DateLabels.duration(-5_000))

    @Test
    fun fullDaysLeft() = assertEquals(30, DateLabels.daysLeft(expiresMillis = 30 * day, nowMillis = 0))

    @Test
    fun startedDayCounts() = assertEquals(2, DateLabels.daysLeft(expiresMillis = day + 1, nowMillis = 0))

    @Test
    fun lastHoursStillShowOneDay() {
        assertEquals(1, DateLabels.daysLeft(expiresMillis = 1000, nowMillis = 0))
        // Date dépassée, suppression pas encore faite par Android.
        assertEquals(1, DateLabels.daysLeft(expiresMillis = 1000, nowMillis = 5000))
    }

    @Test
    fun noExpiryDate() = assertNull(DateLabels.daysLeft(expiresMillis = 0, nowMillis = 0))

    @Test
    fun shareTypeOfPhotosOnly() = assertEquals("image/*", shareType(listOf("image/*", "image/*")))

    @Test
    fun shareTypeOfVideosOnly() = assertEquals("video/*", shareType(listOf("video/*")))

    @Test
    fun shareTypeOfMixedSelection() = assertEquals("*/*", shareType(listOf("image/*", "video/*")))
}
