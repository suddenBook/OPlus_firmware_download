package com.desmond.ofd.download

import com.desmond.ofd.firmware.MIN_REASONABLE_FIRMWARE_BYTES
import com.desmond.ofd.firmware.validateFirmwareSize
import com.desmond.ofd.http.ContentRange
import com.desmond.ofd.http.parseContentRange
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DownloadEngineTest {

    @Test fun parses_content_range() {
        val range = parseContentRange("bytes 50-99/200")

        assertEquals(ContentRange(start = 50, end = 99, totalSize = 200), range)
        assertEquals(50L, range!!.length)
    }

    @Test fun parses_content_range_with_extra_space() {
        val range = parseContentRange("  bytes 0-0/1234  ")

        assertEquals(ContentRange(start = 0, end = 0, totalSize = 1234), range)
        assertEquals(1L, range!!.length)
    }

    @Test fun parses_content_range_with_equals_separator() {
        assertEquals(
            ContentRange(start = 0, end = 0, totalSize = 1234),
            parseContentRange("bytes=0-0/1234"),
        )
    }

    @Test fun rejects_invalid_content_range() {
        assertNull(parseContentRange(null))
        assertNull(parseContentRange(""))
        assertNull(parseContentRange("bytes */200"))
        assertNull(parseContentRange("bytes 99-50/200"))
        assertNull(parseContentRange("bytes 50-200/200"))
        assertNull(parseContentRange("items 0-1/200"))
        assertNull(parseContentRange("bytes==0-0/1234"))
        assertNull(parseContentRange("bytes 99999999999999999999-100000000000000000000/100000000000000000001"))
    }

    @Test fun validates_firmware_size_against_expected_size() {
        assertNull(validateFirmwareSize(MIN_REASONABLE_FIRMWARE_BYTES, MIN_REASONABLE_FIRMWARE_BYTES))
        assertNull(validateFirmwareSize(-1L, MIN_REASONABLE_FIRMWARE_BYTES))

        val suspicious = validateFirmwareSize(MIN_REASONABLE_FIRMWARE_BYTES - 1, 0L)
        assertNotNull(suspicious)
        assertTrue(suspicious!!.contains("Suspiciously small"))

        val zero = validateFirmwareSize(0L, 0L)
        assertNotNull(zero)
        assertTrue(zero!!.contains("Suspiciously small"))

        val mismatch = validateFirmwareSize(MIN_REASONABLE_FIRMWARE_BYTES + 1, MIN_REASONABLE_FIRMWARE_BYTES)
        assertNotNull(mismatch)
        assertTrue(mismatch!!.contains("Size mismatch"))
    }
}
