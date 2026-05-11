package com.desmond.ofd.http

// A real Chromium UA: some firmware CDNs/WAFs treat OkHttp's default UA differently.
internal const val BROWSER_USER_AGENT =
    "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/148.0.0.0 Safari/537.36"
