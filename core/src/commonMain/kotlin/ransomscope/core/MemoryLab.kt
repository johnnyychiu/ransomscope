package ransomscope.core

import kotlin.random.Random

/** Synthetic educational laboratory: never reads operating-system process memory. */
object MemoryLab {
    fun run(seed: Int = 7, variant: String = "present"): MemoryResult {
        require(variant in setOf("present", "erased", "raw-only", "corrupt")) { "Unsupported fixture variant." }
        val random = Random(seed)
        val key = random.nextBytes(16)
        val memory = random.nextBytes(8_192)
        val offset = random.nextInt(128, memory.size - 176)
        val schedule = Aes128.expandKey(key)
        when (variant) {
            "present" -> schedule.copyInto(memory, offset)
            "erased" -> ByteArray(176).copyInto(memory, offset)
            "raw-only" -> key.copyInto(memory, offset)
            "corrupt" -> {
                schedule.copyInto(memory, offset)
                memory[offset + 93] = (memory[offset + 93].toInt() xor 1).toByte()
            }
        }
        // The scanner receives bytes only: no fixture offset, seed, expected key, or marker.
        val candidates = findExpandedKeys(memory)
        val knownPlaintext = "RansomScope demo".encodeToByteArray()
        val ciphertext = Aes128.encryptBlock(knownPlaintext, key)
        val verified = candidates.filter { Aes128.encryptBlock(knownPlaintext, it).contentEquals(ciphertext) }
        return MemoryResult(
            status = if (verified.isNotEmpty()) "verified" else "not-found",
            candidates = candidates.size,
            verified = verified.size,
            scannedBytes = memory.size,
            detail = if (verified.isNotEmpty()) {
                "Synthetic fixture only: AES-128 expanded-key structure matched and the candidate reproduced the encrypted sample block. This is not live RAM capture or ransomware validation."
            } else {
                "No sample-verified AES-128 expanded schedule found in this synthetic '$variant' fixture. Raw random keys have no universal recognizable signature; other recovery methods may still exist."
            },
            keyPreview = verified.firstOrNull()?.let { "${hex(it).take(4)}… (synthetic; hidden)" } ?: "No verified candidate",
        )
    }

    /** Finds the standard 176-byte AES-128 forward expansion layout, not arbitrary cipher layouts. */
    fun findExpandedKeys(memory: ByteArray): List<ByteArray> {
        require(memory.size <= 1_048_576) { "Synthetic scan is limited to 1 MiB." }
        if (memory.size < 176) return emptyList()
        val keys = mutableListOf<ByteArray>()
        for (offset in 0..memory.size - 176) {
            // A first-word check avoids expensive expansion at most noise offsets.
            var firstWordMatches = true
            for (column in 0..3) {
                val rotated = memory[offset + 12 + (column + 1) % 4].toInt() and 0xff
                val expected = (memory[offset + column].toInt() and 0xff) xor Aes128.substitute(rotated) xor
                    if (column == 0) 1 else 0
                if (expected != (memory[offset + 16 + column].toInt() and 0xff)) {
                    firstWordMatches = false
                    break
                }
            }
            if (!firstWordMatches) continue
            val candidate = memory.copyOfRange(offset, offset + 16)
            val expanded = Aes128.expandKey(candidate)
            if (expanded.indices.all { expanded[it] == memory[offset + it] } &&
                keys.none { it.contentEquals(candidate) }) keys += candidate
        }
        return keys
    }

    fun hex(bytes: ByteArray): String = bytes.joinToString("") {
        (it.toInt() and 0xff).toString(16).padStart(2, '0')
    }
}

/** Small reference implementation for public synthetic test blocks, not production encryption. */
object Aes128 {
    private val sBox = IntArray(256) { value ->
        val inverse = if (value == 0) 0 else power(value, 254)
        inverse xor rotate(inverse, 1) xor rotate(inverse, 2) xor rotate(inverse, 3) xor rotate(inverse, 4) xor 0x63
    }

    internal fun substitute(value: Int): Int = sBox[value and 0xff]

    fun expandKey(key: ByteArray): ByteArray {
        require(key.size == 16) { "This lab supports AES-128 keys only." }
        val expanded = ByteArray(176)
        key.copyInto(expanded)
        var count = 16
        var rcon = 1
        val temp = IntArray(4)
        while (count < expanded.size) {
            for (i in 0..3) temp[i] = expanded[count - 4 + i].toInt() and 0xff
            if (count % 16 == 0) {
                val first = temp[0]
                temp[0] = substitute(temp[1]) xor rcon
                temp[1] = substitute(temp[2])
                temp[2] = substitute(temp[3])
                temp[3] = substitute(first)
                rcon = multiply(rcon, 2)
            }
            for (i in 0..3) {
                expanded[count] = (expanded[count - 16].toInt() xor temp[i]).toByte()
                count++
            }
        }
        return expanded
    }

    fun encryptBlock(block: ByteArray, key: ByteArray): ByteArray {
        require(block.size == 16) { "Expected one 16-byte sample block." }
        val expanded = expandKey(key)
        var state = IntArray(16) { (block[it].toInt() xor expanded[it].toInt()) and 0xff }
        for (round in 1..10) {
            state = IntArray(16) { index ->
                val row = index % 4
                val column = index / 4
                substitute(state[((column + row) % 4) * 4 + row])
            }
            if (round != 10) {
                val mixed = IntArray(16)
                for (column in 0..3) {
                    val offset = column * 4
                    val a = state.copyOfRange(offset, offset + 4)
                    mixed[offset] = multiply(a[0], 2) xor multiply(a[1], 3) xor a[2] xor a[3]
                    mixed[offset + 1] = a[0] xor multiply(a[1], 2) xor multiply(a[2], 3) xor a[3]
                    mixed[offset + 2] = a[0] xor a[1] xor multiply(a[2], 2) xor multiply(a[3], 3)
                    mixed[offset + 3] = multiply(a[0], 3) xor a[1] xor a[2] xor multiply(a[3], 2)
                }
                state = mixed
            }
            for (i in state.indices) state[i] = state[i] xor (expanded[round * 16 + i].toInt() and 0xff)
        }
        return ByteArray(16) { state[it].toByte() }
    }

    private fun rotate(value: Int, bits: Int): Int = ((value shl bits) or (value ushr (8 - bits))) and 0xff
    private fun power(value: Int, exponent: Int): Int {
        var result = 1
        var base = value
        var remaining = exponent
        while (remaining > 0) {
            if ((remaining and 1) != 0) result = multiply(result, base)
            base = multiply(base, base)
            remaining = remaining ushr 1
        }
        return result
    }
    private fun multiply(a: Int, b: Int): Int {
        var left = a
        var right = b
        var result = 0
        while (right > 0) {
            if ((right and 1) != 0) result = result xor left
            left = ((left shl 1) xor if ((left and 0x80) != 0) 0x11b else 0) and 0xff
            right = right ushr 1
        }
        return result
    }
}
