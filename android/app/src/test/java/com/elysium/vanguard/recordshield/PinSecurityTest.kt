package com.elysium.vanguard.recordshield

import com.elysium.vanguard.recordshield.data.local.PinSecurity
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class PinSecurityTest {

    @Test
    fun `generateSalt produces 64 character hex string`() {
        val salt = PinSecurity.generateSalt()
        assertEquals(64, salt.length)
        assertTrue(salt.all { it in '0'..'9' || it in 'a'..'f' })
    }

    @Test
    fun `generateSalt produces unique salts`() {
        val salt1 = PinSecurity.generateSalt()
        val salt2 = PinSecurity.generateSalt()
        assertNotEquals(salt1, salt2)
    }

    @Test
    fun `hashPin produces consistent hash for same input`() {
        val salt = PinSecurity.generateSalt()
        val hash1 = PinSecurity.hashPin("123456", salt)
        val hash2 = PinSecurity.hashPin("123456", salt)
        assertEquals(hash1, hash2)
    }

    @Test
    fun `hashPin produces different hashes for different PINs`() {
        val salt = PinSecurity.generateSalt()
        val hash1 = PinSecurity.hashPin("123456", salt)
        val hash2 = PinSecurity.hashPin("654321", salt)
        assertNotEquals(hash1, hash2)
    }

    @Test
    fun `hashPin produces different hashes for different salts`() {
        val salt1 = PinSecurity.generateSalt()
        val salt2 = PinSecurity.generateSalt()
        val hash1 = PinSecurity.hashPin("123456", salt1)
        val hash2 = PinSecurity.hashPin("123456", salt2)
        assertNotEquals(hash1, hash2)
    }

    @Test
    fun `verifyPin returns true for correct PIN`() {
        val salt = PinSecurity.generateSalt()
        val hash = PinSecurity.hashPin("123456", salt)
        assertTrue(PinSecurity.verifyPin("123456", salt, hash))
    }

    @Test
    fun `verifyPin returns false for incorrect PIN`() {
        val salt = PinSecurity.generateSalt()
        val hash = PinSecurity.hashPin("123456", salt)
        assertFalse(PinSecurity.verifyPin("654321", salt, hash))
    }

    @Test
    fun `verifyPin returns false for empty PIN`() {
        val salt = PinSecurity.generateSalt()
        val hash = PinSecurity.hashPin("123456", salt)
        assertFalse(PinSecurity.verifyPin("", salt, hash))
    }

    @Test
    fun `hash produces 64 character hex string`() {
        val salt = PinSecurity.generateSalt()
        val hash = PinSecurity.hashPin("123456", salt)
        assertEquals(64, hash.length)
        assertTrue(hash.all { it in '0'..'9' || it in 'a'..'f' })
    }

    @Test
    fun `PIN 000000 works correctly`() {
        val salt = PinSecurity.generateSalt()
        val hash = PinSecurity.hashPin("000000", salt)
        assertTrue(PinSecurity.verifyPin("000000", salt, hash))
        assertFalse(PinSecurity.verifyPin("000001", salt, hash))
    }

    @Test
    fun `PIN 999999 works correctly`() {
        val salt = PinSecurity.generateSalt()
        val hash = PinSecurity.hashPin("999999", salt)
        assertTrue(PinSecurity.verifyPin("999999", salt, hash))
    }
}
