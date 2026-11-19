"""A/B/C 代码生成入口。模型代码作为文本保存，在这里不加载或执行。"""

from __future__ import annotations

import argparse
import ast
import inspect
import re
import sys
from pathlib import Path

from .cases import matches_b_stage
from .model_call import ROOT, call_codex, digest, json_bytes, parse_json, verify_call, write_new
from .runtime import manual


_SIGNATURES = {
    'A': {
        'initial_assign': 'self, problem, api, budget, rng',
        'search_reassignment': 'self, problem, api, state, feedback, evaluator, budget, rng',
    },
    'B': {
        'search_initial': 'self, problem, api, view, evaluator, budget, rng',
        'search_initial_local': 'self, problem, api, view, context, feedback, evaluator, budget, rng',
        'search_reassignment': 'self, problem, api, view, context, feedback, evaluator, budget, rng',
        'search_calibration': 'self, problem, api, view, context, feedback, evaluator, budget, rng',
    },
    'C': {
        'build': 'self, problem, api, view, priorities, budget, rng',
        'apply_local': 'self, problem, api, view, context, plan, budget, rng',
        'rebuild': 'self, problem, api, view, context, priorities, budget, rng',
    },
}

_PROMPT_FILES = {
    'A': 'allocation.md',
    'B': 'satellite_search.md',
    'C': 'decoder.md',
}

_SCHEMA = {
    'type': 'object',
    'properties': {'description': {'type': 'string'}, 'code': {'type': 'string'}},
    'required': ['description', 'code'],
    'additionalProperties': False,
}

_PARENT_IMPORTS = (
    'from __future__ import annotations\n'
    'import math\nfrom collections.abc import Iterator\n'
    'from satellite_scheduler.model import ProblemInstance, ScheduledTask\n'
    'from satellite_scheduler.problem import evaluate, transformation_time, transition_feasible\n'
    'from satellite_scheduler.runtime.access import ProblemAccess\n'
    'from satellite_scheduler.runtime.contracts import (Assignment, Budget, Feedback, LocalContext, '
    'LocalPlan, LocalResult, Move, SatelliteView, State)\n\n'
)


def preset_parent(name: str) -> dict:
    """保留 P1 手工组合的实际策略源码和构造参数。"""
    if not re.fullmatch(r'A[12]B[12]C[12]', name):
        raise ValueError('parent must be one of the eight P1 manual combinations')
    combo = manual.combination(name)
    modules = {}
    for role, strategy, flag in (('A', combo.a, 'scarcity'), ('B', combo.b, 'scarcity'),
                                 ('C', combo.c, 'minimize_transition')):
        cls = type(strategy)
        source = _PARENT_IMPORTS + inspect.getsource(cls)
        if role == 'C':
            source += '\n\n' + inspect.getsource(manual._same_observations)
        modules[role] = {
            'description': f'P1 {name} 的 {role} 模块：{cls.__name__}({flag}={getattr(strategy, flag)})。',
            'code': source,
        }
    return {'preset': name, 'modules': modules}


def build_prompt(role: str, parent: dict, references: list[dict]) -> str:
    path = Path(__file__).with_name('prompts') / _PROMPT_FILES[role]
    template = path.read_text(encoding='utf-8')
    if (template.count('{{SIGNATURES}}') != 1 or template.count('{{INPUT}}') != 1
            or not template.rstrip().endswith('{{INPUT}}')):
        raise ValueError(f'invalid insertion points in prompt: {path.name}')
    signatures = '\n'.join(f'def {method}({args})' for method, args in _SIGNATURES[role].items())
    data = {'parent': parent, 'references': [
        {key: case.get(key) for key in ('case_id', 'category', 'sub_library', 'description', 'code', 'adaptation')}
        for case in references
    ]}
    return template.replace('{{SIGNATURES}}', signatures).replace(
        '{{INPUT}}', json_bytes(data).decode('utf-8').rstrip('\n'))


