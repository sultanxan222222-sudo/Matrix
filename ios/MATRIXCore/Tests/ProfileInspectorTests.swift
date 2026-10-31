import XCTest
@testable import MATRIXCore

final class ProfileInspectorTests: XCTestCase {
    func testRecognizesWireGuardProfile() {
        let text = "[Interface]\nPrivateKey = hidden\n[Peer]\nPublicKey = peer"
        let result = MATRIXProfileInspector.inspect(text)
        XCTAssertEqual(result.kind, .wireGuard)
        XCTAssertTrue(result.hasPrivateKey)
    }

    func testRecognizesAmneziaExtensionsWithFlexibleSpacing() {
        let text = "[Interface]\nPrivateKey=hidden\n[Peer]\nPublicKey=peer\nJc\t= 4\nH4=123456"
        XCTAssertEqual(MATRIXProfileInspector.inspect(text).kind, .amneziaWG)
    }

    func testRecognizesExtendedAmneziaParameters() {
        let text = "[Interface]\nPrivateKey = hidden\nI5 = custom\n[Peer]\nPublicKey = peer"
        XCTAssertEqual(MATRIXProfileInspector.inspect(text).kind, .amneziaWG)
    }

    func testRecognizesOpenVPNProfile() {
        XCTAssertEqual(MATRIXProfileInspector.inspect("client\nremote vpn.example 1194").kind, .openVPN)
    }

    func testCommentsDoNotCreateFalseAmneziaDetection() {
        let text = "[Interface]\nPrivateKey = hidden\n# Jc = 4\n[Peer]\nPublicKey = peer"
        XCTAssertEqual(MATRIXProfileInspector.inspect(text).kind, .wireGuard)
    }

    func testEmptyPrivateKeyIsNotReportedAsPresent() {
        let text = "[Interface]\nPrivateKey =   \n[Peer]\nPublicKey = peer"
        XCTAssertFalse(MATRIXProfileInspector.inspect(text).hasPrivateKey)
    }

    func testRejectsOversizedAndBinaryLikeText() {
        XCTAssertEqual(MATRIXProfileInspector.inspect(String(repeating: "x", count: 2_000_001)).kind, .unknown)
        XCTAssertEqual(MATRIXProfileInspector.inspect("abc\u{0000}def").kind, .unknown)
    }

    func testUnknownTextDoesNotBecomeAProfile() {
        XCTAssertEqual(MATRIXProfileInspector.inspect("hello world").kind, .unknown)
    }
}
