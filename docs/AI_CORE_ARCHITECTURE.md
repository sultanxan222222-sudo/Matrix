# MATRIX AI CORE — Architecture Contract

Status: design baseline  
Target branch: `feature/matrix-ai-core`  
Base: `main`

## 1. Product goal

MATRIX is an Android VPN client with an adaptive, locally controlled recovery system. Its AI component is a bounded network operator: it observes connection health, forms testable hypotheses, chooses from pre-installed and explicitly permitted network actions, verifies the result, and remembers non-secret outcomes.

The goal is reliable connectivity on difficult networks. No transport can guarantee access through every real allowlist. A relay or gateway must be reachable and authorized by the network in question.

## 2. Non-negotiable safety contract

The AI is not an unrestricted agent and must never be given a shell, arbitrary code execution, unrestricted filesystem access, or an unrestricted socket API.

All network and system operations go through MATRIX Guard and typed adapters.

The following invariants are mandatory:

1. **Least privilege:** the AI can request only documented actions from a finite allowlist.
2. **No self-expansion:** it cannot grant itself permissions, install packages, load native code, change its own executable, or alter Guard policy.
3. **No arbitrary destinations:** network adapters accept endpoints only from a signed/validated configuration or an explicitly user-approved import. The AI cannot invent arbitrary hosts to probe.
4. **No secret access:** private keys, passwords, authentication tokens, and raw profile contents are not exposed to the decision engine or learning store.
5. **No destructive experimentation:** changes are staged, validated, bounded, and reversible. Cryptographic algorithms, trust roots, certificate validation, and Android security settings are not weakened.
6. **Fail closed:** when the user enables Android's always-on VPN lockdown, traffic must not silently fall back outside the tunnel. The app must accurately report when lockdown is unavailable or disabled.
7. **Human control:** the user can stop recovery, disconnect, clear learned telemetry, and revoke imported profiles.
8. **Resource limits:** recovery attempts use capped concurrency, timeouts, exponential backoff with jitter, battery-aware scheduling, and per-endpoint rate limits.
9. **Auditable decisions:** every action has a reason code and a bounded diagnostic record. Secrets and payload contents are never logged.
10. **Signed updates only:** new executable code arrives through a verified, signed application update process; the AI cannot update or rewrite itself.

## 3. Separation of responsibilities

### MATRIX AI Core — decision engine

Consumes sanitized observations and returns a typed plan. It does not open sockets, read credentials, manipulate Android VPN state directly, or execute generated code.

### MATRIX Guard — policy enforcement point

Validates every proposed action against immutable policy, user settings, endpoint trust, rate limits, and current state. Guard can reject any plan. Guard is deterministic and does not rely on the AI to enforce its own restrictions.

### MANE — orchestration and recovery state machine

Owns the lifecycle of connection attempts, verification, recovery, backoff, cancellation, and handover. It executes only Guard-approved plans through registered adapters.

### Network Broker and transport adapters — controlled I/O

Provide narrow operations such as checking a configured endpoint, requesting a profile from a configured provider, or starting/stopping a registered transport. No adapter may expose a generic arbitrary-network API to the AI.

### VPN data plane

Uses the selected, authenticated tunnel implementation. The data plane remains separate from AI reasoning. Only one Android VPN service is active for the app/user profile; transports that cannot coexist are not run as competing VPN services.

## 4. Recovery state machine

Required states:

- IDLE
- PRECHECK
- SELECTING
- CONNECTING
- VERIFYING_TUNNEL
- VERIFYING_ROUTE
- CONNECTED
- DEGRADED
- RECOVERING
- BACKOFF
- USER_STOPPED
- NEEDS_USER_ACTION
- FATAL_CONFIGURATION_ERROR

A connection is not reported as healthy merely because the Android VPN interface started. Verification must distinguish:
- tunnel interface started;
- authenticated peer/transport established, where the backend exposes reliable evidence;
- DNS and route checks passed;
- controlled end-to-end connectivity probe succeeded.

When evidence is unavailable, the UI must state that it is unverified rather than infer success.

Recovery loops must terminate or wait safely on user cancellation, invalid credentials, certificate/trust failures, unsupported configuration, repeated identical failures, or exhausted bounded retries. For transient failures, retries continue with backoff rather than spinning continuously.

## 5. Decision and learning model

Start with a deterministic policy engine and explicit failure taxonomy. A compact on-device ranking model may be introduced only after reliable telemetry and a labeled evaluation set exist.

