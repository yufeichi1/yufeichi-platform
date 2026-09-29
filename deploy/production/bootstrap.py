#!/usr/bin/env python3
"""Offline first administrator initialization. Root reads secrets, Java runs unprivileged."""
import json
import os
from pathlib import Path
import subprocess
import sys

if os.geteuid() != 0 or len(sys.argv) != 2:
    sys.exit('Usage: sudo python3 bootstrap.py /absolute/release/yufeichi-server.jar')
jar = Path(sys.argv[1]).resolve(strict=True)
if not jar.is_relative_to('/opt/yufeichi/backend/releases'):
    sys.exit('JAR must be inside the release directory')
env = dict(os.environ)
for line in Path('/etc/yufeichi/backend.env').read_text().splitlines():
    if line and not line.startswith('#'):
        key, value = line.split('=', 1)
        env[key] = value
admin = json.loads(Path('/etc/yufeichi/initial-admin.json').read_text())
env['BOOTSTRAP_USERNAME'], env['BOOTSTRAP_PASSWORD'] = admin['username'], admin['password']
subprocess.run(['runuser', '-u', 'yufeichi', '-g', 'www-data', '--',
    '/usr/lib/jvm/java-21-openjdk-amd64/bin/java', '-Xmx384m', '-jar', str(jar), '--bootstrap'], env=env, check=True)
print('Offline bootstrap completed. Bootstrap credentials were not added to the service environment.')
