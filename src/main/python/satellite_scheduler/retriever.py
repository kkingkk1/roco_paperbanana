"""用真实 Codex CLI 检索人工复核的案例，并冻结当次内容。"""

from __future__ import annotations

import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import sys
from datetime import datetime

from .cases import CaseData, b_required_count, discover_cases, validate_b_selection


ROOT = Path(__file__).resolve().parents[4]
OUTPUTS = ROOT / 'outputs'


def _digest(raw: bytes) -> str:
    return hashlib.sha256(raw).hexdigest()


def _json_bytes(value) -> bytes:
    return (json.dumps(value, ensure_ascii=False, indent=2) + '\n').encode('utf-8')


def _write_new(path: Path, raw: bytes) -> None:
    with path.open('xb') as file:
        file.write(raw)


def _pairs(pairs):
    result = {}
    for key, value in pairs:
        if key in result:
            raise ValueError(f'duplicate JSON field: {key}')
        result[key] = value
    return result


def _invalid_constant(value):
    raise ValueError(f'invalid JSON constant: {value}')


def _parse(raw: bytes):
    return json.loads(raw.decode('utf-8'), object_pairs_hook=_pairs, parse_constant=_invalid_constant)


def _module(case_id: str) -> str:
    if case_id.startswith('A-'):
        return 'A'
    if case_id.startswith(('BI-', 'BR-')):
        return 'B'
    if case_id.startswith('C-'):
        return 'C'
    raise ValueError(f'unknown case module: {case_id}')


