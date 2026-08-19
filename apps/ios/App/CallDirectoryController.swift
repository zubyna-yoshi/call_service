import CallKit
import Foundation

enum ExtensionState: Equatable {
    case enabled
    case disabled
    case unknown

    var displayName: String {
        switch self {
        case .enabled: return "활성화됨"
        case .disabled: return "비활성화됨"
        case .unknown: return "확인할 수 없음"
        }
    }
}

struct CallDirectoryController {
    private let manager: CXCallDirectoryManager
    private let extensionIdentifier: String

    init(
        manager: CXCallDirectoryManager = .sharedInstance,
        bundle: Bundle = .main
    ) throws {
        self.manager = manager
        extensionIdentifier = try SharedConfiguration.callDirectoryExtensionIdentifier(bundle: bundle)
    }

    func enabledState() async -> ExtensionState {
        await withCheckedContinuation { continuation in
            manager.getEnabledStatusForExtension(withIdentifier: extensionIdentifier) { status, _ in
                switch status {
                case .enabled:
                    continuation.resume(returning: .enabled)
                case .disabled:
                    continuation.resume(returning: .disabled)
                case .unknown:
                    continuation.resume(returning: .unknown)
                @unknown default:
                    continuation.resume(returning: .unknown)
                }
            }
        }
    }

    func reload() async throws {
        try await withCheckedThrowingContinuation { (continuation: CheckedContinuation<Void, Error>) in
            manager.reloadExtension(withIdentifier: extensionIdentifier) { error in
                if let error {
                    continuation.resume(throwing: error)
                } else {
                    continuation.resume(returning: ())
                }
            }
        }
    }

    func openSettings() async throws {
        try await withCheckedThrowingContinuation { (continuation: CheckedContinuation<Void, Error>) in
            manager.openSettings { error in
                if let error {
                    continuation.resume(throwing: error)
                } else {
                    continuation.resume(returning: ())
                }
            }
        }
    }
}
