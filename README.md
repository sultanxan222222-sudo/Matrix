# MATRIX VPN

Adaptive Android VPN project focused on resilient connections, parallel recovery, and transparent diagnostics.

## Current status

The Android app now includes:
- Local management of server-address candidates (host and port only).
- Default Android network monitoring (transport type and Android's validated-internet signal).
- Import and in-memory parsing of WireGuard profiles using the official WireGuard Android tunnel library.
- A user-authorized WireGuard connect/disconnect flow.
- OpenVPN profile recognition/preview only; OpenVPN connection support is not integrated.
- Unit tests and GitHub Actions build/test workflow, configured to publish the debug APK as a downloadable artifact.

A successful build must be confirmed by the latest GitHub Actions run before treating the current revision as build-verified. Real-device connection, peer handshake, traffic routing, and reconnect behavior still require device/server testing.

## Security notes

- Profile text is kept in memory by the current import flow and is not saved by MATRIX.
- A server address alone is only a candidate hint; it does not contain the keys or settings needed to authenticate a tunnel.
- Use only VPN profiles and servers you are authorized to use.
- MATRIX does not disable certificate checks or implement custom cryptography.
- The adaptive recovery policy remains a prototype. Automatic failover between independently configured, authenticated VPN endpoints is not implemented yet.

## Architecture

- Network watcher: observe Android connectivity and network changes.
- Profile inspection: recognize supported configuration formats and extract non-secret endpoint hints.
- WireGuard backend: use the official com.wireguard.android:tunnel implementation rather than a custom cryptographic protocol.
- Parallel recovery: planned diagnostics of the current path while independently probing configured alternatives.
- Adaptive transport: planned selection only among implemented, authenticated transports and endpoints.
- Security core: keep key handling separate from decision logic.

## Build

Open in Android Studio with JDK 17 and Android SDK 35. GitHub Actions runs gradle testDebugUnitTest assembleDebug and uploads app/build/outputs/apk/debug/app-debug.apk as the matrix-vpn-debug-apk artifact for 14 days after a successful run.

## Roadmap

1. Establish a reproducible Android build and unit-test baseline.
2. Integrate and validate the official WireGuard tunnel on a real Android device.
3. Add a secure multi-profile store and independent endpoint probes.
4. Implement tested failover between fully configured, authenticated profiles.
5. Validate reconnects, network handover, DNS behavior, traffic-leak protection, and battery use on real devices.
