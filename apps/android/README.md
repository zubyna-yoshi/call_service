# 사내 발신자 확인 Android 베타

Android 10(API 29) 이상에서 수신 번호를 로컬 직원 디렉터리와 대조해 이름과 조직을 보여주는 소수 사용자용 베타 앱입니다. 앱은 전화를 차단하거나 무음 처리하지 않습니다. `CallScreeningService` 콜백이 오면 가장 먼저 허용 응답을 보내고, 그 뒤에만 로컬 snapshot을 조회합니다.

## 구현 범위

- Kotlin + Jetpack Compose 관리 화면
- `RoleManager.ROLE_CALL_SCREENING` 사용자 동의 흐름
- `CallScreeningService`와 `android.permission.BIND_SCREENING_SERVICE` 선언
- 통화 허용 응답 후 로컬 메모리/원자적 파일 snapshot 조회
- 잠금 화면에서도 보일 수 있고 전화 앱 조작을 막지 않는 상단 caller-ID Activity와 고우선순위 알림 fallback
- `GET /v1/directory`, Bearer 인증, `ETag`/`If-None-Match`, `304 Not Modified`
- 앱 실행/foreground 복귀 시 15분 throttle을 적용한 자동 ETag 동기화
- 마지막 성공 확인 후 7일이 지난 snapshot을 통화 중 표시하지 않는 만료 정책
- Android Keystore AES-GCM으로 Bearer 토큰 암호화 저장
- E.164 형태의 로컬 조회 키 정규화와 보수적인 payload 검증
- 네트워크 없이 검증할 수 있는 `sample_directory.json`
- JVM 단위 테스트와 기기용 asset 계측 테스트

의도적으로 `READ_CALL_LOG`, `READ_CONTACTS`, `SYSTEM_ALERT_WINDOW` 권한은 선언하지 않습니다.

## 빠른 확인

1. Android Studio에서 이 디렉터리(`apps/android`)를 프로젝트로 엽니다.
2. JDK 17과 Android SDK Platform 37을 선택하고 Gradle 동기화를 실행합니다.
3. Android 10 이상 실제 기기에 debug 빌드를 설치합니다.
4. 앱에서 **통화 스크리닝 역할 요청**을 누르고 시스템 화면에서 허용합니다.
5. Android 13 이상에서는 알림 권한을, Android 14 이상에서는 필요할 경우 전체화면 알림 사용도 허용합니다.
6. API 없이 먼저 확인하려면 **네트워크 없이 내장 샘플 불러오기**를 누릅니다.
7. 샘플 번호 중 하나로 기기에 전화를 걸어 caller-ID 카드를 확인합니다.

샘플 데이터는 다음 번호를 포함합니다.

- `+82 10-1234-5678` → 플랫폼개발팀 · 김민수
- `02-555-0101` → 재무팀 · 이서연
- `+82-2-555-0199` → 인사팀 · 박지훈

## API 계약

설정 화면에는 호스트 또는 API prefix까지만 입력합니다. 앱이 `/v1/directory`를 붙입니다. 이미 정확히 `/v1/directory`로 끝나는 URL은 그대로 사용합니다.

```http
GET /v1/directory HTTP/1.1
Accept: application/json
Authorization: Bearer <token>
If-None-Match: "previous-etag"
```

```json
{
  "version": "2026-08-19.1",
  "generated_at": "2026-08-19T00:00:00Z",
  "entries": [
    {
      "phone_number": "+82-10-1234-5678",
      "label": "플랫폼개발팀 · 김민수",
      "name": "김민수",
      "organization": "플랫폼개발팀",
      "number_type": "mobile"
    }
  ]
}
```

- 서버가 `200`을 반환하면 앱이 전체 응답을 검증한 뒤 새 snapshot을 원자적으로 교체합니다.
- 서버가 `304`를 반환하면 기존 snapshot을 유지하고 마지막 확인 시간만 갱신합니다.
- 로컬 snapshot이 없는데 `304`가 오면 오류로 처리합니다.
- 응답 본문은 10 MiB, entries는 100,000건으로 제한합니다.
- redirect는 Bearer 토큰이 다른 origin으로 전달되는 것을 막기 위해 따르지 않습니다.
- HTTPS만 허용하고 URL의 user-info, query, fragment는 거부합니다.