The model may rank pre-approved options using sanitized features such as:
- current transport type (Wi-Fi/cellular);
- coarse network capability signals;
- recent success/failure reason codes;
- latency and loss measurements;
- adapter availability and configuration compatibility;
- battery/network cost preferences.

It must not learn from packet payloads, browsing history, message content, private keys, passwords, or personal files.

Learning is advisory: it ranks allowed actions but cannot expand the action set or override Guard. Model updates are versioned, validated, and rollback-capable. Local history must be resettable by the user.

## 6. Transport strategy

Adapters are modular and independently testable. The initial inventory must reflect what actually exists in the repository:

- **AmneziaWG/WireGuard:** existing embedded backend; validate real device handshake, route, DNS, IPv4/IPv6, and reconnect behavior.
- **VPN Gate/OpenVPN:** current code discovers VPN Gate relays and hands OpenVPN profiles to an external compatible app. Direct in-app OpenVPN is not implemented yet and must not be described as working until a native backend is integrated and tested.
- **TLS/WebTunnel-style relay:** a future MATRIX-controlled gateway and client transport, requiring server infrastructure and a reachable/authorized entry point. WebTunnel is a transport design, not a drop-in VPN server.
- **Snowflake/Tor:** research-only optional transport integration. Snowflake provides a Tor transport path and is not itself a generic VPN exit. Any integration requires a separately designed and tested routing architecture.

Transport selection must be based on actual compatibility and successful verification, not protocol labels or optimistic assumptions. The engine must never impersonate an unrelated allowlisted service, bypass certificate validation, or assume that TLS camouflage defeats a strict IP/domain allowlist.

## 7. Android isolation and platform constraints

- Keep a single VPN ownership path and avoid competing VpnService instances.
- Keep AI/Guard components in the app's least-privileged process context; consider a separate process only where it demonstrably improves fault containment.
- Use Android Keystore for local protection of sensitive stored material.
- Do not request accessibility, contacts, microphone, location, or broad file permissions for the AI.
- Use Android's supported VPN lockdown/always-on controls only with explicit user consent.
- A crash or restart of the AI must not corrupt the VPN data plane or leave stale state. MANE/Watchdog must reconcile actual tunnel state after process recreation.

## 8. Diagnostics and privacy

Diagnostics are opt-in or locally visible, minimized, and secret-free. Store reason codes, adapter identifiers, timestamps, coarse network category, and measured outcomes. Do not store destination browsing history or packet content. Exported diagnostic reports must be reviewed and sanitized before sharing.

## 9. Delivery sequence and acceptance gates

### Phase A — repository and baseline verification
- Rebuild current main baseline.
- Run unit tests and inspect CI workflow.
- Verify current tunnel lifecycle and profile parsing.
- Record actual limitations; do not add speculative features.

### Phase B — Guard and typed action model
- Define immutable action types, policy checks, cancellation, rate limits, and tests.
- Prove disallowed actions are rejected.
- Add no AI-generated shell/code execution.

### Phase C — MANE deterministic recovery
- Implement the state machine and bounded retry policy.
- Add fake-adapter tests for timeout, DNS failure, endpoint failure, cancellation, and successful recovery.
- Verify no busy loops and no false-positive connected state.

### Phase D — transport adapters
- Validate the existing AmneziaWG path on-device.
- Design and integrate direct OpenVPN only if a maintained Android backend is suitable.
- Design a MATRIX-controlled TLS relay only with a reachable server endpoint and end-to-end tests.
- Keep Snowflake/Tor research isolated until routing and threat model are explicit.

### Phase E — AI decision layer and local learning
- Begin with a deterministic ranker.
- Add a compact local model only if it improves results over the baseline in repeatable tests.
- Prove model output cannot bypass Guard or increase privileges.

### Phase F — release qualification
- Unit and instrumentation tests; static/security checks; reproducible CI build.
- Real-device tests on Wi-Fi and cellular, network handover, server loss, tunnel crash, DNS/IPv6 leak checks, battery use, and lock-down behavior.
- No release candidate is final until all critical acceptance gates pass and the final APK artifact is produced and verified.

## 10. Definition of done

A final release is acceptable only when:
- all advertised transports are actually integrated, not merely listed in the UI;
- recovery decisions are bounded, explainable, and cancellable;
- the AI cannot perform actions outside the Guard allowlist;
- secrets and user traffic are not exposed to the AI or telemetry;
- failures are reported honestly;
- the app passes CI and real-device verification;
- the final APK is attached as a verified downloadable artifact.

A successful compile alone is not proof of VPN connectivity, censorship resistance, or leak protection.
