import Foundation

enum SharedConfiguration {
    private static let appGroupInfoKey = "AppGroupIdentifier"
    private static let extensionIdentifierInfoKey = "CallDirectoryExtensionBundleIdentifier"

    static func appGroupIdentifier(bundle: Bundle = .main) throws -> String {
        try resolvedInfoValue(forKey: appGroupInfoKey, bundle: bundle)
    }

    static func callDirectoryExtensionIdentifier(bundle: Bundle = .main) throws -> String {
        try resolvedInfoValue(forKey: extensionIdentifierInfoKey, bundle: bundle)
    }

    private static func resolvedInfoValue(forKey key: String, bundle: Bundle) throws -> String {
        guard
            let value = bundle.object(forInfoDictionaryKey: key) as? String,
            !value.isEmpty,
            !value.contains("$(")
        else {
            if key == appGroupInfoKey {
                throw SharedConfigurationError.missingAppGroupIdentifier
            }
            throw SharedConfigurationError.missingCallDirectoryExtensionIdentifier
        }
        return value
    }
}

enum SharedConfigurationError: LocalizedError {
    case missingAppGroupIdentifier
    case missingCallDirectoryExtensionIdentifier
    case appGroupContainerUnavailable

    var errorDescription: String? {
        switch self {
        case .missingAppGroupIdentifier:
            return "App Group 식별자가 빌드 설정에 없습니다."
        case .missingCallDirectoryExtensionIdentifier:
            return "Call Directory Extension 식별자가 빌드 설정에 없습니다."
        case .appGroupContainerUnavailable:
            return "공유 명부 저장소를 열 수 없습니다. 서명과 App Group 권한을 확인해 주세요."
        }
    }
}
