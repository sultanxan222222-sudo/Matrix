import XCTest
@testable import MATRIXCore

final class ProfileInspectorTests: XCTestCase {
    func testRecognizesWireGuardProfile() {
        let text = "[Interface]\nPrivateKey = hidden\n[Peer]\nPublicKey = peer"
        XCTAssertEqual(MATRIXProfileInspector.inspect(text).kind, .wireGuard)
        XCTAssertTrue(MATRIXProfileInspector.inspect(text).hasPrivateKey)
    }

    func testRecognizesAmneziaExtensions() {
        let text = "[Interface]\nPrivateKey = hidden\nJc = 4\n[Peer]\nPublicKey = peer"
        XCTAssertEqual(MATRIXProfileInspector.inspect(text).kind, .amneziaWG)
    }

    func testRecognizesOpenVPNProfile() {
        XCTAssertEqual(MATRIXProfileInspector.inspect("client\nremote vpn.example 1194").kind, .openVPN)
    }

    func testUnknownTextDoesNotBecomeAProfile() {
        XCTAssertEqual(MATRIXProfileInspector.inspect("hello world").kind, .unknown)
    }
}
