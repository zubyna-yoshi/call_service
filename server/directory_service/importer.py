from __future__ import annotations

import csv
import hashlib
import json
import os
import re
import tempfile
from dataclasses import dataclass
from datetime import datetime, timezone
from pathlib import Path
from typing import Iterable, Mapping

from .limits import MAX_ENTRIES, MAX_SNAPSHOT_BYTES
from .phone import PhoneNumberError, normalize_phone_number
from .xlsx_reader import XLSXReadError, read_worksheet


CANONICAL_COLUMNS = (
    "employee_id",
    "name",
    "organization",
    "office_phone",
    "mobile_phone",
    "active",
    "mobile_visible",
)

_HEADER_ALIASES: dict[str, tuple[str, ...]] = {
    "employee_id": ("employee_id", "employeeid", "user_id", "사번", "직원번호"),
    "name": ("name", "user_nm", "이름", "성명", "직원이름"),
    "organization": (
        "organization",
        "org",
        "full_dept_nm",
        "조직",
        "조직명",
        "부서",
        "부서명",
    ),
    "office_phone": (
        "office_phone",
        "officephone",
        "ofc_tel",
        "사무실번호",
        "사무실전화",
        "직통번호",
        "회사전화",
    ),
    "mobile_phone": (
        "mobile_phone",
        "mobilephone",
        "휴대전화",
        "휴대전화번호",
        "휴대폰",
        "휴대폰번호",
        "핸드폰번호",
    ),
    "active": ("active", "재직여부", "활성", "활성여부", "사용여부"),
    "mobile_visible": (
        "mobile_visible",
        "mobilevisible",
        "mobile_visibility",
        "mobile_public",
        "mobile_consent",
        "mobile_opt_in",
        "publish_mobile",
        "휴대전화공개",
        "휴대전화공개여부",
        "휴대폰공개",
        "휴대폰공개여부",
        "모바일공개",
        "모바일공개여부",
        "휴대전화동의",
        "휴대폰동의",
    ),
}

_TRUE_VALUES = frozenset({"1", "true", "yes", "y", "on", "active", "재직", "사용", "활성", "o"})
_FALSE_VALUES = frozenset({"0", "false", "no", "n", "off", "inactive", "퇴사", "미사용", "비활성", "x"})
_VISIBILITY_TRUE_VALUES = _TRUE_VALUES | frozenset({"공개", "동의", "허용"})
_VISIBILITY_FALSE_VALUES = _FALSE_VALUES | frozenset({"비공개", "미동의", "거부"})
_GENERATED_AT_PATTERN = re.compile(r"^[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}Z$")


class DirectoryImportError(ValueError):
    """A safe, row-oriented validation error for a directory administrator."""


@dataclass(frozen=True)
class ImportResult:
    snapshot: dict[str, object]
    active_employee_count: int
    entry_count: int
    unchanged: bool = False


def parse_column_assignments(assignments: Iterable[str]) -> dict[str, str]:
    result: dict[str, str] = {}
    for assignment in assignments:
        canonical, separator, source = assignment.partition("=")
        canonical = canonical.strip()
        source = source.strip()
        if not separator or canonical not in CANONICAL_COLUMNS or not source:
            raise DirectoryImportError(
                "column mappings must look like canonical_name=CSV header"
            )
        if canonical in result:
            raise DirectoryImportError(f"column {canonical!r} was mapped more than once")
        result[canonical] = source
    return result


def import_csv(
    input_path: Path,
    *,
    country_calling_code: str = "82",
    explicit_columns: Mapping[str, str] | None = None,
    previous_snapshot: Mapping[str, object] | None = None,
    allow_empty: bool = False,
    assume_active: bool = False,
    include_mobile_without_visibility: bool = False,
) -> ImportResult:
    input_path = Path(input_path)
    try:
        source = input_path.open("r", encoding="utf-8-sig", newline="")
    except (OSError, UnicodeError) as error:
        raise DirectoryImportError(f"cannot read CSV: {error}") from error

    with source:
        reader = csv.DictReader(source)
        if not reader.fieldnames:
            raise DirectoryImportError("CSV header row is missing")
        return _import_rows(
            reader.fieldnames,
            reader,
            source_label="CSV",
            country_calling_code=country_calling_code,
            explicit_columns=explicit_columns or {},
            previous_snapshot=previous_snapshot,
            allow_empty=allow_empty,
            assume_active=assume_active,
            include_mobile_without_visibility=include_mobile_without_visibility,
        )


