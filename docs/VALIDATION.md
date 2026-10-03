# Validation · 0.3.0 beta

- User reports connection and live screen working on their device; plugin management remains untested there.
- Terminal renderer smoke test passed: boot under its Content Security Policy, ANSI output, keyboard input, Ctrl, application-cursor arrows, bracketed paste, and viewport resize. No JavaScript errors.
- The actual SshTerminal class passed a localhost SSH-server smoke test: shell/PTY negotiation, input/output, resize, and closing the shell without closing the parent SSH transport.
- Terminal asset allowlist regression added. 12 Kotlin tests and 17 Python tests passed; release build and lint passed with zero lint errors.
- Release APK signature, existing HexDroid certificate, version 0.3.0-beta, non-debuggable manifest, and 16 KiB ZIP alignment verified.
- Android WebView/IME behavior, folding, and interactive terminal programs still require physical-device testing.
- Bundled xterm and fit-addon packages were checked against npm SHA-512 package integrity metadata; versions, integrity values, and MIT licenses are retained with the assets.

To run the renderer test, install Playwright in a temporary directory and expose it through NODE_PATH, install its Chromium browser, then run `node tests/terminal-renderer.cjs`. The test was run with Playwright 1.51.1.

## Earlier validation

### 0.2.1 beta

- Release build, 11 Kotlin unit tests, and Android lint passed (zero lint errors).
- 17 Python tests passed, including a concurrent editor regression proving the editor cannot enter before the plugin file transaction completes.
- New Kotlin regressions cover stderr-only overflow, exact byte limits, export snapshot recovery by a new owner, missing/empty snapshots, and invalid snapshot identifiers.
- Busy confirmation controls and transport-state handling were reviewed and compiled; Android lifecycle interactions still require device testing.
- APK signature, non-debuggable manifest, version, and 16 KiB ZIP alignment verified with the existing HexDroid signing certificate.
- No physical Pwnagotchi was available; SSH, restart behavior, and configuration changes require hardware integration validation.

## Earlier validation

### 0.1.0 beta

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
