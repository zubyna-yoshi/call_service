import SwiftUI

struct ContentView: View {
    @Environment(\.scenePhase) private var scenePhase
    @ObservedObject var model: AppModel
    @State private var showsClearConfirmation = false

    var body: some View {
        NavigationStack {
            Form {
                Section {
                    TextField("https://…", text: $model.serverURLText)
                        .textInputAutocapitalization(.never)
                        .autocorrectionDisabled()
                        .keyboardType(.URL)
                        .accessibilityLabel("명부 서버 HTTPS 주소")

                    SecureField("접근 토큰", text: $model.tokenText)
                        .textInputAutocapitalization(.never)
                        .autocorrectionDisabled()

                    Button("설정 저장") {
                        model.saveSettings()
                    }
                } header: {
                    Text("명부 서버")
                } footer: {
                    Text("서버 주소는 앱 설정에, 접근 토큰은 이 기기의 Keychain에 저장됩니다.")
                }

                Section {
                    LabeledContent("확장 상태", value: model.extensionState.displayName)
                    LabeledContent("로컬 번호", value: "\(model.entryCount)건")
                    if let version = model.version {
                        LabeledContent("명부 버전", value: version)
                    }
                    if let lastSavedAt = model.lastSavedAt {
                        LabeledContent("마지막 저장") {
                            Text(lastSavedAt, format: .dateTime)
                        }
                    }

                    Button("명부 동기화 및 적용") {
                        Task { await model.sync() }
                    }
                    .disabled(model.isWorking)

                    Button("iPhone 설정에서 확장 켜기") {
                        Task { await model.openExtensionSettings() }
                    }
                } header: {
                    Text("Call Directory")
                } footer: {
                    Text("처음 한 번은 iPhone 설정에서 이 앱의 발신자 확인 확장을 직접 활성화해야 합니다.")
                }

                if let message = model.statusMessage {
                    Section {
                        Text(message)
                            .foregroundStyle(model.isError ? .red : .secondary)
                            .accessibilityLabel(model.isError ? "오류: \(message)" : message)
                    }
                }

                Section {
                    Button("로컬 명부와 토큰 삭제", role: .destructive) {
                        showsClearConfirmation = true
                    }
                    .disabled(model.isWorking)
                } footer: {
                    Text("삭제 후 확장을 다시 적재해 시스템의 사내 번호 목록도 비웁니다.")
                }
            }
            .navigationTitle("사내 발신자 확인")
            .overlay {
                if model.isWorking {
                    ProgressView()
                        .padding()
                        .background(.regularMaterial, in: RoundedRectangle(cornerRadius: 12))
                }
            }
            .task {
                await model.synchronizeWhenConfigured()
            }
            .onChange(of: scenePhase) { _, newPhase in
                guard newPhase == .active else { return }
                Task { await model.synchronizeWhenConfigured() }
            }
            .confirmationDialog(
                "이 기기의 명부와 접근 토큰을 삭제할까요?",
                isPresented: $showsClearConfirmation,
                titleVisibility: .visible
            ) {
                Button("삭제", role: .destructive) {
                    Task { await model.clearLocalData() }
                }
                Button("취소", role: .cancel) {}
            }
        }
    }
}
