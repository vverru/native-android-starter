package com.vaultx.app.data.security

/**
 * SecurityVault: Anti-Reverse Engineering & Key Masking Engine.
 *
 * Protects Telegram API credentials against static decompilation (JADX, APKTool, strings).
 * Credentials are never stored as plaintext strings or direct integers in the bytecode.
 * They are mathematically scrambled using bitwise XOR entropy and multi-byte rotating keys.
 */
object SecurityVault {

    // Bit-masked API_ID entropy components: 36163421 (0x0227CF5D)
    private const val OBF_VAL: Long = 0x77085572L
    private const val MASK_A: Long = 0x2A18C3F0L
    private const val MASK_B: Long = 0x5F3759DFL

    // Rotating 8-byte XOR entropy vector
    private val ENTROPY_KEY = byteArrayOf(
        0x5A.toByte(), 0x8F.toByte(), 0x3C.toByte(), 0xD1.toByte(),
        0x7E.toByte(), 0x29.toByte(), 0xB4.toByte(), 0x16.toByte()
    )

    // Obfuscated cipher bytes for Telegram API_HASH: "6034451ec7692aa20b760443cda3c147"
    private val CIPHER_HASH_BYTES = byteArrayOf(
        0x6c.toByte(), 0xbf.toByte(), 0x0f.toByte(), 0xe5.toByte(),
        0x4a.toByte(), 0x1c.toByte(), 0x85.toByte(), 0x73.toByte(),
        0x39.toByte(), 0xb8.toByte(), 0x0a.toByte(), 0xe8.toByte(),
        0x4c.toByte(), 0x48.toByte(), 0xd5.toByte(), 0x24.toByte(),
        0x6a.toByte(), 0xed.toByte(), 0x0b.toByte(), 0xe7.toByte(),
        0x4e.toByte(), 0x1d.toByte(), 0x80.toByte(), 0x25.toByte(),
        0x39.toByte(), 0xeb.toByte(), 0x5d.toByte(), 0xe2.toByte(),
        0x1d.toByte(), 0x18.toByte(), 0x80.toByte(), 0x21.toByte()
    )

    /**
     * Resolves the Telegram API_ID in memory on the fly.
     * ZERO plaintext occurrence in bytecode.
     */
    fun resolveApiId(): Int {
        val unmasked = (OBF_VAL xor MASK_A xor MASK_B).toInt()
        return unmasked
    }

    /**
     * Reconstructs the 32-character Telegram API_HASH dynamically in RAM.
     * Intermediate buffers are unmasked only for the duration of the call.
     */
    fun resolveApiHash(): String {
        val out = CharArray(CIPHER_HASH_BYTES.size)
        val keyLen = ENTROPY_KEY.size
        for (i in CIPHER_HASH_BYTES.indices) {
            val k = ENTROPY_KEY[i % keyLen].toInt() and 0xFF
            val b = CIPHER_HASH_BYTES[i].toInt() and 0xFF
            out[i] = (b xor k).toChar()
        }
        return String(out)
    }
}
