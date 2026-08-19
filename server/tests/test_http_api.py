import json
import tempfile
import threading
import unittest
import urllib.error
import urllib.request
from pathlib import Path

from directory_service.http_api import build_server
from directory_service.importer import import_csv, write_snapshot_atomic


class HTTPAPITests(unittest.TestCase):
    TOKEN = "test-secret-token-that-is-at-least-32-bytes"

    def setUp(self) -> None:
        self.temporary_directory = tempfile.TemporaryDirectory()
        root = Path(self.temporary_directory.name)
        csv_path = root / "employees.csv"
        csv_path.write_text(
            "employee_id,name,organization,office_phone,mobile_phone,active\n"
            "E1,User One,Team One,02-555-0101,,true\n",
            encoding="utf-8",
        )
        snapshot_path = root / "directory.json"
        write_snapshot_atomic(snapshot_path, import_csv(csv_path).snapshot)
        self.server = build_server(
            host="127.0.0.1",
            port=0,
            snapshot_path=snapshot_path,
            api_token=self.TOKEN,
        )
        self.thread = threading.Thread(target=self.server.serve_forever, daemon=True)
        self.thread.start()
        self.base_url = f"http://127.0.0.1:{self.server.server_port}"

    def tearDown(self) -> None:
        self.server.shutdown()
        self.server.server_close()
        self.thread.join(timeout=2)
        self.temporary_directory.cleanup()

    def request(self, path: str, *, token: str | None = None, etag: str | None = None):
        headers = {}
        if token is not None:
            headers["Authorization"] = f"Bearer {token}"
        if etag is not None:
            headers["If-None-Match"] = etag
        return urllib.request.urlopen(
            urllib.request.Request(self.base_url + path, headers=headers),
            timeout=2,
        )

    def test_health_is_public(self) -> None:
        with self.request("/healthz") as response:
            self.assertEqual(response.status, 200)
            self.assertEqual(json.load(response), {"status": "ok"})

    def test_directory_requires_bearer_token(self) -> None:
        with self.assertRaises(urllib.error.HTTPError) as raised:
            self.request("/v1/directory")
        self.assertEqual(raised.exception.code, 401)

    def test_directory_returns_snapshot_and_honors_etag(self) -> None:
        with self.request("/v1/directory", token=self.TOKEN) as response:
            self.assertEqual(response.status, 200)
            etag = response.headers["ETag"]
            document = json.load(response)
        self.assertEqual(document["entries"][0]["phone_number"], "+8225550101")

        with self.assertRaises(urllib.error.HTTPError) as raised:
            self.request(
                "/v1/directory",
                token=self.TOKEN,
                etag=etag,
            )
        self.assertEqual(raised.exception.code, 304)
        self.assertEqual(raised.exception.headers["ETag"], etag)
        self.assertIsNone(raised.exception.headers.get("Content-Length"))


if __name__ == "__main__":
    unittest.main()
