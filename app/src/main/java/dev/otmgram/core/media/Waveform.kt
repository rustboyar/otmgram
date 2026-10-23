package dev.otmgram.core.media

/** Voice message waveforms: volume levels 0..31, packed by Telegram as 5-bit values. */
object Waveform {
    const val MAX_LEVEL = 31

    fun decode(data: ByteArray?): List<Int> {
        if (data == null) return emptyList()
        val count = data.size * 8 / 5
        return List(count) { i ->
            val bit = i * 5
            val low = data[bit / 8].toInt() and 0xFF
            val high = (data.getOrNull(bit / 8 + 1)?.toInt() ?: 0) and 0xFF
            ((low or (high shl 8)) ushr (bit % 8)) and MAX_LEVEL
        }
    }

    fun encode(levels: List<Int>): ByteArray {
        val bytes = ByteArray((levels.size * 5 + 7) / 8)
        levels.forEachIndexed { i, level ->
            val bit = i * 5
            val value = (level.coerceIn(0, MAX_LEVEL)) shl (bit % 8)
            bytes[bit / 8] = (bytes[bit / 8].toInt() or (value and 0xFF)).toByte()
            if (value > 0xFF) bytes[bit / 8 + 1] = (bytes[bit / 8 + 1].toInt() or (value ushr 8)).toByte()
        }
        return bytes
    }
}
