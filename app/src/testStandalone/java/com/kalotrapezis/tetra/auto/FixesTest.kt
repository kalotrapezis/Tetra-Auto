package com.kalotrapezis.tetra.auto

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

class FixesTest {
    private fun fix(acc: Float, at: Long, phone: Boolean, speed: Float = 0f, bearing: Float = Float.NaN) = Fix(38.0, 23.7, acc, speed, bearing, at, phone)

    @Test fun bestTakesTheMoreAccurateFreshFix() {
        val car = fix(12f, 9_500, false); val phone = fix(5f, 9_800, true)
        assertEquals(5f, pickFix(car, phone, 10_000, GpsSource.Best)!!.accuracy)
    }

    @Test fun aTieGoesToTheCar() {
        val car = fix(8f, 9_500, false)
        assertSame(car, pickFix(car, fix(8f, 9_900, true), 10_000, GpsSource.Best))
    }

    @Test fun aStaleFixLosesToAFreshOne() {
        val car = fix(3f, 7_000, false); val phone = fix(20f, 9_900, true) // the car's is 3 s old
        assertEquals(true, pickFix(car, phone, 10_000, GpsSource.Best)!!.phone)
    }

    @Test fun neitherFreshShowsTheNewer() {
        val car = fix(3f, 1_000, false); val phone = fix(20f, 5_000, true)
        assertEquals(true, pickFix(car, phone, 10_000, GpsSource.Best)!!.phone)
        assertNull(pickFix(null, null, 10_000, GpsSource.Best))
    }

    @Test fun aChosenSourceIsKept() {
        val car = fix(30f, 9_900, false); val phone = fix(3f, 9_900, true)
        assertSame(car, pickFix(car, phone, 10_000, GpsSource.Car))
        assertEquals(true, pickFix(car, phone, 10_000, GpsSource.Phone)!!.phone)
    }

    @Test fun aMovingPhoneFixIsMovedOn() {
        // 30 m/s due north, 1 s old: about 30 m (0.00027°) further north
        val moved = ahead(fix(5f, 9_000, true, speed = 30f, bearing = 0f), 10_000)
        assertEquals(38.0 + 30 / 111_320.0, moved.lat, 1e-7)
        assertEquals(23.7, moved.lng, 1e-9)
    }
}
