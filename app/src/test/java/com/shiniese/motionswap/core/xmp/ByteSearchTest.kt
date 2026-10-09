package com.shiniese.motionswap

import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import com.shiniese.motionswap.core.xmp.ByteRange
import com.shiniese.motionswap.core.xmp.ByteSearch
import com.shiniese.motionswap.core.xmp.StreamIo
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

/** The byte primitives every scanner in `core/xmp` is built on. */
@DisplayName("ByteSearch / StreamIo")
class ByteSearchTest {

    private fun ascii(s: String) = s.toByteArray(Charsets.US_ASCII)

    private fun b(vararg v: Int) = ByteArray(v.size) { v[it].toByte() }

    @Nested
    @DisplayName("indexOf(haystack, needle, from)")
    inner class IndexOf {

        @Test
        fun `finds a needle at the start, middle and end`() {
            val hay = ascii("abcdefabcdef")
            assertEquals(0, ByteSearch.indexOf(hay, ascii("abc"), 0))
            assertEquals(6, ByteSearch.indexOf(hay, ascii("abc"), 1))
            assertEquals(3, ByteSearch.indexOf(hay, ascii("def"), 0))
        }

        @Test
        fun `from skips an earlier occurrence`() {
            val hay = ascii("aXaXa")
            // "aX" occurs at 0 and 2; starting at 1 must find the second.
            assertEquals(2, ByteSearch.indexOf(hay, ascii("aX"), 1))
        }

        @Test
        fun `returns minus one when absent, and when the needle is longer than the haystack`() {
            assertEquals(-1, ByteSearch.indexOf(ascii("abc"), ascii("xyz")))
            assertEquals(-1, ByteSearch.indexOf(ascii("ab"), ascii("abc"), 0))
        }

        @Test
        fun `a negative start reports not-found rather than throwing`() {
            // Detection passes offsets derived from file metadata, so a bad one reaching here
            // should degrade to "no match", not abort the whole probe.
            assertEquals(-1, ByteSearch.indexOf(ascii("abc"), ascii("a"), -1))
        }

        @Test
        fun `a needle ending exactly at the last byte is found`() {
            val hay = ascii("xxabc")
            assertEquals(2, ByteSearch.indexOf(hay, ascii("abc")))
        }

        @Test
        fun `an empty needle is the start position clamped into range`() {
            assertEquals(0, ByteSearch.indexOf(ascii("abc"), ByteArray(0), 0))
            assertEquals(2, ByteSearch.indexOf(ascii("abc"), ByteArray(0), 2))
            assertEquals(3, ByteSearch.indexOf(ascii("abc"), ByteArray(0), 99))
        }

        @Test
        fun `a single-byte overload matches the needle overload`() {
            val hay = ascii("banana")
            assertEquals(2, ByteSearch.indexOf(hay, 'n'.code.toByte(), 0))
            assertEquals(ByteSearch.indexOf(hay, ascii("na"), 0), ByteSearch.indexOf(hay, 'n'.code.toByte(), 0))
            assertEquals(-1, ByteSearch.indexOf(hay, 'z'.code.toByte(), 0))
        }
    }

    @Nested
    @DisplayName("startsWith(haystack, prefix, at)")
    inner class StartsWith {

        @Test
        fun `matches at, before and past the end of the haystack`() {
            val hay = ascii("abcdef")
            assertTrue(ByteSearch.startsWith(hay, ascii("abc"), 0))
            assertTrue(ByteSearch.startsWith(hay, ascii("cde"), 2))
            // An `at` that would run off the end is false, not an index-out-of-bounds: this is
            // how a stale video offset in a metadata packet gets rejected.
            assertFalse(ByteSearch.startsWith(hay, ascii("abcd"), 3))
            assertFalse(ByteSearch.startsWith(hay, ascii("a"), -1))
        }

        @Test
        fun `an empty prefix always matches`() {
            assertTrue(ByteSearch.startsWith(ascii("abc"), ByteArray(0), 0))
            assertTrue(ByteSearch.startsWith(ascii("abc"), ByteArray(0), 3))
        }
    }

    @Nested
    @DisplayName("contains")
    inner class Contains {

        @Test
        fun `is indexOf against zero`() {
            assertTrue(ByteSearch.contains(ascii("hello world"), ascii("lo w")))
            assertFalse(ByteSearch.contains(ascii("hello"), ascii("hellos")))
        }
    }

    @Nested
    @DisplayName("big-endian integer reads")
    inner class IntegerReads {

        @Test
        fun `reads a UInt16 without sign extension`() {
            // 0xFFFF would come back as -1 if the bytes were OR'd into a signed Int naively.
            assertEquals(0xFFFF, ByteSearch.readUInt16BE(b(0xFF, 0xFF), 0))
            assertEquals(0x0201, ByteSearch.readUInt16BE(b(0x02, 0x01), 0))
            assertEquals(0, ByteSearch.readUInt16BE(b(0x00, 0x00), 0))
        }

        @Test
        fun `reads a UInt32 as a Long so it stays positive`() {
            // 0xFFFFFFFF exceeds Int.MAX_VALUE; returning a Long is what keeps it correct.
            assertEquals(0xFFFFFFFFL, ByteSearch.readUInt32BE(b(0xFF, 0xFF, 0xFF, 0xFF), 0))
            assertEquals(0x01020304L, ByteSearch.readUInt32BE(b(0x01, 0x02, 0x03, 0x04), 0))
        }

        @Test
        fun `respects the offset`() {
            val data = b(0x00, 0x00, 0x00, 0x12, 0x34)
            assertEquals(0x1234, ByteSearch.readUInt16BE(data, 3))
            // offset 1 into a buffer holding 0x12345678 reads the last three bytes of it.
            assertEquals(0x12345678L, ByteSearch.readUInt32BE(b(0, 0x12, 0x34, 0x56, 0x78), 1))
        }

        @Test
        fun `rejects a read that would run past the end`() {
            // require, not a silent zero: a truncated file means the metadata is untrustworthy
            // and the caller has to decide what to do about it.
            assertThrows(IllegalArgumentException::class.java) {
                ByteSearch.readUInt16BE(b(0x01), 0)
            }
            assertThrows(IllegalArgumentException::class.java) {
                ByteSearch.readUInt32BE(b(0x01, 0x02, 0x03), 0)
            }
            assertThrows(IllegalArgumentException::class.java) {
                ByteSearch.readUInt16BE(b(0x01, 0x02), -1)
            }
        }

        @Test
        fun `reads a real ftyp box size`() {
            // The shape Mp4BoxScanner validates against.
            val box = b(0x00, 0x00, 0x00, 0x20, 0x66, 0x74, 0x79, 0x70)
            assertEquals(32L, ByteSearch.readUInt32BE(box, 0))
            assertTrue(ByteSearch.startsWith(box, ascii("ftyp"), 4))
        }
    }

