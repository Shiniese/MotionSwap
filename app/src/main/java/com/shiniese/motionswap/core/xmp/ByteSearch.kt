package com.shiniese.motionswap.core.xmp

/**
 * A half-open byte interval `[start, endExclusive)`.
 *
 * Kotlin's `IntRange` is closed at both ends, which makes off-by-one slicing bugs easy
 * to introduce when converting from file offsets. This type matches `copyOfRange` and
 * `Data.subdata` semantics directly.
 */
data class ByteRange(val start: Int, val endExclusive: Int) {
    val length: Int get() = endExclusive - start

    init {
        require(start >= 0 && endExclusive >= start) { "invalid range $start..$endExclusive" }
    }
}

/** Byte-level primitives shared by the XMP and MP4 scanners. */
internal object ByteSearch {

    fun indexOf(haystack: ByteArray, needle: ByteArray, fromIndex: Int = 0): Int {
        if (needle.isEmpty()) return fromIndex.coerceIn(0, haystack.size)
        if (fromIndex < 0) return -1
        val last = haystack.size - needle.size
        if (last < 0) return -1
        outer@ for (i in fromIndex..last) {
            for (j in needle.indices) {
                if (haystack[i + j] != needle[j]) continue@outer
            }
            return i
        }
        return -1
    }

    fun indexOf(haystack: ByteArray, byte: Byte, fromIndex: Int = 0): Int {
        var i = fromIndex.coerceAtLeast(0)
        while (i < haystack.size) {
            if (haystack[i] == byte) return i
            i++
        }
        return -1
    }

    fun startsWith(haystack: ByteArray, prefix: ByteArray, at: Int): Boolean {
        if (at < 0 || at + prefix.size > haystack.size) return false
        for (j in prefix.indices) {
            if (haystack[at + j] != prefix[j]) return false
        }
        return true
    }

    fun contains(haystack: ByteArray, needle: ByteArray): Boolean =
        indexOf(haystack, needle) >= 0

    /** Reads a big-endian UInt32 as a Long to stay clear of signed-int overflow. */
    fun readUInt32BE(data: ByteArray, offset: Int): Long {
        require(offset >= 0 && offset + 4 <= data.size) { "cannot read 4 bytes at $offset" }
        return ((data[offset].toLong() and 0xFF) shl 24) or
            ((data[offset + 1].toLong() and 0xFF) shl 16) or
            ((data[offset + 2].toLong() and 0xFF) shl 8) or
            (data[offset + 3].toLong() and 0xFF)
    }

    /** Reads a big-endian UInt16 as an Int; the width of a JPEG segment length field. */
    fun readUInt16BE(data: ByteArray, offset: Int): Int {
        require(offset >= 0 && offset + 2 <= data.size) { "cannot read 2 bytes at $offset" }
        return ((data[offset].toInt() and 0xFF) shl 8) or (data[offset + 1].toInt() and 0xFF)
    }

    }

/** Small stream helpers; deliberately avoids loading whole motion photos into memory. */
internal object StreamIo {

    fun skipFully(input: java.io.InputStream, count: Long): Long {
        var remaining = count
        while (remaining > 0) {
            val skipped = input.skip(remaining)
            if (skipped > 0) {
                remaining -= skipped
                continue
            }
            if (input.read() == -1) return count - remaining
            remaining--
        }
        return count
    }

    /** Reads exactly [buffer].size bytes, or returns however many were actually read. */
    fun readFully(input: java.io.InputStream, buffer: ByteArray): Long {
        var total = 0
        while (total < buffer.size) {
            val read = input.read(buffer, total, buffer.size - total)
            if (read == -1) break
            total += read
        }
        return total.toLong()
    }

    fun copyRange(input: java.io.InputStream, output: java.io.OutputStream, length: Long) {
        val buffer = ByteArray(COPY_BUFFER)
        var remaining = length
        while (remaining > 0) {
            val toRead = minOf(buffer.size.toLong(), remaining).toInt()
            val read = input.read(buffer, 0, toRead)
            if (read == -1) break
            output.write(buffer, 0, read)
            remaining -= read
        }
    }

    /**
     * Copies at most [length] bytes, stopping right after the first `FF D9` it sees.
     *
     * That marker is the JPEG's own end of image, and it has to be found by scanning
     * forward rather than by searching backwards: several vendors put a blob of their own
     * between the image and the video they appended (`Pixel.jpg` has 32,364 bytes of
     * `aecDebug` data there, `Samsung.jpg` has 138), and the clip itself contains `FF D9`
     * pairs inside its compressed data. A backwards search finds one of those and cuts tens
     * of megabytes too early.
     *
     * Scanning forward from the start of the entropy-coded data is safe because `FF D9`
     * cannot occur there legitimately — entropy data escapes `FF` as `FF 00` or uses a
     * restart marker `D0`-`D7`, and `D9` is neither.
     */
    fun copyRangeUntilEoi(input: java.io.InputStream, output: java.io.OutputStream, length: Long) {
        val buffer = ByteArray(COPY_BUFFER)
        var previousWasFF = false
        var remaining = length
        while (remaining > 0) {
            val toRead = minOf(buffer.size.toLong(), remaining).toInt()
            val read = input.read(buffer, 0, toRead)
            if (read == -1) break
            for (i in 0 until read) {
                val b = buffer[i].toInt() and 0xFF
                if (previousWasFF && b == JpegMarkers.MARKER_EOI) {
                    output.write(buffer, 0, i + 1)
                    return
                }
                previousWasFF = b == JpegMarkers.MARKER_PREFIX
            }
            output.write(buffer, 0, read)
            remaining -= read
        }
    }

    const val COPY_BUFFER = 64 * 1024
}