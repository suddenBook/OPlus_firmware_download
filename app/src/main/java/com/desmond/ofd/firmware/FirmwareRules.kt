package com.desmond.ofd.firmware

import java.util.Locale

internal const val MIN_FULL_FIRMWARE_BYTES = 1L shl 30

internal fun validateFirmwareSize(resolvedSize: Long, expectedSize: Long): String? {
    if (resolvedSize in 0 until MIN_FULL_FIRMWARE_BYTES) {
        return "Suspiciously small full-package response: $resolvedSize bytes"
    }
    if (resolvedSize > 0 && expectedSize > 0 && resolvedSize != expectedSize) {
        return "Size mismatch: server=$resolvedSize bytes, expected=$expectedSize bytes"
    }
    return null
}

internal fun formatFirmwareBytes(bytes: Long): String = when {
    bytes < 0 -> "unknown"
    bytes >= 1L shl 30 -> String.format(Locale.US, "%.2f GiB", bytes / (1L shl 30).toDouble())
    bytes >= 1L shl 20 -> String.format(Locale.US, "%.1f MiB", bytes / (1L shl 20).toDouble())
    bytes >= 1L shl 10 -> String.format(Locale.US, "%.1f KiB", bytes / (1L shl 10).toDouble())
    else -> "$bytes B"
}
