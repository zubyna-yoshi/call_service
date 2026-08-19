import Foundation
import SwiftUI

@MainActor
final class AppModel: ObservableObject {
    @Published var serverURLText: String
    @Published var tokenText: String
    @Published private(set) var extensionState: ExtensionState = .unknown
    @Published private(set) var entryCount = 0
    @Published private(set) var version: String?
    @Published private(set) var lastSavedAt: Date?
    @Published private(set) var isWorking = false
    @Published private(set) var statusMessage: String?
    @Published private(set) var isError = false

    private let configurationStore: AppConfigurationStore
    private let tokenStore: KeychainTokenStore?
    private let snapshotStore: DirectorySnapshotStore?
    private let callDirectoryController: CallDirectoryController?

    init() {
        let configurationStore = AppConfigurationStore()
        self.configurationStore = configurationStore
        serverURLText = configurationStore.savedBaseURLString

        let tokenStore = try? KeychainTokenStore()
        self.tokenStore = tokenStore
        tokenText = (try? tokenStore?.load()) ?? ""

        snapshotStore = try? DirectorySnapshotStore.live()
        callDirectoryController = try? CallDirectoryController()
        refreshSnapshotMetadata()
    }

    func refresh() async {
        refreshSnapshotMetadata()
        guard let callDirectoryController else {
            presentError(SharedConfigurationError.missingCallDirectoryExtensionIdentifier)
            return
        }
        extensionState = await callDirectoryController.enabledState()
    }

    func synchronizeWhenConfigured() async {
        await refresh()
        guard
            !serverURLText.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty,
            !tokenText.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty
        else {
            return
        }
        await sync()
    }

    func saveSettings() {
        do {
            let url = try AppConfigurationStore.validatedBaseURL(from: serverURLText)
            let token = try validatedToken()
            guard let tokenStore else {
                throw KeychainTokenError.missingServiceIdentifier
            }
            configurationStore.saveBaseURL(url)
            try tokenStore.save(token)
            tokenText = token
            presentSuccess("설정을 안전하게 저장했습니다.")
        } catch {
            presentError(error)
        }
    }

    func sync() async {
        guard !isWorking else { return }
        isWorking = true
        defer { isWorking = false }

        do {
            let baseURL = try AppConfigurationStore.validatedBaseURL(from: serverURLText)
            let token = try validatedToken()
            guard let tokenStore else {
                throw KeychainTokenError.missingServiceIdentifier
            }
            guard let snapshotStore else {
                throw SharedConfigurationError.appGroupContainerUnavailable
            }
            guard let callDirectoryController else {
                throw SharedConfigurationError.missingCallDirectoryExtensionIdentifier
            }

            configurationStore.saveBaseURL(baseURL)
            try tokenStore.save(token)

            let client = DirectorySyncClient(snapshotStore: snapshotStore)
            let outcome = try await client.sync(baseURL: baseURL, bearerToken: token)

            // Reload for both 200 and 304. This makes a prior reload failure recoverable
            // without requiring the server to issue a new version or ETag.
            try await callDirectoryController.reload()
            refreshSnapshotMetadata()
            extensionState = await callDirectoryController.enabledState()

            switch outcome {
            case .updated(let count, let version):
                presentSuccess("명부 \(count)건(버전 \(version))을 동기화했습니다.")
            case .notModified(let count, _):
                presentSuccess("서버 변경 없음 · 로컬 명부 \(count)건을 다시 적용했습니다.")
            }
        } catch {
            presentError(error)
        }
    }

    func openExtensionSettings() async {
        do {
            guard let callDirectoryController else {
                throw SharedConfigurationError.missingCallDirectoryExtensionIdentifier
            }
            try await callDirectoryController.openSettings()
        } catch {
            presentError(error)
        }
    }

    func clearLocalData() async {
        guard !isWorking else { return }
        isWorking = true
        defer { isWorking = false }

        do {
            guard let snapshotStore else {
                throw SharedConfigurationError.appGroupContainerUnavailable
            }
            guard let callDirectoryController else {
                throw SharedConfigurationError.missingCallDirectoryExtensionIdentifier
            }
            try snapshotStore.delete()
            try tokenStore?.delete()
            tokenText = ""
            try await callDirectoryController.reload()
            refreshSnapshotMetadata()
            presentSuccess("로컬 명부와 저장된 토큰을 삭제했습니다.")
        } catch {
            presentError(error)
        }
    }

    private func refreshSnapshotMetadata() {
        do {
            let snapshot = try snapshotStore?.read()
            entryCount = snapshot?.entries.count ?? 0
            version = snapshot?.version
            lastSavedAt = snapshot?.savedAt
        } catch {
            entryCount = 0
            version = nil
            lastSavedAt = nil
            presentError(error)
        }
    }

    private func validatedToken() throws -> String {
        guard let token = DirectoryBearerTokenValidator.validatedToken(tokenText) else {
            throw DirectorySyncError.invalidToken
        }
        return token
    }

    private func presentSuccess(_ message: String) {
        isError = false
        statusMessage = message
    }

    private func presentError(_ error: Error) {
        isError = true
        statusMessage = (error as? LocalizedError)?.errorDescription ?? "작업에 실패했습니다."
    }
}
