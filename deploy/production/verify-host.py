#!/usr/bin/env python3
"""Read-only deployment diagnostics; never prints environment values or passwords."""
import os
from pathlib import Path
import subprocess

assert os.geteuid() == 0, 'Run as root'
COMPOSE = ['docker', 'compose', '-f', '/opt/yufeichi/deploy/compose.yml']

def output(args):
    return subprocess.check_output(args, text=True).strip()

def sql(query):
    return output(COMPOSE + ['exec', '-T', 'mysql', 'sh', '-c',
        'MYSQL_PWD="$(cat /run/secrets/mysql-root)" exec mysql -uroot -Nse "$1"', 'sh', query])

assert sql('SELECT COUNT(*) FROM yufeichi.flyway_schema_history WHERE success=1 AND checksum IS NOT NULL') == '11'
assert sql("SELECT status FROM yufeichi.sys_user WHERE id=1 AND username='admin'") == '0'
assert sql('SELECT completed FROM yufeichi.sys_bootstrap_state WHERE id=1') == '1'
print('Migration versions/success/checksum:')
print(sql('SELECT version,success,checksum FROM yufeichi.flyway_schema_history ORDER BY installed_rank'))
print('MySQL:', sql('SELECT VERSION()'))
print(output(COMPOSE + ['ps']))
redis = output(COMPOSE + ['exec', '-T', 'redis', 'sh', '-c',
    'REDISCLI_AUTH="$(cat /run/secrets/redis-password)" redis-cli --raw CONFIG GET appendonly appendfsync maxmemory-policy'])
assert 'yes' in redis and 'always' in redis and 'noeviction' in redis
print('Redis persistence:', redis.replace('\n', ' '))
service = output(['systemctl', 'show', 'yufeichi', '-p', 'User', '-p', 'Group', '-p', 'Restart', '-p', 'NRestarts', '-p', 'ActiveState', '-p', 'UnitFileState'])
assert 'User=yufeichi' in service and 'ActiveState=active' in service and 'UnitFileState=enabled' in service
print(service)
for file in ['/etc/yufeichi/backend.env', '/etc/yufeichi', '/etc/yufeichi/secrets']:
    stat = Path(file).stat()
    expected = 0o600 if file.endswith('.env') else 0o700
    assert stat.st_mode & 0o777 == expected, file
    print('Protected path:', oct(stat.st_mode & 0o777), file)
print('Backend current:', Path('/opt/yufeichi/backend/current').resolve())
print('Frontend current:', Path('/var/www/yufeichi-app/current').resolve())
print(output(['ss', '-lnt']))
print(output(['df', '-h', '/']))
print('PASS: migration/bootstrap, health, persistent Redis, non-root enabled service and protected secrets')
