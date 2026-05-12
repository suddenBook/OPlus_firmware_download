package com.desmond.ofd.http

// A real Chromium UA: some firmware CDNs/WAFs treat OkHttp's default UA differently.
internal const val BROWSER_USER_AGENT =
    "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/148.0.0.0 Safari/537.36"

// Mobile Chrome on Android. OPPO's downloadCheck gate sometimes rejects desktop-looking
// callers with the 2306 anti-leech body — a real OTA download comes from a phone, so
// match that profile for both the probe and the chunk downloads.
internal const val FIRMWARE_USER_AGENT =
    "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36"