저장된 URL과 토큰이 있으면 관리 앱 첫 실행 및 foreground 복귀 시 자동 동기화를 시도합니다. 마지막 성공 확인 또는 마지막 자동 시도 후 15분 이내면 요청하지 않습니다. 네트워크 장애 때 화면 전환마다 재시도하는 것을 막기 위해 실패한 자동 시도도 throttle 기준에 포함합니다. **API에서 동기화** 버튼은 이 제한과 무관하게 항상 새 요청을 시도합니다.

## 번호와 중복 정책

국가번호 기본값은 대한민국 `82`입니다. `010-...`, `02-...`, `0082...`, `+82...` 형식을 하나의 `+` + ASCII 숫자 키로 바꿉니다. ASCII 숫자, 첫 글자의 `+`, 일반적인 구분자 이외 문자가 있거나 최종 숫자가 7~15자 범위를 벗어나면 payload를 거부합니다. `1012345678`처럼 국내 번호의 앞 `0`을 잃어 국가번호를 추측해야 하는 값과 `+82(0)10...`처럼 국가번호 뒤에 국내용 trunk `0`을 함께 쓴 값도 거부합니다. 명시적인 다른 국가 E.164 번호는 유지합니다.

동기화는 fail-closed입니다.

- 정규화할 수 없는 전화번호 또는 표시 가능한 label이 없는 행이 하나라도 있으면 전체 payload를 거부합니다.
- 같은 정규화 번호와 같은 label은 한 건으로 통합합니다. 뒤 행의 부가 필드가 남습니다.
- 같은 정규화 번호가 서로 다른 label을 가리키면 전체 payload를 거부합니다.
- 검증 실패 시 디스크에 쓰기 전 오류가 발생하므로 이전 정상 snapshot이 유지됩니다.

이 정책은 잘못된 서버 행을 조용히 누락해 불완전한 디렉터리로 교체하는 것보다 안전합니다. CSV를 API JSON으로 바꾸는 서버도 동일 규칙으로 사전 검증하는 것이 좋습니다.

## 5초 제한을 지키는 구조

`DirectoryCallScreeningService.onScreenCall()`의 순서는 고정되어 있습니다.

1. 수신 통화인지 확인
2. 모든 차단/무음 옵션이 `false`인 `respondToCall()` 즉시 호출
3. 설정 확인
4. 마지막 성공 확인 후 7일 이내인 로컬 snapshot만 조회
5. 일치할 때 caller-ID UI 표시

따라서 API 장애, JSON 크기, 동기화 지연은 통화 허용 응답 경로에 들어오지 않습니다. snapshot은 `noBackupFilesDir`의 `AtomicFile`에 저장되고 최초 로드 뒤에는 번호 Map을 메모리에 캐시합니다. 깨진 파일은 통화를 방해하지 않도록 조회 실패로 처리합니다. 마지막 `200` 또는 `304` 성공 확인 시각에서 7일이 지나면 파일을 즉시 지우지는 않지만 통화 중 조회 결과는 반환하지 않습니다. 기기 시계의 일반적인 흔들림은 미래 5분까지 허용하고, 그보다 미래인 확인 시각은 보수적으로 사용할 수 없는 것으로 처리합니다. 내장 샘플을 다시 가져오면 테스트용 확인 시각이 갱신됩니다.

## 플랫폼 제약

- 사용자가 통화 스크리닝 역할을 명시적으로 허용해야 하며, 기기에서 한 앱만 이 역할을 보유할 수 있습니다.
- 이 앱에는 `READ_CONTACTS`가 없습니다. Android는 기기 연락처에 이미 있는 번호를 `CallScreeningService`로 전달하지 않을 수 있으므로, 베타의 주 대상은 로컬 연락처에 없는 직원 번호입니다.
- OEM 및 Android 버전에 따라 백그라운드 Activity 시작이 제한될 수 있습니다. 앱은 Activity와 함께 고우선순위/full-screen 알림을 fallback으로 사용합니다.
- Android 13 이상에서는 알림 런타임 권한이 필요합니다. Android 14 이상에서는 전체화면 알림 권한/정책 상태에 따라 heads-up 알림으로만 보일 수 있습니다.
- Play 배포 시 `USE_FULL_SCREEN_INTENT` 정책 적합성을 별도로 확인해야 합니다. 사내 MDM/비공개 배포도 단말 정책 검증이 필요합니다.
- 긴급통화, 제한 번호, SIP/비전화 URI, 번호가 제공되지 않는 통화에는 표시할 수 없습니다.
- 발신번호는 위조될 수 있습니다. 표시 결과는 직원 신원 인증 수단이 아닙니다.

