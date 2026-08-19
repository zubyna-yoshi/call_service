import Foundation
import XCTest
@testable import EmployeeCallerID

final class DirectoryCoreTests: XCTestCase {
    func testEndpointBuilderAppendsDirectoryPathToBaseRoot() throws {
        let baseURL = try XCTUnwrap(URL(string: "https://directory.example.invalid"))

        XCTAssertEqual(
            DirectoryEndpointBuilder.endpoint(from: baseURL).absoluteString,
            "https://directory.example.invalid/v1/directory"
        )
    }

    func testEndpointBuilderKeepsCompleteDirectoryEndpoint() throws {
        let endpoint = try XCTUnwrap(
            URL(string: "https://directory.example.invalid/v1/directory")
        )

        XCTAssertEqual(DirectoryEndpointBuilder.endpoint(from: endpoint), endpoint)

        let endpointWithTrailingSlash = try XCTUnwrap(
            URL(string: "https://directory.example.invalid/v1/directory/")
        )
        XCTAssertEqual(
            DirectoryEndpointBuilder.endpoint(from: endpointWithTrailingSlash),
            endpoint
        )
    }

    func testETagValidatorAcceptsEntityTagsAndRejectsUnsafeValues() {
        XCTAssertEqual(DirectoryETagValidator.validHeaderValue("\"sha256-value\""), "\"sha256-value\"")
        XCTAssertEqual(DirectoryETagValidator.validHeaderValue("W/\"weak-value\""), "W/\"weak-value\"")
        XCTAssertNil(DirectoryETagValidator.validHeaderValue("unquoted"))
        XCTAssertNil(DirectoryETagValidator.validHeaderValue("\"unsafe\r\nvalue\""))
        XCTAssertNil(
            DirectoryETagValidator.validHeaderValue(
                "\"" + String(repeating: "a", count: DirectoryETagValidator.maximumByteCount) + "\""
            )
        )
    }

    func testBearerTokenValidatorEnforcesServerUTF8ByteLimits() {
        XCTAssertNil(
            DirectoryBearerTokenValidator.validatedToken(
                String(repeating: "a", count: DirectoryBearerTokenValidator.minimumByteCount - 1)
            )
        )
        XCTAssertEqual(
            DirectoryBearerTokenValidator.validatedToken(String(repeating: "a", count: 32)),
            String(repeating: "a", count: 32)
        )
        XCTAssertEqual(
            DirectoryBearerTokenValidator.validatedToken(String(repeating: "가", count: 11)),
            String(repeating: "가", count: 11)
        )
        XCTAssertNil(
            DirectoryBearerTokenValidator.validatedToken(
                String(repeating: "a", count: DirectoryBearerTokenValidator.maximumByteCount + 1)
            )
        )
        XCTAssertNil(
            DirectoryBearerTokenValidator.validatedToken(
                String(repeating: "a", count: 32) + "\n" + String(repeating: "b", count: 32)
            )
        )
    }

    func testE164ConversionProducesPositiveCallKitInteger() throws {
        XCTAssertEqual(
            try PhoneNumberNormalizer.callDirectoryNumber(fromE164: "+821012345678"),
            821012345678
        )
    }

    func testE164ConversionRejectsLocalAndFormattedNumbers() {
        XCTAssertThrowsError(try PhoneNumberNormalizer.callDirectoryNumber(fromE164: "01012345678"))
        XCTAssertThrowsError(try PhoneNumberNormalizer.callDirectoryNumber(fromE164: "+82 10 1234 5678"))
        XCTAssertThrowsError(try PhoneNumberNormalizer.callDirectoryNumber(fromE164: "+012345"))
        XCTAssertThrowsError(try PhoneNumberNormalizer.callDirectoryNumber(fromE164: "+1234567890123456"))
    }

    func testIndexBuilderSortsAndCollapsesExactDuplicates() throws {
        let records = [
            makeEntry(phone: "+821055555555", label: "Team B · User B"),
            makeEntry(phone: "+82211112222", label: "Team A · User A"),
            makeEntry(phone: "+821055555555", label: "Team B · User B")
        ]

        let result = try DirectoryIndexBuilder.build(from: records)

        XCTAssertEqual(result.count, 2)
        XCTAssertEqual(result.map(\.phoneNumber), [82211112222, 821055555555])
    }

    func testIndexBuilderRejectsConflictingDuplicate() {
        let records = [
            makeEntry(phone: "+821055555555", label: "Team A · User A"),
            makeEntry(phone: "+821055555555", label: "Team B · User B")
        ]

        XCTAssertThrowsError(try DirectoryIndexBuilder.build(from: records)) { error in
            XCTAssertEqual(error as? DirectoryIndexError, .conflictingDuplicate)
        }
    }

    func testIndexBuilderBuildsSingleLineFallbackLabel() throws {
        let record = makeEntry(
            phone: "+821055555555",
            label: nil,
            name: "User\nA",
            organization: "Team   A"
        )

        let result = try DirectoryIndexBuilder.build(from: [record])

        XCTAssertEqual(result.first?.label, "Team A · User A")
    }

