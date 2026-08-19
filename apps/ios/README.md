# iOS 사내 발신자 확인 베타

SwiftUI 호스트 앱이 `GET /v1/directory`에서 전체 명부를 받아 검증한 뒤 App Group에 원자적으로 저장하고, Call Directory Extension을 reload하는 소수 직원용 베타입니다. 수신 시 네트워크 요청은 발생하지 않습니다. iOS가 미리 적재된 번호와 발신 번호를 대조합니다.

## 프로젝트 생성과 서명

필요 도구는 Xcode 16 이상과 [XcodeGen](https://github.com/yonaskolb/XcodeGen)입니다.

```sh
cd apps/ios
cp Config/Local.xcconfig.example Config/Local.xcconfig
xcodegen generate
open EmployeeCallerID.xcodeproj
```

`Config/Local.xcconfig`에서 다음 네 값을 조직 계정에 맞게 바꿉니다. 이 파일은 git에서 제외됩니다.

- `APP_BUNDLE_IDENTIFIER`
- `CALL_DIRECTORY_EXTENSION_BUNDLE_IDENTIFIER`
- `APP_GROUP_IDENTIFIER`
- `DEVELOPMENT_TEAM`

Apple Developer 포털에서 동일한 App Group을 등록하고 앱 ID와 extension 앱 ID 양쪽에 연결해야 합니다. Xcode의 두 target에서 App Groups capability가 같은 식별자를 가리키는지도 확인합니다. 기본 `com.example...` 값은 Simulator 구조 확인용이며 실제 기기 서명에는 사용하지 마세요.

실기기/배포 빌드 전에는 조직용 `AppIcon` asset도 추가하세요. 현재 저장소에는 임시 브랜드 이미지를 넣지 않았습니다.

## 베타 사용 순서

1. 앱을 설치하고 명부 서버의 HTTPS 기준 URL(예: `https://directory.example.com`) 또는 완전한 endpoint URL(예: `https://directory.example.com/v1/directory`)과 짧은 수명의 Bearer token을 입력합니다. token은 서버 계약과 같은 UTF-8 32바이트 이상·16 KiB 이하만 허용합니다. URL과 token은 소스에 들어가지 않습니다. URL은 `UserDefaults`, token은 `AfterFirstUnlockThisDeviceOnly` Keychain 항목에 저장됩니다.
2. `명부 동기화 및 적용`을 누릅니다.
3. `iPhone 설정에서 확장 켜기`를 눌러 시스템 설정의 전화 차단 및 발신자 확인 화면에서 `사내 발신자 확인`을 활성화합니다.
4. 앱으로 돌아와 다시 동기화합니다. 화면에서 활성 상태와 로컬 번호 수를 확인합니다.

설정이 저장된 이후에는 앱 실행 및 foreground 복귀 때 ETag 조건부 sync를 자동 시도합니다. 소수 베타에서 부서 이동 반영은 이 foreground sync와 수동 버튼을 안전망으로 사용합니다. 백그라운드 푸시/MDM 갱신은 이 버전에 포함하지 않았습니다.

extension에는 API token이나 이름/조직의 별도 필드가 전달되지 않습니다. App Group snapshot에는 CallKit에 필요한 E.164 정수 번호와 한 줄 label, 버전/ETag 메타데이터만 저장됩니다. 파일은 원자적으로 교체되며 `completeUntilFirstUserAuthentication` 보호를 사용합니다.

앱의 privacy manifest는 서버 URL 보관에 쓰는 `UserDefaults` required-reason API만 선언하며, 이 베타 앱 자체는 추적이나 분석 데이터 수집 SDK를 포함하지 않습니다. 실제 배포 전에 서버와 조직의 개인정보 처리까지 포함한 App Store Connect privacy 답변은 별도로 검토해야 합니다.

## API 계약

```http
GET /v1/directory
Authorization: Bearer <token>
Accept: application/json
If-None-Match: <previous-etag>
```

```json
{
  "version": 7,
  "generated_at": "2030-01-01T00:00:00Z",
  "entries": [
    {
      "phone_number": "+82211112222",
      "label": "Team A · User A",
      "name": "User A",
      "organization": "Team A",
      "number_type": "office"
    }
  ]
}
```

- `version`은 문자열 또는 정수를 허용합니다.
- `phone_number`는 `+`와 2~15개의 ASCII 숫자로 이루어진 canonical E.164여야 합니다. 국내 형식이나 공백/하이픈 포함 번호는 서버에서 변환해야 합니다.
- `label`이 비어 있으면 `organization · name`을 사용합니다.
- 번호는 Int64로 변환한 뒤 오름차순 정렬됩니다. 같은 번호/label은 하나로 합치고, 같은 번호에 서로 다른 label이 있으면 전체 sync를 거부하여 이전의 정상 snapshot을 유지합니다.
- 유효한 quoted `ETag`가 있으면 다음 요청에 `If-None-Match`를 전송합니다. 비정상 형식이나 1 KiB를 넘는 값은 저장·재전송하지 않습니다. `304 Not Modified`에서도 extension reload를 수행하므로 이전 reload 실패를 재시도할 수 있습니다.
- 응답 body 상한은 10 MiB입니다. HTTP 오류 body나 직원 레코드는 앱 로그에 남기지 않습니다.
- API 통신은 cookie와 URL cache를 사용하지 않는 ephemeral session으로 수행해 명부 응답을 별도 네트워크 cache에 남기지 않습니다.
- 입력 URL이 `/v1/directory`로 끝나면 그대로 사용하고, 그렇지 않으면 그 경로를 뒤에 붙입니다. Bearer token을 예상 밖 host로 전달하지 않도록 redirect 응답은 따르지 않습니다.

서버 없이 decoder/정규화 로직을 시험하는 익명 fixture는 `Tests/Fixtures/directory-response.json`에 있습니다.

## 테스트

프로젝트 생성 후 설치된 Simulator 이름에 맞춰 실행합니다.

```sh
xcodebuild \
  -project EmployeeCallerID.xcodeproj \
  -scheme EmployeeCallerID \
  -destination 'platform=iOS Simulator,name=iPhone 16' \
  test
```

단위 테스트는 기준 URL/완전한 endpoint 조합, ETag·Bearer token 경계 검증, E.164→Int64 변환, 잘못된 형식 거부, 정렬, 동일 항목 중복 제거, 충돌 감지, fallback label, 독립 JSON fixture decoding, snapshot 원자 저장/복원을 다룹니다.

## 알려진 iOS 제약

- 사용자가 Call Directory Extension을 직접 활성화해야 하며 앱이 이를 강제로 켤 수 없습니다.
- 시스템 연락처에 저장된 이름이 Call Directory label보다 우선할 수 있습니다.
- extension은 수신 시 REST API를 호출하지 않습니다. 최신성은 앱 실행·foreground 복귀·수동 sync 주기에 좌우됩니다.
- 서버 token 폐기만으로 iOS에 이미 적재된 번호가 즉시 지워지지는 않습니다. 베타 종료·퇴사 단말에서는 앱의 `로컬 명부와 토큰 삭제`를 실행해 extension reload가 끝난 것을 확인한 뒤 앱을 제거해야 합니다.
- PBX가 모든 통화를 하나의 대표번호로 발신하거나 발신번호가 비공개이면 개인을 구분할 수 없습니다.
- 동일 번호에는 label 하나만 등록할 수 있습니다.
- 번호와 label의 일치는 신원 인증이 아니며 발신번호 변조 방지 수단으로 사용하면 안 됩니다.
- Apple은 고정된 최대 항목 수를 공개하지 않으므로 실제 베타 기기와 명부 규모로 한도/성능을 검증해야 합니다.