def import_xlsx(
    input_path: Path,
    *,
    sheet_name: str | None = None,
    country_calling_code: str = "82",
    explicit_columns: Mapping[str, str] | None = None,
    previous_snapshot: Mapping[str, object] | None = None,
    allow_empty: bool = False,
    assume_active: bool = False,
    include_mobile_without_visibility: bool = False,
) -> ImportResult:
    try:
        fieldnames, rows = read_worksheet(Path(input_path), sheet_name=sheet_name)
    except XLSXReadError as error:
        raise DirectoryImportError(str(error)) from error
    return _import_rows(
        fieldnames,
        rows,
        source_label="worksheet",
        country_calling_code=country_calling_code,
        explicit_columns=explicit_columns or {},
        previous_snapshot=previous_snapshot,
        allow_empty=allow_empty,
        assume_active=assume_active,
        include_mobile_without_visibility=include_mobile_without_visibility,
    )


def import_directory_file(
    input_path: Path,
    *,
    sheet_name: str | None = None,
    country_calling_code: str = "82",
    explicit_columns: Mapping[str, str] | None = None,
    previous_snapshot: Mapping[str, object] | None = None,
    allow_empty: bool = False,
    assume_active: bool = False,
    include_mobile_without_visibility: bool = False,
) -> ImportResult:
    suffix = Path(input_path).suffix.casefold()
    common_arguments = {
        "country_calling_code": country_calling_code,
        "explicit_columns": explicit_columns,
        "previous_snapshot": previous_snapshot,
        "allow_empty": allow_empty,
        "assume_active": assume_active,
        "include_mobile_without_visibility": include_mobile_without_visibility,
    }
    if suffix == ".csv":
        if sheet_name is not None:
            raise DirectoryImportError("--sheet can only be used with an XLSX input")
        return import_csv(input_path, **common_arguments)
    if suffix == ".xlsx":
        return import_xlsx(input_path, sheet_name=sheet_name, **common_arguments)
    raise DirectoryImportError("input file must end in .csv or .xlsx")


def _import_rows(
    fieldnames: Iterable[str],
    rows: Iterable[Mapping[str | None, str | None]],
    *,
    source_label: str,
    country_calling_code: str,
    explicit_columns: Mapping[str, str],
    previous_snapshot: Mapping[str, object] | None,
    allow_empty: bool,
    assume_active: bool,
    include_mobile_without_visibility: bool,
) -> ImportResult:
    columns = resolve_columns(fieldnames, explicit_columns)
    if "active" not in columns and not assume_active:
        raise DirectoryImportError(
            "active column is missing; use --assume-active only for a verified active-only export"
        )
    entries_by_phone: dict[str, tuple[dict[str, str], str, int]] = {}
    active_employees: dict[str, tuple[str, str, int]] = {}

    for row_number, row in enumerate(rows, start=2):
        if None in row:
            raise DirectoryImportError(f"row {row_number}: too many fields")
        employee_id = _required_text(row, columns, "employee_id", row_number, 100)
        name = _required_text(row, columns, "name", row_number, 100)
        organization = _required_text(row, columns, "organization", row_number, 120)
        active = (
            _parse_active(_optional_text(row, columns, "active"), row_number)
            if "active" in columns
            else True
        )
        if not active:
            continue

        office_phone = _optional_text(row, columns, "office_phone")
        mobile_phone = _optional_text(row, columns, "mobile_phone")
        mobile_visible = (
            _parse_mobile_visible(
                _optional_text(row, columns, "mobile_visible"), row_number
            )
            if "mobile_visible" in columns
            else include_mobile_without_visibility
        )
        if not office_phone and not mobile_phone:
            raise DirectoryImportError(
                f"row {row_number}: office_phone or mobile_phone is required"
            )

        existing_employee = active_employees.get(employee_id)
        if existing_employee is not None:
            existing_name, existing_organization, existing_row = existing_employee
            if (existing_name, existing_organization) != (name, organization):
                raise DirectoryImportError(
                    f"row {row_number}: employee_id metadata conflicts with row {existing_row}"
                )
        else:
            active_employees[employee_id] = (name, organization, row_number)
        label = f"{organization} · {name}"
        if len(label) > 180:
            raise DirectoryImportError(f"row {row_number}: caller ID label is too long")
        for number_type, raw_phone in (
            ("office", office_phone),
            ("mobile", mobile_phone if mobile_visible else ""),
        ):
            if not raw_phone:
                continue
            try:
                phone_number = normalize_phone_number(raw_phone, country_calling_code)
            except PhoneNumberError as error:
                raise DirectoryImportError(
                    f"row {row_number}: invalid {number_type} phone ({error})"
                ) from error

            entry = {
                "phone_number": phone_number,
                "label": label,
                "name": name,
                "organization": organization,
                "number_type": number_type,
            }
            existing = entries_by_phone.get(phone_number)
            if existing is not None:
                existing_entry, _existing_employee_id, existing_row = existing
                if existing_entry["label"] == entry["label"]:
                    continue
                raise DirectoryImportError(
                    f"row {row_number}: phone conflicts with row {existing_row}"
                )
            entries_by_phone[phone_number] = (entry, employee_id, row_number)
            if len(entries_by_phone) > MAX_ENTRIES:
                raise DirectoryImportError(
                    f"snapshot cannot contain more than {MAX_ENTRIES} phone entries"
                )

    if not entries_by_phone and not allow_empty:
        raise DirectoryImportError(
            f"{source_label} contains no active employee phone numbers; "
            "use --allow-empty only when clearing every device is intentional"
        )

    entries = [record[0] for record in entries_by_phone.values()]
    entries.sort(key=lambda entry: _phone_sort_key(entry["phone_number"]))
    entries_payload = json.dumps(
        entries,
        ensure_ascii=False,
        sort_keys=True,
        separators=(",", ":"),
    ).encode("utf-8")
    version = hashlib.sha256(entries_payload).hexdigest()

    if (
        previous_snapshot
        and set(previous_snapshot) == {"version", "generated_at", "entries"}
        and previous_snapshot.get("version") == version
        and _is_valid_generated_at(previous_snapshot.get("generated_at"))
        and previous_snapshot.get("entries") == entries
    ):
        preserved_snapshot = dict(previous_snapshot)
        _validate_snapshot_limits(preserved_snapshot)
        return ImportResult(
            snapshot=preserved_snapshot,
            active_employee_count=len(active_employees),
            entry_count=len(entries),
            unchanged=True,
        )

    generated_at = datetime.now(timezone.utc).replace(microsecond=0).isoformat().replace("+00:00", "Z")
    snapshot: dict[str, object] = {
        "version": version,
        "generated_at": generated_at,
        "entries": entries,
    }
    _validate_snapshot_limits(snapshot)
    return ImportResult(
        snapshot=snapshot,
        active_employee_count=len(active_employees),
        entry_count=len(entries),
    )