    func testIndexBuilderRejectsOversizedLabel() {
        let record = makeEntry(phone: "+821055555555", label: String(repeating: "A", count: 181))

        XCTAssertThrowsError(try DirectoryIndexBuilder.build(from: [record])) { error in
            XCTAssertEqual(error as? DirectoryIndexError, .invalidLabel)
        }
    }

    func testExampleJSONDecodesWithoutServer() throws {
        let fixtureURL = try XCTUnwrap(
            Bundle(for: Self.self).url(forResource: "directory-response", withExtension: "json")
        )
        let payload = try DirectoryResponseDecoder.decode(Data(contentsOf: fixtureURL))

        XCTAssertEqual(payload.version, "7")
        XCTAssertEqual(payload.entries.count, 2)
        XCTAssertEqual(payload.entries.first?.numberType, "office")
    }

    func testSnapshotRoundTrip() throws {
        let directoryURL = FileManager.default.temporaryDirectory
            .appendingPathComponent(UUID().uuidString, isDirectory: true)
        addTeardownBlock {
            try? FileManager.default.removeItem(at: directoryURL)
        }
        let store = DirectorySnapshotStore(directoryURL: directoryURL)
        let snapshot = DirectorySnapshot(
            formatVersion: DirectorySnapshot.currentFormatVersion,
            version: "test-version",
            generatedAt: "2030-01-01T00:00:00Z",
            savedAt: Date(timeIntervalSince1970: 123),
            etag: "test-etag",
            entries: [DirectoryEntry(phoneNumber: 821012345678, label: "Team A · User A")]
        )

        try store.write(snapshot)

        XCTAssertEqual(try store.read(), snapshot)
    }

    func testSnapshotStoreRejectsUnsortedEntries() throws {
        let directoryURL = FileManager.default.temporaryDirectory
            .appendingPathComponent(UUID().uuidString, isDirectory: true)
        addTeardownBlock {
            try? FileManager.default.removeItem(at: directoryURL)
        }
        let store = DirectorySnapshotStore(directoryURL: directoryURL)
        let snapshot = DirectorySnapshot(
            formatVersion: DirectorySnapshot.currentFormatVersion,
            version: "test-version",
            generatedAt: "2030-01-01T00:00:00Z",
            savedAt: Date(timeIntervalSince1970: 123),
            etag: nil,
            entries: [
                DirectoryEntry(phoneNumber: 821055555555, label: "Team B · User B"),
                DirectoryEntry(phoneNumber: 82211112222, label: "Team A · User A")
            ]
        )

        XCTAssertThrowsError(try store.write(snapshot)) { error in
            XCTAssertEqual(error as? DirectorySnapshotStoreError, .invalidEntries)
        }
    }

    func testSyncSendsBearerAndETagAndHandlesNotModified() async throws {
        let directoryURL = FileManager.default.temporaryDirectory
            .appendingPathComponent(UUID().uuidString, isDirectory: true)
        addTeardownBlock {
            try? FileManager.default.removeItem(at: directoryURL)
        }
        let store = DirectorySnapshotStore(directoryURL: directoryURL)
        try store.write(
            DirectorySnapshot(
                formatVersion: DirectorySnapshot.currentFormatVersion,
                version: "6",
                generatedAt: "2030-01-01T00:00:00Z",
                savedAt: Date(timeIntervalSince1970: 123),
                etag: "\"test-etag-6\"",
                entries: [DirectoryEntry(phoneNumber: 821012345678, label: "Team A · User A")]
            )
        )

        let responseURL = try XCTUnwrap(URL(string: "https://example.invalid/v1/directory"))
        let response = try XCTUnwrap(
            HTTPURLResponse(
                url: responseURL,
                statusCode: 304,
                httpVersion: "HTTP/2",
                headerFields: [:]
            )
        )
        let transport = StubTransport(data: Data(), response: response)
        let client = DirectorySyncClient(transport: transport, snapshotStore: store)
        let token = String(repeating: "t", count: 32)

        let outcome = try await client.sync(
            baseURL: try XCTUnwrap(URL(string: "https://example.invalid")),
            bearerToken: token
        )

        XCTAssertEqual(outcome, .notModified(entryCount: 1, version: "6"))
        XCTAssertEqual(transport.lastRequest?.value(forHTTPHeaderField: "Authorization"), "Bearer \(token)")
        XCTAssertEqual(transport.lastRequest?.value(forHTTPHeaderField: "If-None-Match"), "\"test-etag-6\"")
        XCTAssertEqual(transport.lastRequest?.url?.path, "/v1/directory")
    }

    private func makeEntry(
        phone: String,
        label: String?,
        name: String? = nil,
        organization: String? = nil
    ) -> DirectoryAPIEntry {
        DirectoryAPIEntry(
            phoneNumber: phone,
            label: label,
            name: name,
            organization: organization,
            numberType: "office"
        )
    }
}

private final class StubTransport: HTTPTransport {
    private let data: Data
    private let response: HTTPURLResponse
    private(set) var lastRequest: URLRequest?

    init(data: Data, response: HTTPURLResponse) {
        self.data = data
        self.response = response
    }

    func data(for request: URLRequest) async throws -> (Data, HTTPURLResponse) {
        lastRequest = request
        return (data, response)
    }
}
