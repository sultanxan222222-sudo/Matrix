import NetworkExtension

/// Extension entry point. A real encrypted tunnel is intentionally not simulated here.
/// The provider will only be activated after a vetted WireGuard/AWG engine is integrated.
final class PacketTunnelProvider: NEPacketTunnelProvider {
    override func startTunnel(options: [String: NSObject]?,
                              completionHandler: @escaping (Error?) -> Void) {
        let error = NSError(
            domain: "org.matrixvpn.ios",
            code: 1001,
            userInfo: [NSLocalizedDescriptionKey: "VPN engine is not integrated yet."]
        )
        completionHandler(error)
    }

    override func stopTunnel(with reason: NEProviderStopReason,
                             completionHandler: @escaping () -> Void) {
        completionHandler()
    }
}
