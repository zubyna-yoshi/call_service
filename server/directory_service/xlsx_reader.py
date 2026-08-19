from __future__ import annotations

import posixpath
import re
from pathlib import Path
from zipfile import BadZipFile, ZipFile
import xml.etree.ElementTree as ET


class XLSXReadError(ValueError):
    """Raised when a workbook is unsafe, unsupported, or structurally invalid."""


_MAIN_NS = "http://schemas.openxmlformats.org/spreadsheetml/2006/main"
_REL_NS = "http://schemas.openxmlformats.org/officeDocument/2006/relationships"
_PACKAGE_REL_NS = "http://schemas.openxmlformats.org/package/2006/relationships"
_NS = {"m": _MAIN_NS, "r": _REL_NS, "p": _PACKAGE_REL_NS}
_CELL_REFERENCE = re.compile(r"^([A-Z]+)([1-9][0-9]*)$")

_MAX_ARCHIVE_BYTES = 25 * 1024 * 1024
_MAX_TOTAL_UNCOMPRESSED_BYTES = 100 * 1024 * 1024
_MAX_PART_BYTES = 40 * 1024 * 1024
_MAX_PARTS = 2_000
_MAX_SHARED_STRINGS = 200_000
_MAX_SHARED_CHARACTERS = 20 * 1024 * 1024
_MAX_COLUMNS = 100
_MAX_DATA_ROWS = 100_000


def read_worksheet(
    path: Path,
    *,
    sheet_name: str | None = None,
) -> tuple[list[str], list[dict[str, str]]]:
    """Read plain cell values from one XLSX worksheet without executing formulas."""

    path = Path(path)
    try:
        if path.stat().st_size > _MAX_ARCHIVE_BYTES:
            raise XLSXReadError("XLSX file exceeds the 25 MiB beta limit")
        archive = ZipFile(path)
    except (OSError, BadZipFile) as error:
        raise XLSXReadError(f"cannot open XLSX workbook: {error}") from error

    with archive:
        _validate_archive(archive)
        workbook = _read_xml(archive, "xl/workbook.xml")
        relationships = _read_xml(archive, "xl/_rels/workbook.xml.rels")
        relationship_targets = {
            relationship.attrib.get("Id"): relationship.attrib.get("Target")
            for relationship in relationships.findall("p:Relationship", _NS)
            if relationship.attrib.get("TargetMode") != "External"
        }

        sheets = workbook.findall("m:sheets/m:sheet", _NS)
        if not sheets:
            raise XLSXReadError("XLSX workbook has no worksheet")
        if sheet_name is None:
            selected = sheets[0]
        else:
            selected = next(
                (sheet for sheet in sheets if sheet.attrib.get("name") == sheet_name),
                None,
            )
            if selected is None:
                raise XLSXReadError(f"worksheet {sheet_name!r} does not exist")

        relationship_id = selected.attrib.get(f"{{{_REL_NS}}}id")
        target = relationship_targets.get(relationship_id)
        if not target:
            raise XLSXReadError("worksheet relationship is missing")
        worksheet_part = _resolve_workbook_target(target)
        worksheet = _read_xml(archive, worksheet_part)
        shared_strings = _read_shared_strings(archive)
        return _rows_from_worksheet(worksheet, shared_strings)


def _validate_archive(archive: ZipFile) -> None:
    parts = archive.infolist()
    if len(parts) > _MAX_PARTS:
        raise XLSXReadError("XLSX workbook contains too many archive parts")
    total_size = 0
    for part in parts:
        if part.flag_bits & 0x1:
            raise XLSXReadError("encrypted XLSX workbooks are not supported")
        if part.file_size > _MAX_PART_BYTES:
            raise XLSXReadError(f"XLSX part {part.filename!r} is too large")
        total_size += part.file_size
        if total_size > _MAX_TOTAL_UNCOMPRESSED_BYTES:
            raise XLSXReadError("XLSX uncompressed size exceeds the beta limit")


def _read_xml(archive: ZipFile, name: str) -> ET.Element:
    try:
        info = archive.getinfo(name)
        if info.file_size > _MAX_PART_BYTES:
            raise XLSXReadError(f"XLSX part {name!r} is too large")
        return ET.fromstring(archive.read(info))
    except KeyError as error:
        raise XLSXReadError(f"required XLSX part {name!r} is missing") from error
    except ET.ParseError as error:
        raise XLSXReadError(f"XLSX part {name!r} contains invalid XML") from error


