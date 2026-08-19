# Call Service beta

소수의 사내 직원을 대상으로, 수신 번호를 Excel/CSV 직원 명부와 매칭해 `조직 · 이름`을 표시하는 iOS/Android 베타 프로젝트입니다.

## 구성

- `server/`: Excel/CSV 검증·정규화, 버전된 디렉터리 API
- `apps/ios/`: SwiftUI 앱과 Call Directory Extension
- `apps/android/`: Android 앱과 `CallScreeningService`
- `data/sample_employees.csv`: 가상 직원 데이터로 만든 예시
- `docs/architecture.md`: 동기화·통화 처리·보안 원칙

## Excel/CSV 기본 형식

UTF-8 CSV의 첫 행에 다음 헤더를 사용합니다.

```csv
employee_id,name,organization,office_phone,mobile_phone,active,mobile_visible
```

- `employee_id`, `name`, `organization`은 필수입니다.
- `office_phone`, `mobile_phone` 중 하나 이상이 필요합니다.
- 한국 국내 형식 번호는 `+82` E.164 형식으로 정규화됩니다.
- `active=false`인 직원은 배포 명부에서 제외됩니다. `active` 열이 없는 파일은 기본적으로 거부합니다.
- 휴대전화는 `mobile_visible=true`인 행만 포함하며, 공개 여부 열이 없으면 기본적으로 제외합니다.
- XLSX는 첫 번째 시트를 기본으로 읽고, 필요하면 `--sheet`로 시트명을 지정합니다.
- 실제 직원 CSV/XLSX는 Git에 추가하지 않도록 `.gitignore`에서 차단합니다.

## 빠른 시작

```sh
cd server
python3 -m directory_service import-file \
  --input ../data/sample_employees.csv \
  --output var/directory.json
```

현재 사내 추출 파일처럼 `active`와 휴대전화 공개 여부 열이 모두 없다면, 파일이 현 재직자만 담고 있고 베타 참여자가 휴대전화 공개에 동의했음을 확인한 뒤에만 다음 두 옵션을 함께 사용합니다.

```sh
python3 -m directory_service import-file \
  --input /secure/path/employees.xlsx \
  --output var/directory.json \
  --assume-active \
  --include-mobile
```

이후 API 실행과 클라이언트 설정은 [서버 안내](server/README.md), [iOS 안내](apps/ios/README.md), [Android 안내](apps/android/README.md)를 참고하세요.

부서 이동이나 번호 변경이 생기면 최신 Excel/CSV를 다시 내보내 같은 `import-file` 명령을 실행합니다. 검증이 모두 통과한 경우에만 서버 snapshot이 원자적으로 교체되고, 베타 앱은 다음 실행·foreground 복귀 또는 수동 동기화 때 새 `조직 · 이름`을 받습니다. 원본 파일 자체를 자동 감시하거나 인사 시스템을 직접 조회하는 기능은 이 CSV 베타 범위에 포함하지 않았습니다.

## 베타 운영 원칙

1. 통화 시점에 서버를 조회하지 않고, 단말에 최근 동기화된 명부만 조회합니다.
2. 통화기록은 서버로 업로드하지 않습니다.
3. 휴대전화 번호는 베타 참여자의 동의와 사내 공개 기준을 먼저 확인합니다.
4. 사내 PBX가 대표번호만 전달하면 직원별 식별은 불가능합니다.
5. Android는 마지막 성공 동기화 후 7일이 지나면 표시를 중단합니다. iOS는 베타 종료 전에 앱의 로컬 삭제 기능으로 Call Directory를 비워야 합니다.

서버와 각 모바일 앱의 실행 방법은 하위 디렉터리의 README를 참고하세요.