def _counts(cases: list[CaseData]) -> dict[str, int]:
    grouped = {module: [item for item in cases if _module(item.case_id) == module]
               for module in ('A', 'B', 'C')}
    if not grouped['A'] or not grouped['C']:
        raise ValueError('A and C must each have a reviewed candidate')
    return {'A': (len(grouped['A']) + 19) // 20,
            'B': b_required_count(grouped['B']),
            'C': (len(grouped['C']) + 19) // 20}


def _schema():
    item = {'type': 'object', 'properties': {'case_id': {'type': 'string'},
                                               'adaptation': {'type': 'string'}},
            'required': ['case_id', 'adaptation'], 'additionalProperties': False}
    return {'type': 'object',
            'properties': {module: {'type': 'array', 'items': item} for module in ('A', 'B', 'C')},
            'required': ['A', 'B', 'C'], 'additionalProperties': False}


def _prompt(cases: list[CaseData], counts: dict[str, int]) -> str:
    by_module = {module: [] for module in ('A', 'B', 'C')}
    for item in cases:
        entry = {'case_id': item.case_id, 'category': item.category, 'description': item.description}
        if item.sub_library:
            entry['sub_library'] = item.sub_library
        by_module[_module(item.case_id)].append(entry)
    return (
        '你是卫星成像调度项目的 Retriever。依据下列真实案例，为各模块选择不同的算法思路，'
        '每条给出针对本项目的简短适配说明。\n'
        '问题：任务可分配到候选卫星或暂未完成；观测从合法离散姿态候选中选择，满足窗口、'
        '成像时长和姿态转换，目标为已完成任务的原始收益之和。'
        'A负责初始化归属和基于已有调度的重分配；初始化不通过C试探跨星迁移。'
        'B负责初始单星搜索和重分配后的局部调整，可以受控试评C。'
        'C负责观测选择与授权局部编辑，不得改变未释放观测。'
        '案例的匹配容量、固定任务配额、候选扫描范围和采样假设不改变本项目的可行域；'
        '适配时须处理无法分配或未完成的任务，并保留原始离散姿态记录。\n'
        f'从提供的候选中选A {counts["A"]}条、B {counts["B"]}条、C {counts["C"]}条；'
        'B须同时覆盖initial和post_reassignment，ID不得重复。'
        '案例只实现一个阶段时，不得声称它已实现另一阶段；如需补充，请明确表述为待实现的适配。'
        '只返回指定JSON，每项包含case_id和非空adaptation。不要调用工具或访问文件。\n'
        '候选：\n' + json.dumps(by_module, ensure_ascii=False, separators=(',', ':')) + '\n'
    )


def _load_sources(cases_root: Path, cases: list[CaseData]):
    result = {}
    for item in cases:
        path = cases_root / 'backend' / 'sources' / f'{item.case_id}.json'
        raw = path.read_bytes()
        record = _parse(raw)
        if not isinstance(record, dict) or record.get('case_id') != item.case_id:
            raise ValueError(f'bad source record: {item.case_id}')
        versions = record.get('versions')
        if not isinstance(versions, list):
            raise ValueError(f'missing source versions: {item.case_id}')
        matching = [version for version in versions if isinstance(version, dict)
                    and version.get('case_sha256') == item.case_sha256]
        if len(matching) != 1 or not isinstance(matching[0].get('source'), list) or not matching[0]['source']:
            raise ValueError(f'current case source missing or ambiguous: {item.case_id}')
        result[item.case_id] = {'source_file_sha256': _digest(raw), 'version': matching[0]}
    return result


def _validated_review(cases_root: Path, cases: list[CaseData]) -> bytes:
    """只接受同组已比较的案例版本；哈希仅用于绑定复核对象。"""
    raw = (cases_root / 'backend' / 'semantic-review.json').read_bytes()
    record = _parse(raw)
    if (not isinstance(record, dict) or record.get('schema') != 'case-semantic-cohort-v1'
            or not isinstance(record.get('basis'), str) or not record['basis'].strip()
            or not isinstance(record.get('evidence'), str) or not record['evidence'].strip()
            or not isinstance(record.get('reviewed'), list)):
        raise ValueError('invalid semantic review record')
    reviewed = {}
    for item in record['reviewed']:
        if (not isinstance(item, dict) or set(item) != {'case_id', 'case_sha256', 'decision'}
                or not isinstance(item['case_id'], str) or item['case_id'] in reviewed
                or not isinstance(item['case_sha256'], str)
                or not re.fullmatch(r'[0-9a-f]{64}', item['case_sha256'])
                or not isinstance(item['decision'], str) or not item['decision'].strip()):
            raise ValueError('invalid semantic review member')
        reviewed[item['case_id']] = item['case_sha256']
    for item in cases:
        if item.case_id not in reviewed:
            raise ValueError(f'case has not passed semantic review: {item.case_id}')
        if reviewed[item.case_id] != item.case_sha256:
            raise ValueError(f'semantic review is for a different case version: {item.case_id}')
    return raw


def _from_entry(entry) -> CaseData:
    if not isinstance(entry, dict):
        raise ValueError('candidate entry must be an object')
    required = ('case_id', 'category', 'description', 'code', 'case_sha256')
    if any(not isinstance(entry.get(key), str) or not entry[key].strip() for key in required):
        raise ValueError('candidate entry is incomplete')
    phase = entry.get('sub_library')
    if phase is not None and not isinstance(phase, str):
        raise ValueError('invalid candidate sub_library')
    return CaseData(*(entry[key] for key in required), phase)


def _prepare(run: Path, reviewed_ids: list[str], selection_basis: str, model: str) -> None:
    if not reviewed_ids or len(set(reviewed_ids)) != len(reviewed_ids) or not selection_basis.strip():
        raise ValueError('provide distinct reviewed case IDs and a selection basis')
    discovered = discover_cases(ROOT / 'cases')
    index = {item.case_id: item for item in discovered}
    missing = sorted(set(reviewed_ids) - set(index))
    if missing:
        raise ValueError(f'unknown reviewed case IDs: {missing}')
    chosen = [index[case_id] for case_id in reviewed_ids]
    if any(not item.description.strip() or not item.code.strip() for item in chosen):
        raise ValueError('candidate description or code is empty')
    counts = _counts(chosen)
    review_raw = _validated_review(ROOT / 'cases', chosen)
    sources = _load_sources(ROOT / 'cases', chosen)
    candidates = [{'case_id': item.case_id, 'category': item.category, 'description': item.description,
                   'code': item.code, 'case_sha256': item.case_sha256, 'sub_library': item.sub_library}
                  for item in chosen]
    candidate_raw = _json_bytes(candidates)
    prompt = _prompt(chosen, counts).encode('utf-8')
    schema = _json_bytes(_schema())
    source_raw = _json_bytes(sources)
    _write_new(run / 'candidates.json', candidate_raw)
    _write_new(run / 'candidate-sources.json', source_raw)
    _write_new(run / 'semantic-review.json', review_raw)
    _write_new(run / 'request.txt', prompt)
    _write_new(run / 'response-schema.json', schema)
    metadata = {'run_id': run.name, 'created_at': datetime.now().astimezone().isoformat(),
                'cwd': str(ROOT), 'model': model, 'immutable_model_snapshot': None,
                'selection_basis': selection_basis, 'candidate_counts':
                    {module: sum(_module(item.case_id) == module for item in chosen)
                     for module in ('A', 'B', 'C')},
                'required_counts': counts, 'candidate_sha256': _digest(candidate_raw),
                'candidate_sources_sha256': _digest(source_raw),
                'semantic_review_sha256': _digest(review_raw),
                'request_sha256': _digest(prompt), 'schema_sha256': _digest(schema)}
    _write_new(run / 'prepared.json', _json_bytes(metadata))


def _load_prepared(run: Path):
    prepared = _parse((run / 'prepared.json').read_bytes())
    if not isinstance(prepared, dict) or prepared.get('run_id') != run.name:
        raise ValueError('invalid run metadata')
    for filename, key in (('candidates.json', 'candidate_sha256'),
                          ('candidate-sources.json', 'candidate_sources_sha256'),
                          ('request.txt', 'request_sha256'), ('response-schema.json', 'schema_sha256')):
        if _digest((run / filename).read_bytes()) != prepared.get(key):
            raise ValueError(f'prepared file changed: {filename}')
    if ('semantic_review_sha256' in prepared and
            _digest((run / 'semantic-review.json').read_bytes()) != prepared['semantic_review_sha256']):
        raise ValueError('prepared file changed: semantic-review.json')
    data = _parse((run / 'candidates.json').read_bytes())
    if not isinstance(data, list):
        raise ValueError('candidates must be an array')
    cases = [_from_entry(item) for item in data]
    if len({item.case_id for item in cases}) != len(cases) or _counts(cases) != prepared.get('required_counts'):
        raise ValueError('candidate IDs or counts changed')
    return prepared, cases


def _select(raw: bytes, cases: list[CaseData], counts: dict[str, int]):
    response = _parse(raw)
    if not isinstance(response, dict) or set(response) != {'A', 'B', 'C'}:
        raise ValueError('response must contain exactly A, B and C')
    index = {item.case_id: item for item in cases}
    selected = {}
    for module in ('A', 'B', 'C'):
        entries = response[module]
        if not isinstance(entries, list) or len(entries) != counts[module]:
            raise ValueError(f'incorrect {module} response count')
        chosen = []
        for entry in entries:
            if not isinstance(entry, dict) or set(entry) != {'case_id', 'adaptation'}:
                raise ValueError(f'invalid {module} response item')
            case_id, adaptation = entry['case_id'], entry['adaptation']
            if (not isinstance(case_id, str) or case_id not in index or _module(case_id) != module
                    or not isinstance(adaptation, str) or not adaptation.strip()):
                raise ValueError(f'invalid {module} case or adaptation')
            item = index[case_id]
            chosen.append({'case_id': item.case_id, 'case_sha256': item.case_sha256,
                           'category': item.category, 'description': item.description,
                           'code': item.code, 'sub_library': item.sub_library, 'adaptation': adaptation})
        if len({entry['case_id'] for entry in chosen}) != len(chosen):
            raise ValueError(f'duplicate {module} case ID')
        selected[module] = chosen
    validate_b_selection([item for item in cases if _module(item.case_id) == 'B'],
                         [item['case_id'] for item in selected['B']])
    return selected


def _call(run: Path, prepared: dict) -> None:
    executable = shutil.which('codex.cmd' if os.name == 'nt' else 'codex')
    if not executable:
        raise RuntimeError('existing Codex CLI was not found')
    command = [executable, 'exec', '--ephemeral', '--json', '--sandbox', 'read-only',
               '--model', prepared['model'], '-c', 'model_reasoning_effort="low"',
               '--disable', 'multi_agent', '--disable', 'apps',
               '--disable', 'shell_tool', '--disable', 'unified_exec', '--output-schema',
               str(run / 'response-schema.json'), '--output-last-message', str(run / 'response.json'),
               '--skip-git-repo-check', '-']
    _write_new(run / 'call-started.json', _json_bytes({'at': datetime.now().astimezone().isoformat(),
                                                        'command': command}))
    try:
        completed = subprocess.run(command, input=(run / 'request.txt').read_bytes(),
                                   stdout=subprocess.PIPE, stderr=subprocess.PIPE, cwd=ROOT, check=False)
    except OSError as error:
        _write_new(run / 'call-result.json', _json_bytes({'error': str(error), 'exit_code': None}))
        raise
    _write_new(run / 'codex-events.jsonl', completed.stdout)
    _write_new(run / 'codex-stderr.log', completed.stderr)
    usage = []
    for line in completed.stdout.splitlines():
        try:
            event = _parse(line)
        except (ValueError, UnicodeError):
            continue
        if isinstance(event, dict) and isinstance(event.get('usage'), dict):
            usage.append(event['usage'])
    _write_new(run / 'call-result.json', _json_bytes({'exit_code': completed.returncode, 'usage_events': usage,
                                                       'finished_at': datetime.now().astimezone().isoformat()}))
    if completed.returncode != 0:
        raise RuntimeError(f'Codex CLI failed with exit code {completed.returncode}; see codex-stderr.log')


def _verify_call(run: Path, response_raw: bytes) -> dict:
    result_path = run / 'call-result.json'
    events_path = run / 'codex-events.jsonl'
    if not result_path.is_file() or not events_path.is_file():
        raise RuntimeError('response lacks a completed call record; inspect before recovery')
    result = _parse(result_path.read_bytes())
    if not isinstance(result, dict) or result.get('exit_code') != 0:
        raise RuntimeError('prior call failed; preserve its response and inspect before recovery')
    events = [_parse(line) for line in events_path.read_bytes().splitlines() if line.strip()]
    if any(not isinstance(event, dict) for event in events):
        raise ValueError('invalid Codex event')
    if (sum(event.get('type') == 'turn.started' for event in events) != 1
            or sum(event.get('type') == 'turn.completed' for event in events) != 1):
        raise ValueError('expected one completed model turn')
    messages = [event['item']['text'] for event in events
                if event.get('type') == 'item.completed' and isinstance(event.get('item'), dict)
                and event['item'].get('type') == 'agent_message' and isinstance(event['item'].get('text'), str)]
    if not messages or _parse(messages[-1].encode('utf-8')) != _parse(response_raw):
        raise ValueError('saved response does not match the completed model message')
    usage = [event['usage'] for event in events if isinstance(event.get('usage'), dict)]
    if usage != result.get('usage_events'):
        raise ValueError('saved usage differs from the model events')
    return result


def execute(run_id: str, reviewed_ids: list[str], selection_basis: str, model: str) -> dict:
    if not re.fullmatch(r'[A-Za-z0-9][A-Za-z0-9_-]{0,79}', run_id):
        raise ValueError('invalid run ID')
    OUTPUTS.mkdir(exist_ok=True)
    outputs = OUTPUTS.resolve(strict=True)
    run = outputs / run_id
    run.mkdir(exist_ok=True)
    is_junction = getattr(os.path, 'isjunction', lambda _: False)
    if run.is_symlink() or is_junction(run) or run.resolve(strict=True).parent != outputs:
        raise ValueError('run directory must be a direct non-link child of outputs')
    if not (run / 'prepared.json').exists():
        if any(run.iterdir()):
            raise ValueError('incomplete prepared run; preserve files and inspect before recovery')
        _prepare(run, reviewed_ids, selection_basis, model)
    prepared, cases = _load_prepared(run)
    if reviewed_ids and (reviewed_ids != [item.case_id for item in cases]
                         or selection_basis != prepared.get('selection_basis')
                         or model != prepared.get('model')):
        raise ValueError('resume arguments differ from the prepared run')
    response_path = run / 'response.json'
    if not response_path.exists():
        if (run / 'call-started.json').exists():
            raise RuntimeError('prior call has no saved response; inspect transport and usage before recovery')
        _call(run, prepared)
    response_raw = response_path.read_bytes()
    call_result = _verify_call(run, response_raw)
    selected = _select(response_raw, cases, prepared['required_counts'])
    package_raw = _json_bytes({'run_id': run_id, 'cases': selected})
    package_path = run / 'case-package.json'
    if package_path.exists():
        if package_path.read_bytes() != package_raw:
            raise ValueError('frozen package differs from saved candidates or response')
    else:
        _write_new(package_path, package_raw)
    run_path = run / 'run.json'
    result = {'run_id': run_id, 'state': 'frozen', 'prepared_sha256':
              _digest((run / 'prepared.json').read_bytes()), 'response_sha256': _digest(response_raw),
              'package_sha256': _digest(package_raw), 'package_sha256_definition':
              'SHA-256 of exact UTF-8 bytes of case-package.json; run.json is excluded',
              'selected_ids': {module: [item['case_id'] for item in selected[module]]
                               for module in ('A', 'B', 'C')},
              'model': prepared['model'], 'immutable_model_snapshot': prepared['immutable_model_snapshot'],
              'call_result': call_result}
    if run_path.exists():
        if _parse(run_path.read_bytes()) != result:
            raise ValueError('frozen run metadata differs from saved content')
    else:
        _write_new(run_path, _json_bytes(result))
    return result


def main() -> int:
    parser = argparse.ArgumentParser(description='使用真实Codex CLI检索已复核案例并冻结')
    parser.add_argument('run_id')
    parser.add_argument('--reviewed-case', action='append', default=[])
    parser.add_argument('--selection-basis', default='')
    parser.add_argument('--model', default='gpt-6.1-terra')
    args = parser.parse_args()
    try:
        result = execute(args.run_id, args.reviewed_case, args.selection_basis, args.model)
    except Exception as error:
        print(f'{type(error).__name__}: {error}', file=sys.stderr)
        return 1
    print(json.dumps(result, ensure_ascii=False, indent=2))
    return 0


if __name__ == '__main__':
    raise SystemExit(main())
