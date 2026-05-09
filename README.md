# OPlus Firmware Download

Android app for checking and downloading full OTA firmware packages for OPPO,
realme, and OnePlus devices.

Minimum supported Android version: Android 13.

Release APKs are currently arm64-v8a only.

## Backends

The app uses two sources.

### realme-ota

Website: https://github.com/R0rt1z2/realme-ota

This backend talks to OPPO's OTA endpoint. The app ports the request logic from
`realme-ota` into Kotlin:

- build the OTA request from model, current OTA version, RealmeUI version,
  region, NV ID, and optional IMEI
- encrypt the request body using the protocol required by the RealmeUI version
- send the request to OPPO's update endpoint
- decrypt the response
- read the download URL, size, and MD5 from the returned component packet

RealmeUI version matters. The upstream CLI requires it explicitly, and this app
does the same in Manual mode. If you select a device that is not the phone
running the app, choose the RealmeUI generation that matches the OTA version you
are checking.

If OPPO rejects the supplied OTA version, the app retries once with the same
zero-tail OTA version fallback used by `realme-ota`.

### danielspringer.at

Website: https://roms.danielspringer.at/index.php?view=ota

This backend is simpler. It only needs device and region. The app parses the
site catalog, maps the selected model and region to the site's labels, then asks
for the latest entry with `version_index=0`.

OTA version, RealmeUI version, NV ID, and IMEI are not used for this backend.

## Auto Mode

Auto mode reads firmware properties from the current phone:

- `ro.product.name`
- `ro.build.version.ota`
- `ro.build.oplus_nv_id`
- `ro.build.version.realmeui`
- `ro.build.version.oplusrom`
- `ro.build.display.id`

Android does not expose these through normal public SDK APIs, so the app reads
`android.os.SystemProperties` by reflection.

If that fails, the app does not crash. It shows an error card and you can use
Manual mode instead.

## Manual Mode

Manual mode lets you choose:

- device
- OTA version
- RealmeUI version
- region
- NV ID
- optional IMEI

The device catalog is bundled in the app. It is based on:

https://github.com/KHwang9883/MobileModels

## Version Selection

When both backends return firmware, the app compares display versions such as:

```text
PLK110_16.0.7.206(CN01)
PLK110_16.0.5.702(CN01)
```

It ignores the model prefix and region suffix, then compares the numeric version
body. In this example, `16.0.7.206` is newer than `16.0.5.702`.

The newest parseable result becomes the main download result. The backend list
still shows what each source returned.

## Download Behavior

Downloads use Android's document picker, so the user chooses where the ZIP is
saved.

The downloader supports:

- foreground-service download notification
- automatic or manual thread count
- HTTP range downloads when the server supports `206 Partial Content`
- single-thread fallback when range requests are not supported
- MD5 verification when the backend provides an expected MD5
- retry on MD5 mismatch

Canceling a download cancels the HTTP calls, removes the download item, removes
the notification, and deletes the partially written local file.

