import tempfile
import unittest
from pathlib import Path
from zipfile import ZipFile

from directory_service.importer import DirectoryImportError, import_xlsx


class XLSXImporterTests(unittest.TestCase):
    def setUp(self) -> None:
        self.temporary_directory = tempfile.TemporaryDirectory()
        self.root = Path(self.temporary_directory.name)

    def tearDown(self) -> None:
        self.temporary_directory.cleanup()

    def test_imports_plain_shared_string_workbook_and_detected_aliases(self) -> None:
        path = self.root / "employees.xlsx"
        values = [
            "user_id",
            "user_nm",
            "duty_nm",
            "ofc_tel",
            "mobile_phone",
            "full_dept_nm",
            "E1",
            "User One",
            "Engineer",
            "02-555-0101",
            "010-1234-5678",
            "Platform Team",
        ]
        self.write_workbook(path, values=values)

        result = import_xlsx(
            path,
            assume_active=True,
            include_mobile_without_visibility=True,
        )

        self.assertEqual(result.active_employee_count, 1)
        self.assertEqual(result.entry_count, 2)
        self.assertEqual(
            [entry["phone_number"] for entry in result.snapshot["entries"]],
            ["+8225550101", "+821012345678"],
        )
        self.assertEqual(result.snapshot["entries"][0]["label"], "Platform Team · User One")

    def test_rejects_formula_cells(self) -> None:
        path = self.root / "formula.xlsx"
        values = [
            "user_id",
            "user_nm",
            "duty_nm",
            "ofc_tel",
            "mobile_phone",
            "full_dept_nm",
            "E1",
            "User One",
            "Engineer",
            "02-555-0101",
            "010-1234-5678",
            "Platform Team",
        ]
        self.write_workbook(path, values=values, formula_cell="D2")

        with self.assertRaisesRegex(DirectoryImportError, "formula cell"):
            import_xlsx(path)

    def write_workbook(
        self,
        path: Path,
        *,
        values: list[str],
        formula_cell: str | None = None,
    ) -> None:
        shared_items = "".join(f"<si><t>{value}</t></si>" for value in values)
        header_cells = "".join(
            f'<c r="{column}1" t="s"><v>{index}</v></c>'
            for index, column in enumerate("ABCDEF")
        )
        data_cells = []
        for index, column in enumerate("ABCDEF", start=6):
            reference = f"{column}2"
            formula = "<f>1+1</f>" if reference == formula_cell else ""
            data_cells.append(
                f'<c r="{reference}" t="s">{formula}<v>{index}</v></c>'
            )
        with ZipFile(path, "w") as archive:
            archive.writestr(
                "xl/workbook.xml",
                '<?xml version="1.0"?>'
                '<workbook xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main" '
                'xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships">'
                '<sheets><sheet name="Employees" sheetId="1" r:id="rId1"/></sheets>'
                '</workbook>',
            )
            archive.writestr(
                "xl/_rels/workbook.xml.rels",
                '<?xml version="1.0"?>'
                '<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">'
                '<Relationship Id="rId1" '
                'Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet" '
                'Target="worksheets/sheet1.xml"/>'
                '</Relationships>',
            )
            archive.writestr(
                "xl/sharedStrings.xml",
                '<?xml version="1.0"?>'
                '<sst xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main">'
                f"{shared_items}</sst>",
            )
            archive.writestr(
                "xl/worksheets/sheet1.xml",
                '<?xml version="1.0"?>'
                '<worksheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main">'
                '<sheetData>'
                f'<row r="1">{header_cells}</row>'
                f'<row r="2">{"".join(data_cells)}</row>'
                '</sheetData></worksheet>',
            )


if __name__ == "__main__":
    unittest.main()
