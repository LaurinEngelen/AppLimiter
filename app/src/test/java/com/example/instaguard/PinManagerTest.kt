package com.example.instaguard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class PinManagerTest {

    private lateinit var fakePrefs: FakeSharedPreferences

    @Before
    fun setUp() {
        fakePrefs = FakeSharedPreferences()
    }

    @Test
    fun testPinValidation() {
        assertTrue(PinManager.isValidPin("1234"))
        assertTrue(PinManager.isValidPin("0000"))
        assertTrue(PinManager.isValidPin("9999"))

        assertFalse(PinManager.isValidPin(""))
        assertFalse(PinManager.isValidPin("123"))
        assertFalse(PinManager.isValidPin("12345"))
        assertFalse(PinManager.isValidPin("12a4"))
        assertFalse(PinManager.isValidPin("abcd"))
    }

    @Test
    fun testSetAndVerifyPin() {
        assertFalse(PinManager.isPinEnabled(fakePrefs))

        // Set valid PIN
        val success = PinManager.setPin(fakePrefs, "4321")
        assertTrue(success)
        assertTrue(PinManager.isPinEnabled(fakePrefs))

        // Verify correct PIN
        assertTrue(PinManager.verifyPin(fakePrefs, "4321"))

        // Reject wrong PIN
        assertFalse(PinManager.verifyPin(fakePrefs, "1234"))
        assertFalse(PinManager.verifyPin(fakePrefs, "0000"))
        assertFalse(PinManager.verifyPin(fakePrefs, "432"))
    }

    @Test
    fun testDisablePin() {
        PinManager.setPin(fakePrefs, "8888")
        assertTrue(PinManager.isPinEnabled(fakePrefs))

        PinManager.disablePin(fakePrefs)
        assertFalse(PinManager.isPinEnabled(fakePrefs))
        assertFalse(PinManager.verifyPin(fakePrefs, "8888"))
    }

    @Test
    fun testPinIsNotStoredInPlaintext() {
        PinManager.setPin(fakePrefs, "5678")
        val storedHash = fakePrefs.getString("app_pin_hash", null)
        assertNotEquals("5678", storedHash)
        assertEquals(64, storedHash?.length) // SHA-256 hex string is 64 characters
    }
}
