import Foundation

public enum MATRIXProfileKind: Equatable {
    case wireGuard
    case amneziaWG
    case openVPN
    case unknown
}

public struct MATRIXProfileInspection: Equatable {
    public let kind: MATRIXProfileKind
    public let hasInterfaceSection: Bool
    public let hasPeerSection: Bool
    public let hasPrivateKey: Bool

    public init(kind: MATRIXProfileKind, hasInterfaceSection: Bool, hasPeerSection: Bool, hasPrivateKey: Bool) {
        self.kind = kind
        self.hasInterfaceSection = hasInterfaceSection
        self.hasPeerSection = hasPeerSection
        self.hasPrivateKey = hasPrivateKey
    }
}

public enum MATRIXProfileInspector {
    /// Inspects configuration structure only. Never returns or logs key material.
    public static func inspect(_ text: String) -> MATRIXProfileInspection {
        let lines = text.components(separatedBy: .newlines).map {
            $0.trimmingCharacters(in: .whitespacesAndNewlines)
        }
        let activeLines = lines.filter { !$0.isEmpty && !$0.hasPrefix("#") && !$0.hasPrefix(";") }
        let normalized = activeLines.map { $0.lowercased() }
        let joined = normalized.joined(separator: "\n")

        let isOpenVPN = normalized.contains(where: { $0 == "client" || $0.hasPrefix("remote ") })
            && (joined.contains("remote ") || joined.contains("<openvpn>"))
        if isOpenVPN {
            return MATRIXProfileInspection(kind: .openVPN, hasInterfaceSection: false, hasPeerSection: false, hasPrivateKey: false)
        }

        let hasInterface = normalized.contains("[interface]")
        let hasPeer = normalized.contains("[peer]")
        let hasPrivateKey = normalized.contains(where: {
            $0.hasPrefix("privatekey") && $0.split(separator: "=", maxSplits: 1).count == 2
                && !$0.split(separator: "=", maxSplits: 1)[1].trimmingCharacters(in: .whitespaces).isEmpty
        })

        // AmneziaWG parameters may be placed in either Interface or Peer.
        // Match keys rather than exact spacing so tabs and compact formatting work.
        let amneziaKeys: Set<String> = ["jc", "jmin", "jmax", "s1", "s2", "h1", "h2", "h3", "h4"]
        let isAmnezia = normalized.contains { line in
            guard let key = line.split(separator: "=", maxSplits: 1).first else { return false }
            return amneziaKeys.contains(key.trimmingCharacters(in: .whitespaces))
        }

        let kind: MATRIXProfileKind = hasInterface && hasPeer
            ? (isAmnezia ? .amneziaWG : .wireGuard)
            : .unknown
        return MATRIXProfileInspection(kind: kind, hasInterfaceSection: hasInterface, hasPeerSection: hasPeer, hasPrivateKey: hasPrivateKey)
    }
}
