#!/usr/bin/env python3
"""Check at most three explicitly supplied candidates. No account changes or credential output."""
import json
import os
from pathlib import Path
import urllib.error
import urllib.request

if os.geteuid() != 0:
    raise SystemExit('Run as root')
path = Path('/etc/yufeichi/acceptance-admin.json')
assert path.stat().st_mode & 0o077 == 0
data = json.loads(path.read_text())
passwords = data.get('passwords', [data.get('password')])
assert 1 <= len(passwords) <= 3 and all(isinstance(p, str) and p for p in passwords)
for password in passwords:
    credential = {'username': data['username'], 'password': password}
    request = urllib.request.Request('https://yufeichi.com/api/auth/login',
        data=json.dumps(credential).encode(), headers={'Content-Type': 'application/json'})
    try:
        with urllib.request.urlopen(request, timeout=20) as response:
            assert response.status == 200
            token = json.load(response)['data']['token']
        logout = urllib.request.Request('https://yufeichi.com/api/auth/logout', method='POST',
            headers={'Authorization': 'Bearer ' + token})
        with urllib.request.urlopen(logout, timeout=20) as response:
            assert response.status == 200
        path.write_text(json.dumps(credential))
        print('AI_ADMIN_LOGIN_PASS')
        break
    except urllib.error.HTTPError as error:
        if error.code != 401:
            raise SystemExit('Login check stopped: HTTP ' + str(error.code))
else:
    path.unlink()
    raise SystemExit('AI_ADMIN_LOGIN_FAILED: supplied credentials did not match; no account changed')
