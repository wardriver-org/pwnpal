# Validation · 0.1.0 beta

- Android debug APK compiled successfully using JDK 17 / Gradle 8.11.1 / AGP 8.9.2.
- Android lint: zero errors. Remaining warnings concern newer dependency versions and optional Kotlin convenience extensions.
- Kotlin unit tests: 4 passed (address/port validation, shell quoting, fingerprint hashing).
- Python configuration tests: 6 passed (backup integrity, permissions, invalid TOML rejection, stale-write rejection, empty/oversized files, symlinks).
- APK signature verified with Android SDK apksigner (v2 signature).
- Manifest checked: package org.wardriver.pwnpal, version 0.1.0-beta, min SDK 26, target SDK 35.
- APK installed and Home screen rendered in an Android 11 / API 30 emulator. First boot was slow and System UI temporarily stalled without hardware acceleration; the app subsequently launched. System bar appearance corrected after visual inspection.
- Firmware handler and defaults inspected from jayofelony/pwnagotchi tag v2.9.5.4.

Hardware integration remains unverified: no connection to the user’s Pwnagotchi was available. Bluetooth provisioning, Android USB support, passwordless sudo, and any third-party firmware modifications must be checked on the actual device.

## Release-signed build

- `assembleRelease` and release-critical lint passed.
- Non-debuggable APK verified.
- Signing certificate matches the HexDroid release keystore.
- APK signature and 16 KiB ZIP alignment verified.
