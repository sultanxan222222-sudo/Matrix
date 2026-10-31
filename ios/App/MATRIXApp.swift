import SwiftUI
import UniformTypeIdentifiers
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
    @State private var status = "Импортируйте конфигурацию для проверки формата"
    @State private var detectedKind: MATRIXProfileKind = .unknown

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
                Image(systemName: "lock.shield").font(.system(size: 74)).foregroundStyle(.yellow)
                Text("Твой туннель. Твои правила.").font(.title2.bold()).foregroundStyle(.white)
                Text(profileName).font(.headline).foregroundStyle(.white.opacity(0.8))
                Text(status).font(.subheadline).multilineTextAlignment(.center).foregroundStyle(.white.opacity(0.65))
                Button {
                    showingImporter = true
                } label: {
                    Label("Импортировать профиль", systemImage: "doc.badge.plus")
                        .font(.headline).frame(maxWidth: .infinity).padding()
                        .background(Color.yellow, in: RoundedRectangle(cornerRadius: 15))
                        .foregroundStyle(.black)
                }
                .fileImporter(isPresented: $showingImporter, allowedContentTypes: [.plainText, .data], allowsMultipleSelection: false) { result in
                    importProfile(result)
                }
                Text(kindLabel).font(.caption.bold()).foregroundStyle(.yellow)
                Spacer()
                Text("Предварительная iOS-сборка • VPN-движок ещё подключается")
                    .font(.caption2).foregroundStyle(.white.opacity(0.4))
            }
            .padding(24)
        }
        .preferredColorScheme(.dark)
    }

    private var kindLabel: String {
        switch detectedKind {
        case .wireGuard: return "Формат: WireGuard"
        case .amneziaWG: return "Формат: AmneziaWG"
        case .openVPN: return "Формат: OpenVPN (нужен отдельный движок)"
        case .unknown: return "Формат: не определён"
        }
    }

    private func importProfile(_ result: Result<[URL], Error>) {
        do {
            guard let url = try result.get().first else { return }
            let access = url.startAccessingSecurityScopedResource()
            defer { if access { url.stopAccessingSecurityScopedResource() } }
            let text = try String(contentsOf: url, encoding: .utf8)
            let inspection = MATRIXProfileInspector.inspect(text)
            detectedKind = inspection.kind
            profileName = url.lastPathComponent
            status = inspection.hasInterfaceSection && inspection.hasPeerSection
                ? "Структура профиля распознана. Подключение пока не включено."
                : "Файл прочитан, но структура VPN-профиля не подтверждена."
        } catch {
            status = "Не удалось прочитать файл: \(error.localizedDescription)"
        }
    }
}
