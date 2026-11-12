# MATRIX Community Nodes — volunteer relay network

## What MATRIX connects to today

The Android app has a live adapter for the public VPN Gate volunteer relay directory:
- Official live directory/API: https://www.vpngate.net/api/iphone/
- Human-readable service information: https://www.vpngate.net/
- The app also uses a separately hosted mirror only after validating its schema, pinned data commit, byte count and SHA-256 digest; if that fails, it falls back to the official CSV API.
- The directory is refreshed when the user requests a search. Server IPs and hostnames are volatile and must not be hard-coded into the APK or presented as permanent MATRIX-owned addresses.
- Only entries with a valid IPv4 endpoint and an OpenVPN client profile are accepted. The app validates the profile, stores it in the Android Keystore-backed encrypted profile vault, and connects with the embedded OpenVPN 3 Core. VPN Gate credentials are supplied only for VPN Gate profiles.
- The UI calls this pool “MATRIX Community” but clearly identifies VPN Gate as the upstream source. The volunteer computers are independently operated; they are not owned or administered by MATRIX.

## Connection and safety model

1. The user asks MATRIX to find community nodes.
2. MATRIX downloads the current relay catalogue and ranks candidates using the catalogue's published score, ping, and throughput.
3. The user chooses one relay. MATRIX shows its host/IP and warns that it is run by an independent operator.
4. MATRIX validates and encrypts the selected OpenVPN profile locally.
5. The embedded OpenVPN 3 Core establishes the VPN tunnel. No separate VPN application is launched.
6. Once OpenVPN reports CONNECTED, MATRIX probes https://www.youtube.com/generate_204 through the app's current network route and reports basic YouTube web-endpoint reachability. This is diagnostic only; it does not prove that all YouTube video CDN hosts are reachable or that playback will be smooth.

Public volunteer relays are not trusted exits. Their operators may observe connection metadata and the public destination traffic that is not protected by end-to-end encryption. Never use unknown volunteer relays for banking, credentials, or sensitive activity. A working VPN tunnel is not a guarantee of privacy, anonymity, or YouTube availability.

## What is needed for a separate MATRIX-operated volunteer directory

The existing VPN Gate integration gives MATRIX immediate access to computers already volunteered to that independent service. It does **not** make those computers MATRIX-owned.

A separate network where computer owners register directly with MATRIX needs:
- a publicly reachable, maintained registration/directory service;
- an authenticated node-agent protocol and short-lived node credentials;
- an operator installer for supported Linux/Windows hosts;
- per-user VPN credentials/keys, revocation, node health checks and abuse controls;
- a reachable public endpoint (or a separately designed relay for hosts behind CGNAT);
- deployment and operational ownership of the directory service.

No MATRIX-operated directory endpoint or volunteer node addresses are fabricated by this repository. Until those components are deployed and real operators join, the correct live source for volunteer exits is the VPN Gate directory above. A node address is valid only while its operator keeps the relay online and its public endpoint reachable.
