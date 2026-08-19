from __future__ import annotations

import hashlib
import hmac
import json
import os
import re
import threading
from dataclasses import dataclass
from datetime import datetime
from http import HTTPStatus
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from urllib.parse import urlsplit

from .limits import MAX_ENTRIES, MAX_SNAPSHOT_BYTES

MAX_TOKEN_BYTES = 16 * 1024
MIN_TOKEN_BYTES = 32
_CANONICAL_PHONE = re.compile(r"^\+[1-9][0-9]{6,14}$")
_GENERATED_AT_PATTERN = re.compile(
    r"^[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}Z$"
)


class SnapshotLoadError(RuntimeError):
    pass


@dataclass(frozen=True)
class LoadedSnapshot:
    payload: bytes
    etag: str
    version: str
    modified_ns: int
    file_size: int


class SnapshotStore:
    def __init__(self, path: Path):
        self._path = Path(path)
        self._lock = threading.Lock()
        self._cached: LoadedSnapshot | None = None

    def load(self) -> LoadedSnapshot:
        try:
            stat = self._path.stat()
        except OSError as error:
            raise SnapshotLoadError("directory snapshot is unavailable") from error
        if stat.st_size <= 0 or stat.st_size > MAX_SNAPSHOT_BYTES:
            raise SnapshotLoadError("directory snapshot size is invalid")

        cached = self._cached
        if cached and cached.modified_ns == stat.st_mtime_ns and cached.file_size == stat.st_size:
            return cached

        with self._lock:
            cached = self._cached
            if cached and cached.modified_ns == stat.st_mtime_ns and cached.file_size == stat.st_size:
                return cached
            try:
                with self._path.open("rb") as source:
                    payload = source.read(MAX_SNAPSHOT_BYTES + 1)
                if len(payload) > MAX_SNAPSHOT_BYTES:
                    raise SnapshotLoadError("directory snapshot size is invalid")
                document = json.loads(payload)
                version = document["version"]
                generated_at = document["generated_at"]
                entries = document["entries"]
            except (OSError, UnicodeError, json.JSONDecodeError, KeyError, TypeError) as error:
                raise SnapshotLoadError("directory snapshot is invalid") from error
            if not isinstance(version, str) or not version:
                raise SnapshotLoadError("directory snapshot version is invalid")
            if not isinstance(generated_at, str) or not generated_at:
                raise SnapshotLoadError("directory snapshot timestamp is invalid")
            if not isinstance(entries, list):
                raise SnapshotLoadError("directory snapshot entries are invalid")
            _validate_snapshot_document(document)

            # The ETag covers the exact representation sent on the wire.
            etag = '"' + hashlib.sha256(payload).hexdigest() + '"'
            loaded = LoadedSnapshot(payload, etag, version, stat.st_mtime_ns, stat.st_size)
            self._cached = loaded
            return loaded


def create_handler(
    *,
    snapshot_store: SnapshotStore,
    expected_token: str,
) -> type[BaseHTTPRequestHandler]:
    token_bytes = expected_token.encode("utf-8")
    if not MIN_TOKEN_BYTES <= len(token_bytes) <= MAX_TOKEN_BYTES:
        raise ValueError("API token must contain 32 to 16384 UTF-8 bytes")

    class DirectoryRequestHandler(BaseHTTPRequestHandler):
        server_version = "CallDirectoryBeta/0.1"
        sys_version = ""
        protocol_version = "HTTP/1.1"

        def do_GET(self) -> None:  # noqa: N802 - BaseHTTPRequestHandler API
            parsed = urlsplit(self.path)
            if parsed.query or parsed.fragment:
                self._json_error(HTTPStatus.NOT_FOUND, "not_found")
                return
            if parsed.path == "/healthz":
                self._send_json(HTTPStatus.OK, b'{"status":"ok"}\n')
                return
            if parsed.path != "/v1/directory":
                self._json_error(HTTPStatus.NOT_FOUND, "not_found")
                return
            if not self._is_authorized():
                self.send_response(HTTPStatus.UNAUTHORIZED)
                self.send_header("WWW-Authenticate", 'Bearer realm="directory"')
                self.send_header("Cache-Control", "no-store")
                self.send_header("Content-Length", "0")
                self.end_headers()
                return

            try:
                snapshot = snapshot_store.load()
            except SnapshotLoadError:
                self._json_error(HTTPStatus.SERVICE_UNAVAILABLE, "directory_unavailable")
                return

            if _etag_matches(self.headers.get("If-None-Match"), snapshot.etag):
                self.send_response(HTTPStatus.NOT_MODIFIED)
                self._common_directory_headers(snapshot)
                self.end_headers()
                return

            self.send_response(HTTPStatus.OK)
            self._common_directory_headers(snapshot)
            self.send_header("Content-Type", "application/json; charset=utf-8")
            self.send_header("Content-Length", str(len(snapshot.payload)))
            self.end_headers()
            self.wfile.write(snapshot.payload)

        def do_HEAD(self) -> None:  # noqa: N802
            self._json_error(HTTPStatus.METHOD_NOT_ALLOWED, "method_not_allowed")

        def do_POST(self) -> None:  # noqa: N802
            self._json_error(HTTPStatus.METHOD_NOT_ALLOWED, "method_not_allowed")

        def log_message(self, format_string: str, *args: object) -> None:
            # Log method/path/status only. Authorization values and response bodies
            # are never interpolated by BaseHTTPRequestHandler's standard format.
            super().log_message(format_string, *args)

        def _is_authorized(self) -> bool:
            authorization = self.headers.get("Authorization")
            if authorization is None or not authorization.startswith("Bearer "):
                return False
            supplied = authorization[7:].encode("utf-8")
            if len(supplied) > MAX_TOKEN_BYTES:
                return False
            return hmac.compare_digest(supplied, token_bytes)

        def _common_directory_headers(self, snapshot: LoadedSnapshot) -> None:
            self.send_header("ETag", snapshot.etag)
            self.send_header("X-Directory-Version", snapshot.version)
            self.send_header("Cache-Control", "private, no-store")
            self.send_header("X-Content-Type-Options", "nosniff")

        def _send_json(self, status: HTTPStatus, payload: bytes) -> None:
            self.send_response(status)
            self.send_header("Content-Type", "application/json; charset=utf-8")
            self.send_header("Cache-Control", "no-store")
            self.send_header("X-Content-Type-Options", "nosniff")
            self.send_header("Content-Length", str(len(payload)))
            self.end_headers()
            self.wfile.write(payload)

        def _json_error(self, status: HTTPStatus, code: str) -> None:
            payload = json.dumps(
                {"error": code}, separators=(",", ":")
            ).encode("utf-8") + b"\n"
            self._send_json(status, payload)

    return DirectoryRequestHandler


