package utils

import org.apache.commons.codec.digest.DigestUtils

object PasswordHasher {
    fun hashPassword(password: String): String {
        return DigestUtils.sha512Hex(password)
    }
}