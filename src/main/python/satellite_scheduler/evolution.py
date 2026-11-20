"""独立演化的初始化和同轮组合控制。策略源码在组装时保持为文本。"""

from __future__ import annotations

import argparse
import sys
from collections.abc import Mapping
from dataclasses import dataclass
from itertools import combinations, product
from pathlib import Path

from . import generation, retriever
from .model_call import digest, json_bytes, parse_json


_ROLES = ('A', 'B', 'C')
_PRESETS = tuple(f'A{a}B{b}C{c}' for a, b, c in product('12', repeat=3))


@dataclass(frozen=True, slots=True)
class ModuleVersion:
    role: str
    description: str
    code: str
    preset: str | None = None

    @property
    def version_hash(self) -> str:
        return digest(json_bytes({
            'role': self.role, 'code_sha256': digest(self.code.encode('utf-8')), 'preset': self.preset,
        }))


@dataclass(frozen=True, slots=True)
class AlgorithmVersion:
    modules: tuple[ModuleVersion, ...]

    def __post_init__(self) -> None:
        modules = tuple(self.modules)
        if tuple(module.role for module in modules) != _ROLES:
            raise ValueError('an algorithm must contain A, B and C in order')
        object.__setattr__(self, 'modules', modules)

    @property
    def algorithm_hash(self) -> str:
        return digest(json_bytes({module.role: module.version_hash for module in self.modules}))


@dataclass(frozen=True, slots=True)
class ModuleCandidate:
    """通过静态检查的模块及其生成时读取的父版本。"""

    base_algorithm_hash: str
    version: ModuleVersion


@dataclass(frozen=True, slots=True)
class RoundCombination:
    changed_modules: tuple[str, ...]
    module_versions: tuple[str | None, ...]
    algorithm: AlgorithmVersion | None
    failures: tuple[tuple[str, str], ...]

    def record(self) -> dict:
        return {
            'slot': ''.join('1' if role in self.changed_modules else '0' for role in _ROLES),
            'changed_modules': self.changed_modules,
            'module_versions': dict(zip(_ROLES, self.module_versions, strict=True)),
            'algorithm_hash': self.algorithm.algorithm_hash if self.algorithm is not None else None,
            'status': 'static_failure' if self.failures else 'assembled',
            'failures': dict(self.failures),
        }


@dataclass(frozen=True, slots=True)
class RoundAssembly:
    record_path: Path
    combinations: tuple[RoundCombination, ...]


def preset_version(name: str) -> AlgorithmVersion:
    parent = generation.preset_parent(name)
    return AlgorithmVersion(tuple(
        ModuleVersion(role, parent['modules'][role]['description'], parent['modules'][role]['code'],
                      name[2 * index:2 * index + 2])
        for index, role in enumerate(_ROLES)
    ))


def assemble_combinations(parent: AlgorithmVersion,
                          candidates: Mapping[str, ModuleCandidate | str]) -> tuple[RoundCombination, ...]:
    """从同一父版组装。字符串为该角色的静态拒绝原因。"""
    if set(candidates) != set(_ROLES):
        raise ValueError('a round requires a candidate or a static failure for each of A, B and C')
    parent_hash = parent.algorithm_hash
    approved, rejected = {}, {}
    for role in _ROLES:
        candidate = candidates[role]
        if isinstance(candidate, str):
            rejected[role] = candidate
        elif candidate.version.role != role:
            rejected[role] = f'candidate role differs from {role}'
        elif candidate.base_algorithm_hash != parent_hash:
            rejected[role] = 'candidate base_algorithm_hash differs from the frozen parent'
        else:
            approved[role] = candidate.version

    assembled = []
    for size in range(len(_ROLES) + 1):
        for changed in combinations(_ROLES, size):
            versions = tuple(approved.get(module.role) if module.role in changed else module
                             for module in parent.modules)
            failures = tuple((role, rejected[role]) for role in changed if role in rejected)
            complete = None if failures else AlgorithmVersion(tuple(module for module in versions if module))
            identities = tuple(module.version_hash if module is not None else None for module in versions)
            assembled.append(RoundCombination(changed, identities, complete, failures))
    return tuple(assembled)


def initialize(run_id: str, reviewed_ids: list[str], selection_basis: str, model: str) -> dict:
    """冻结一次 Retriever 结果及本次独立演化的八个 P1 起点。"""
    generation._validate_id(run_id)
    generation._validate_model(model)
    outputs = generation._directory(generation.ROOT / 'outputs', create=True)
    retrieval = retriever.execute(run_id, reviewed_ids, selection_basis, model)
    if retrieval['model'] != model:
        raise ValueError('model differs from the frozen Retriever run')
    run = generation._directory(outputs / run_id)
    generation._load_cases(run)
    stage = generation._directory(run / 'evolution', create=True)
    generation._freeze(stage / 'response-schema.json', json_bytes(generation._SCHEMA))
    record = {
        'run_id': run_id,
        'retriever_run_sha256': digest(generation._read(run / 'run.json')),
        'initial_population': {name: preset_version(name).algorithm_hash for name in _PRESETS},
    }
    generation._freeze(stage / 'initialization.json', json_bytes(record))
    return record


def _parent_record(parent: AlgorithmVersion) -> dict:
    return {
        'base_algorithm_hash': parent.algorithm_hash,
        'modules': {
            module.role: {
                'module_version': module.version_hash,
                'preset': module.preset,
                'description': module.description,
                'code': module.code,
            } for module in parent.modules
        },
    }


