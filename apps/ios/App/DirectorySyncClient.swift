import Foundation

protocol HTTPTransport {
    func data(for request: URLRequest) async throws -> (Data, HTTPURLResponse)
}

private final class RejectingRedirectDelegate: NSObject, URLSessionTaskDelegate {
    func urlSession(
        _ session: URLSession,
        task: URLSessionTask,
        willPerformHTTPRedirection response: HTTPURLResponse,
        newRequest request: URLRequest,
        completionHandler: @escaping (URLRequest?) -> Void
    ) {
        // An API endpoint redirect is a configuration error. Refusing it keeps the
        // Bearer credential from being replayed to an unexpected destination.
        completionHandler(nil)
    }
}

struct URLSessionTransport: HTTPTransport {
    let session: URLSession

    init(session: URLSession? = nil) {
        if let session {
            self.session = session
        } else {
            let configuration = URLSessionConfiguration.ephemeral
            configuration.requestCachePolicy = .reloadIgnoringLocalCacheData
            configuration.urlCache = nil
            configuration.httpShouldSetCookies = false
            self.session = URLSession(
                configuration: configuration,
                delegate: RejectingRedirectDelegate(),
                delegateQueue: nil
            )
        }
    }

    func data(for request: URLRequest) async throws -> (Data, HTTPURLResponse) {
        let (data, response) = try await session.data(for: request)
        guard let httpResponse = response as? HTTPURLResponse else {
            throw DirectorySyncError.nonHTTPResponse
        }
        return (data, httpResponse)
    }
}

enum DirectorySyncOutcome: Equatable {
    case updated(entryCount: Int, version: String)
    case notModified(entryCount: Int, version: String?)

    var entryCount: Int {
        switch self {
        case .updated(let count, _), .notModified(let count, _): return count
        }
    }
}

enum DirectoryEndpointBuilder {
    private static let endpointPathSuffix = ["v1", "directory"]

    static func endpoint(from configuredURL: URL) -> URL {
        let pathComponents = configuredURL.path
            .split(separator: "/", omittingEmptySubsequences: true)
            .map(String.init)

        if pathComponents.suffix(endpointPathSuffix.count).elementsEqual(endpointPathSuffix) {
            // The server route is exact, so normalize a user-entered trailing slash.
            var components = URLComponents(
                url: configuredURL,
                resolvingAgainstBaseURL: false
            )
            components?.path = "/" + pathComponents.joined(separator: "/")
            return components?.url ?? configuredURL
        }

        return configuredURL
            .appendingPathComponent("v1", isDirectory: true)
            .appendingPathComponent("directory", isDirectory: false)
    }
}

enum DirectoryETagValidator {
    // This is deliberately much smaller than the response-body ceiling. The
    // service emits a quoted SHA-256 tag, while the bound avoids persisting or
    // replaying an unexpectedly large HTTP header from a misconfigured server.
    static let maximumByteCount = 1_024

    static func validHeaderValue(_ candidate: String?) -> String? {
        guard
            let candidate,
            !candidate.isEmpty,
            candidate.utf8.count <= maximumByteCount
        else {
            return nil
        }

        let opaqueTag: Substring
        if candidate.hasPrefix("W/") {
            opaqueTag = candidate.dropFirst(2)
        } else {
            opaqueTag = candidate[...]
        }

        guard opaqueTag.count >= 2, opaqueTag.first == "\"", opaqueTag.last == "\"" else {
            return nil
        }

        let contents = opaqueTag.dropFirst().dropLast()
        guard contents.unicodeScalars.allSatisfy({ scalar in
            let value = scalar.value
            return value == 0x21 || (0x23...0x7E).contains(value)
        }) else {
            return nil
        }
        return candidate
    }
}

enum DirectoryBearerTokenValidator {
    static let minimumByteCount = 32
    static let maximumByteCount = 16 * 1_024

    static func validatedToken(_ rawValue: String) -> String? {
        let token = rawValue.trimmingCharacters(in: .whitespacesAndNewlines)
        let byteCount = token.utf8.count
        guard
            (minimumByteCount...maximumByteCount).contains(byteCount),
            token.rangeOfCharacter(from: .newlines) == nil
        else {
            return nil
        }
        return token
    }
}

