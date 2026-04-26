package com.example.security

import de.mkammerer.argon2.Argon2Factory

class PasswordHasher {
    private val argon2 = Argon2Factory.create()

    fun hash(password: String): String {
        return argon2.hash(3, 64 * 1024, 2, password)
    }

    fun verify(password: String, hash: String): Boolean {
        return argon2.verify(hash, password)
    }
}
