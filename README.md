# MATRIX VPN — Hybrid

MATRIX is an Android VPN client with a dedicated WARP profile flow, an encrypted multi-profile pool, WireGuard/AmneziaWG tunnel support, a public OpenVPN catalogue hand-off, and evidence-based profile selection.

## Implemented in 0.6.0

- Dedicated WARP — separate mode entry restored in the main screen. It obtains a WARP-compatible WireGuard/AWG profile from a third-party community generator. This is not an official Cloudflare API or a Cloudflare-supported custom client.
- Multiple WireGuard/AmneziaWG profiles can be saved, selected, and deleted. Each configuration is encrypted with AES-GCM using a key held in Android Keystore; metadata is stored separately.
- Existing 0.5.x single-profile storage is migrated into the encrypted profile pool on first launch.
- Per-profile history records successful/failed HTTPS connectivity checks and a smoothed response-time estimate. The pool UI ranks profiles using local observations; unknown profiles are clearly marked untested.
- Guarded fallback attempts saved WireGuard/AmneziaWG profiles only when Android confirms MATRIX is configured as Always-on VPN with Block connections without VPN (lockdown). WARP mode is excluded from cross-provider fallback so it remains separate.
- VPN Gate directory discovery is retained. VPN Gate publishes OpenVPN profiles; MATRIX shares them with a compatible external OpenVPN app. The embedded MATRIX tunnel backend does not natively connect OpenVPN profiles.
- Manual import from Android file picker or clipboard remains available.
- User permission is required before starting a tunnel; MATRIX does not auto-connect on app launch.

## Important limits

- WARP profiles are obtained from a third-party generator. Download or parsing does not prove a real Cloudflare WARP tunnel is reachable.
- HTTPS reachability through a tunnel is a practical signal, not a cryptographic WireGuard peer-handshake measurement or proof of anonymity.
- Automatic fallback is sequential recovery after a failed initial HTTPS probe, not seamless multipath. It is gated by Android lockdown to reduce leak risk.
- VPN Gate nodes are volunteer-operated and may be slow, unstable, or untrusted. OpenVPN files are handed to a separate OpenVPN client.
- A trustworthy public WireGuard directory cannot be assumed to provide valid credentials or safe endpoints. MATRIX does not invent node keys or publish third-party private keys.
- Real-device verification remains required for generated WARP profiles, DNS/IPv6 leak behavior, Android lockdown interaction, and network handover.

## Architecture

- Tunnel engine: AmneziaWG Android backend, intended for standard WireGuard and AWG profiles.
- Profile vault: AES-GCM encrypted per-profile storage backed by Android Keystore.
- WARP flow: isolated profile acquisition and selection; user-controlled connect.
- Free-node directory: VPN Gate OpenVPN catalogue with explicit external-client hand-off.
- Health and selection: locally observed success/failure plus smoothed HTTPS response time.
- Recovery: ordered fallback only under Android Always-on VPN + lockdown.
- Network watcher and Cloudflare trace probe provide diagnostics; neither alone proves peer identity.

## Build and validation

CI runs gradle testDebugUnitTest assembleDebug with JDK 17 and Android SDK 36. The debug APK is uploaded as matrix-vpn-debug-apk artifact for 14 days. CI success confirms compilation and unit tests, not real-device VPN operation.

Target initial validation device: Realme Note 50, Android 15. Android 13–16 remain compatibility targets until tested.