def resolve_columns(
    fieldnames: Iterable[str],
    explicit_columns: Mapping[str, str],
) -> dict[str, str]:
    original_headers = list(fieldnames)
    if not original_headers or any(
        not isinstance(header, str) or not header.strip() for header in original_headers
    ):
        raise DirectoryImportError("CSV contains an empty header")
    if len(set(original_headers)) != len(original_headers):
        raise DirectoryImportError("CSV contains duplicate headers")

    normalized_to_original: dict[str, list[str]] = {}
    for header in original_headers:
        normalized_to_original.setdefault(_normalized_header(header), []).append(header)
    if any(len(headers) > 1 for headers in normalized_to_original.values()):
        raise DirectoryImportError("CSV contains duplicate normalized headers")

    resolved: dict[str, str] = {}
    for canonical in CANONICAL_COLUMNS:
        explicit = explicit_columns.get(canonical)
        if explicit is not None:
            matching_headers = [
                header
                for header in original_headers
                if header == explicit or header.strip() == explicit
            ]
            if len(matching_headers) != 1:
                raise DirectoryImportError(
                    f"mapped CSV header {explicit!r} for {canonical!r} does not exist"
                )
            resolved[canonical] = matching_headers[0]
            continue

        candidates: list[str] = []
        for alias in _HEADER_ALIASES[canonical]:
            candidates.extend(normalized_to_original.get(_normalized_header(alias), []))
        candidates = list(dict.fromkeys(candidates))
        if len(candidates) > 1:
            raise DirectoryImportError(
                f"multiple headers match {canonical!r}; use --column {canonical}=HEADER"
            )
        if candidates:
            resolved[canonical] = candidates[0]

    for required in ("employee_id", "name", "organization"):
        if required not in resolved:
            raise DirectoryImportError(
                f"required column {required!r} is missing; use --column to map it"
            )
    if "office_phone" not in resolved and "mobile_phone" not in resolved:
        raise DirectoryImportError("office_phone or mobile_phone column is required")
    return resolved


def load_existing_snapshot(path: Path) -> dict[str, object] | None:
    path = Path(path)
    if not path.exists():
        return None
    try:
        if path.stat().st_size > MAX_SNAPSHOT_BYTES:
            raise DirectoryImportError("existing snapshot exceeds the 10 MiB limit")
        with path.open("rb") as source:
            payload = source.read(MAX_SNAPSHOT_BYTES + 1)
        if len(payload) > MAX_SNAPSHOT_BYTES:
            raise DirectoryImportError("existing snapshot exceeds the 10 MiB limit")
        value = json.loads(payload)
    except (OSError, UnicodeError, json.JSONDecodeError) as error:
        raise DirectoryImportError(f"existing snapshot is invalid: {error}") from error
    if not isinstance(value, dict):
        raise DirectoryImportError("existing snapshot root must be an object")
    return value


