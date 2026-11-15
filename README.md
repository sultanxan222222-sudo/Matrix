# MATRIX VPN — Hybrid 0.8.1

MATRIX is an Android VPN client with separate WARP and VPN modes, an encrypted profile pool, AmneziaWG/WireGuard support, an embedded OpenVPN 3 Core engine, relay selection, and guarded recovery between saved VPN profiles.

## Included in 0.8.1

- WARP-compatible profile generation through a third-party community service and connection through AmneziaWG.
- Import and connection for WireGuard and AmneziaWG profiles.
- Embedded OpenVPN 3 Core through a Rust/JNI bridge and Android VpnService; VPN Gate profiles connect inside MATRIX rather than launching another VPN app.
- AES-GCM profile encryption with an Android Keystore key.
- Public VPN Gate discovery, CSV/profile validation, duplicate filtering, directory ranking, and a bounded TCP endpoint preflight on the physical network.
- One-tap VPN Gate candidate preparation and sequential connection attempts. If the volunteer queue fails, saved WARP/WireGuard profiles are eligible as fallback candidates.
- Strict mutual exclusion between the WARP and ordinary VPN modes: before any new connection, MATRIX stops and verifies both embedded engines so only one can own Android's VPN interface.
- Recovery stays inside the selected mode: WARP can fall back only to saved WARP profiles; ordinary VPN can fall back only to non-WARP profiles. VPN Gate relay recovery never silently changes into WARP.
- Cross-engine recovery between OpenVPN 3 and WireGuard/AmneziaWG after repeated connection-check failures, only when Android Always-on VPN and Block connections without VPN are confirmed.
- Periodic HTTPS reachability and latency checks while the MATRIX process remains alive with an active VPN service; three consecutive failed checks are required before automatic recovery.
- Optional HTTP CONNECT proxy configuration for TCP-capable OpenVPN profiles. MATRIX creates a separate encrypted copy and preserves the original profile.
- Compact translucent glass controls, a decorative rotating Matrix globe, and a gold RX/TX display based on MATRIX app-UID traffic counters.

## Transport and security boundaries

- OpenVPN profiles can use their configured TCP or UDP transport. The optional HTTP CONNECT proxy is for TCP profiles only, requires a user-provided trusted proxy, and does not itself provide obfs4, Snowflake, WebTunnel, or guaranteed DPI evasion. Those transports require compatible remote server infrastructure and are not claimed by this client.
- WARP profiles come from a third-party generator, not an official Cloudflare client/API. Creating a profile does not prove a successful WARP handshake.
- VPN Gate nodes are independent volunteer-operated computers. Their operators are not MATRIX; treat them as untrusted exits and do not use them for sensitive activity.
- The globe animation is decorative only. It does not display the user's location, network geography, or a live VPN route.
- RX/TX values are Android traffic counters for the MATRIX app UID. They are not whole-device rates and are not a verified VPN tunnel throughput measurement.
- HTTPS reachability is a practical connectivity signal, not a cryptographic peer-handshake measurement or proof of anonymity.
- Automatic tunnel switching is sequential, not seamless multipath. It is disabled unless Android lockdown is confirmed, to avoid intentionally falling back to an unprotected direct route.
- Android background execution and Doze can still affect timing. The monitor runs while the app process is retained by the active VPN service, but this is not a guarantee against OS process termination or OEM battery policies.

## Build and validation

CI builds the ARM64 OpenVPN 3 native library, runs JVM unit tests, assembles the debug APK, verifies the native library is packaged, and uploads the APK and source bundle. This is build validation, not a substitute for a physical-device acceptance run.

Before calling a release production-ready, install on the target phone and test both engines, Android VPN consent, screen-off/background monitoring, reconnects, mode switching, DNS and IPv6 leak behavior, lockdown, captive portals, Wi-Fi/mobile handover, and long-duration stability. The current CI cannot certify those device-specific behaviors.

## Build environment

JDK 17, Android SDK 36, Android NDK 27.2, Gradle 8.13. App minSdk 26, targetSdk 35. The generated workflow APK is a debug build for ARM64 devices. The 0.8.1 build includes exclusive engine handover and persisted WireGuard backend state across Activity recreation.
