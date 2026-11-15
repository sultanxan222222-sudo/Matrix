# MATRIX VPN — Hybrid 0.7.2

MATRIX is an Android VPN client with dedicated WARP and VPN flows, encrypted profile storage, AmneziaWG/WireGuard support, an embedded OpenVPN 3 Core path, and a compact Matrix-inspired visual interface.

## Implemented in 0.7.2

- Dedicated WARP profile acquisition and connection flow using the AmneziaWG backend.
- Standard WireGuard and AmneziaWG profile import and connection.
- Embedded OpenVPN 3 Core through a Rust/JNI bridge and Android VpnService for OpenVPN profiles, including VPN Gate profiles.
- AES-GCM encrypted profile vault backed by Android Keystore.
- Per-profile health history and evidence-based local ranking.
- Guarded sequential fallback among saved WireGuard/AmneziaWG profiles when Android Always-on VPN + Block connections without VPN lockdown is enabled.
- VPN Gate public directory discovery.
- Compact glass navigation controls preserving the existing translucent visual language.
- Animated green Matrix globe with a moving route marker and luminous trail.
- Small gold RX/TX rate indicator based on traffic counters for the MATRIX Android UID.

## Important limits and validation

- The WARP profile generator is a third-party community service, not an official Cloudflare API. Profile retrieval does not prove a working Cloudflare WARP tunnel.
- RX/TX figures are Android UID traffic rates, not total-device throughput or proof of traffic through a particular VPN node.
- HTTPS reachability is a practical connectivity signal, not a peer-handshake measurement or proof of anonymity.
- Fallback is sequential recovery after a failed initial connectivity check, not seamless multipath or live cross-engine failover.
- VPN Gate nodes are volunteer-operated and may be slow, unstable, or untrusted. Do not use them for sensitive traffic.
- CI compilation and unit tests do not replace real-device checks for OpenVPN TUN lifecycle, DNS/IPv6 leaks, Android lockdown, mobile/Wi-Fi handover, and long-running stability.

## Build

CI uses JDK 17, Android SDK 36, NDK 27.2, and builds the ARM64 native OpenVPN bridge plus the Android debug APK. Android 13–16 remain compatibility targets until validated on physical devices.
