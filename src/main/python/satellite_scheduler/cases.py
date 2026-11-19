"""读取并校验案例内容；本模块不执行案例或访问后台来源。"""

from __future__ import annotations

import hashlib
import json
import re
import zipfile
from dataclasses import dataclass
from pathlib import Path, PurePosixPath
from typing import Sequence


@dataclass(frozen=True, slots=True)
class CaseData:
    case_id: str
    category: str
    description: str
    code: str
    case_sha256: str
    sub_library: str | None = None


def _object_pairs(pairs: list[tuple[str, object]]) -> dict[str, object]:
    result: dict[str, object] = {}
    for key, value in pairs:
        if key in result:
            raise ValueError(f"Duplicate JSON key: {key}")
        result[key] = value
    return result


def _invalid_constant(value: str) -> None:
    raise ValueError(f"Invalid JSON constant: {value}")


def _text(value: object, name: str, *, nonblank: bool = False) -> str:
    if not isinstance(value, str) or (nonblank and not value.strip()):
        raise ValueError(f"{name} must be a {'nonblank ' if nonblank else ''}string")
    return value


def matches_b_stage(case_id: str, sub_library: str | None) -> bool:
    prefix = "BI" if sub_library == "initial" else "BR" if sub_library == "post_reassignment" else None
    return bool(prefix and re.fullmatch(prefix + r"-(?!0+$)[0-9]{3,}", case_id))


def _relative_source_path(value: str) -> PurePosixPath:
    if value.startswith("/") or "\\" in value or ":" in value or "\0" in value:
        raise ValueError(f"Source path must be relative and use /: {value}")
    relative = PurePosixPath(value)
    if relative.is_absolute() or ".." in relative.parts:
        raise ValueError(f"Source path must stay inside case directory: {value}")
    return relative


def _source_path(root: Path, value: str) -> Path:
    relative = _relative_source_path(value)
    source = root.joinpath(*relative.parts).resolve(strict=True)
    if not source.is_relative_to(root) or not source.is_file():
        raise ValueError(f"Source must be a file inside case directory: {value}")
    return source


def read_case(metadata_path: str | Path, case_directory: str | Path | None = None,
              *, historical_case_id: str | None = None, source_archive: str | Path | None = None) -> CaseData:
    """校验身份、源码清单、UTF-8 和哈希；可显式从归档读取历史源码。"""
    metadata_path = Path(metadata_path)
    root = Path(case_directory) if case_directory is not None else metadata_path.parent
    root = root.resolve(strict=True)
    if not root.is_dir():
        raise ValueError(f"Case directory is not a directory: {root}")
    if source_archive is not None and metadata_path.resolve(strict=True).is_relative_to(root):
        raise ValueError("Source archives are only available for historical metadata")
    if historical_case_id is not None:
        if not historical_case_id.strip():
            raise ValueError("historical_case_id must not be blank")
        if metadata_path.resolve(strict=True).is_relative_to(root):
            raise ValueError("Historical metadata must be outside the current case directory")
    metadata_bytes = metadata_path.read_bytes()
    metadata = json.loads(metadata_bytes.decode("utf-8"), object_pairs_hook=_object_pairs,
                          parse_constant=_invalid_constant)
    if not isinstance(metadata, dict):
        raise ValueError("case metadata must be an object")
    case_id = _text(metadata.get("case_id"), "case_id", nonblank=True)
    if case_id != (historical_case_id if historical_case_id is not None else root.name):
        raise ValueError(f"case_id does not match case directory: {case_id}")
    if source_archive is not None and (case_id in (".", "..") or any(ch in case_id for ch in "/\\:\0")):
        raise ValueError("Invalid case_id for source archive")
    sub_library = None
    if case_id.startswith(("BI-", "BR-")):
        sub_library = _text(metadata.get("sub_library"), "sub_library", nonblank=True)
        if not matches_b_stage(case_id, sub_library):
            raise ValueError(f"Invalid B case ID or sub_library: {case_id}, {sub_library}")
    elif case_id.startswith("B-") and "sub_library" in metadata:
        sub_library = _text(metadata["sub_library"], "sub_library", nonblank=True)
        if (not re.fullmatch(r"B-[0-9]{3,}", case_id)
                or sub_library not in ("initial", "post_reassignment")):
            raise ValueError(f"Invalid B sub_library: {sub_library}")
    category = _text(metadata.get("category"), "category", nonblank=True)
    parts = metadata.get("description")
    if not isinstance(parts, list):
        raise ValueError("description must be an array")
    description = "".join(_text(item, "description element") for item in parts)
    code = metadata.get("code")
    if not isinstance(code, dict):
        raise ValueError("code must be an object")
    file_format = _text(code.get("format"), "code.format")
    files = code.get("files")
    if not isinstance(files, list) or not files:
        raise ValueError("code.files must be a nonempty array")
    if file_format not in ("single_file", "file_markers"):
        raise ValueError(f"Unsupported code format: {file_format}")
    if file_format == "single_file" and len(files) != 1:
        raise ValueError("single_file requires exactly one source file")
    assembled: list[str] = []
    for entry in files:
        if not isinstance(entry, dict):
            raise ValueError("code.files element must be an object")
        path = _text(entry.get("path"), "code.files.path", nonblank=True)
        expected = _text(entry.get("sha256"), "code.files.sha256")
        if not re.fullmatch(r"[0-9a-fA-F]{64}", expected):
            raise ValueError(f"Invalid SHA-256 for source: {path}")
        if file_format == "file_markers" and (not path.startswith("src/") or len(path) == 4):
            raise ValueError(f"file_markers source must start with src/: {path}")
        if file_format == "single_file" and len(PurePosixPath(path).parts) != 1:
            raise ValueError(f"single_file source must be beside case metadata: {path}")
        if source_archive is None:
            source = _source_path(root, path)
            raw = source.read_bytes()
        else:
            relative = _relative_source_path(path)
            name = PurePosixPath(case_id, relative).as_posix()
            with zipfile.ZipFile(source_archive) as archive:
                entries = [item for item in archive.infolist() if item.filename == name]
                if len(entries) != 1 or entries[0].is_dir():
                    raise ValueError(f"Missing or ambiguous archived source: {name}")
                raw = archive.read(entries[0])
        if hashlib.sha256(raw).hexdigest() != expected.lower():
            raise ValueError(f"SHA-256 mismatch for source: {path}")
        text = raw.decode("utf-8")
        if file_format == "file_markers":
            text = f"// FILE: {path[4:]}\n" + text
        assembled.append(text)
    return CaseData(case_id, category, description, "\n".join(assembled),
                    hashlib.sha256(metadata_bytes).hexdigest(), sub_library)