## 보안과 운영

- 토큰은 소스, BuildConfig, sample 파일에 넣지 않습니다. 설정 화면에서 받은 값은 Android Keystore의 비반출 AES 키로 암호화됩니다.
- Bearer 토큰은 서버 계약과 동일하게 앞뒤 공백을 제거한 UTF-8 기준 32~16,384바이트만 저장하고 요청에 사용합니다. 줄바꿈이 포함된 값은 거부합니다.
- 설정 화면의 **토큰 및 로컬 snapshot 삭제**는 인증 정보와 직원 전화번호·이름이 담긴 로컬 snapshot을 함께 제거합니다.
- 앱 데이터 백업과 cleartext traffic을 비활성화했습니다.
- snapshot에는 직원 이름, 조직, 전화번호가 있으므로 업무용 관리 기기, 화면 잠금, 원격 삭제 정책을 권장합니다.
- caller-ID 알림은 잠금 화면에서 `PRIVATE`로 표시합니다. 조직 정책에 따라 알림 미리보기를 더 제한할 수 있습니다.
- 직원 이동/퇴사 반영을 위해 앱 실행 시 수동 동기화 외에, 운영판에서는 충전·네트워크 조건을 둔 주기적 WorkManager 동기화를 추가하는 편이 좋습니다. 이 베타에는 불필요한 백그라운드 의존성과 트래픽을 줄이기 위해 포함하지 않았습니다.
- 서버는 짧은 수명의 사용자별 토큰, 최소 권한, 토큰 폐기, 접근 감사, TLS 인증서 운영을 갖춰야 합니다.

## 빌드와 테스트

프로젝트 설정은 AGP 9.3.0, Gradle 9.5.0, JDK 17, compile SDK 37, target SDK 36, min SDK 29입니다. Compose BOM 2026.06.01의 Compose 1.12 계열이 compile SDK 37을 요구하므로 SDK Platform 37도 설치해야 합니다. 이 생성 환경에는 JDK 런타임, Android SDK, `gradle`/`adb`가 없어 실제 컴파일과 테스트를 실행하지 못했습니다. 또한 바이너리 `gradle-wrapper.jar`를 생성할 Gradle 설치가 없어서 wrapper properties만 포함했습니다.

JDK 17과 Gradle 9.5를 준비한 뒤 최초 한 번 wrapper를 생성할 수 있습니다.

```shell
gradle wrapper --gradle-version 9.5.0
./gradlew testDebugUnitTest
./gradlew connectedDebugAndroidTest
./gradlew assembleDebug
```

Android Studio의 bundled JDK/Gradle로 직접 동기화한 뒤 같은 test task를 실행해도 됩니다. 실제 수신 통화와 잠금 화면 동작은 에뮬레이터만으로 충분하지 않으므로 베타 대상 제조사별 실제 기기에서 확인해야 합니다.

## 주요 파일

- `app/src/main/java/com/company/callservice/telecom/DirectoryCallScreeningService.kt`: 즉시 허용 + 로컬 조회
- `app/src/main/java/com/company/callservice/telecom/CallerIdPresenter.kt`: Activity/알림 fallback
- `app/src/main/java/com/company/callservice/data/DirectoryRepository.kt`: API/ETag 동기화와 샘플 import
- `app/src/main/java/com/company/callservice/data/DirectorySnapshotStore.kt`: 원자적 로컬 snapshot
- `app/src/main/java/com/company/callservice/data/SnapshotFreshnessPolicy.kt`: 통화 조회용 7일 만료 판단
- `app/src/main/java/com/company/callservice/data/PhoneNumberNormalizer.kt`: 번호 정규화
- `app/src/main/java/com/company/callservice/settings/BearerTokenPolicy.kt`: 토큰 UTF-8 길이 계약
- `app/src/main/java/com/company/callservice/settings/SettingsStore.kt`: 일반 설정과 Keystore secret
- `app/src/main/assets/sample_directory.json`: 오프라인 테스트 데이터
