import Foundation

/// Stores imported VPN profiles in the app-group container rather than in
/// NETunnelProviderManager preferences, which must contain only an opaque ID.
public enum MATRIXProfileVault {
    public static let appGroupIdentifier = "group.org.matrixvpn.shared"

    public static func save(_ contents: String, identifier: UUID) throws {
        let directory = try sharedDirectory()
        let file = directory.appendingPathComponent(identifier.uuidString).appendingPathExtension("conf")
        try contents.write(to: file, atomically: true, encoding: .utf8)
        try FileManager.default.setAttributes(
            [.protectionKey: FileProtectionType.completeUntilFirstUserAuthentication],
            ofItemAtPath: file.path
        )
    }

    public static func load(identifier: UUID) throws -> String {
        let directory = try sharedDirectory()
        let file = directory.appendingPathComponent(identifier.uuidString).appendingPathExtension("conf")
        return try String(contentsOf: file, encoding: .utf8)
    }

    private static func sharedDirectory() throws -> URL {
        guard let url = FileManager.default.containerURL(
            forSecurityApplicationGroupIdentifier: appGroupIdentifier
        ) else {
            throw NSError(domain: "org.matrixvpn.vault", code: 1,
                          userInfo: [NSLocalizedDescriptionKey: "Общее хранилище MATRIX недоступно."])
        }
        try FileManager.default.createDirectory(at: url, withIntermediateDirectories: true)
        return url
    }
}
