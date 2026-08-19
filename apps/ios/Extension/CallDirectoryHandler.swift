import CallKit
import Foundation

@objc(CallDirectoryHandler)
final class CallDirectoryHandler: CXCallDirectoryProvider {
    override func beginRequest(with context: CXCallDirectoryExtensionContext) {
        context.delegate = self

        do {
            let snapshot = try DirectorySnapshotStore.live().read()

            // The persisted snapshot is always a full snapshot. During an
            // incremental request, clear the previous identification entries first
            // and then provide the complete, sorted set.
            if context.isIncremental {
                context.removeAllIdentificationEntries()
            }

            for entry in snapshot?.entries ?? [] {
                context.addIdentificationEntry(
                    withNextSequentialPhoneNumber: CXCallDirectoryPhoneNumber(entry.phoneNumber),
                    label: entry.label
                )
            }
            context.completeRequest()
        } catch {
            // Do not include directory records or phone numbers in the error.
            let safeError = NSError(
                domain: "EmployeeCallerID.CallDirectoryExtension",
                code: 1,
                userInfo: [NSLocalizedDescriptionKey: "The local directory snapshot could not be loaded."]
            )
            context.cancelRequest(withError: safeError)
        }
    }
}

extension CallDirectoryHandler: CXCallDirectoryExtensionContextDelegate {
    func requestFailed(for extensionContext: CXCallDirectoryExtensionContext, withError error: Error) {
        // Intentionally empty: system errors can contain operational details and
        // should be surfaced by the host app without logging directory PII here.
    }
}
