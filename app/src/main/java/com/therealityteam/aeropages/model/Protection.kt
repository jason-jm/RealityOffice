package com.therealityteam.aeropages.model

import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64

/**
 * A password kept as its hash, as the files keep it: Excel's [SHA512] (UTF-16LE password after a [salt], hashed again
 * [spinCount] times — xlsx `algorithmName`/`hashValue`/`saltValue`/`spinCount`), Excel's old 16-bit [LEGACY] verifier
 * (xlsx `password`, hex) or ODF's unsalted [SHA256] / [SHA1] of the UTF-8 password (`table:protection-key`). [value] and
 * [salt] are Base64 (hex for [LEGACY]).
 */
data class ProtectionHash(val algorithm: String, val value: String, val salt: String? = null, val spinCount: Int = 0) {
    /** Whether xlsx can hold it (Excel's own schemes) — ODF's unsalted digests of UTF-8 cannot be written there, nor the other way round. */
    val forXlsx: Boolean get() = algorithm == LEGACY || salt != null
    val forOdf: Boolean get() = salt == null && (algorithm == SHA256 || algorithm == SHA1)

    companion object {
        const val SHA512 = "SHA-512"
        const val SHA256 = "SHA-256"
        const val SHA1 = "SHA-1"
        const val LEGACY = "XOR"
    }
}

/**
 * Excel's Protect Sheet options — "Allow all users of this worksheet to": select locked / unlocked cells, format cells,
 * columns and rows, insert columns, rows and hyperlinks, delete columns and rows, sort, use AutoFilter and PivotTables,
 * edit objects and scenarios — and the password to unprotect it ([passwords]: the same password kept for each format).
 */
data class SheetProtection(
    val selectLocked: Boolean = true,
    val selectUnlocked: Boolean = true,
    val formatCells: Boolean = false,
    val formatColumns: Boolean = false,
    val formatRows: Boolean = false,
    val insertColumns: Boolean = false,
    val insertRows: Boolean = false,
    val insertHyperlinks: Boolean = false,
    val deleteColumns: Boolean = false,
    val deleteRows: Boolean = false,
    val sort: Boolean = false,
    val autoFilter: Boolean = false,
    val pivotTables: Boolean = false,
    val editObjects: Boolean = false,
    val editScenarios: Boolean = false,
    val passwords: List<ProtectionHash> = emptyList(),
) {
    val hasPassword: Boolean get() = passwords.isNotEmpty()
}

/** Making and checking protection passwords the way Excel and LibreOffice do. */
object Passwords {
    /** The spin count Excel 2013 and later use. */
    const val SPIN = 100_000

    /** The hashes kept for a new [password]: Excel's salted SHA-512 for xlsx and ODF's SHA-256 for ODS. */
    fun create(password: String, random: SecureRandom = SecureRandom()): List<ProtectionHash> {
        val salt = ByteArray(16).also { random.nextBytes(it) }
        return listOf(
            ProtectionHash(ProtectionHash.SHA512, excel(password, ProtectionHash.SHA512, salt, SPIN), b64(salt), SPIN),
            ProtectionHash(ProtectionHash.SHA256, odf(password, ProtectionHash.SHA256)),
        )
    }

    /** Whether [password] is the one [hashes] keep (any of them that can be checked); no hashes: no password, anything goes. */
    fun matches(hashes: List<ProtectionHash>, password: String): Boolean {
        if (hashes.isEmpty()) return true
        return hashes.any { h ->
            runCatching {
                when {
                    h.algorithm == ProtectionHash.LEGACY -> h.value.toInt(16) == xorVerifier(password)
                    h.salt != null -> excel(password, h.algorithm, Base64.getDecoder().decode(h.salt), h.spinCount) == h.value
                    // ODF keeps a digest of the UTF-8 password; some writers hashed UTF-16 instead.
                    else -> odf(password, h.algorithm) == h.value ||
                        b64(MessageDigest.getInstance(h.algorithm).digest(password.toByteArray(Charsets.UTF_16LE))) == h.value
                }
            }.getOrDefault(false)
        }
    }

    /** Excel's iterated hash: H(salt + UTF-16LE password), then [spin] times H(hash + iteration as 4 bytes little-endian); Base64. */
    fun excel(password: String, algorithm: String, salt: ByteArray, spin: Int): String {
        val md = MessageDigest.getInstance(algorithm)
        md.update(salt)
        var hash = md.digest(password.toByteArray(Charsets.UTF_16LE))
        val counter = ByteArray(4)
        for (i in 0 until spin) {
            counter[0] = i.toByte(); counter[1] = (i shr 8).toByte(); counter[2] = (i shr 16).toByte(); counter[3] = (i shr 24).toByte()
            md.update(hash); hash = md.digest(counter)
        }
        return b64(hash)
    }

    /** ODF's `table:protection-key`: the digest of the UTF-8 password, Base64. */
    fun odf(password: String, algorithm: String): String =
        b64(MessageDigest.getInstance(algorithm).digest(password.toByteArray(Charsets.UTF_8)))

    /**
     * Excel's old 16-bit password verifier (xlsx `password`, the .xls sheet protection): each byte of the password from
     * the last, the hash rotated left within 15 bits and XORed with it, then with the length and 0xCE4B.
     */
    fun xorVerifier(password: String): Int {
        if (password.isEmpty()) return 0
        val bytes = password.map { (it.code and 0xFF).let { b -> if (b == 0) (it.code shr 8) and 0xFF else b } }
        fun rotate(v: Int) = ((v shr 14) and 1) or ((v shl 1) and 0x7FFF)
        var v = 0
        for (i in bytes.indices.reversed()) { v = rotate(v); v = v xor bytes[i] }
        v = rotate(v)
        v = v xor bytes.size
        v = v xor 0xCE4B
        return v and 0xFFFF
    }

    /** The ODF digest algorithm URI for [algorithm], and back. */
    fun odfUri(algorithm: String): String = when (algorithm) {
        ProtectionHash.SHA1 -> "http://www.w3.org/2000/09/xmldsig#sha1"
        else -> "http://www.w3.org/2000/09/xmldsig#sha256"   // as LibreOffice writes it (xmlenc#sha256 is read too)
    }

    fun fromOdfUri(uri: String?): String = when {
        uri == null || uri.endsWith("sha1") -> ProtectionHash.SHA1
        uri.endsWith("sha256") -> ProtectionHash.SHA256
        uri.endsWith("sha512") -> ProtectionHash.SHA512
        else -> ProtectionHash.SHA256
    }

    private fun b64(bytes: ByteArray): String = Base64.getEncoder().encodeToString(bytes)
}
