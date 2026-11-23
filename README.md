# MATRIX VPN — Hybrid 0.8.1

MATRIX is an Android VPN client with separate WARP and VPN modes, an encrypted profile pool, AmneziaWG/WireGuard support, an embedded OpenVPN 3 Core engine, relay selection, and guarded recovery between saved VPN profiles.

## Included in 0.8.1

- WARP-compatible profile generation through a third-party community service and connection through AmneziaWG.
- Import and connection for WireGuard and AmneziaWG profiles.
- Embedded OpenVPN 3 Core through a Rust/JNI bridge and Android VpnService; VPN Gate profiles connect inside MATRIX rather than launching another VPN app.
- AES-GCM profile encryption with an Android Keystore key.
- Public VPN Gate discovery, CSV/profile validation, duplicate filtering, directory ranking, and a bounded TCP endpoint preflight on the physical network.
- One-tap VPN Gate candidate preparation and sequential connection attempts. If the volunteer queue fails, saved WARP/WireGuard profiles are eligible as fallback candidates.
- Strict mutual exclusion between the WARP and ordinary VPN modes: before any new connection, MATRIX stops and verifies both embedded engines so only one can own Android's VPN interface.
- Recovery stays inside the selected mode: WARP can fall back only to saved WARP profiles; ordinary VPN can fall back only to non-WARP profiles. VPN Gate relay recovery never silently changes into WARP.
- Cross-engine recovery between OpenVPN 3 and WireGuard/AmneziaWG after repeated connection-check failures, only when Android Always-on VPN and Block connections without VPN are confirmed.
- Periodic HTTPS reachability and latency checks while the MATRIX process remains alive with an active VPN service; three consecutive failed checks are required before automatic recovery.
- Optional HTTP CONNECT proxy configuration for TCP-capable OpenVPN profiles. MATRIX creates a separate encrypted copy and preserves the original profile.
- Compact translucent glass controls, a decorative rotating Matrix globe, and a gold RX/TX display based on MATRIX app-UID traffic counters.

## Transport and security boundaries

- OpenVPN profiles can use their configured TCP or UDP transport. The optional HTTP CONNECT proxy is for TCP profiles only, requires a user-provided trusted proxy, and does not itself provide obfs4, Snowflake, WebTunnel, or guaranteed DPI evasion. Those transports require compatible remote server infrastructure and are not claimed by this client.
- WARP profiles come from a third-party generator, not an official Cloudflare client/API. Creating a profile does not prove a successful WARP handshake.
- VPN Gate nodes are independent volunteer-operated computers. Their operators are not MATRIX; treat them as untrusted exits and do not use them for sensitive activity.
- The globe animation is decorative only. It does not display the user's location, network geography, or a live VPN route.
- RX/TX values are Android traffic counters for the MATRIX app UID. They are not whole-device rates and are not a verified VPN tunnel throughput measurement.
- HTTPS reachability is a practical connectivity signal, not a cryptographic peer-handshake measurement or proof of anonymity.
- Automatic tunnel switching is sequential, not seamless multipath. It is disabled unless Android lockdown is confirmed, to avoid intentionally falling back to an unprotected direct route.
- Android background execution and Doze can still affect timing. The monitor runs while the app process is retained by the active VPN service, but this is not a guarantee against OS process termination or OEM battery policies.

## Build and validation

CI builds the ARM64 OpenVPN 3 native library, runs JVM unit tests, assembles the debug APK, verifies the native library is packaged, and uploads the APK and source bundle. This is build validation, not a substitute for a physical-device acceptance run.

For the full-AI branch, CI additionally builds the Garlic Android ARM64 runtime, converts the Qwen3.5-0.8B checkpoint into the native Q4i model pack, verifies every generated model-pack file against its SHA-256 and byte-size manifest, checks required model/license assets, packages the model and native libraries into the APK, and verifies the expected APK entries before uploading artifacts.

Before calling a release production-ready, install on the target phone and test both engines, Android VPN consent, screen-off/background monitoring, reconnects, mode switching, DNS and IPv6 leak behavior, lockdown, captive portals, Wi-Fi/mobile handover, and long-duration stability. The current CI cannot certify those device-specific behaviors.

## Build environment

JDK 17, Android SDK 36, Android NDK 27.2, Gradle 8.13. App minSdk 26, targetSdk 35. The generated workflow APK is a debug build for ARM64 devices. The 0.8.1 build includes exclusive engine handover and persisted WireGuard backend state across Activity recreation.

## MATRIX AI — Garlic Qwen3.5

The `feature/matrix-ai-core` line integrates Garlic LLM with the Qwen3.5-0.8B Q4i model as an internal, on-device network-policy engine. It is not a chatbot and does not expose chat, voice, Python, PS4, or remote inference services.

- The Android ARM64 native engine is adapted to OpenCL-on-Vulkan through clvk. The original Garlic inference/tokenizer path is retained and the Android build produces the model pack and SPIR-V kernels.
- The model receives only aggregate health observations, measured HTTPS latency/history, and opaque IDs for a bounded set of saved MATRIX profiles. VPN configuration text, private keys, credentials, raw IP endpoints, DNS contents, and browsing data are not sent to the model.
- The model can recommend only a profile ID already supplied by the host. A strict JSON output gate validates the action, confidence, reason code, tunnel state, and candidate allowlist. Invalid or low-confidence output is discarded.
- MATRIX, not the model, owns VPN permissions, tunnel lifecycle, profile parsing, sequential engine handover, and Android lockdown checks.
- A generation-based emergency-stop latch invalidates delayed inference, probe, recovery, and Android VPN-consent callbacks. The model cannot disable lockdown or the emergency stop.
- Model weights and runtime assets are built into the CI APK. On first launch the native loader copies the verified assets to app-private storage and performs a real GPU token-pass smoke test before advertising Garlic as ready.

### AI validation status

A successful Gradle build is not proof of useful model decisions or safe VPN operation. Release acceptance still requires: successful ARM64 native compilation and packaged-library checks; deterministic policy-gate and emergency-stop tests; numerical/inference smoke tests on the target device; repeated candidate-selection and failover trials; Android Always-on VPN + Block connections without VPN verification; DNS/IPv6 leak tests; and long-running/background/Doze testing on the Realme Note 50.

The mobile Garlic port deliberately disables unsupported acceleration/layout paths (including SWAR/Q4S/Q3S, TurboQuant and speculative decoding) until their Android kernels pass numerical reference tests. If the device cannot provide the required Vulkan/OpenCL GPU or safe memory headroom, MATRIX AI must report unavailable and leave manual VPN operation intact.

### Licensing

Retain Garlic LLM's GPL-3.0-or-later notices and the Qwen3.5 model license/attribution files in every distributable source/model package. The model's license terms must be reviewed separately from the engine's GPL license.
