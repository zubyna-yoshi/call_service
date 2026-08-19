import Foundation

struct DirectorySnapshotStore {
    private let fileURL: URL

    init(directoryURL: URL) {
        fileURL = directoryURL.appendingPathComponent("call-directory-snapshot.json", isDirectory: false)
    }

    static func live(
        fileManager: FileManager = .default,
        bundle: Bundle = .main
    ) throws -> DirectorySnapshotStore {
        let identifier = try SharedConfiguration.appGroupIdentifier(bundle: bundle)
        guard let containerURL = fileManager.containerURL(
            forSecurityApplicationGroupIdentifier: identifier
        ) else {
            throw SharedConfigurationError.appGroupContainerUnavailable
        }
        return DirectorySnapshotStore(directoryURL: containerURL)
    }

    func read() throws -> DirectorySnapshot? {
        guard FileManager.default.fileExists(atPath: fileURL.path) else {
            return nil
        }
        let data = try Data(contentsOf: fileURL)
        let snapshot = try JSONDecoder().decode(DirectorySnapshot.self, from: data)
        guard snapshot.formatVersion == DirectorySnapshot.currentFormatVersion else {
            throw DirectorySnapshotStoreError.unsupportedFormat
        }
        try validate(entries: snapshot.entries)
        return snapshot
    }

    func write(_ snapshot: DirectorySnapshot) throws {
        guard snapshot.formatVersion == DirectorySnapshot.currentFormatVersion else {
            throw DirectorySnapshotStoreError.unsupportedFormat
        }
        try validate(entries: snapshot.entries)
        try FileManager.default.createDirectory(
            at: fileURL.deletingLastPathComponent(),
            withIntermediateDirectories: true
        )
        let encoder = JSONEncoder()
        encoder.outputFormatting = [.sortedKeys]
        let data = try encoder.encode(snapshot)
        try data.write(
            to: fileURL,
            options: [.atomic, .completeFileProtectionUntilFirstUserAuthentication]
        )
    }

    func delete() throws {
        guard FileManager.default.fileExists(atPath: fileURL.path) else { return }
        try FileManager.default.removeItem(at: fileURL)
    }

    private func validate(entries: [DirectoryEntry]) throws {
        var previousNumber: Int64?
        for entry in entries {
            guard
                entry.phoneNumber > 0,
                !entry.label.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty,
                previousNumber.map({ entry.phoneNumber > $0 }) ?? true
            else {
                throw DirectorySnapshotStoreError.invalidEntries
            }
            previousNumber = entry.phoneNumber
        }
    }
}

enum DirectorySnapshotStoreError: LocalizedError, Equatable {
    case unsupportedFormat
    case invalidEntries

    var errorDescription: String? {
        switch self {
        case .unsupportedFormat:
            return "공유 명부 파일 형식을 이 앱 버전에서 읽을 수 없습니다."
        case .invalidEntries:
            return "공유 명부가 정렬되지 않았거나 잘못된 항목을 포함합니다."
        }
    }
}
