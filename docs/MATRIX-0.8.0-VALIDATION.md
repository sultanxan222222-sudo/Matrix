# MATRIX 0.8.0 — implementation and acceptance record

## Implemented
- Embedded OpenVPN 3 Core ARM64 bridge and Android VpnService integration.
- AmneziaWG/WireGuard profiles and separate WARP mode.
- AES-GCM profile vault backed by Android Keystore.
- VPN Gate discovery, catalog/profile validation, bounded physical-network TCP preflight and selectable volunteer nodes.
- One-tap candidate queue, sequential connection checks and fallback to saved WARP/WireGuard profiles.
- Cross-engine recovery with explicit tunnel-stop confirmation; automatic recovery is permitted only while Android Always-on VPN + Block connections without VPN (lockdown) are verified.
- Periodic external HTTPS/latency monitoring while the Android process remains alive with an active VPN service.
- HTTP CONNECT proxy profile copies for TCP OpenVPN profiles, with original profile preserved.
- Decorative-only globe and app UID traffic-rate display.

## Deliberate boundaries
- The TCP scout checks a listening TCP endpoint only. It does not perform OpenVPN authentication or a TUN handshake. UDP and unknown protocols are left for a regular connection attempt.
- HTTP CONNECT is a user-configured TCP proxy transport. It is not obfs4, Snowflake, WebTunnel or a universal DPI bypass. These transports require matching server-side software/infrastructure and are not claimed here.
- Automatic switching is serial and guarded by Android lockdown; it is not seamless multipath.
- The quality monitor uses HTTPS reachability and response latency, not peer handshake inspection. Android can delay or terminate background work under power/OEM policy.
- VPN Gate nodes are volunteer-operated and should be treated as untrusted exits.
- RX/TX measures app UID counters, not device-wide rates or verified tunnel throughput.
- The globe is purely decorative.

## CI acceptance gates
1. Compile OpenVPN 3 Core for Android ARM64 using the pinned source and NDK.
2. Run JVM unit tests for endpoint parsing, HTTP CONNECT profile creation, and repeated-failure quality policy.
3. Build the Android debug APK.
4. Verify that `libmatrix_openvpn.so` is inside the APK.
5. Upload the APK and source archive as workflow artifacts.

## Physical-device acceptance still required
A successful CI run does not prove routing safety or device compatibility. The final APK still needs a real-device test for permission grant/revoke, OpenVPN and AmneziaWG handshakes, DNS and IPv6 leak behavior, lockdown during handover, background monitoring, screen-off behavior, Wi-Fi/mobile switching, reconnects and long sessions. Do not label the build production-ready until those tests are performed.
