# Excel/CSV directory API

외부 패키지 없이 Python 3.11+ 표준 라이브러리로 실행되는 소수 직원 베타용 API입니다. Excel/CSV import는 번호를 검증하고 원자적으로 snapshot을 교체합니다. HTTP API는 snapshot을 Bearer token으로 보호하고 ETag/304를 제공합니다.

## 1. Excel/CSV import

```sh
cd server
python3 -m directory_service import-file \
  --input ../data/sample_employees.csv \
  --output var/directory.json
```

입력 확장자는 `.csv` 또는 `.xlsx`입니다. 기본 헤더, 흔한 한글 헤더, `user_id`/`user_nm`/`full_dept_nm`/`ofc_tel`을 자동 인식합니다. 열 이름이 다르거나 후보가 두 개 이상이면 명시적으로 매핑합니다.

```sh
python3 -m directory_service import-file \
  --input /secure/path/employees.csv \
  --output var/directory.json \
  --column employee_id=사용자ID \
  --column name=성명 \
  --column organization=소속부서 \
  --column mobile_phone=휴대폰 \
  --column active=재직상태 \
  --column mobile_visible=휴대폰공개동의
```

- CSV는 UTF-8, UTF-8 BOM, CRLF를 지원합니다. Excel에서 CSV로 내보낸다면 `CSV UTF-8`을 선택하세요.
- XLSX는 첫 시트를 읽으며 `--sheet '직원명부'`로 다른 시트를 선택할 수 있습니다. 수식은 실행하지 않고 거부하므로, 수식 열은 값으로 붙여넣으세요.
- 국내 번호의 선행 `0`이 Excel 숫자 서식으로 사라지지 않게 전화번호 열을 텍스트로 관리하세요.
- `active`(또는 재직여부 별칭) 열은 기본적으로 필수이며 빈 값도 거부합니다. 현재 재직자만 담은 것으로 검증된 export에만 `--assume-active`를 사용하세요.
- `mobile_visible`(또는 `mobile_consent`, `휴대폰공개여부` 등) 열의 true/공개/동의 행만 휴대전화를 배포합니다. false/비공개/빈 값은 제외됩니다.
- 공개 열이 없으면 휴대전화는 기본적으로 제외됩니다. 소수의 동의된 베타 명부임을 확인한 경우에만 `--include-mobile`을 사용하세요. 공개 열이 있으면 이 플래그는 false/빈 값을 덮어쓰지 않습니다.
- 같은 번호와 같은 표시 레이블은 중복을 합치지만, 같은 번호의 표시 레이블이 다르면 snapshot을 교체하지 않습니다.
- 같은 데이터를 재import하면 기존 버전과 타임스탬프를 유지합니다.
- snapshot은 최대 100,000개 번호, 10 MiB로 제한되며 import·원자적 쓰기·HTTP 로드 모두에서 같은 제한을 검사합니다.
- 모든 직원을 비활성화한 명부는 실수로 기존 명부를 지우는 것을 막기 위해 기본적으로 거부합니다. 베타 종료·전체 폐기가 의도된 경우에만 `--allow-empty`를 사용하세요.

## 2. API 실행

token을 명령행 인자로 넘기지 말고 환경변수로 주입합니다.

```sh
python3 -c 'import secrets; print(secrets.token_urlsafe(32))'
```

위 명령으로 32바이트 이상의 무작위 token을 먼저 생성하세요.

```sh
cd server
export CALL_SERVICE_API_TOKEN='replace-with-a-long-random-token'
python3 -m directory_service serve \
  --snapshot var/directory.json \
  --host 127.0.0.1 \
  --port 8080
```

확인:

```sh
curl http://127.0.0.1:8080/healthz
curl -H "Authorization: Bearer $CALL_SERVICE_API_TOKEN" \
  http://127.0.0.1:8080/v1/directory
```

앱은 `GET /v1/directory`를 사용하고 응답의 `ETag`를 다음 `If-None-Match`에 보냅니다. snapshot 파일이 원자적으로 교체되면 서버 재시작 없이 새 버전을 제공합니다.

## HTTPS 및 베타 배포

내장 HTTP 서버는 스냅샷 제공에만 초점을 두고 있으며 TLS를 종료하지 않습니다. 실제 기기에서는:

1. 사내 VPN/제한된 네트워크에 서버를 배치하고,
2. Caddy, nginx 또는 조직의 API gateway에서 HTTPS를 종료하며,
3. 내장 서버는 `127.0.0.1`에만 bind하고,
4. 베타 종료/유출 시 token을 교체하세요.

공유 Bearer token은 소수·동의된 베타에만 사용하고, 정식 배포 전에 사내 SSO와 사용자별 짧은 수명의 token으로 교체하세요.

## 테스트

```sh
cd server
python3 -m unittest discover -s tests -v
```
