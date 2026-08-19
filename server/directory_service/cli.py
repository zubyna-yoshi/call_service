from __future__ import annotations

import argparse
import sys
from pathlib import Path

from .http_api import SnapshotLoadError, build_server, token_from_environment
from .importer import (
    DirectoryImportError,
    import_directory_file,
    load_existing_snapshot,
    parse_column_assignments,
    write_snapshot_atomic,
)


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(prog="python -m directory_service")
    subcommands = parser.add_subparsers(dest="command", required=True)

    importer = subcommands.add_parser(
        "import-file",
        help="validate a UTF-8 CSV or XLSX workbook and write a snapshot",
    )
    _add_import_arguments(importer)

    legacy_importer = subcommands.add_parser(
        "import-csv",
        help="compatibility alias for import-file (CSV input only)",
    )
    _add_import_arguments(legacy_importer, csv_only=True)

    server = subcommands.add_parser("serve", help="serve the current snapshot")
    server.add_argument("--snapshot", type=Path, required=True)
    server.add_argument("--host", default="127.0.0.1")
    server.add_argument("--port", default=8080, type=_port)
    return parser


def main(arguments: list[str] | None = None) -> int:
    parser = build_parser()
    args = parser.parse_args(arguments)
    try:
        if args.command in {"import-file", "import-csv"}:
            columns = parse_column_assignments(args.column)
            previous = load_existing_snapshot(args.output)
            if args.command == "import-csv" and args.input.suffix.casefold() != ".csv":
                raise DirectoryImportError("import-csv requires a .csv input; use import-file")
            result = import_directory_file(
                args.input,
                sheet_name=getattr(args, "sheet", None),
                country_calling_code=args.country_code,
                explicit_columns=columns,
                previous_snapshot=previous,
                allow_empty=args.allow_empty,
                assume_active=args.assume_active,
                include_mobile_without_visibility=args.include_mobile,
            )
            if not result.unchanged:
                write_snapshot_atomic(args.output, result.snapshot)
            state = "unchanged" if result.unchanged else "updated"
            print(
                f"snapshot {state}: {result.active_employee_count} active employees, "
                f"{result.entry_count} phone entries, version {result.snapshot['version']}"
            )
            return 0

        token = token_from_environment()
        http_server = build_server(
            host=args.host,
            port=args.port,
            snapshot_path=args.snapshot,
            api_token=token,
        )
        host, port = http_server.server_address[:2]
        print(f"directory API listening on http://{host}:{port}", flush=True)
        try:
            http_server.serve_forever()
        except KeyboardInterrupt:
            pass
        finally:
            http_server.server_close()
        return 0
    except (DirectoryImportError, SnapshotLoadError, OSError, ValueError) as error:
        print(f"error: {error}", file=sys.stderr)
        return 2


def _port(value: str) -> int:
    port = int(value)
    if not 0 <= port <= 65535:
        raise argparse.ArgumentTypeError("port must be between 0 and 65535")
    return port


def _add_import_arguments(parser: argparse.ArgumentParser, *, csv_only: bool = False) -> None:
    parser.add_argument(
        "--input",
        type=Path,
        required=True,
        help="UTF-8 CSV path" if csv_only else "UTF-8 CSV or XLSX path",
    )
    parser.add_argument("--output", type=Path, required=True, help="snapshot JSON path")
    parser.add_argument(
        "--country-code",
        default="82",
        help="default calling code for domestic numbers (default: 82)",
    )
    parser.add_argument(
        "--column",
        action="append",
        default=[],
        metavar="FIELD=HEADER",
        help="map a canonical field to an exact source header; repeat as needed",
    )
    parser.add_argument(
        "--allow-empty",
        action="store_true",
        help="publish zero entries intentionally (for revocation/beta shutdown)",
    )
    parser.add_argument(
        "--assume-active",
        action="store_true",
        help="treat every row as active only when the export has no active column",
    )
    parser.add_argument(
        "--include-mobile",
        action="store_true",
        help=(
            "include mobile numbers only when no per-row mobile_visible column exists; "
            "use only for a consented beta"
        ),
    )
    if not csv_only:
        parser.add_argument(
            "--sheet",
            help="exact XLSX worksheet name (default: first worksheet)",
        )
