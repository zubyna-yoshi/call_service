import Foundation
import Security

struct KeychainTokenStore {
    private let service: String
    private let account = "directory-api-access-token"

    init(bundle: Bundle = .main) throws {
        guard let identifier = bundle.bundleIdentifier, !identifier.isEmpty else {
            throw KeychainTokenError.missingServiceIdentifier
        }
        service = identifier
    }

    func load() throws -> String? {
        var query = baseQuery
        query[kSecReturnData as String] = true
        query[kSecMatchLimit as String] = kSecMatchLimitOne

        var result: CFTypeRef?
        let status = SecItemCopyMatching(query as CFDictionary, &result)
        if status == errSecItemNotFound {
            return nil
        }
        guard status == errSecSuccess, let data = result as? Data else {
            throw KeychainTokenError.unhandledStatus(status)
        }
        guard let token = String(data: data, encoding: .utf8) else {
            throw KeychainTokenError.invalidStoredValue
        }
        return token
    }

    func save(_ token: String) throws {
        guard let data = token.data(using: .utf8) else {
            throw KeychainTokenError.invalidStoredValue
        }

        let attributes: [String: Any] = [
            kSecValueData as String: data,
            kSecAttrAccessible as String: kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly
        ]
        let updateStatus = SecItemUpdate(baseQuery as CFDictionary, attributes as CFDictionary)
        if updateStatus == errSecSuccess {
            return
        }
        guard updateStatus == errSecItemNotFound else {
            throw KeychainTokenError.unhandledStatus(updateStatus)
        }

        var insert = baseQuery
        attributes.forEach { insert[$0.key] = $0.value }
        let addStatus = SecItemAdd(insert as CFDictionary, nil)
        guard addStatus == errSecSuccess else {
            throw KeychainTokenError.unhandledStatus(addStatus)
        }
    }

    func delete() throws {
        let status = SecItemDelete(baseQuery as CFDictionary)
        guard status == errSecSuccess || status == errSecItemNotFound else {
            throw KeychainTokenError.unhandledStatus(status)
        }
    }

    private var baseQuery: [String: Any] {
        [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: service,
            kSecAttrAccount as String: account
        ]
    }
}

enum KeychainTokenError: LocalizedError {
    case missingServiceIdentifier
    case invalidStoredValue
    case unhandledStatus(OSStatus)

    var errorDescription: String? {
        switch self {
        case .missingServiceIdentifier:
            return "앱 식별자를 확인할 수 없어 Keychain을 사용할 수 없습니다."
        case .invalidStoredValue:
            return "Keychain 토큰 값이 올바르지 않습니다."
        case .unhandledStatus(let status):
            return "Keychain 작업에 실패했습니다. (상태 코드: \(status))"
        }
    }
}
