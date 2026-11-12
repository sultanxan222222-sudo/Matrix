# VAYNAX HYBRID — free-node integration plan

Status: engineering plan; no claim of working public VPN endpoints until real tunnel handshakes are verified.

## Reuse existing MATRIX implementation

The MATRIX Android app already has:
- Android VPN consent/connect flow.
- Embedded AmneziaWG-capable tunnel backend (also intended for standard WireGuard profiles).
- In-memory profile parsing for WireGuard and AmneziaWG.
- Android network monitoring and an HTTPS connectivity probe.
- A VPN Gate directory, but it currently exports OpenVPN profiles to an external OpenVPN app; MATRIX does not yet run OpenVPN internally.
- GitHub Actions debug build and unit-test workflow.

VAYNAX HYBRID should extend this implementation rather than create a second VPN stack.

## Separate the control plane from VPN exits

The VAYNAX messenger repository has a Node service and a Render blueprint, but that service is not a VPN exit node. The current Render blueprint requests the `starter` plan and a persistent disk; do not describe it as a free VPN server or assume it is a zero-cost deployment.

The control plane may publish signed/validated node metadata (provider, protocol, region, last-check time, capability flags). It must never receive user traffic, private keys, or full VPN profiles containing secrets.

## Initial provider strategy

1. Define a provider adapter interface that can fetch candidate node metadata from documented public sources.
2. Accept only profiles supported by the installed tunnel backend (WireGuard/AmneziaWG in the first phase).
3. Keep OpenVPN/VPN Gate entries marked as external-only until a native OpenVPN engine is integrated and tested.
4. Validate configuration syntax, endpoint reachability, DNS behavior, and an actual authenticated tunnel handshake. An HTTPS request alone is not proof that the VPN peer connected.
5. Rank candidates using recent success, latency, loss, stability, and region. Expire stale candidates.
6. Never embed public profile private keys in the app, repository, logs, analytics, or control-plane database.
7. Require explicit user consent before connecting; show provider/source and basic trust warnings.

## Failover design

- Maintain a pool of independently configured, authenticated profiles.
- Probe alternatives without leaking user traffic outside the active tunnel.
- Switch only after a verified alternative is ready; otherwise preserve the current connection or clearly report disconnection.
- Avoid claiming seamless multipath until packet-level implementation and leak tests demonstrate it.
- Add tests for DNS leaks, IPv4/IPv6 routing, captive portals, Wi-Fi/mobile handover, battery use, and repeated reconnects.

## Delivery gates

- Gate A: provider adapters and deterministic parser/unit tests.
- Gate B: at least two independently sourced, legally usable endpoints with verified handshakes.
- Gate C: safe failover and traffic-leak tests on a real Android 15 device.
- Gate D: release APK only after repeatable CI build and device verification.

Target device for initial validation: Realme Note 50, Android 15. Keep Android 13–16 compatibility as a target, not a claim, until each API level is tested.
