import Foundation
import NetworkExtension
import WireGuardKit
import MATRIXCore

/// Packet tunnel backed by the AmneziaWG Apple WireGuardKit implementation.
final class PacketTunnelProvider: NEPacketTunnelProvider {
    private lazy var adapter = WireGuardAdapter(with: self) { _, message in
        // Never log the raw profile or private keys.
        NSLog("MATRIX VPN: %@", message)
    }

    override func startTunnel(options: [String: NSObject]?,
                              completionHandler: @escaping (Error?) -> Void) {
        guard let tunnelProtocol = protocolConfiguration as? NETunnelProviderProtocol,
              let rawIdentifier = tunnelProtocol.providerConfiguration?["MATRIXProfileID"] as? String,
              let identifier = UUID(uuidString: rawIdentifier) else {
            completionHandler(makeError(1001, "Не найден идентификатор профиля MATRIX."))
            return
        }

        do {
            let profile = try MATRIXProfileVault.load(identifier: identifier)
            let configuration = try TunnelConfiguration(fromWgQuickConfig: profile, called: "MATRIX")
            adapter.start(tunnelConfiguration: configuration) { error in
                guard let error else {
                    completionHandler(nil)
                    return
                }
                completionHandler(self.makeError(1002, "Не удалось запустить VPN: \(error)"))
            }
        } catch {
            completionHandler(makeError(1003, "Не удалось загрузить конфигурацию VPN: \(error.localizedDescription)"))
        }
    }

    override func stopTunnel(with reason: NEProviderStopReason,
                             completionHandler: @escaping () -> Void) {
        adapter.stop { _ in completionHandler() }
    }

    private func makeError(_ code: Int, _ message: String) -> NSError {
        NSError(domain: "org.matrixvpn.ios", code: code,
                userInfo: [NSLocalizedDescriptionKey: message])
    }
}
