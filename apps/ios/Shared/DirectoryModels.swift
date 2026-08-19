import Foundation

struct DirectoryAPIResponse: Decodable, Equatable {
    let version: String
    let generatedAt: String
    let entries: [DirectoryAPIEntry]

    private enum CodingKeys: String, CodingKey {
        case version
        case generatedAt = "generated_at"
        case entries
    }

    init(from decoder: Decoder) throws {
        let container = try decoder.container(keyedBy: CodingKeys.self)
        if let stringVersion = try? container.decode(String.self, forKey: .version) {
            version = stringVersion
        } else if let integerVersion = try? container.decode(Int64.self, forKey: .version) {
            version = String(integerVersion)
        } else {
            throw DecodingError.typeMismatch(
                String.self,
                .init(codingPath: decoder.codingPath, debugDescription: "version must be a string or integer")
            )
        }
        generatedAt = try container.decode(String.self, forKey: .generatedAt)
        entries = try container.decode([DirectoryAPIEntry].self, forKey: .entries)
    }
}

struct DirectoryAPIEntry: Decodable, Equatable {
    let phoneNumber: String
    let label: String?
    let name: String?
    let organization: String?
    let numberType: String?

    private enum CodingKeys: String, CodingKey {
        case phoneNumber = "phone_number"
        case label
        case name
        case organization
        case numberType = "number_type"
    }
}

struct DirectoryEntry: Codable, Equatable {
    let phoneNumber: Int64
    let label: String
}

struct DirectorySnapshot: Codable, Equatable {
    static let currentFormatVersion = 1

    let formatVersion: Int
    let version: String
    let generatedAt: String
    let savedAt: Date
    let etag: String?
    let entries: [DirectoryEntry]
}
