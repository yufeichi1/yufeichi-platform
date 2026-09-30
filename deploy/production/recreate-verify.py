#!/usr/bin/env python3
"""Controlled production container recreation, with persistent volumes preserved."""
import fcntl
import hashlib
import importlib.util
import json
import os
from pathlib import Path
import subprocess
import time

def main():
    assert os.geteuid() == 0
    lock = open('/run/lock/yufeichi-ops.lock', 'w')
    fcntl.flock(lock, fcntl.LOCK_EX | fcntl.LOCK_NB)
    spec = importlib.util.spec_from_file_location('ops', Path(__file__).with_name('ops-probe.py'))
    ops = importlib.util.module_from_spec(spec); spec.loader.exec_module(ops)
    state = ops.private_json(ops.STATE)
    client = ops.Client('https://yufeichi.com')
    compose = ['docker', 'compose', '-f', '/opt/yufeichi/deploy/compose.yml']
    def output(args):
        return subprocess.check_output(args, text=True).strip()
    def containers():
        ids = output(compose + ['ps', '-q']).splitlines()
        data = json.loads(output(['docker', 'inspect', *ids]))
        return {item['Name']: {'id': item['Id'], 'volumes': sorted(m['Name'] for m in item['Mounts'] if m['Type'] == 'volume')} for item in data}
    def revoked_ttl():
        key = 'security:revoked:' + hashlib.sha256(state['revoked_token'].encode()).hexdigest()
        return int(output(compose + ['exec', '-T', 'redis', 'sh', '-c',
            'REDISCLI_AUTH="$(cat /run/secrets/redis-password)" exec redis-cli --raw TTL "$1"', 'sh', key]))
    before = containers()
    assert revoked_ttl() > 0
    ops.check(client, state, revoked=True)
    try:
        subprocess.run(['systemctl', 'stop', 'yufeichi'], check=True)
        subprocess.run(compose + ['up', '-d', '--force-recreate', '--wait', '--wait-timeout', '180', 'mysql', 'redis'], check=True)
    finally:
        subprocess.run(['systemctl', 'start', 'yufeichi'], check=True)
    after = containers()
    assert before.keys() == after.keys()
    assert all(before[name]['id'] != after[name]['id'] and before[name]['volumes'] == after[name]['volumes'] for name in before)
    for attempt in range(90):
        try:
            if client.api('/api/health') == 'ok': break
        except Exception: pass
        time.sleep(2)
    else:
        raise RuntimeError('Backend recovery timeout')
    assert revoked_ttl() > 0
    ops.check(client, state, revoked=True, write=True, upload=True)
    print('RECREATE_PASS: new container IDs, same named volumes, database/image/API writes and live Redis revocation preserved', flush=True)

if __name__ == '__main__':
    main()
