import SwiftUI
import UniformTypeIdentifiers
import NetworkExtension
import MATRIXCore

@main
struct MATRIXApp: App {
    var body: some Scene {
        WindowGroup { ContentView() }
    }
}

struct ContentView: View {
    @State private var showingImporter = false
    @State private var profileName = "Профиль не выбран"
    @State private var status = "Импортируйте конфигурацию WireGuard или AmneziaWG"
    @State private var detectedKind: MATRIXProfileKind = .unknown
    @State private var profileIdentifier: UUID?
    @State private var tunnelManager: NETunnelProviderManager?
    @State private var isConnecting = false

    var body: some View {
        ZStack {
            Color(red: 0.035, green: 0.045, blue: 0.065).ignoresSafeArea()
            VStack(spacing: 22) {
                HStack(spacing: 10) {
                    Image(systemName: "shield.lefthalf.filled").font(.system(size: 30, weight: .bold)).foregroundStyle(.yellow)
                    Text("MATRIX").font(.system(size: 27, weight: .black, design: .rounded)).foregroundStyle(.white)
                    Spacer()
                    Text("iOS").font(.caption.bold()).foregroundStyle(.yellow)
                }
                .padding(.top, 18)

                Spacer()
                Image(systemName: connectionIcon)
                    .font(.system(size: 74)).foregroundStyle(.yellow)
                Text("Твой туннель. Твои правила.")
                    .font(.title2.bold()).foregroundStyle(.white)
                Text(profileName).font(.headline).foregroundStyle(.white.opacity(0.8))
                Text(status).font(.subheadline).multilineTextAlignment(.center)
                    .foregroundStyle(.white.opacity(0.65))

                Button { showingImporter = true } label: {
                    Label("Импортировать профиль", systemImage: "doc.badge.plus")
                        .font(.headline).frame(maxWidth: .infinity).padding()
                        .background(Color.yellow, in: RoundedRectangle(cornerRadius: 15))
                        .foregroundStyle(.black)
                }
                .fileImporter(isPresented: $showingImporter,
                              allowedContentTypes: [.plainText, .data],
                              allowsMultipleSelection: false) { result in
                    importProfile(result)
                }

                if profileIdentifier != nil && (detectedKind == .wireGuard || detectedKind == .amneziaWG) {
                    Button(action: toggleVPN) {
                        HStack {
                            if isConnecting { ProgressView().tint(.black) }
                            Text(buttonTitle).font(.headline)
                        }
                        .frame(maxWidth: .infinity).padding()
                        .background(isVPNActive ? Color.white : Color(red: 0.20, green: 0.75, blue: 0.48),
                                    in: RoundedRectangle(cornerRadius: 15))
                        .foregroundStyle(.black)
                    }
                    .disabled(isConnecting)
                }

                Text(kindLabel).font(.caption.bold()).foregroundStyle(.yellow)
                Spacer()
                Text("MATRIX • AmneziaWG / WireGuard")
                    .font(.caption2).foregroundStyle(.white.opacity(0.4))
            }
            .padding(24)
        }
        .preferredColorScheme(.dark)
        .onReceive(NotificationCenter.default.publisher(for: .NEVPNStatusDidChange)) { _ in
            refreshVPNStatus()
        }
        .onAppear { loadExistingManager() }
    }

    private var isVPNActive: Bool {
        guard let status = tunnelManager?.connection.status else { return false }
        return status == .connected || status == .connecting || status == .reasserting
    }

    private var buttonTitle: String {
        isVPNActive ? "Отключить MATRIX" : "Подключить MATRIX"
    }

    private var connectionIcon: String {
        isVPNActive ? "lock.shield.fill" : "lock.shield"
    }

    private var kindLabel: String {
        switch detectedKind {
        case .wireGuard: return "Формат: WireGuard"
        case .amneziaWG: return "Формат: AmneziaWG"
        case .openVPN: return "Формат: OpenVPN (движок ещё не подключён)"
        case .unknown: return "Формат: не определён"
        }
    }

