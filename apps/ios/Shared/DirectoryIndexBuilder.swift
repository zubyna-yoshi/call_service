import Foundation

enum PhoneNumberNormalizer {
    /// Converts canonical E.164 (`+` followed by 2...15 ASCII digits) to the
    /// positive Int64 format required by CallKit's Call Directory API.
    static func callDirectoryNumber(fromE164 rawValue: String) throws -> Int64 {
        let value = rawValue.trimmingCharacters(in: .whitespacesAndNewlines)
        guard value.first == "+" else {
            throw DirectoryIndexError.invalidPhoneNumber
        }

        let digits = value.dropFirst()
        guard (2...15).contains(digits.count), digits.first != "0" else {
            throw DirectoryIndexError.invalidPhoneNumber
        }
        let containsOnlyASCIIDigits = digits.unicodeScalars.allSatisfy {
            (48...57).contains($0.value)
        }
        guard containsOnlyASCIIDigits, let result = Int64(digits), result > 0 else {
            throw DirectoryIndexError.invalidPhoneNumber
        }
        return result
    }
}

enum DirectoryIndexBuilder {
    /// Produces the strictly ascending, duplicate-free sequence required by
    /// CXCallDirectoryExtensionContext. Exact duplicates are collapsed. A phone
    /// number mapped to two labels aborts the sync so stale but correct data wins.
    static func build(from records: [DirectoryAPIEntry]) throws -> [DirectoryEntry] {
        var labelsByNumber: [Int64: String] = [:]
        labelsByNumber.reserveCapacity(records.count)

        for record in records {
            let number = try PhoneNumberNormalizer.callDirectoryNumber(fromE164: record.phoneNumber)
            let label = try displayLabel(for: record)

            if let existingLabel = labelsByNumber[number], existingLabel != label {
                throw DirectoryIndexError.conflictingDuplicate
            }
            labelsByNumber[number] = label
        }

        return labelsByNumber
            .map { DirectoryEntry(phoneNumber: $0.key, label: $0.value) }
            .sorted { $0.phoneNumber < $1.phoneNumber }
    }

    private static func displayLabel(for record: DirectoryAPIEntry) throws -> String {
        if let label = normalizedSingleLine(record.label), !label.isEmpty {
            guard label.count <= 180 else {
                throw DirectoryIndexError.invalidLabel
            }
            return label
        }

        let fallback = [record.organization, record.name]
            .compactMap(normalizedSingleLine)
            .filter { !$0.isEmpty }
            .joined(separator: " · ")
        guard !fallback.isEmpty else {
            throw DirectoryIndexError.missingLabel
        }
        guard fallback.count <= 180 else {
            throw DirectoryIndexError.invalidLabel
        }
        return fallback
    }

    private static func normalizedSingleLine(_ value: String?) -> String? {
        value?
            .split(whereSeparator: { $0.isWhitespace })
            .joined(separator: " ")
    }
}

enum DirectoryIndexError: Error, Equatable {
    case invalidPhoneNumber
    case missingLabel
    case invalidLabel
    case conflictingDuplicate
}
