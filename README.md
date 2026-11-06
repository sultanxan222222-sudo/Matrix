# MATRIX VPN — Caravan branch

MATRIX is an Android VPN client that brings several tunnel backends behind one control surface. The `feat/caravan-multi-engine` branch is the integration track for the **MATRIX CARAVAN** design.

## What this branch contains

- **WireGuard / AmneziaWG:** retains the existing embedded AmneziaWG-capable Android backend and profile import.
- **WARP-compatible profile mode:** retains the project's existing profile generator integration. The generator endpoint is a third-party service; it is not operated by Cloudflare and its availability is not guaranteed.
- **VPN Gate directory:** downloads the public volunteer relay catalogue, parses CSV columns by header, validates embedded OpenVPN profiles, de-duplicates relays, and ranks candidates.
- **Embedded OpenVPN engine:** includes the pinned OpenVPN-for-Android-derived `vpnLib` as a Git submodule, so VPN Gate profiles can be started from MATRIX instead of being handed to a separate VPN app.
- **One-tap caravan attempt:** requests Android VPN permission, loads VPN Gate candidates, tries relays in ranked order, checks external HTTPS after OpenVPN reports connected, and moves to the next candidate after connection timeout, authentication failure, tunnel exit, or failed egress check.
- **WARP fallback:** if VPN Gate returns no valid relay or its candidate queue is exhausted, MATRIX tries to fetch a WARP-compatible profile and use the existing WireGuard/AmneziaWG engine.
- **Background route scouting:** while a VPN Gate OpenVPN tunnel is connected, MATRIX can test up to 30 alternate relay TCP endpoints in parallel over an available physical Wi-Fi/mobile network. The scout is bounded (six workers, 5.5-second overall budget) and prioritizes TCP-reachable candidates for a later failover without taking down the current tunnel.

## What route scouting does — and does not — prove

A successful TCP socket connection to a relay's advertised endpoint is only a reachability hint. It does **not** authenticate OpenVPN, prove that the complete VPN tunnel will work, or establish that all phone traffic is routed through the VPN. UDP and unspecified-protocol relays are deliberately left eligible for normal connection attempts; a TCP-only probe must never label them as blocked.

Android allows one active VPN interface for the current user/profile. Therefore the caravan does not run multiple independent full VPN tunnels simultaneously. It scouts candidate endpoints in parallel, keeps the current tunnel alive during that preflight, and only then attempts a sequential tunnel switch if the current route fails.

The current branch does **not yet** implement traffic splitting/multipath, packet-level route steering, or dedicated obfuscation transports such as obfs4, WebTunnel, or Snowflake. Consequently it is not a guarantee against every form of DPI, censorship, server shutdown, or network outage. Those require separate compatible transports and further engineering.

## Build

Requirements:
- JDK 17
- Android SDK 36
- Gradle 8.13 (CI)
- Git with submodule support

Clone and initialize the pinned OpenVPN engine submodule before building:

```bash
git clone --branch feat/caravan-multi-engine https://github.com/sultanxan222222-sudo/Matrix.git
cd Matrix
git submodule update --init --recursive
gradle testDebugUnitTest assembleDebug
```

GitHub Actions builds and tests the debug APK and uploads it as `matrix-vpn-debug-apk` after a successful run. A green CI build verifies compilation and unit tests only. It does not replace real-device verification.

## Device validation still required

Test the final APK on the target Realme Note 50 and other supported Android versions (13–16): VPN permission flow, OpenVPN and WireGuard/AWG handshakes, Wi-Fi-to-cellular handover, DNS routing/leak behavior, reconnect after network loss, relay failover, and battery use. The current code has not been validated on the user's phone in this branch.

## Security and privacy

- VPN Gate relays are operated by volunteers. The relay operator can observe traffic metadata and any unencrypted traffic; do not use these relays for banking, passwords, or sensitive activity.
- Public VPN Gate availability, speed, and privacy practices vary by relay and can change.
- VPN profiles and private keys are sensitive. MATRIX should not log profile content or put credentials in analytics.
- The current import flow keeps user-selected profile contents in memory rather than saving them as a profile database.
- MATRIX does not disable certificate validation or implement its own cryptography.
- Review the exact upstream licensing and notices before distributing an APK containing the embedded OpenVPN engine. See [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md).

## Version and toolchain

This development branch targets app version `0.6.0-caravan` (version code 7), Android Gradle Plugin 8.13.2, Kotlin 2.2.21, Gradle 8.13 in CI, and Android SDK 36.


## Android TV edition

The `feat/android-tv-matrix` branch provides a separate TV package (`org.matrixvpn.tv`) using the same Caravan VPN engines and route logic. It adds a landscape 10-foot interface, large D-pad focusable controls, Leanback launcher registration and a TV banner. It keeps Android 8.0/API 26 as the minimum; the TCL television reported by the project owner runs an Android 9/Pie-based Union TV build. The TV APK targets Android 9/API 28 for compatibility with that older platform. Install and VPN service behavior still require validation on the physical TCL TV.

Build with:

```bash
git clone --branch feat/android-tv-matrix https://github.com/sultanxan222222-sudo/Matrix.git
cd Matrix
git submodule update --init --recursive
gradle testDebugUnitTest assembleDebug
```