def validate_response(role: str, raw: bytes) -> dict[str, str]:
    result = parse_json(raw)
    if not isinstance(result, dict) or set(result) != {'description', 'code'} or any(
            not isinstance(value, str) or not value.strip() for value in result.values()):
        raise ValueError('response must contain only nonblank description and code')
    initialization, separator, reassignment = result['description'].strip().replace('\r\n', '\n').partition(
        '\n\nReassignment:')
    if (not separator or not initialization.startswith('Initialization:')
            or not initialization.removeprefix('Initialization:').strip()
            or not any(line.startswith('- ') and line[2:].strip() for line in reassignment.splitlines())):
        raise ValueError('description must have Initialization and Reassignment sections')
    tree = ast.parse(result['code'])
    compile(tree, f'<{role} model source>', 'exec', dont_inherit=True)  # 编译检查，不运行。
    classes = [node for node in tree.body if isinstance(node, ast.ClassDef) and node.name == 'Strategy']
    if (len(classes) != 1 or classes[0].bases or classes[0].keywords
            or classes[0].decorator_list or getattr(classes[0], 'type_params', ())):
        raise ValueError('code must define one undecorated Strategy class without a base')
    definitions = {'__init__': 'self', **_SIGNATURES[role]}
    for name, signature in definitions.items():
        methods = [node for node in classes[0].body if isinstance(node, (ast.FunctionDef, ast.AsyncFunctionDef))
                   and node.name == name]
        if name == '__init__' and not methods:
            continue
        if len(methods) != 1 or not isinstance(methods[0], ast.FunctionDef) or methods[0].decorator_list:
            raise ValueError(f'{role} requires a regular Strategy.{name} method')
        args = methods[0].args
        if (', '.join(arg.arg for arg in [*args.posonlyargs, *args.args]) != signature
                or args.defaults or args.vararg or args.kwarg or args.kwonlyargs):
            raise ValueError(f'{role} Strategy.{name} has a different signature')
    return result


def _directory(path: Path, *, create: bool = False) -> Path:
    if path.is_symlink() or getattr(path, 'is_junction', lambda: False)():
        raise ValueError(f'linked run directory: {path}')
    if create:
        path.mkdir(exist_ok=True)
    resolved = path.resolve(strict=True)
    if not resolved.is_dir() or resolved.parent != path.parent.resolve():
        raise ValueError(f'directory is outside its expected parent: {path}')
    return resolved


def _read(path: Path) -> bytes:
    if path.is_symlink() or not path.is_file():
        raise ValueError(f'expected a regular file: {path}')
    return path.read_bytes()


def _freeze(path: Path, raw: bytes) -> None:
    try:
        write_new(path, raw)
    except FileExistsError:
        if _read(path) != raw:
            raise ValueError(f'frozen input or candidate changed: {path.name}') from None


def _load_cases(run: Path) -> tuple[dict, str]:
    raw = _read(run / 'case-package.json')
    package_hash = digest(raw)
    metadata = parse_json(_read(run / 'run.json'))
    if (not isinstance(metadata, dict) or metadata.get('run_id') != run.name
            or metadata.get('state') != 'frozen' or metadata.get('package_sha256') != package_hash):
        raise ValueError('case package differs from its frozen run record')
    package = parse_json(raw)
    if not isinstance(package, dict) or package.get('run_id') != run.name:
        raise ValueError('case package run ID differs')
    cases = package.get('cases')
    if not isinstance(cases, dict) or set(cases) != set(_SIGNATURES):
        raise ValueError('case package must have A, B and C')
    ids = {}
    for role, entries in cases.items():
        if not isinstance(entries, list) or not entries:
            raise ValueError(f'no frozen references for {role}')
        ids[role] = []
        for entry in entries:
            if (not isinstance(entry, dict)
                    or any(not isinstance(entry.get(key), str) or not entry[key].strip()
                           for key in ('case_id', 'category', 'description', 'code', 'adaptation', 'case_sha256'))):
                raise ValueError(f'invalid frozen {role} reference')
            case_id = entry['case_id']
            phase = entry.get('sub_library')
            belongs = (matches_b_stage(case_id, phase) if role == 'B'
                       else phase is None and re.fullmatch(rf'{role}-[0-9]+', case_id) is not None)
            if not belongs:
                raise ValueError(f'incorrect module or stage for {case_id}')
            ids[role].append(case_id)
        if len(set(ids[role])) != len(ids[role]):
            raise ValueError(f'duplicate frozen {role} reference')
    if (ids != metadata.get('selected_ids')
            or {case['sub_library'] for case in cases['B']} != {'initial', 'post_reassignment'}):
        raise ValueError('frozen references differ from the run record or omit a B stage')
    return cases, package_hash


