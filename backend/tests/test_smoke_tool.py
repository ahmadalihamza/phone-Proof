"""The live smoke command must not report success before releasing its state."""
import importlib.util
import tempfile
from pathlib import Path
from types import SimpleNamespace

import pytest


spec = importlib.util.spec_from_file_location('phoneproof_smoke_tool', Path(__file__).resolve().parents[2] / 'tools/smoke_backend.py')
smoke = importlib.util.module_from_spec(spec)
spec.loader.exec_module(smoke)


def test_cleanup_retries_a_transient_windows_file_lock(monkeypatch):
    state = tempfile.TemporaryDirectory(prefix='phoneproof-smoke-')
    original = state.cleanup
    attempts = []

    def locked_once():
        attempts.append(1)
        if len(attempts) == 1:
            raise PermissionError('Simulated delayed SQLite mapping release')
        original()

    monkeypatch.setattr(state, 'cleanup', locked_once)
    monkeypatch.setattr(smoke.time, 'sleep', lambda _: None)
    try:
        smoke.cleanup_temporary_state(state)
        assert len(attempts) == 2
        assert not Path(state.name).exists()
    finally:
        original()


def test_cleanup_does_not_hide_a_permanent_file_lock(monkeypatch):
    state = tempfile.TemporaryDirectory(prefix='phoneproof-smoke-')
    original = state.cleanup
    attempts = []

    def locked():
        attempts.append(1)
        raise PermissionError('Persistent lock fixture')

    monkeypatch.setattr(state, 'cleanup', locked)
    monkeypatch.setattr(smoke.time, 'sleep', lambda _: None)
    try:
        with pytest.raises(PermissionError, match='Persistent lock'):
            smoke.cleanup_temporary_state(state)
        assert len(attempts) == 8
        assert Path(state.name).exists()
    finally:
        original()


def test_cleanup_refuses_a_directory_outside_its_owned_namespace(tmp_path):
    state = SimpleNamespace(name=str(tmp_path / 'project-data'), cleanup=lambda: pytest.fail('Must not delete this path'))
    with pytest.raises(ValueError, match='restricted'):
        smoke.cleanup_temporary_state(state)
