import Foundation

enum DirectoryResponseDecoder {
    static func decode(_ data: Data) throws -> DirectoryAPIResponse {
        try JSONDecoder().decode(DirectoryAPIResponse.self, from: data)
    }
}