def build_server(
    *,
    host: str,
    port: int,
    snapshot_path: Path,
    api_token: str,
) -> ThreadingHTTPServer:
    store = SnapshotStore(snapshot_path)
    # Fail before binding so an operator doesn't expose an empty/broken service.
    store.load()
    handler = create_handler(snapshot_store=store, expected_token=api_token)
    server = ThreadingHTTPServer((host, port), handler)
    server.daemon_threads = True
    return server


def token_from_environment() -> str:
    token = os.environ.get("CALL_SERVICE_API_TOKEN", "")
    if not token:
        raise ValueError("CALL_SERVICE_API_TOKEN is required")
    if "\r" in token or "\n" in token:
        raise ValueError("CALL_SERVICE_API_TOKEN cannot contain a newline")
    return token


def _etag_matches(header: str | None, current_etag: str) -> bool:
    if header is None:
        return False
    candidates = [candidate.strip() for candidate in header.split(",")]
    if "*" in candidates:
        return True
    normalized_current = current_etag.removeprefix("W/")
    return any(candidate.removeprefix("W/") == normalized_current for candidate in candidates)


def _validate_snapshot_document(document: dict[str, object]) -> None:
    if set(document) != {"version", "generated_at", "entries"}:
        raise SnapshotLoadError("directory snapshot fields are invalid")
    generated_at = document["generated_at"]
    if not _is_valid_generated_at(generated_at):
        raise SnapshotLoadError("directory snapshot timestamp is invalid")
    entries = document["entries"]
    if not isinstance(entries, list) or len(entries) > MAX_ENTRIES:
        raise SnapshotLoadError("directory snapshot entry count is invalid")

    previous_phone_value = 0
    for entry in entries:
        if not isinstance(entry, dict) or set(entry) != {
            "phone_number",
            "label",
            "name",
            "organization",
            "number_type",
        }:
            raise SnapshotLoadError("directory snapshot entry fields are invalid")
        phone_number = entry["phone_number"]
        label = entry["label"]
        name = entry["name"]
        organization = entry["organization"]
        number_type = entry["number_type"]
        if not isinstance(phone_number, str) or not _CANONICAL_PHONE.fullmatch(phone_number):
            raise SnapshotLoadError("directory snapshot phone number is invalid")
        phone_value = int(phone_number[1:])
        if phone_value <= previous_phone_value:
            raise SnapshotLoadError("directory snapshot phone numbers are not strictly sorted")
        previous_phone_value = phone_value
        if not isinstance(label, str) or not label.strip() or len(label) > 180:
            raise SnapshotLoadError("directory snapshot label is invalid")
        if not isinstance(name, str) or len(name) > 100:
            raise SnapshotLoadError("directory snapshot name is invalid")
        if not isinstance(organization, str) or len(organization) > 120:
            raise SnapshotLoadError("directory snapshot organization is invalid")
        if number_type not in {"office", "mobile"}:
            raise SnapshotLoadError("directory snapshot number type is invalid")

    canonical_entries = json.dumps(
        entries,
        ensure_ascii=False,
        sort_keys=True,
        separators=(",", ":"),
    ).encode("utf-8")
    expected_version = hashlib.sha256(canonical_entries).hexdigest()
    if document["version"] != expected_version:
        raise SnapshotLoadError("directory snapshot version does not match its entries")


def _is_valid_generated_at(value: object) -> bool:
    if not isinstance(value, str) or not _GENERATED_AT_PATTERN.fullmatch(value):
        return False
    try:
        datetime.strptime(value, "%Y-%m-%dT%H:%M:%SZ")
    except ValueError:
        return False
    return True
