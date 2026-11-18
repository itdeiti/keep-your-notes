package app.notizen

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AppLockTest {
    @Test fun pinRules() {
        assertTrue(AppLock.validPin("1234"))
        assertTrue(AppLock.validPin("12345678"))
        assertFalse(AppLock.validPin("123"))
        assertFalse(AppLock.validPin("123456789"))
        assertFalse(AppLock.validPin("12a4"))
    }

    @Test fun hashDependsOnPinAndSalt() {
        val salt = ByteArray(16) { it.toByte() }
        val h = AppLock.hash("1234", salt, iter = 1000)
        assertArrayEquals(h, AppLock.hash("1234", salt, iter = 1000))
        assertFalse(h.contentEquals(AppLock.hash("1235", salt, iter = 1000)))
        assertFalse(h.contentEquals(AppLock.hash("1234", ByteArray(16), iter = 1000)))
    }

    @Test fun penaltyStartsAtFifthFailureAndDoublesUpToOneHour() {
        assertEquals(0L, AppLock.penaltyMs(4))
        assertEquals(30_000L, AppLock.penaltyMs(5))
        assertEquals(60_000L, AppLock.penaltyMs(6))
        assertEquals(3_600_000L, AppLock.penaltyMs(12))
        assertEquals(3_600_000L, AppLock.penaltyMs(100))
    }
}