def _request_candidate(role_dir: Path, *, run_id: str, role: str, model: str, schema: Path,
                       parent: dict, references: list[dict], resume_only: bool) -> tuple[ModuleCandidate | str, Path]:
    failed_attempt = None
    base_hash = parent['base_algorithm_hash']
    for attempt in (role_dir, role_dir / 'repair'):
        attempt = generation._directory(attempt, create=not resume_only)
        prompt, raw = generation._request_response(
            attempt, model, schema, lambda: generation.build_prompt(role, parent, references, repair=failed_attempt),
            resume_only=resume_only,
        )
        try:
            result = generation.validate_response(role, raw, check_permissions=True)
        except (ValueError, SyntaxError) as error:
            failed_attempt = {'reason': f'{type(error).__name__}: {error}',
                              'response': raw.decode('utf-8', errors='replace')}
            continue
        try:
            generation._save_candidate(attempt / 'candidate.json', run_id=run_id, role=role,
                                       base_hash=base_hash, prompt=prompt, raw=raw, result=result)
        except ValueError as error:
            return str(error), attempt
        return ModuleCandidate(base_hash, ModuleVersion(role, result['description'], result['code'])), attempt
    return failed_attempt['reason'], attempt


def run_round(run_id: str, chain_id: str, round_number: int, parent: AlgorithmVersion | str) -> RoundAssembly:
    """冻结同轮共同父版，分别调用三个生成角色，再记录八个搭配。"""
    generation._validate_id(run_id)
    generation._validate_id(chain_id, label='chain')
    if type(round_number) is not int or round_number < 1:
        raise ValueError('round number must be a positive integer')
    outputs = generation._directory(generation.ROOT / 'outputs')
    run = generation._directory(outputs / run_id)
    stage = generation._directory(run / 'evolution')
    initialized = parse_json(generation._read(stage / 'initialization.json'))
    retrieval_raw = generation._read(run / 'run.json')
    if (not isinstance(initialized, dict) or initialized.get('run_id') != run_id
            or initialized.get('retriever_run_sha256') != digest(retrieval_raw)):
        raise ValueError('Retriever run differs from the frozen evolution initialization')
    cases, _ = generation._load_cases(run)
    model = parse_json(retrieval_raw).get('model')
    generation._validate_model(model)
    if isinstance(parent, str):
        preset = preset_version(parent)
        population = initialized.get('initial_population')
        if not isinstance(population, dict) or population.get(parent) != preset.algorithm_hash:
            raise ValueError('preset parent differs from the frozen initial population')
        parent = preset

    schema = stage / 'response-schema.json'
    if generation._read(schema) != json_bytes(generation._SCHEMA):
        raise ValueError('schema differs from the frozen evolution initialization')
    rounds = generation._directory(stage / 'rounds', create=True)
    chain = generation._directory(rounds / chain_id, create=True)
    current = generation._directory(chain / str(round_number), create=True)
    snapshot = _parent_record(parent)
    generation._freeze(current / 'parent.json', json_bytes(snapshot))
    record_path = current / 'combinations.json'
    resume_only = record_path.exists() or record_path.is_symlink()
    attempts = generation._directory(current / 'generation', create=True)
    candidates, sources = {}, {}
    for role in _ROLES:
        candidate, attempt = _request_candidate(
            attempts / role, run_id=run_id, role=role, model=model, schema=schema,
            parent=snapshot, references=cases[role], resume_only=resume_only,
        )
        candidates[role] = candidate
        sources[role] = attempt.relative_to(current).as_posix()

    assembled = assemble_combinations(parent, candidates)
    generation._freeze(record_path, json_bytes({
        'run_id': run_id, 'chain_id': chain_id, 'round': round_number,
        'base_algorithm_hash': snapshot['base_algorithm_hash'], 'generation': sources,
        'combinations': [combo.record() for combo in assembled],
    }))
    return RoundAssembly(record_path, assembled)


def main() -> int:
    parser = argparse.ArgumentParser(description='初始化独立演化，或组装同一轮的八种搭配')
    commands = parser.add_subparsers(dest='command', required=True)
    start = commands.add_parser('init')
    start.add_argument('run_id')
    start.add_argument('--reviewed-case', action='append', default=[])
    start.add_argument('--selection-basis', default='')
    start.add_argument('--model', required=True)
    step = commands.add_parser('round')
    step.add_argument('run_id')
    step.add_argument('chain_id')
    step.add_argument('round_number', type=int)
    step.add_argument('--parent', choices=_PRESETS, required=True)
    args = parser.parse_args()
    try:
        if args.command == 'init':
            result = initialize(args.run_id, args.reviewed_case, args.selection_basis, args.model)
        else:
            assembled = run_round(args.run_id, args.chain_id, args.round_number, args.parent)
            result = {'record': str(assembled.record_path), 'combinations': len(assembled.combinations),
                      'static_failures': sum(bool(combo.failures) for combo in assembled.combinations)}
    except (OSError, ValueError, RuntimeError, SyntaxError) as error:
        print(f'{type(error).__name__}: {error}', file=sys.stderr)
        return 1
    print(json_bytes(result).decode('utf-8'), end='')
    return 0


if __name__ == '__main__':
    raise SystemExit(main())
