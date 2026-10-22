# MATRIX VPN

Adaptive Android VPN research project focused on resilient connections, parallel recovery, and clear diagnostics.

## Status
This is an initial Android project scaffold, **not yet a working VPN**. It does not currently route device traffic through a tunnel. The VPN service is a placeholder until a maintained, authenticated tunnel engine is integrated.

## Architecture
- Network watcher: observe Android connectivity and network changes.
- Parallel recovery: diagnose the current path while independently probing alternatives.
- Adaptive transport: choose only among implemented, authenticated transports and endpoints.
- Security core: keep key handling separate from decision logic.
- Android VPN service: planned integration via Android VpnService.

## Build
Open in Android Studio with JDK 17 and Android SDK 35. CI builds the debug APK and runs unit tests.

## Roadmap
1. Recovery-policy foundation and Android app scaffold.
2. Connectivity telemetry and independent candidate-path probes.
3. Integrate a maintained, audited tunnel implementation.
4. Secure endpoint configuration and leak-prevention tests.
5. Validate reconnects, network handover, and battery use on real devices.
