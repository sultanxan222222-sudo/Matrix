# Third-party components

## OpenVPN for Android engine

- Component: the `vpnLib` Android library module.
- Source repository: https://github.com/IyedAmiri/OpenGateVPN
- Pinned source commit: `12f8ea97976f1bac0307a013b40712098b865d43`
- Module path in this repository: `third_party/OpenGateVPN/vpnLib`
- License declared by the source repository: GNU GPL v3.
- Upstream project: OpenVPN for Android (ics-openvpn), by Arne Schwabe; the
  upstream OpenVPN code carries its own GPLv2 terms and additional notices.
- The pinned submodule is used only as an embedded OpenVPN engine; MATRIX's UI,
  directory parser, route selector, and recovery policy are separate code.

Before distributing a release APK, retain all upstream copyright and license
notices, audit the exact pinned native libraries, and provide the corresponding
source and notices required by the applicable licenses. This branch is an
integration build, not yet a device-validated release.
