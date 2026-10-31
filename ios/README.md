# MATRIX VPN — iOS

This is the native iOS workstream for the existing MATRIX VPN project. Android sources remain unchanged.

## Current implementation

- Native SwiftUI app with VPN profile file import.
- WireGuard and AmneziaWG profile recognition, including the AWG parameters accepted by the pinned engine.
- AmneziaWG Apple `WireGuardKit` package pinned to revision `9d5ee60edefa95b933a738dd7cda671dd18021fc`.
- iOS arm64 Go backend build integrated into the packet-tunnel target build phase.
- `NEPacketTunnelProvider` backed by `WireGuardAdapter` for starting and stopping the tunnel.
- Imported profile content is stored in the shared App Group container with iOS file protection. VPN preferences contain only an opaque profile identifier, not the private key text.
- Separate CI tracks compile the app, compile the packet-tunnel extension, compile shared-core tests, and independently prove the AmneziaWG Go backend can be built for iPhone arm64.

## Current limitations and release requirements

- CI compilation is not the same as a successful VPN session. The tunnel still requires testing on a physical iPhone with a valid AmneziaWG/WireGuard server profile.
- The Apple Developer Team must register and enable the `group.org.matrixvpn.shared` App Group and the Network Extension `packet-tunnel-provider` capability for the app and extension identifiers.
- Apple Network Extension entitlement approval and valid signing/provisioning are required for installation and TestFlight distribution.
- OpenVPN profiles are recognized but are not connected by the current built-in engine. VPN Gate OpenVPN support needs a separate iOS OpenVPN engine or handoff to a compatible client.
- Free WARP profile generation and VPN Gate directory discovery from the Android version have not yet been ported to this iOS UI.

## Build

Install Xcode, Go 1.26, and XcodeGen, then run:

```sh
cd ios
xcodegen generate
xcodebuild -project MATRIX.xcodeproj -scheme MATRIX -sdk iphoneos -destination 'generic/platform=iOS' CODE_SIGNING_ALLOWED=NO build
```

The packet-tunnel target builds the pinned AmneziaWG Go backend for iOS arm64 before linking `WireGuardKit`.
