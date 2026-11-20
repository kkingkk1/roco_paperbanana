"""用两个现成模块版本实评单个设计实例，并检验组合演化的选版。"""

from __future__ import annotations

import os
import platform
import re
import shutil
import subprocess
import sys
from pathlib import Path

from . import generation
from .evolution import AlgorithmVersion, ModuleCandidate, assemble_combinations, preset_version
from .loader import load_scene
from .model_call import digest, json_bytes, parse_json
from .problem import ProblemAdapter


def _record(path: Path, data: dict) -> None:
    """在持有本次运行的写锁时发布完整记录，恢复时校验既有记录。"""
    raw = json_bytes(data)
    if path.exists() or path.is_symlink():
        if generation._read(path) != raw:
            raise ValueError(f'运行记录与冻结内容不一致: {path}')
        return
    pending = path.with_suffix('.pending')
    if pending.is_symlink():
        raise ValueError(f'临时记录是符号链接: {pending}')
    pending.write_bytes(raw)
    pending.replace(path)


def _preset_name(version: AlgorithmVersion) -> str:
    name = ''.join(module.preset or '' for module in version.modules)
    if not re.fullmatch(r'A[12]B[12]C[12]', name) or preset_version(name).algorithm_hash != version.algorithm_hash:
        raise ValueError('单实例运行仅接收源码身份匹配的 P1 预置组合')
    return name


def _solve(command: list[str], name: str, normalizer: int) -> dict:
    completed = subprocess.run([*command, '-Combination', name], capture_output=True, text=True,
                               encoding='utf-8', errors='replace', check=False)
    lines = completed.stdout.splitlines()
    header = re.fullmatch(r'status=(SUCCESS|FAILURE|HARD_TIMEOUT) profit=(\d+|null) observations=(\d+) '
                          r'elapsedMs=(\d+)\.(\d{3}) detail=(.*)', lines[0]) if lines else None
    if header is None:
        raise RuntimeError(f'受限求解入口未返回评价结果 ({name}): {completed.stderr.strip()}')
    status, raw_profit, count, elapsed_ms, microseconds, detail = header.groups()
    succeeded = status == 'SUCCESS'
    if succeeded != (completed.returncode == 0) or succeeded != (raw_profit != 'null'):
        raise RuntimeError(f'求解状态与退出码或收益不一致: {name}')
    if len(lines) != int(count) + 1 or (not succeeded and int(count) != 0):
        raise RuntimeError(f'观测数和求解状态不一致: {name}')
    schedule = []
    for line in lines[1:]:
        fields = re.fullmatch(r'task=(\d+) satellite=(\d+) window=(\d+) start=([\d.Ee+-]+) end=([\d.Ee+-]+)', line)
        if fields is None:
            raise RuntimeError(f'受限求解入口返回无效观测: {name}')
        task, satellite, window, start, end = fields.groups()
        schedule.append({'task': int(task), 'satellite': int(satellite), 'window': int(window),
                         'start': float(start), 'end': float(end)})
    profit = int(raw_profit) if succeeded else None
    return {'status': status, 'raw_profit': profit, 'evaluation_profit': profit if succeeded else 0,
            'score': profit / normalizer if succeeded else None,
            'elapsed_us': int(elapsed_ms) * 1000 + int(microseconds), 'observations': schedule,
            'detail': detail}


def _evaluate(events: Path, run_id: str, condition_hash: str, algorithm: AlgorithmVersion,
              command: list[str], normalizer: int, origin: str) -> tuple[dict, bool]:
    name = _preset_name(algorithm)
    identity = {'run_id': run_id, 'condition_sha256': condition_hash, 'algorithm_hash': algorithm.algorithm_hash}
    event_id = digest(json_bytes(identity))
    path = events / f'{event_id}.json'
    if path.exists() or path.is_symlink():
        result = parse_json(generation._read(path))
        if (not isinstance(result, dict) or result.get('event_id') != event_id
                or any(result.get(key) != value for key, value in identity.items())):
            raise ValueError(f'缓存结果的身份与当前评价不一致: {path}')
        return result, False
    result = {'event_id': event_id, **identity, 'origin': origin, 'combination': name,
              **_solve(command, name, normalizer)}
    _record(path, result)
    return result, True


def _rank(events: list[dict], count: int) -> list[dict]:
    successful = {event['algorithm_hash']: event for event in events if event['status'] == 'SUCCESS'}
    return sorted(successful.values(), key=lambda event: (-event['score'], event['elapsed_us'],
                                                         event['algorithm_hash']))[:count]


