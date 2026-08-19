import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

from directory_service.importer import (
    DirectoryImportError,
    import_csv,
    load_existing_snapshot,
    write_snapshot_atomic,
)
from directory_service.limits import MAX_ENTRIES


class ImporterTests(unittest.TestCase):
    def setUp(self) -> None:
        self.temporary_directory = tempfile.TemporaryDirectory()
        self.root = Path(self.temporary_directory.name)

    def tearDown(self) -> None:
        self.temporary_directory.cleanup()

    def write_csv(self, content: str, *, bom: bool = False) -> Path:
        path = self.root / "employees.csv"
        encoding = "utf-8-sig" if bom else "utf-8"
        path.write_text(content, encoding=encoding, newline="")
        return path

    def test_imports_bom_and_korean_aliases(self) -> None:
        source = self.write_csv(
            "사번,이름,부서,사무실번호,휴대전화번호,재직여부,휴대전화공개여부\r\n"
            "E1,가나다,플랫폼팀,02-555-0101,010-1234-5678,재직,공개\r\n",
            bom=True,
        )
        result = import_csv(source)

        self.assertEqual(result.active_employee_count, 1)
        self.assertEqual(result.entry_count, 2)
        self.assertEqual(
            [entry["phone_number"] for entry in result.snapshot["entries"]],
            ["+8225550101", "+821012345678"],
        )
        self.assertEqual(result.snapshot["entries"][0]["label"], "플랫폼팀 · 가나다")

    def test_skips_inactive_employee(self) -> None:
        source = self.write_csv(
            "employee_id,name,organization,office_phone,mobile_phone,active\n"
            "E1,User One,Team One,02-555-0101,,true\n"
            "E2,User Two,Team Two,02-555-0102,,false\n"
        )
        result = import_csv(source)
        self.assertEqual(result.active_employee_count, 1)
        self.assertEqual(result.entry_count, 1)

    def test_missing_active_requires_explicit_assumption(self) -> None:
        source = self.write_csv(
            "employee_id,name,organization,office_phone\n"
            "E1,User One,Team One,02-555-0101\n"
        )
        with self.assertRaisesRegex(DirectoryImportError, "--assume-active"):
            import_csv(source)

        result = import_csv(source, assume_active=True)
        self.assertEqual(result.active_employee_count, 1)
        self.assertEqual(result.entry_count, 1)

    def test_blank_active_is_rejected_when_column_exists(self) -> None:
        source = self.write_csv(
            "employee_id,name,organization,office_phone,active\n"
            "E1,User One,Team One,02-555-0101,\n"
        )
        with self.assertRaisesRegex(DirectoryImportError, "active is required"):
            import_csv(source, assume_active=True)

    def test_mobile_is_private_without_visibility_column_unless_opted_in(self) -> None:
        source = self.write_csv(
            "employee_id,name,organization,office_phone,mobile_phone,active\n"
            "E1,User One,Team One,02-555-0101,010-1234-5678,true\n"
        )

        private_result = import_csv(source)
        self.assertEqual(private_result.entry_count, 1)
        self.assertEqual(
            private_result.snapshot["entries"][0]["number_type"], "office"
        )

        opted_in_result = import_csv(
            source, include_mobile_without_visibility=True
        )
        self.assertEqual(opted_in_result.entry_count, 2)

    def test_visibility_column_includes_true_and_excludes_false_or_blank(self) -> None:
        source = self.write_csv(
            "employee_id,name,organization,office_phone,mobile_phone,active,휴대폰공개여부\n"
            "E1,User One,Team One,02-555-0101,010-1111-0001,true,동의\n"
            "E2,User Two,Team Two,02-555-0102,010-1111-0002,true,비공개\n"
            "E3,User Three,Team Three,02-555-0103,010-1111-0003,true,\n"
        )
        result = import_csv(source, include_mobile_without_visibility=True)

        self.assertEqual(result.active_employee_count, 3)
        self.assertEqual(result.entry_count, 4)
        mobile_entries = [
            entry
            for entry in result.snapshot["entries"]
            if entry["number_type"] == "mobile"
        ]
        self.assertEqual(
            [entry["phone_number"] for entry in mobile_entries], ["+821011110001"]
        )

    def test_unknown_mobile_visibility_is_rejected(self) -> None:
        source = self.write_csv(
            "employee_id,name,organization,office_phone,mobile_phone,active,mobile_visible\n"
            "E1,User One,Team One,02-555-0101,010-1234-5678,true,maybe\n"
        )
        with self.assertRaisesRegex(DirectoryImportError, "mobile_visible"):
            import_csv(source)

    def test_empty_snapshot_requires_explicit_opt_in(self) -> None:
        source = self.write_csv(
            "employee_id,name,organization,office_phone,mobile_phone,active\n"
            "E1,User One,Team One,02-555-0101,,false\n"
        )
        with self.assertRaisesRegex(DirectoryImportError, "--allow-empty"):
            import_csv(source)

        result = import_csv(source, allow_empty=True)
        self.assertEqual(result.active_employee_count, 0)
        self.assertEqual(result.entry_count, 0)
        self.assertEqual(result.snapshot["entries"], [])

    def test_rejects_phone_conflict_between_employees(self) -> None:
        source = self.write_csv(
            "employee_id,name,organization,office_phone,mobile_phone,active\n"
            "E1,User One,Team One,02-555-0101,,true\n"
            "E2,User Two,Team Two,02-555-0101,,true\n"
        )
        with self.assertRaisesRegex(DirectoryImportError, "conflicts"):
            import_csv(source)

    def test_rejects_inconsistent_metadata_for_one_employee_id(self) -> None:
        source = self.write_csv(
            "employee_id,name,organization,office_phone,mobile_phone,active\n"
            "E1,User One,Team One,02-555-0101,,true\n"
            "E1,User One,Team Two,02-555-0102,,true\n"
        )
        with self.assertRaisesRegex(DirectoryImportError, "metadata conflicts"):
            import_csv(source)

    def test_rejects_label_longer_than_mobile_contract(self) -> None:
        source = self.write_csv(
            "employee_id,name,organization,office_phone,mobile_phone,active\n"
            f"E1,{'N' * 80},{'O' * 100},02-555-0101,,true\n"
        )
        with self.assertRaisesRegex(DirectoryImportError, "label is too long"):
            import_csv(source)

    def test_rejects_ambiguous_aliases(self) -> None:
        source = self.write_csv(
            "employee_id,사번,name,organization,office_phone\n"
            "E1,E1,User One,Team One,02-555-0101\n"
        )
        with self.assertRaisesRegex(DirectoryImportError, "multiple headers"):
            import_csv(source)

    def test_whitespace_header_mapping_uses_exact_dict_reader_keys(self) -> None:
        source = self.write_csv(
            " employee_id , name , organization , office_phone , active \n"
            " E1 , User One , Team One , 02-555-0101 , true \n"
        )
        result = import_csv(source)
        self.assertEqual(result.entry_count, 1)
        self.assertEqual(result.snapshot["entries"][0]["name"], "User One")

        explicitly_mapped = import_csv(
            source,
            explicit_columns={"name": "name"},
        )
        self.assertEqual(explicitly_mapped.entry_count, 1)

    def test_rejects_headers_that_duplicate_after_normalization(self) -> None:
        source = self.write_csv(
            "employee_id,name, name ,organization,office_phone,active\n"
            "E1,User One,User One,Team One,02-555-0101,true\n"
        )
        with self.assertRaisesRegex(DirectoryImportError, "normalized headers"):
            import_csv(source)

    def test_same_phone_and_label_collapses_across_employee_ids(self) -> None:
        source = self.write_csv(
            "employee_id,name,organization,office_phone,active\n"
            "E1,Shared Desk,Operations,02-555-0101,true\n"
            "E2,Shared Desk,Operations,02-555-0101,true\n"
        )
        result = import_csv(source)
        self.assertEqual(result.active_employee_count, 2)
        self.assertEqual(result.entry_count, 1)

    def test_preserves_snapshot_when_entries_are_unchanged(self) -> None:
        source = self.write_csv(
            "employee_id,name,organization,office_phone,mobile_phone,active\n"
            "E1,User One,Team One,02-555-0101,,true\n"
        )
        first = import_csv(source)
        second = import_csv(source, previous_snapshot=first.snapshot)
        self.assertTrue(second.unchanged)
        self.assertEqual(second.snapshot, first.snapshot)

    def test_repairs_noncanonical_existing_snapshot(self) -> None:
        source = self.write_csv(
            "employee_id,name,organization,office_phone,mobile_phone,active\n"
            "E1,User One,Team One,02-555-0101,,true\n"
        )
        first = import_csv(source)
        corrupt = dict(first.snapshot)
        corrupt["unexpected"] = "value"

        repaired = import_csv(source, previous_snapshot=corrupt)

        self.assertFalse(repaired.unchanged)
        self.assertNotIn("unexpected", repaired.snapshot)

    def test_invalid_previous_timestamp_is_regenerated(self) -> None:
        source = self.write_csv(
            "employee_id,name,organization,office_phone,active\n"
            "E1,User One,Team One,02-555-0101,true\n"
        )
        first = import_csv(source)
        invalid = dict(first.snapshot)
        invalid["generated_at"] = "2026-02-30T00:00:00Z"

        regenerated = import_csv(source, previous_snapshot=invalid)

        self.assertFalse(regenerated.unchanged)
        self.assertNotEqual(regenerated.snapshot["generated_at"], invalid["generated_at"])

    def test_atomic_snapshot_round_trip(self) -> None:
        source = self.write_csv(
            "employee_id,name,organization,office_phone,mobile_phone,active\n"
            "E1,User One,Team One,02-555-0101,,true\n"
        )
        snapshot_path = self.root / "var" / "directory.json"
        result = import_csv(source)
        write_snapshot_atomic(snapshot_path, result.snapshot)
        loaded = load_existing_snapshot(snapshot_path)
        self.assertEqual(loaded, result.snapshot)
        self.assertEqual(snapshot_path.stat().st_mode & 0o777, 0o600)

    def test_generated_and_written_snapshots_enforce_size_limit(self) -> None:
        source = self.write_csv(
            "employee_id,name,organization,office_phone,active\n"
            "E1,User One,Team One,02-555-0101,true\n"
        )
        with patch("directory_service.importer.MAX_SNAPSHOT_BYTES", 1):
            with self.assertRaisesRegex(DirectoryImportError, "10 MiB"):
                import_csv(source)

            snapshot_path = self.root / "too-large.json"
            with self.assertRaisesRegex(DirectoryImportError, "10 MiB"):
                write_snapshot_atomic(
                    snapshot_path,
                    {"version": "x", "generated_at": "x", "entries": []},
                )
            self.assertFalse(snapshot_path.exists())

    def test_atomic_writer_enforces_entry_limit_before_writing(self) -> None:
        snapshot_path = self.root / "too-many.json"
        oversized = {
            "version": "x",
            "generated_at": "2026-08-20T00:00:00Z",
            "entries": [{}] * (MAX_ENTRIES + 1),
        }
        with self.assertRaisesRegex(DirectoryImportError, "at most"):
            write_snapshot_atomic(snapshot_path, oversized)
        self.assertFalse(snapshot_path.exists())


if __name__ == "__main__":
    unittest.main()