def _resolve_workbook_target(target: str) -> str:
    if target.startswith("/"):
        resolved = posixpath.normpath(target.lstrip("/"))
    else:
        resolved = posixpath.normpath(posixpath.join("xl", target))
    if not resolved.startswith("xl/worksheets/") or not resolved.endswith(".xml"):
        raise XLSXReadError("worksheet target is outside xl/worksheets")
    return resolved


def _read_shared_strings(archive: ZipFile) -> list[str]:
    try:
        archive.getinfo("xl/sharedStrings.xml")
    except KeyError:
        return []
    root = _read_xml(archive, "xl/sharedStrings.xml")
    values: list[str] = []
    character_count = 0
    for item in root.findall("m:si", _NS):
        value = "".join(node.text or "" for node in item.iterfind(".//m:t", _NS))
        character_count += len(value)
        if character_count > _MAX_SHARED_CHARACTERS:
            raise XLSXReadError("XLSX shared strings exceed the beta limit")
        values.append(value)
        if len(values) > _MAX_SHARED_STRINGS:
            raise XLSXReadError("XLSX contains too many shared strings")
    return values


def _rows_from_worksheet(
    worksheet: ET.Element,
    shared_strings: list[str],
) -> tuple[list[str], list[dict[str, str]]]:
    xml_rows = worksheet.findall("m:sheetData/m:row", _NS)
    populated_rows: list[tuple[int, dict[int, str]]] = []
    for fallback_row_number, xml_row in enumerate(xml_rows, start=1):
        try:
            row_number = int(xml_row.attrib.get("r", fallback_row_number))
        except ValueError as error:
            raise XLSXReadError("worksheet contains an invalid row number") from error
        cells: dict[int, str] = {}
        fallback_column = 0
        for cell in xml_row.findall("m:c", _NS):
            reference = cell.attrib.get("r")
            if reference:
                match = _CELL_REFERENCE.fullmatch(reference)
                if not match:
                    raise XLSXReadError(f"cell reference {reference!r} is invalid")
                column_index = _column_index(match.group(1))
            else:
                column_index = fallback_column
            fallback_column = column_index + 1
            if column_index >= _MAX_COLUMNS:
                raise XLSXReadError("worksheet exceeds the 100-column beta limit")
            cells[column_index] = _cell_text(cell, shared_strings, reference or "cell")
        if any(value.strip() for value in cells.values()):
            populated_rows.append((row_number, cells))

    if not populated_rows:
        raise XLSXReadError("worksheet is empty")
    _, header_cells = populated_rows[0]
    if not header_cells:
        raise XLSXReadError("worksheet header row is empty")
    highest_header_column = max(header_cells)
    headers = [header_cells.get(index, "").strip() for index in range(highest_header_column + 1)]
    if any(not header for header in headers):
        raise XLSXReadError("worksheet contains an empty header cell")

    rows: list[dict[str, str]] = []
    for row_number, cells in populated_rows[1:]:
        populated_outside_header = [
            index for index, value in cells.items()
            if index > highest_header_column and value.strip()
        ]
        if populated_outside_header:
            raise XLSXReadError(f"worksheet row {row_number} has data beyond the header")
        rows.append(
            {
                header: cells.get(index, "")
                for index, header in enumerate(headers)
            }
        )
        if len(rows) > _MAX_DATA_ROWS:
            raise XLSXReadError("worksheet exceeds the 100000-row beta limit")
    return headers, rows


def _cell_text(cell: ET.Element, shared_strings: list[str], reference: str) -> str:
    if cell.find("m:f", _NS) is not None:
        raise XLSXReadError(f"formula cell {reference} is not supported; paste values first")
    cell_type = cell.attrib.get("t", "n")
    if cell_type == "inlineStr":
        return "".join(node.text or "" for node in cell.iterfind(".//m:t", _NS))
    value = cell.findtext("m:v", default="", namespaces=_NS)
    if cell_type == "s":
        try:
            return shared_strings[int(value)]
        except (ValueError, IndexError) as error:
            raise XLSXReadError(f"shared string index in {reference} is invalid") from error
    if cell_type in {"str", "d", "n"}:
        return value
    if cell_type == "b":
        return "true" if value == "1" else "false"
    if cell_type == "e":
        raise XLSXReadError(f"error cell {reference} is not supported")
    raise XLSXReadError(f"cell type {cell_type!r} in {reference} is not supported")


def _column_index(column_letters: str) -> int:
    result = 0
    for character in column_letters:
        result = result * 26 + (ord(character) - ord("A") + 1)
    return result - 1
