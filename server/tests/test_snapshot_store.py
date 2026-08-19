import json
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

from directory_service.http_api import SnapshotLoadError, SnapshotStore, create_handler
from directory_service.importer import import_csv, write_snapshot_atomic
from directory_service.limits import MAX_ENTRIES


class SnapshotStoreTests(unittest.TestCase):
    def setUp(self) -> None:
        self.temporary_directory = tempfile.TemporaryDirectory()
        self.root = Path(self.temporary_directory.name)
        source = self.root / "employees.csv"
        source.write_text(
            "employee_id,name,organization,office_phone,mobile_phone,active\n"
            "E1,User One,Team One,02-555-0101,,true\n",
            encoding="utf-8",
        )
        self.snapshot_path = self.root / "directory.json"
        write_snapshot_atomic(self.snapshot_path, import_csv(source).snapshot)

    def tearDown(self) -> None:
        self.temporary_directory.cleanup()

    def test_rejects_snapshot_whose_version_does_not_match_entries(self) -> None:
        document = json.loads(self.snapshot_path.read_text(encoding="utf-8"))
        document["version"] = "0" * 64
        self.snapshot_path.write_text(json.dumps(document), encoding="utf-8")

        with self.assertRaisesRegex(SnapshotLoadError, "version"):
            SnapshotStore(self.snapshot_path).load()

    def test_rejects_snapshot_with_invalid_generated_timestamp(self) -> None:
        document = json.loads(self.snapshot_path.read_text(encoding="utf-8"))
        document["generated_at"] = "2026-02-30T00:00:00Z"
        self.snapshot_path.write_text(json.dumps(document), encoding="utf-8")

        with self.assertRaisesRegex(SnapshotLoadError, "timestamp"):
            SnapshotStore(self.snapshot_path).load()

    def test_rejects_short_api_token_before_serving(self) -> None:
        with self.assertRaisesRegex(ValueError, "32 to"):
            create_handler(
                snapshot_store=SnapshotStore(self.snapshot_path),
                expected_token="too-short",
            )

    def test_rejects_snapshot_larger_than_shared_limit(self) -> None:
        with patch("directory_service.http_api.MAX_SNAPSHOT_BYTES", 10):
            self.snapshot_path.write_bytes(b"x" * 11)
            with self.assertRaisesRegex(SnapshotLoadError, "size"):
                SnapshotStore(self.snapshot_path).load()

    def test_rejects_more_than_maximum_entries(self) -> None:
        document = {
            "version": "x",
            "generated_at": "2026-08-20T00:00:00Z",
            "entries": [{}] * (MAX_ENTRIES + 1),
        }
        self.snapshot_path.write_text(json.dumps(document), encoding="utf-8")
        with self.assertRaisesRegex(SnapshotLoadError, "entry count"):
            SnapshotStore(self.snapshot_path).load()


if __name__ == "__main__":
    unittest.main()