def _validate_discoveries(root: Path, entries: Sequence[tuple[Path, CaseData]]) -> tuple[CaseData, ...]:
    by_id: dict[str, list[tuple[Path, CaseData]]] = {}
    for path, data in entries:
        by_id.setdefault(data.case_id, []).append((path, data))
    for case_id, occurrences in by_id.items():
        if len(occurrences) > 1:
            versions = {item.case_sha256 for _, item in occurrences}
            label = 'Conflicting versions of case ID' if len(versions) > 1 else 'Duplicate case ID'
            details = ', '.join(f'{path} ({item.case_sha256})' for path, item in occurrences)
            raise ValueError(f'{label}: {case_id}: {details}')
    stages = {'initial': 'initial_single_satellite_optimization',
              'post_reassignment': 'post_reassignment_adjustment'}
    for path, data in entries:
        if re.fullmatch(r'A-(?!0+$)[0-9]{3,}', data.case_id):
            directory = root / 'A' / data.case_id
        elif re.fullmatch(r'C-(?!0+$)[0-9]{3,}', data.case_id):
            directory = root / 'C' / data.case_id
        elif matches_b_stage(data.case_id, data.sub_library):
            directory = root / 'B' / stages[data.sub_library] / data.case_id
        else:
            raise ValueError(f'Invalid case ID or B stage: {data.case_id}, {data.sub_library}')
        expected = directory / f'{data.case_id}.json'
        if path != expected:
            raise ValueError(f'Case in wrong module or stage directory: {path}; expected {expected}')
    return tuple(data for _, data in entries)


def discover_cases(cases_root: str | Path) -> tuple[CaseData, ...]:
    """发现结构有效的当前案例；不声明不同编号具有不同算法语义。"""
    root = Path(cases_root).resolve(strict=True)
    if not root.is_dir():
        raise ValueError(f'Cases root is not a directory: {root}')
    paths = sorted(path for module in ('A', 'B', 'C')
                   for path in (root / module).rglob('*.json'))
    entries = [(path.resolve(strict=True), read_case(path)) for path in paths]
    return _validate_discoveries(root, entries)


def b_required_count(candidates: Sequence[CaseData]) -> int:
    phases: dict[str, str] = {}
    for candidate in candidates:
        if not matches_b_stage(candidate.case_id, candidate.sub_library):
            raise ValueError(f"Invalid B candidate: {candidate.case_id}")
        if candidate.case_id in phases:
            raise ValueError(f"Duplicate B candidate: {candidate.case_id}")
        phases[candidate.case_id] = candidate.sub_library
    missing = {"initial", "post_reassignment"} - set(phases.values())
    if missing:
        raise ValueError(f"B sub-library has no eligible candidates: {', '.join(sorted(missing))}")
    return max(2, (len(candidates) + 19) // 20)


def validate_b_selection(candidates: Sequence[CaseData], selected_ids: Sequence[str]) -> None:
    required = b_required_count(candidates)
    if len(selected_ids) != required:
        raise ValueError("B selection count does not match required count")
    available = {candidate.case_id: candidate.sub_library for candidate in candidates}
    if any(item not in available for item in selected_ids):
        raise ValueError("B selection contains an ID outside the candidate set")
    if len(set(selected_ids)) != len(selected_ids):
        raise ValueError("B selection contains a duplicate ID")
    if {available[item] for item in selected_ids} != {"initial", "post_reassignment"}:
        raise ValueError("B selection must cover both sub-libraries")