def write_snapshot_atomic(path: Path, snapshot: Mapping[str, object]) -> None:
    path = Path(path)
    entries = snapshot.get("entries")
    if not isinstance(entries, list) or len(entries) > MAX_ENTRIES:
        raise DirectoryImportError(
            f"snapshot entries must be a list containing at most {MAX_ENTRIES} items"
        )
    payload = _snapshot_payload(snapshot)
    if len(payload) > MAX_SNAPSHOT_BYTES:
        raise DirectoryImportError("snapshot exceeds the 10 MiB limit")
    path.parent.mkdir(parents=True, exist_ok=True)

    file_descriptor, temporary_name = tempfile.mkstemp(
        prefix=f".{path.name}.",
        suffix=".tmp",
        dir=path.parent,
    )
    try:
        os.fchmod(file_descriptor, 0o600)
        with os.fdopen(file_descriptor, "wb") as temporary_file:
            file_descriptor = -1
            temporary_file.write(payload)
            temporary_file.flush()
            os.fsync(temporary_file.fileno())
        os.replace(temporary_name, path)
        try:
            directory_fd = os.open(path.parent, os.O_RDONLY)
        except OSError:
            directory_fd = -1
        if directory_fd >= 0:
            try:
                os.fsync(directory_fd)
            finally:
                os.close(directory_fd)
    except Exception:
        if file_descriptor >= 0:
            os.close(file_descriptor)
        try:
            os.unlink(temporary_name)
        except FileNotFoundError:
            pass
        raise


def _required_text(
    row: Mapping[str, str | None],
    columns: Mapping[str, str],
    canonical: str,
    row_number: int,
    maximum_length: int,
) -> str:
    value = _clean_text(row.get(columns[canonical]))
    if not value:
        raise DirectoryImportError(f"row {row_number}: {canonical} is required")
    if len(value) > maximum_length:
        raise DirectoryImportError(f"row {row_number}: {canonical} is too long")
    return value


def _optional_text(
    row: Mapping[str, str | None],
    columns: Mapping[str, str],
    canonical: str,
) -> str:
    source = columns.get(canonical)
    return _clean_text(row.get(source)) if source is not None else ""


def _clean_text(value: str | None) -> str:
    return " ".join((value or "").strip().split())


def _parse_active(value: str, row_number: int) -> bool:
    if not value:
        raise DirectoryImportError(f"row {row_number}: active is required")
    normalized = value.casefold()
    if normalized in _TRUE_VALUES:
        return True
    if normalized in _FALSE_VALUES:
        return False
    raise DirectoryImportError(f"row {row_number}: active has an unknown value")


def _parse_mobile_visible(value: str, row_number: int) -> bool:
    if not value:
        return False
    normalized = value.casefold()
    if normalized in _VISIBILITY_TRUE_VALUES:
        return True
    if normalized in _VISIBILITY_FALSE_VALUES:
        return False
    raise DirectoryImportError(
        f"row {row_number}: mobile_visible has an unknown value"
    )


def _is_valid_generated_at(value: object) -> bool:
    if not isinstance(value, str) or not _GENERATED_AT_PATTERN.fullmatch(value):
        return False
    try:
        datetime.strptime(value, "%Y-%m-%dT%H:%M:%SZ")
    except ValueError:
        return False
    return True


def _snapshot_payload(snapshot: Mapping[str, object]) -> bytes:
    return (
        json.dumps(snapshot, ensure_ascii=False, sort_keys=True, separators=(",", ":"))
        + "\n"
    ).encode("utf-8")


def _validate_snapshot_limits(snapshot: Mapping[str, object]) -> None:
    entries = snapshot.get("entries")
    if not isinstance(entries, list) or len(entries) > MAX_ENTRIES:
        raise DirectoryImportError(
            f"snapshot cannot contain more than {MAX_ENTRIES} phone entries"
        )
    if len(_snapshot_payload(snapshot)) > MAX_SNAPSHOT_BYTES:
        raise DirectoryImportError("snapshot exceeds the 10 MiB limit")


def _normalized_header(value: str) -> str:
    return "".join(character for character in value.strip().casefold() if character not in " _-")


def _phone_sort_key(phone_number: str) -> int:
    return int(phone_number.removeprefix("+"))
