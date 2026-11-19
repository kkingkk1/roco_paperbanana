"""复用现有 Codex CLI，留存真实请求的事件与用量。"""

from __future__ import annotations

import hashlib
import json
import os
import shutil
import subprocess
from datetime import datetime
from pathlib import Path


ROOT = Path(__file__).resolve().parents[4]


def digest(raw: bytes) -> str:
    return hashlib.sha256(raw).hexdigest()


def json_bytes(value) -> bytes:
    return (json.dumps(value, ensure_ascii=False, indent=2) + '\n').encode('utf-8')


def write_new(path: Path, raw: bytes) -> None:
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


def parse_json(raw: bytes):
    return json.loads(raw.decode('utf-8'), object_pairs_hook=_pairs, parse_constant=_invalid_constant)


def call_codex(run: Path, model: str, *, schema: Path | None = None) -> None:
    executable = shutil.which('codex.cmd' if os.name == 'nt' else 'codex')
    if not executable:
        raise RuntimeError('existing Codex CLI was not found')
    command = [executable, 'exec', '--ephemeral', '--json', '--sandbox', 'read-only',
               '--model', model, '-c', 'model_reasoning_effort="low"',
               '--disable', 'multi_agent', '--disable', 'apps',
               '--disable', 'shell_tool', '--disable', 'unified_exec', '--output-schema',
               str(schema if schema is not None else run / 'response-schema.json'),
               '--output-last-message', str(run / 'response.json'), '--skip-git-repo-check', '-']
    write_new(run / 'call-started.json', json_bytes({'at': datetime.now().astimezone().isoformat(),
                                                     'command': command}))
    try:
        completed = subprocess.run(command, input=(run / 'request.txt').read_bytes(),
                                   stdout=subprocess.PIPE, stderr=subprocess.PIPE, cwd=ROOT, check=False)
    except OSError as error:
        write_new(run / 'call-result.json', json_bytes({'error': str(error), 'exit_code': None}))
        raise
    write_new(run / 'codex-events.jsonl', completed.stdout)
    write_new(run / 'codex-stderr.log', completed.stderr)
    usage = []
    for line in completed.stdout.splitlines():
        try:
            event = parse_json(line)
        except (ValueError, UnicodeError):
            continue
        if isinstance(event, dict) and isinstance(event.get('usage'), dict):
            usage.append(event['usage'])
    write_new(run / 'call-result.json', json_bytes({'exit_code': completed.returncode, 'usage_events': usage,
                                                    'finished_at': datetime.now().astimezone().isoformat()}))
    if completed.returncode != 0:
        raise RuntimeError(f'Codex CLI failed with exit code {completed.returncode}; see codex-stderr.log')


def verify_call(run: Path, response_raw: bytes) -> dict:
    result_path = run / 'call-result.json'
    events_path = run / 'codex-events.jsonl'
    if not result_path.is_file() or not events_path.is_file():
        raise RuntimeError('response lacks a completed call record; inspect before recovery')
    result = parse_json(result_path.read_bytes())
    if not isinstance(result, dict) or result.get('exit_code') != 0:
        raise RuntimeError('prior call failed; preserve its response and inspect before recovery')
    events = [parse_json(line) for line in events_path.read_bytes().splitlines() if line.strip()]
    if any(not isinstance(event, dict) for event in events):
        raise ValueError('invalid Codex event')
    if (sum(event.get('type') == 'turn.started' for event in events) != 1
            or sum(event.get('type') == 'turn.completed' for event in events) != 1):
        raise ValueError('expected one completed model turn')
    messages = [event['item']['text'] for event in events
                if event.get('type') == 'item.completed' and isinstance(event.get('item'), dict)
                and event['item'].get('type') == 'agent_message' and isinstance(event['item'].get('text'), str)]
    if not messages or parse_json(messages[-1].encode('utf-8')) != parse_json(response_raw):
        raise ValueError('saved response does not match the completed model message')
    usage = [event['usage'] for event in events if isinstance(event.get('usage'), dict)]
    if usage != result.get('usage_events'):
        raise ValueError('saved usage differs from the model events')
    return result
