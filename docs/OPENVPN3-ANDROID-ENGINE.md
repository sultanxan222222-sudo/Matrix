# MATRIX embedded OpenVPN 3 engine — integration record

Status: embedded-engine implementation is now present on branch feature/openvpn3-embedded-engine: the pinned OpenVPN 3 Rust Core, JNI adapter, Android VpnService TUN callbacks, separate WARP/OpenVPN app flows, and ARM64 CI build have been added. The first native build exposed a bindgen sysroot issue; the corrected target-specific Android sysroot is being verified by CI. This is not yet a claim that a downloadable APK or a real-device VPN session is ready.

## Pinned upstream engine

- Project: https://github.com/networks-rs/openvpn-connect-rs
- Pinned commit: `98fcf3ba0abfddb9f2458daace5ab2182052054b`
- Crate release: `0.1.2`
- License: MPL-2.0 (upstream repository license); preserve notices and publish source for covered modifications as required.
- Core: OpenVPN 3 Core 3.11.7, compiled from source by `openvpn-connect-sys`.
- Android targets documented upstream: `aarch64-linux-android`, `armv7-linux-androideabi`, `i686-linux-android`, `x86_64-linux-android`.

This is a real OpenVPN 3 client core, not an .ovpn file exporter and not a wrapper that launches another VPN app.

## Required Android bridge

The Rust `openvpn_connect::EventHandler` must be implemented by a MATRIX adapter. Its `TunBuilder` callbacks must be forwarded to a dedicated Android `VpnService` implementation:

- `new_tunnel` / `set_layer`: initialize an Android `VpnService.Builder` and accept only TUN (layer 3).
- `add_address`, `add_route`, `exclude_route`, `reroute_gateway`: map negotiated IPv4/IPv6 addresses and routes to the Android builder.
- `set_dns_options`, `set_mtu`, `set_session_name`: configure DNS, MTU and the session label.
- `establish`: call Android `Builder.establish()` and transfer the owned TUN file descriptor to OpenVPN Core.
- `teardown`: close the TUN descriptor and release the builder/session state.
- `socket_protect`: call `VpnService.protect(fd)` for the OpenVPN transport socket so it cannot loop back into its own tunnel.

The JNI bridge must retain the service reference safely for the whole session, attach native worker threads to the JVM before callbacks, propagate callback failures, and release global references/descriptors on every exit path. The OpenVPN `Client::connect()` call is blocking and must run on a dedicated worker thread; `Client::stop()` is the cross-thread disconnect control.

## MATRIX mode contract

- Keep the existing AmneziaWG backend for WARP.
- Use the embedded OpenVPN 3 core only for OpenVPN profiles (including VPN Gate).
- Preserve the two independent mode controls and saved profile selection.
- Android permits only one active `VpnService` tunnel for the app/user at a time. Switching modes must stop the active tunnel before starting the other.
- Never export/share an OpenVPN profile as the normal connect path. File export may remain only as an explicit troubleshooting action, if retained at all.

## Build and acceptance gates

1. Build the Rust native library for MATRIX's Android ABI(s) with the Android NDK; do not ship a placeholder JNI library.
2. Compile the Kotlin `VpnService` and JNI bridge against the native API.
3. Run unit tests for profile evaluation, lifecycle transitions, TUN callback mapping and descriptor cleanup.
4. Run Android CI and inspect the generated APK contents for the native library.
5. Install on a real Android device and test a known-good OpenVPN server: permission prompt, protected transport socket, TUN establishment, DNS/routes, traffic through tunnel, disconnect and mode switching.
6. Only then label the APK integrated/ready. CI compilation alone does not prove a live VPN connection.

## Current validation gap

The integration code is present in the new branch, but CI has not yet completed a successful ARM64 native build and Gradle APK build. Real-device testing on Android 15 is also still required to validate the TUN descriptor lifecycle, protected OpenVPN socket, DNS/routes, traffic through the tunnel, disconnect, and switching between WARP and OpenVPN. Do not label any APK as release-ready until these gates pass.
