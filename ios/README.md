# MATRIX VPN — iOS evaluation

This is a native iOS workstream built alongside the existing Android application. It does not replace or modify the Android implementation.

## Three parallel tracks

1. **Native app shell:** SwiftUI interface and secure document import.
2. **VPN platform boundary:** Network Extension packet-tunnel target and entitlement declarations. This target is only a compile-time integration boundary; it does not claim to establish a VPN tunnel.
3. **Shared configuration core:** platform-independent profile classification with unit tests for WireGuard, AmneziaWG markers, and OpenVPN detection.

## Current limitations

- No WireGuard/AmneziaWG packet-tunnel engine has been integrated yet.
- OpenVPN is detected but not connected.
- The Network Extension entitlement must be approved for the Apple Developer Team used for signing.
- Simulator compilation does not validate VPN operation on a physical iPhone.
- TestFlight distribution requires an appropriately signed build and Apple Developer Program access.

## Build

Install Xcode and XcodeGen, then run:

```sh
cd ios
xcodegen generate
xcodebuild -project MATRIX.xcodeproj -scheme MATRIX -sdk iphonesimulator CODE_SIGNING_ALLOWED=NO build
```

The GitHub Actions workflow runs three independent macOS jobs so app compilation, tunnel-extension compilation, and shared-core tests are evaluated separately.
