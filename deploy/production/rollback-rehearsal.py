#!/usr/bin/env python3
"""Explicit compatible backend rollback rehearsal; always returns to the starting release.
Frontend source is unchanged between these candidates; both release links are exercised.
DB migrations are never rolled back.
"""
import argparse
import fcntl
import importlib.util
import os
from pathlib import Path
import subprocess
import time
import uuid

def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('release', type=Path)
    parser.add_argument('--frontend', type=Path, required=True)
    parser.add_argument('--upload', action='store_true', help='Also verify a fresh upload through Nginx')
    args = parser.parse_args()
    assert os.geteuid() == 0
    lock = open('/run/lock/yufeichi-ops.lock', 'w')
    fcntl.flock(lock, fcntl.LOCK_EX | fcntl.LOCK_NB)
    current = Path('/opt/yufeichi/backend/current')
    original, candidate = current.resolve(), args.release.resolve()
    front_current = Path('/var/www/yufeichi-app/current')
    original_front, candidate_front = front_current.resolve(), args.frontend.resolve()
    assert candidate.is_relative_to('/opt/yufeichi/backend/releases') and candidate != original
    assert (candidate / 'yufeichi-server.jar').is_file()
    assert candidate_front.is_relative_to('/var/www/yufeichi-app/releases') and candidate_front != original_front
    assert (candidate_front / 'index.html').is_file()
    spec = importlib.util.spec_from_file_location('ops', Path(__file__).with_name('ops-probe.py'))
    ops = importlib.util.module_from_spec(spec); spec.loader.exec_module(ops)
    state = ops.private_json(ops.STATE)
    client = ops.Client('https://yufeichi.com')
    def schema():
        return subprocess.check_output(['docker', 'compose', '-f', '/opt/yufeichi/deploy/compose.yml', 'exec', '-T', 'mysql', 'sh', '-c',
            'MYSQL_PWD="$(cat /run/secrets/mysql-root)" exec mysql -uroot -Nse "SELECT version,success,checksum FROM yufeichi.flyway_schema_history ORDER BY installed_rank"'], text=True)
    def switch(target, frontend):
        temporary = current.with_name('current.ops-' + uuid.uuid4().hex)
        temporary.symlink_to(target)
        os.replace(temporary, current)
        temporary_front = front_current.with_name('current.ops-' + uuid.uuid4().hex)
        temporary_front.symlink_to(frontend)
        os.replace(temporary_front, front_current)
        subprocess.run(['systemctl', 'restart', 'yufeichi'], check=True)
        for attempt in range(90):
            try:
                if client.api('/api/health') == 'ok': return
            except Exception: pass
            time.sleep(2)
        raise RuntimeError('Backend did not become healthy')
    before = schema()
    try:
        switch(candidate, candidate_front)
        assert b'<html' in client.raw('/')[0].lower()
        ops.check(client, state, revoked=True, write=True, upload=args.upload)
        assert schema() == before
        print('ROLLBACK_CANDIDATE_PASS:', candidate.name, 'schema unchanged', flush=True)
    finally:
        switch(original, original_front)
        ops.check(client, state, revoked=True, write=True, upload=True)
        assert schema() == before and current.resolve() == original and front_current.resolve() == original_front
        print('ROLLBACK_CURRENT_RESTORED:', original.name, 'schema unchanged', flush=True)
    print('ROLLBACK_REHEARSAL_PASS', flush=True)

if __name__ == '__main__':
    main()
