package com.desmond.ofd.firmware

internal const val MIN_REASONABLE_FIRMWARE_BYTES = 1L shl 20

internal fun validateFirmwareSize(resolvedSize: Long, expectedSize: Long): String? {
    if (resolvedSize in 0 until MIN_REASONABLE_FIRMWARE_BYTES) {
        return "Suspiciously small response: $resolvedSize bytes"
    }
    if (resolvedSize > 0 && expectedSize > 0 && resolvedSize != expectedSize) {
        return "Size mismatch: server=$resolvedSize bytes, expected=$expectedSize bytes"
    }
    return null
}
