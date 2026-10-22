# MATRIX VPN

Adaptive Android VPN project focused on resilient connections, Cloudflare WARP profile import, and transparent diagnostics.

## Current status

The Android app includes:
- Import and in-memory parsing of standard WireGuard and AmneziaWG profiles.
- An embedded AmneziaWG-capable Android tunnel library (com.zaneschepke:amneziawg-android:2.3.7), intended to support the AWG extensions emitted by WARP configuration generators.
- A user-authorized VPN connect/disconnect flow.
- Default Android network monitoring and an external HTTPS connectivity probe after tunnel startup.
- OpenVPN profile recognition/preview only; OpenVPN connection support is not integrated.
- Unit tests and GitHub Actions build/test workflow, configured to publish a debug APK as a downloadable artifact.

The current build toolchain uses Android Gradle Plugin 8.10.1, Kotlin 2.2.21, Gradle 8.11.1, and Android SDK 36. A successful CI build confirms compilation and unit tests, not a real Cloudflare WARP handshake. Real-device tests are still required to confirm each generated profile mode (Legacy, AWG 2.0, AWG 3.0 and AWG 3.1), routing, DNS, and endpoint reachability.

## Security notes

- Profile text is kept in memory by the current import flow and is not saved by MATRIX.
- A server address alone is only a candidate hint; it does not contain the keys or settings needed to authenticate a tunnel.
- Use only VPN profiles and servers you are authorized to use.
- MATRIX does not disable certificate checks or implement custom cryptography.
- The adaptive recovery policy remains a prototype. Automatic failover between independently configured, authenticated VPN endpoints is not implemented yet.

## Architecture

- Network watcher: observe Android connectivity and Android's validated-internet signal.
- Profile inspection: identify WireGuard, AmneziaWG and OpenVPN profile formats without logging secret contents.
- Tunnel backend: embedded AmneziaWG Android library, intended to run both ordinary WireGuard and AWG profiles.
- Connectivity probe: test external HTTPS after tunnel startup; this does not independently prove a WireGuard peer handshake.
- Parallel recovery: planned diagnostics of the current path while independently probing configured alternatives.
- Security core: keep key handling separate from decision logic.

## Build

Open in Android Studio with JDK 17 and Android SDK 36. GitHub Actions runs gradle testDebugUnitTest assembleDebug and uploads app/build/outputs/apk/debug/app-debug.apk as the matrix-vpn-debug-apk artifact for 14 days after a successful run.

## Roadmap

1. Build and unit-test the AmneziaWG-capable variant.
2. Validate generated WARP profiles on a real Android device.
3. Add a secure multi-profile store and independent endpoint probes.
4. Implement tested failover between fully configured, authenticated profiles.
5. Validate reconnects, network handover, DNS behavior, traffic-leak protection, and battery use on the device.