struct DirectorySyncClient {
    private static let maximumResponseSize = 10 * 1_024 * 1_024

    private let transport: HTTPTransport
    private let snapshotStore: DirectorySnapshotStore

    init(
        transport: HTTPTransport = URLSessionTransport(),
        snapshotStore: DirectorySnapshotStore
    ) {
        self.transport = transport
        self.snapshotStore = snapshotStore
    }

    func sync(baseURL: URL, bearerToken: String) async throws -> DirectorySyncOutcome {
        guard let token = DirectoryBearerTokenValidator.validatedToken(bearerToken) else {
            throw DirectorySyncError.invalidToken
        }

        // A malformed or outdated local snapshot must not permanently block a
        // full recovery download. In that case omit If-None-Match and replace it.
        let existingSnapshot = try? snapshotStore.read()
        let endpoint = DirectoryEndpointBuilder.endpoint(from: baseURL)
        var request = URLRequest(url: endpoint)
        request.httpMethod = "GET"
        request.timeoutInterval = 20
        request.setValue("Bearer \(token)", forHTTPHeaderField: "Authorization")
        request.setValue("application/json", forHTTPHeaderField: "Accept")
        request.setValue("no-store", forHTTPHeaderField: "Cache-Control")
        if let etag = DirectoryETagValidator.validHeaderValue(existingSnapshot?.etag) {
            request.setValue(etag, forHTTPHeaderField: "If-None-Match")
        }

        let (data, response) = try await transport.data(for: request)
        if response.statusCode == 304 {
            guard let existingSnapshot else {
                throw DirectorySyncError.notModifiedWithoutSnapshot
            }
            return .notModified(
                entryCount: existingSnapshot.entries.count,
                version: existingSnapshot.version
            )
        }
        guard response.statusCode == 200 else {
            throw DirectorySyncError.httpStatus(response.statusCode)
        }
        guard data.count <= Self.maximumResponseSize else {
            throw DirectorySyncError.responseTooLarge
        }

        let responsePayload: DirectoryAPIResponse
        do {
            responsePayload = try DirectoryResponseDecoder.decode(data)
        } catch {
            throw DirectorySyncError.invalidPayload
        }
        guard
            !responsePayload.version.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty,
            !responsePayload.generatedAt.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty
        else {
            throw DirectorySyncError.invalidPayload
        }

        let entries: [DirectoryEntry]
        do {
            entries = try DirectoryIndexBuilder.build(from: responsePayload.entries)
        } catch {
            throw DirectorySyncError.invalidDirectory
        }

        let snapshot = DirectorySnapshot(
            formatVersion: DirectorySnapshot.currentFormatVersion,
            version: responsePayload.version,
            generatedAt: responsePayload.generatedAt,
            savedAt: Date(),
            etag: DirectoryETagValidator.validHeaderValue(
                response.value(forHTTPHeaderField: "ETag")
            ),
            entries: entries
        )
        try snapshotStore.write(snapshot)
        return .updated(entryCount: entries.count, version: responsePayload.version)
    }
}

enum DirectorySyncError: LocalizedError {
    case invalidToken
    case nonHTTPResponse
    case httpStatus(Int)
    case notModifiedWithoutSnapshot
    case responseTooLarge
    case invalidPayload
    case invalidDirectory

    var errorDescription: String? {
        switch self {
        case .invalidToken:
            return "접근 토큰은 UTF-8 기준 32바이트 이상, 16 KiB 이하여야 합니다."
        case .nonHTTPResponse:
            return "서버에서 올바른 HTTP 응답을 받지 못했습니다."
        case .httpStatus(let status):
            return "명부 서버 요청에 실패했습니다. (HTTP \(status))"
        case .notModifiedWithoutSnapshot:
            return "서버가 로컬 명부 없이 변경 없음 응답을 반환했습니다."
        case .responseTooLarge:
            return "명부 응답이 허용 크기를 초과했습니다."
        case .invalidPayload:
            return "명부 응답 형식이 올바르지 않습니다."
        case .invalidDirectory:
            return "명부에 잘못된 번호, 레이블 또는 충돌 항목이 있습니다. 기존 명부를 유지합니다."
        }
    }
}
