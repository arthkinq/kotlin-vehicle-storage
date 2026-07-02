package utils

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*

class PasswordHasherTest {

    @Test
    fun testHashPassword() {
        val password = "mySecretPassword123"
        val hash1 = PasswordHasher.hashPassword(password)
        val hash2 = PasswordHasher.hashPassword(password)

        assertNotNull(hash1)
        assertFalse(hash1.isBlank())
        
        assertEquals(hash1, hash2, "Hashes of the same password must match")
    }

    @Test
    fun testDifferentPasswordsHaveDifferentHashes() {
        val hash1 = PasswordHasher.hashPassword("passwordA")
        val hash2 = PasswordHasher.hashPassword("passwordB")
        
        assertNotEquals(hash1, hash2, "Hashes of different passwords must not match")
    }
}
