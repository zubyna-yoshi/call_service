import Foundation

struct AppConfigurationStore {
    private enum Key {
        static let baseURL = "directory.api.baseURL"
    }

    private let defaults: UserDefaults

    init(defaults: UserDefaults = .standard) {
        self.defaults = defaults
    }

    var savedBaseURLString: String {
        defaults.string(forKey: Key.baseURL) ?? ""
    }

    func saveBaseURL(_ url: URL) {
        defaults.set(url.absoluteString, forKey: Key.baseURL)
    }

    static func validatedBaseURL(from rawValue: String) throws -> URL {
        let trimmed = rawValue.trimmingCharacters(in: .whitespacesAndNewlines)
        guard
            let components = URLComponents(string: trimmed),
            components.scheme?.lowercased() == "https",
            components.host?.isEmpty == false,
            components.user == nil,
            components.password == nil,
            components.query == nil,
            components.fragment == nil,
            let url = components.url
        else {
            throw AppConfigurationError.invalidBaseURL
        }
        return url
    }
}

enum AppConfigurationError: LocalizedError {
    case invalidBaseURL

    var errorDescription: String? {
        "서버 주소는 사용자 정보·쿼리·fragment가 없는 HTTPS URL이어야 합니다."
    }
}
