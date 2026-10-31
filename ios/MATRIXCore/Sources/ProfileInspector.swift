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
    /// Classifies profile text without retaining it or exposing key material in diagnostics.
    public static func inspect(_ text: String) -> MATRIXProfileInspection {
        let normalized = text.lowercased()
        if normalized.contains("<openvpn>") || normalized.contains("client\n") && normalized.contains("remote ") {
            return MATRIXProfileInspection(kind: .openVPN, hasInterfaceSection: false, hasPeerSection: false, hasPrivateKey: false)
        }

        let hasInterface = normalized.contains("[interface]")
        let hasPeer = normalized.contains("[peer]")
        let hasPrivateKey = normalized.contains("privatekey") && normalized.contains("=")
        let isAmnezia = ["jc =", "jmin =", "jmax =", "s1 =", "s2 =", "h1 =", "h2 ="].contains { normalized.contains($0) }

        let kind: MATRIXProfileKind
        if hasInterface && hasPeer {
            kind = isAmnezia ? .amneziaWG : .wireGuard
        } else {
            kind = .unknown
        }
        return MATRIXProfileInspection(kind: kind, hasInterfaceSection: hasInterface, hasPeerSection: hasPeer, hasPrivateKey: hasPrivateKey)
    }
}