def generate(run_id: str, role: str, case_run_id: str, parent_name: str, model: str) -> dict:
    if role not in _SIGNATURES:
        raise ValueError('role must be A, B or C')
    for name in (run_id, case_run_id):
        if not re.fullmatch(r'[A-Za-z0-9][A-Za-z0-9_-]{0,79}', name):
            raise ValueError('invalid run ID')
    if not re.fullmatch(r'[A-Za-z0-9][A-Za-z0-9._-]{0,79}', model):
        raise ValueError('invalid model ID')
    outputs = _directory(ROOT / 'outputs')
    cases, package_hash = _load_cases(_directory(outputs / case_run_id))
    parent = preset_parent(parent_name)
    parent_raw = json_bytes(parent)
    base_hash = digest(parent_raw)
    run = _directory(outputs / run_id, create=True)
    stage = _directory(run / 'generation', create=True)
    _freeze(stage / 'prepared.json', json_bytes({
        'run_id': run_id, 'case_run_id': case_run_id, 'package_sha256': package_hash,
        'base_algorithm_hash': base_hash, 'model': model,
    }))
    _freeze(stage / 'parent.json', parent_raw)
    schema = stage / 'response-schema.json'
    _freeze(schema, json_bytes(_SCHEMA))
    role_dir = _directory(stage / role, create=True)
    request_path = role_dir / 'request.txt'
    response_path = role_dir / 'response.json'
    candidate_path = role_dir / 'candidate.json'
    has_response = response_path.exists() or response_path.is_symlink()
    if has_response and (candidate_path.exists() or candidate_path.is_symlink()):
        # 恢复已保存的候选时，后续提示词修改不改变实际发送的请求。
        prompt = _read(request_path)
    else:
        prompt = build_prompt(role, parent, cases[role]).encode('utf-8')
        _freeze(request_path, prompt)
    if not has_response:
        started = role_dir / 'call-started.json'
        if started.exists() or started.is_symlink():
            raise RuntimeError('prior call has no saved response; inspect transport and usage before recovery')
        call_codex(role_dir, model, schema=schema)
    raw = _read(response_path)
    verify_call(role_dir, raw)
    result = validate_response(role, raw)
    record = {
        'run_id': run_id, 'target_module': role, 'base_algorithm_hash': base_hash,
        'request_sha256': digest(prompt), 'response_sha256': digest(raw),
        'code_sha256': digest(result['code'].encode('utf-8')), **result,
    }
    _freeze(candidate_path, json_bytes(record))
    return {'role': role, 'base_algorithm_hash': base_hash, 'candidate': str(candidate_path)}


def main() -> int:
    parser = argparse.ArgumentParser(description='真实调用一个 A/B/C 代码生成角色')
    parser.add_argument('run_id')
    parser.add_argument('role', choices=tuple(_SIGNATURES))
    parser.add_argument('--case-run-id', required=True)
    parser.add_argument('--parent', required=True)
    parser.add_argument('--model', required=True)
    args = parser.parse_args()
    try:
        result = generate(args.run_id, args.role, args.case_run_id, args.parent, args.model)
    except (OSError, ValueError, RuntimeError, SyntaxError) as error:
        print(f'{type(error).__name__}: {error}', file=sys.stderr)
        return 1
    print(json_bytes(result).decode('utf-8'), end='')
    return 0


if __name__ == '__main__':
    raise SystemExit(main())
