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
    /// Inspects format and structure only. Never returns or logs key material.
    public static func inspect(_ text: String) -> MATRIXProfileInspection {
        guard !text.isEmpty, text.utf8.count <= 2_000_000, !text.contains("\u{0000}") else {
            return MATRIXProfileInspection(kind: .unknown, hasInterfaceSection: false, hasPeerSection: false, hasPrivateKey: false)
        }

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
            guard let separator = $0.firstIndex(of: "=") else { return false }
            return $0[..<separator].trimmingCharacters(in: .whitespaces) == "privatekey"
                && !$0[$0.index(after: separator)...].trimmingCharacters(in: .whitespaces).isEmpty
        })

        // These markers are supported by the pinned AmneziaWG Apple parser/engine.
        let amneziaKeys: Set<String> = [
            "jc", "jmin", "jmax", "s1", "s2", "s3", "s4",
            "h1", "h2", "h3", "h4", "i1", "i2", "i3", "i4", "i5",
            "headerprotectionkey", "contentpaddingaddition",
            "rekeyaftertime", "rekeytimeout", "rejectaftertime",
            "keepalivetimeout", "maxhandshakeattempts", "randomtrailers", "disablecookies"
        ]
        let isAmnezia = normalized.contains { line in
            guard let separator = line.firstIndex(of: "=") else { return false }
            let key = line[..<separator].trimmingCharacters(in: .whitespaces)
            return amneziaKeys.contains(key)
        }

        let kind: MATRIXProfileKind = hasInterface && hasPeer
            ? (isAmnezia ? .amneziaWG : .wireGuard)
            : .unknown
        return MATRIXProfileInspection(kind: kind, hasInterfaceSection: hasInterface, hasPeerSection: hasPeer, hasPrivateKey: hasPrivateKey)
    }
}
