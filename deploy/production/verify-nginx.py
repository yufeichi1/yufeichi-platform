#!/usr/bin/env python3
"""Public error boundaries. --outage explicitly stops/starts the backend for a gateway check."""
import json
import os
import subprocess
import sys
import time
import urllib.error
import urllib.request

def check(path, status, body=None):
    req = urllib.request.Request('https://yufeichi.com' + path, data=body,
        headers={'Content-Type': 'application/octet-stream'} if body else {})
    try:
        response = urllib.request.urlopen(req, timeout=40)
    except urllib.error.HTTPError as exc:
        response = exc
    data = response.read()
    assert response.status == status, (path, response.status, status)
    assert 'application/json' in response.headers.get('Content-Type', ''), path
    result = json.loads(data)
    assert isinstance(result['code'], int), path
    print('PASS', path, status, 'JSON', flush=True)

check('/api', 404)
check('/api/auth/me', 401)
check('/api/articles/9223372036854775807', 404)
check('/api/admin/files/upload', 413, b'x' * (6 * 1024 * 1024 + 1))
if '--outage' in sys.argv:
    assert os.geteuid() == 0
    assert subprocess.check_output(['systemctl', 'is-active', 'yufeichi'], text=True).strip() == 'active'
    try:
        subprocess.run(['systemctl', 'stop', 'yufeichi'], check=True)
        check('/api/health', 503)
    finally:
        subprocess.run(['systemctl', 'start', 'yufeichi'], check=True)
        for attempt in range(90):
            try:
                check('/api/health', 200)
                break
            except (AssertionError, urllib.error.URLError):
                time.sleep(2)
        else:
            raise RuntimeError('Backend did not recover; inspect systemd journal')
print('NGINX_ACCEPTANCE_PASS', flush=True)