def _evaluate_generation(stage: Path, run_id: str, parent: AlgorithmVersion, condition: dict,
                         command: list[str]) -> dict:
    condition_hash = digest(json_bytes(condition))
    _record(stage / 'run.json', {'run_id': run_id, 'condition_sha256': condition_hash, **condition})
    events = generation._directory(stage / 'evaluations', create=True)
    ranks = generation._directory(stage / 'rounds', create=True)
    initial, performed = _evaluate(events, run_id, condition_hash, parent, command,
                                   condition['normalizer'], 'initialization')
    if initial['status'] != 'SUCCESS':
        raise RuntimeError('唯一手工起点未通过实例评价')
    all_evaluated = [initial]
    cache_hits = 0
    solves = int(performed)
    variants = (preset_version('A1B1C1'), preset_version('A2B2C2'))
    for number in (1, 2):
        old_presets = {module.preset for module in parent.modules}
        alternatives = {module.role: module for variant in variants for module in variant.modules
                        if module.preset not in old_presets}
        candidates = {role: ModuleCandidate(parent.algorithm_hash, module) for role, module in alternatives.items()}
        combinations = assemble_combinations(parent, candidates)
        rows, evaluated = [], []
        for combination in combinations:
            row = combination.record()
            if combination.algorithm is not None:
                origin = f'round/{number}/{row["slot"]}'
                result, performed = _evaluate(events, run_id, condition_hash, combination.algorithm,
                                              command, condition['normalizer'], origin)
                hit = result['origin'] != origin
                cache_hits += hit
                solves += performed
                evaluated.append(result)
                row.update(status=result['status'], evaluation_event_id=result['event_id'],
                           source_run_id=result['run_id'], cache_hit=hit, combination=result['combination'])
            rows.append(row)
        next_parent, = _rank(evaluated, 1)
        _record(ranks / f'{number}.json', {'round': number, 'base_algorithm_hash': parent.algorithm_hash,
                                          'combinations': rows, 'next_parent_event_id': next_parent['event_id']})
        all_evaluated.extend(evaluated)
        parent = preset_version(next_parent['combination'])
    top, = _rank(all_evaluated, 1)
    summary = {'run_id': run_id, 'initial_event_id': initial['event_id'], 'top_event_id': top['event_id'],
               'evaluated_combinations': len({event['algorithm_hash'] for event in all_evaluated}),
               'rounds': 2, 'combination_slots': 16, 'cache_hits': cache_hits,
               'instance_solves': len({event['event_id'] for event in all_evaluated}),
               'top_combination': top['combination'], 'raw_profit': top['raw_profit'], 'score': top['score']}
    _record(stage / 'selection.json', summary)
    return {**summary, 'new_solves_this_invocation': solves, 'selection': str(stage / 'selection.json')}


def run_preset_generation(run_id: str, scene: Path, tasks: int, satellites: int, seed: int,
                          parent_name: str) -> dict:
    generation._validate_id(run_id)
    if os.name != 'nt':
        raise RuntimeError('预置组合的受限运行入口需要 Windows 和 PowerShell 7')
    import msvcrt

    pwsh = shutil.which('pwsh')
    python = shutil.which('python')
    if not pwsh or not python:
        raise RuntimeError('需要现有的 pwsh 和 python 命令')
    root = generation.ROOT
    scene = scene.resolve(strict=True)
    parent = preset_version(parent_name)
    instance = load_scene(scene, tasks, satellites)
    adapter = ProblemAdapter(instance)
    observable = {window.task_id for satellite in instance.satellites for window in satellite.windows
                  if next(adapter.candidates(window), None) is not None}
    normalizer = sum(task.priority for task in instance.tasks if task.id in observable)
    if normalizer <= 0:
        raise ValueError('设计实例没有正收益的合法观测，无法归一化选版')
    data_files = ['tasklist.txt', *(f'{prefix}_{index}.txt' for index in range(1, satellites + 1)
                                   for prefix in ('outputtimewindow', 'outputattitude'))]
    scripts = ['scripts/run-python-manual.ps1', 'scripts/run-python-manual-worker.ps1', 'scripts/P1ResourceJob.cs']
    contracts = ['vault/Knowledge/问题建模.md', 'vault/Knowledge/多智能体算法与评价合同.md']
    sources = [*sorted(Path(__file__).parent.rglob('*.py')), *(root / name for name in scripts + contracts)]
    command = [pwsh, '-NoProfile', '-NonInteractive', '-File', str(root / scripts[0]), '-Scene', str(scene),
               '-Tasks', str(tasks), '-Satellites', str(satellites), '-Seed', str(seed)]
    condition = {'stage': 'design', 'scene': str(scene), 'tasks': tasks, 'satellites': satellites,
                 'data_sha256': {name: digest((scene / name).read_bytes()) for name in data_files},
                 'seed': seed, 'normalizer': normalizer, 'initial_algorithm_hash': parent.algorithm_hash,
                 'source_sha256': {path.relative_to(root).as_posix(): digest(path.read_bytes()) for path in sources},
                 'cwd': str(Path.cwd()), 'worker_command': command, 'hostname': platform.node(),
                 'os': platform.platform(), 'controller_python': sys.version, 'worker_python': python,
                 'worker_python_version': subprocess.check_output([python, '-VV'], text=True).strip(),
                 'limits': {'cores': 1, 'memory_bytes': 2 * 1024**3, 'hard_ms': 5000, 'priority': 'Normal'}}
    outputs = generation._directory(root / 'outputs', create=True)
    run = generation._directory(outputs / run_id, create=True)
    stage = generation._directory(run / 'preset-evolution', create=True)
    lock_path = stage / 'write.lock'
    if lock_path.is_symlink():
        raise ValueError(f'运行锁是符号链接: {lock_path}')
    with lock_path.open('ab') as lock:
        msvcrt.locking(lock.fileno(), msvcrt.LK_NBLCK, 1)
        return _evaluate_generation(stage, run_id, parent, condition, command)