    @Nested
    @DisplayName("ByteRange")
    inner class Ranges {

        @Test
        fun `length is half-open`() {
            assertEquals(5, ByteRange(2, 7).length)
            assertEquals(0, ByteRange(4, 4).length)
        }

        @Test
        fun `rejects an inverted or negative range`() {
            assertThrows(IllegalArgumentException::class.java) { ByteRange(5, 4) }
            assertThrows(IllegalArgumentException::class.java) { ByteRange(-1, 4) }
            // Equal bounds are the empty range and are legitimate.
            assertEquals(0, ByteRange(4, 4).length)
        }
    }

    @Nested
    @DisplayName("StreamIo")
    inner class Streams {

        @Test
        fun `copyRange writes exactly the requested length`() {
            val src = ByteArray(1000) { it.toByte() }
            val out = ByteArrayOutputStream()
            StreamIo.copyRange(ByteArrayInputStream(src), out, 400)
            assertArrayEquals(src.copyOf(400), out.toByteArray())
        }

        @Test
        fun `copyRange stops at end of input rather than padding`() {
            val out = ByteArrayOutputStream()
            StreamIo.copyRange(ByteArrayInputStream(b(1, 2, 3)), out, 100)
            assertArrayEquals(b(1, 2, 3), out.toByteArray())
        }

        @Test
        fun `copyRangeUntilEoi stops right after FF D9`() {
            val src = b(0x11, 0x22, 0xFF, 0xD9, 0x33, 0x44)
            val out = ByteArrayOutputStream()
            StreamIo.copyRangeUntilEoi(ByteArrayInputStream(src), out, 100)
            assertArrayEquals(b(0x11, 0x22, 0xFF, 0xD9), out.toByteArray())
        }

        @Test
        fun `copyRangeUntilEoi finds a marker split across two buffer reads`() {
            // COPY_BUFFER is 64 KiB, so place FF and D9 either side of the boundary. A scanner
            // that tracked only the current chunk would copy the whole thing instead.
            val src = ByteArray(64 * 1024 + 10) { 0x41 }
            src[64 * 1024 - 1] = 0xFF.toByte()
            src[64 * 1024] = 0xD9.toByte()
            val out = ByteArrayOutputStream()
            StreamIo.copyRangeUntilEoi(ByteArrayInputStream(src), out, src.size.toLong())
            // 65,536 bytes of chunk one (the FF is the last of them) plus the single D9.
            assertEquals(64 * 1024 + 1, out.size())
        }

        @Test
        fun `copyRangeUntilEoi honours the budget when there is no marker`() {
            val src = ByteArray(500) { 0x41 }
            val out = ByteArrayOutputStream()
            StreamIo.copyRangeUntilEoi(ByteArrayInputStream(src), out, 120)
            assertEquals(120, out.size())
        }

        @Test
        fun `skipFully skips across the whole stream`() {
            // InputStream.skip is allowed to return 0, so this has to fall back to read().
            var skipped = 0L
            val input = object : ByteArrayInputStream(ByteArray(300)) {
                override fun skip(n: Long): Long {
                    skipped++
                    return if (skipped < 3) 0 else super.skip(n)
                }
            }
            assertEquals(200L, StreamIo.skipFully(input, 200))
            assertTrue(skipped >= 3, "expected the zero-skip fallback to be exercised")
        }

        @Test
        fun `skipFully reports a short skip at end of input`() {
            assertEquals(10L, StreamIo.skipFully(ByteArrayInputStream(ByteArray(10)), 999))
        }

        @Test
        fun `readFully reports a partial read instead of throwing`() {
            val buffer = ByteArray(50)
            assertEquals(10L, StreamIo.readFully(ByteArrayInputStream(ByteArray(10)), buffer))
            val exact = ByteArray(10)
            assertEquals(10L, StreamIo.readFully(ByteArrayInputStream(ByteArray(10)), exact))
        }

        @Test
        fun `readFully handles a stream that dribbles one byte at a time`() {
            val buffer = ByteArray(4)
            val dribble = object : ByteArrayInputStream(b(9, 8, 7, 6)) {
                override fun read(b: ByteArray, off: Int, len: Int): Int {
                    return if (len == 0) 0 else super.read(b, off, 1)
                }
            }
            assertEquals(4L, StreamIo.readFully(dribble, buffer))
            assertArrayEquals(b(9, 8, 7, 6), buffer)
        }
    }

    }