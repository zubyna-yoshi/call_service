# Beta architecture

## Data flow

```text
Excel/CSV 관리자
    │ import / validate
    ▼
Directory API ── snapshot version + ETag ──▶ Mobile app
                                                   │ atomic local save
                         ┌────────────────────────┐
                         ▼                        ▼
                 iOS Call Directory       Android CallScreeningService
```

## API contract

`GET /v1/directory`는 Bearer token을 검증하고 다음 snapshot을 반환합니다.

```json
{
  "version": "sha256 digest",
  "generated_at": "2026-08-19T00:00:00Z",
  "entries": [
    {
      "phone_number": "+8225550101",
      "label": "플랫폼개발팀 · 가나다",
      "name": "가나다",
      "organization": "플랫폼개발팀",
      "number_type": "office"
    }
  ]
}
```

클라이언트는 `If-None-Match`로 현재 버전을 전송하고, 변경이 없을 때 `304 Not Modified`를 받습니다. 앱은 완전한 snapshot을 임시 파일에 검증한 뒤 기존 파일과 원자적으로 교체합니다.

## Number matching

- CSV import 시 국내 번호를 E.164로 정규화합니다.
- iOS Call Directory에는 `+`를 제외한 숫자를 64-bit 정수로 전달합니다.
- Android는 수신 `tel:` 핸들을 같은 E.164 형식으로 정규화한 후 로컬 snapshot을 조회합니다.
- Android snapshot은 마지막 `200`/`304` 성공 확인 후 7일까지만 통화 조회에 사용합니다.
- 같은 번호가 서로 다른 표시 label을 가리키면 import를 중단합니다. 번호와 label이 모두 같으면 표시 결과가 동일하므로 한 건으로 통합합니다.

## Beta security boundary

- API token은 소스에 저장하지 않고, 서버 환경변수와 단말의 보안 저장소에 보관합니다.
- 실제 배포는 HTTPS 뒤에서만 수행합니다.
- 재직 상태 열이 없는 파일과 휴대전화 공개 여부 열이 없는 파일은 각각 명시적 관리자 옵션 없이는 fail-closed로 거부·제외합니다.
- 베타 token이 하나의 공유 비밀이라면 유출 시 모든 사용자 권한이 노출됩니다. 정식 배포 전에는 사내 SSO와 사용자별 짧은 수명의 token으로 교체합니다.
- 로그아웃, 퇴사, 베타 종료 시 단말 snapshot을 삭제하고 OS 발신자 ID 목록을 재적재합니다. 특히 iOS는 token 폐기만으로 이미 적재된 항목이 자동 삭제되지 않으므로 단말의 삭제·reload 절차가 필요합니다.