    private func importProfile(_ result: Result<[URL], Error>) {
        do {
            guard let url = try result.get().first else { return }
            let access = url.startAccessingSecurityScopedResource()
            defer { if access { url.stopAccessingSecurityScopedResource() } }
            let contents = try String(contentsOf: url, encoding: .utf8)
            let inspection = MATRIXProfileInspector.inspect(contents)
            detectedKind = inspection.kind
            profileName = url.lastPathComponent

            guard inspection.hasInterfaceSection && inspection.hasPeerSection && inspection.hasPrivateKey,
                  inspection.kind == .wireGuard || inspection.kind == .amneziaWG else {
                profileIdentifier = nil
                status = inspection.kind == .openVPN
                    ? "OpenVPN распознан, но его отдельный движок пока не встроен."
                    : "Конфигурация неполная или не поддерживается."
                return
            }

            let identifier = UUID()
            try MATRIXProfileVault.save(contents, identifier: identifier)
            profileIdentifier = identifier
            status = "Профиль сохранён в защищённом хранилище. Можно подключаться."
        } catch {
            profileIdentifier = nil
            status = "Ошибка импорта: \(error.localizedDescription)"
        }
    }

    private func toggleVPN() {
        if isVPNActive {
            tunnelManager?.connection.stopVPNTunnel()
            status = "Отключение MATRIX…"
            return
        }
        guard let profileIdentifier else { return }
        isConnecting = true
        status = "Подготавливаю защищённый туннель…"

        NETunnelProviderManager.loadAllFromPreferences { managers, error in
            if let error {
                finishWithError("Не удалось прочитать настройки VPN: \(error.localizedDescription)")
                return
            }
            let manager = managers?.first(where: {
                ($0.protocolConfiguration as? NETunnelProviderProtocol)?.providerBundleIdentifier == "org.matrixvpn.ios.PacketTunnel"
            }) ?? NETunnelProviderManager()

            let tunnelProtocol = NETunnelProviderProtocol()
            tunnelProtocol.providerBundleIdentifier = "org.matrixvpn.ios.PacketTunnel"
            tunnelProtocol.serverAddress = "MATRIX VPN"
            tunnelProtocol.providerConfiguration = ["MATRIXProfileID": profileIdentifier.uuidString]
            manager.protocolConfiguration = tunnelProtocol
            manager.localizedDescription = "MATRIX VPN"
            manager.isEnabled = true

            manager.saveToPreferences { saveError in
                if let saveError {
                    finishWithError("Не удалось сохранить VPN: \(saveError.localizedDescription)")
                    return
                }
                manager.loadFromPreferences { loadError in
                    if let loadError {
                        finishWithError("Не удалось загрузить VPN: \(loadError.localizedDescription)")
                        return
                    }
                    do {
                        try manager.connection.startVPNTunnel()
                        DispatchQueue.main.async {
                            tunnelManager = manager
                            isConnecting = false
                            status = "Запрос на подключение отправлен iOS."
                        }
                    } catch {
                        finishWithError("iOS не запустила VPN: \(error.localizedDescription)")
                    }
                }
            }
        }
    }

    private func finishWithError(_ message: String) {
        DispatchQueue.main.async {
            isConnecting = false
            status = message
        }
    }

    private func loadExistingManager() {
        NETunnelProviderManager.loadAllFromPreferences { managers, _ in
            let manager = managers?.first(where: {
                ($0.protocolConfiguration as? NETunnelProviderProtocol)?.providerBundleIdentifier == "org.matrixvpn.ios.PacketTunnel"
            })
            DispatchQueue.main.async { tunnelManager = manager }
        }
    }

    private func refreshVPNStatus() {
        guard let manager = tunnelManager else { return }
        DispatchQueue.main.async {
            switch manager.connection.status {
            case .connected:
                status = "MATRIX подключён."
                isConnecting = false
            case .connecting, .reasserting:
                status = "Устанавливается VPN-соединение…"
            case .disconnected:
                status = "MATRIX отключён."
                isConnecting = false
            case .disconnecting:
                status = "Отключение MATRIX…"
            case .invalid:
                status = "VPN ещё не настроен."
                isConnecting = false
            @unknown default:
                status = "Состояние VPN обновляется."
            }
        }
    }
}
