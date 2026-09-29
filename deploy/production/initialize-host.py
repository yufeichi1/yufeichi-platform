#!/usr/bin/env python3
"""Run as root once on a backed-up host. Never prints generated secrets."""
import json
import os
from pathlib import Path
import pwd
import secrets
import subprocess
import sys

def run(*args):
    subprocess.run(args, check=True)

if os.geteuid() != 0:
    sys.exit("Run as root")
base = Path('/etc/yufeichi')
if (base / 'backend.env').exists():
    sys.exit('Configuration already exists; refusing to overwrite credentials')
try:
    pwd.getpwnam('yufeichi')
except KeyError:
    run('useradd', '--system', '--create-home', '--home-dir', '/var/lib/yufeichi', '--shell', '/usr/sbin/nologin', 'yufeichi')
run('install', '-d', '-m', '700', str(base), str(base / 'secrets'))
run('install', '-d', '-m', '2750', '-o', 'yufeichi', '-g', 'www-data', '/var/lib/yufeichi/uploads')
run('install', '-d', '-m', '750', '-o', 'yufeichi', '-g', 'www-data', '/var/log/yufeichi/backend')
run('chmod', '755', '/var/lib/yufeichi')
run('install', '-d', '-m', '755', '/opt/yufeichi/backend/releases', '/opt/yufeichi/deploy', '/var/www/yufeichi-app/releases')

def write(path, text, mode=0o600):
    fd = os.open(path, os.O_WRONLY | os.O_CREAT | os.O_EXCL, mode)
    with os.fdopen(fd, 'w') as f:
        f.write(text)
    os.chmod(path, mode)

db_password, redis_password = secrets.token_hex(32), secrets.token_hex(32)
write(base / 'secrets/mysql-root', secrets.token_hex(32), 0o444)
write(base / 'secrets/mysql-password', db_password, 0o444)
write(base / 'secrets/redis-password', redis_password, 0o444)
# The secret directory is root-only on the host. Read-only container mounts must
# be readable by mysql/redis after their entrypoints drop root privileges.
write(base / 'redis.conf', '\n'.join([
    'bind 0.0.0.0', 'protected-mode yes', 'port 6379', 'dir /data',
    'requirepass ' + redis_password, 'appendonly yes', 'appendfsync always',
    'save ""', 'maxmemory 256mb', 'maxmemory-policy noeviction', 'loglevel notice', '']), 0o444)
config = dict(SPRING_PROFILES_ACTIVE='prod', SERVER_PORT='8080', DB_HOST='127.0.0.1', DB_PORT='3306',
    DB_NAME='yufeichi', DB_USERNAME='yufeichi', DB_PASSWORD=db_password, REDIS_HOST='127.0.0.1',
    REDIS_PORT='6379', REDIS_PASSWORD=redis_password, JWT_SECRET=secrets.token_hex(64), JWT_EXPIRE_MINUTES='45',
    UPLOAD_PATH='/var/lib/yufeichi/uploads', LOG_PATH='/var/log/yufeichi/backend', TRUSTED_PROXIES='127.0.0.1')
write(base / 'backend.env', ''.join(f'{key}={value}\n' for key, value in config.items()))
admin = dict(username='yufeichi-owner', password=secrets.token_urlsafe(32), login_url='https://yufeichi.com/login')
write(base / 'initial-admin.json', json.dumps(admin))
print('Dedicated account, protected configuration and fresh secrets created; no values displayed.')
