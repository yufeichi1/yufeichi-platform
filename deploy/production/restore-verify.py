#!/usr/bin/env python3
"""Restore a verified backup into disposable loopback containers and a non-root Java process.
Never imports into the production container; private restored files are retained for diagnosis.
"""
import argparse
import gzip
import hashlib
import importlib.util
import json
import os
from pathlib import Path
import pwd
import grp
import secrets
import socket
import subprocess
import tarfile
import time
import uuid

def run(args, **kwargs):
    return subprocess.check_output(args, text=True, stderr=subprocess.PIPE, **kwargs).strip()

def free_port():
    with socket.socket() as sock:
        sock.bind(('127.0.0.1', 0))
        return sock.getsockname()[1]

def wait(check, description):
    for attempt in range(150):
        try:
            if check():
                return
        except Exception:
            pass
        time.sleep(1)
    raise RuntimeError('Timed out: ' + description)

def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('backup', type=Path)
    parser.add_argument('--jar', type=Path, default=Path('/opt/yufeichi/backend/current/yufeichi-server.jar'))
    args = parser.parse_args()
    assert os.geteuid() == 0
    backup = args.backup.resolve()
    assert backup.is_relative_to('/var/backups/yufeichi') and backup.is_dir()
    jar = args.jar.resolve()
    assert jar.is_relative_to('/opt/yufeichi/backend/releases') and jar.is_file()
    expected_files = {'database.sql.gz', 'uploads.tar.gz', 'config.tar.gz', 'flyway.txt', 'manifest.txt'}
    for line in (backup / 'SHA256SUMS').read_text().splitlines():
        expected, name = line.split()
        assert name in expected_files
        assert hashlib.sha256((backup / name).read_bytes()).hexdigest() == expected
    assert all((backup / name).is_file() for name in expected_files)
    spec = importlib.util.spec_from_file_location('ops_probe', Path(__file__).with_name('ops-probe.py'))
    ops = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(ops)
    state = ops.private_json(ops.STATE)
    suffix = uuid.uuid4().hex[:10]
    workspace = Path('/var/lib/yufeichi/restore-runs') / suffix
    workspace.mkdir(parents=True, mode=0o750)
    os.chmod(workspace.parent, 0o750)
    os.chown(workspace.parent, 0, grp.getgrnam('www-data').gr_gid)
    os.chown(workspace, 0, grp.getgrnam('www-data').gr_gid)
    secret_dir = workspace / 'secrets'
    secret_dir.mkdir(mode=0o700)
    root_password, app_password, redis_password = [secrets.token_hex(32) for _ in range(3)]
    (secret_dir / 'mysql-root').write_text(root_password)
    (secret_dir / 'redis.conf').write_text('bind 0.0.0.0\nprotected-mode yes\nrequirepass ' + redis_password + '\nsave ""\n')
    for path in secret_dir.iterdir():
        path.chmod(0o444)
    with tarfile.open(backup / 'uploads.tar.gz') as archive:
        for member in archive.getmembers():
            name = Path(member.name)
            assert not name.is_absolute() and '..' not in name.parts and name.parts[0] == 'uploads'
            assert member.isdir() or member.isfile(), 'Links in upload backup are forbidden'
        archive.extractall(workspace, filter='data')
    uid, gid = pwd.getpwnam('yufeichi').pw_uid, grp.getgrnam('www-data').gr_gid
    for path in [workspace / 'uploads', *(workspace / 'uploads').rglob('*')]:
        os.chown(path, uid, gid)
        path.chmod(0o750 if path.is_dir() else 0o640)
    logs = workspace / 'logs'
    logs.mkdir(mode=0o750)
    os.chown(logs, uid, gid)
    containers = []
    process = None
    log_file = None
    mysql = 'yufeichi-restore-mysql-' + suffix
    redis = 'yufeichi-restore-redis-' + suffix
    try:
        run(['docker', 'run', '-d', '--name', mysql, '--label', 'yufeichi.test=restore', '--memory', '768m',
            '-p', '127.0.0.1::3306', '-e', 'MYSQL_ROOT_PASSWORD_FILE=/run/secrets/mysql-root',
            '-v', str(secret_dir / 'mysql-root') + ':/run/secrets/mysql-root:ro', 'mysql:8.4.11', '--innodb-buffer-pool-size=128M'])
        containers.append(mysql)
        def sql(query):
            return run(['docker', 'exec', '-i', mysql, 'sh', '-c',
                'MYSQL_PWD="$(cat /run/secrets/mysql-root)" exec mysql -uroot -N'], input=query)
        wait(lambda: sql('SELECT 1;') == '1', 'isolated MySQL')
        # Pipe the database archive only to the container created above.
        dump = subprocess.Popen(['docker', 'exec', '-i', mysql, 'sh', '-c',
            'MYSQL_PWD="$(cat /run/secrets/mysql-root)" exec mysql -uroot'], stdin=subprocess.PIPE, stdout=subprocess.DEVNULL, stderr=subprocess.PIPE)
        with gzip.open(backup / 'database.sql.gz', 'rb') as source:
            while block := source.read(1024 * 1024):
                dump.stdin.write(block)
        dump.stdin.close()
        assert dump.wait() == 0, 'Independent DB import failed'
        sql("CREATE USER 'restore_app'@'%' IDENTIFIED BY '" + app_password + "'; GRANT ALL PRIVILEGES ON yufeichi.* TO 'restore_app'@'%';")
        assert sql('SELECT COUNT(*) FROM yufeichi.flyway_schema_history WHERE success=1;') == '10'
        assert sql('SELECT COUNT(*) FROM yufeichi.sys_user_role WHERE user_id=2;') != '0'
        assert sql('SELECT COUNT(*) FROM yufeichi.blog_article_tag WHERE article_id=' + str(state['article']) + ';') == '1'
        metadata = sql('SELECT file_url,file_size FROM yufeichi.file_info WHERE deleted=0;')
        for row in metadata.splitlines():
            url, size = row.split('\t')
            assert url.startswith('/uploads/')
            image = (workspace / url.lstrip('/')).resolve()
            assert image.is_relative_to(workspace / 'uploads') and image.is_file()
            assert image.stat().st_size == int(size)
        print('RESTORED_FILE_LINKS_PASS:', len(metadata.splitlines()), 'metadata rows', flush=True)
        run(['docker', 'run', '-d', '--name', redis, '--label', 'yufeichi.test=restore', '--memory', '128m',
            '-p', '127.0.0.1::6379', '-v', str(secret_dir / 'redis.conf') + ':/usr/local/etc/redis/redis.conf:ro',
            'redis:7.4.10', 'redis-server', '/usr/local/etc/redis/redis.conf'])
        containers.append(redis)
        mysql_port = run(['docker', 'port', mysql, '3306/tcp']).rsplit(':', 1)[1]
        redis_port = run(['docker', 'port', redis, '6379/tcp']).rsplit(':', 1)[1]
        backend_port = free_port()
        env = {**os.environ, 'SPRING_PROFILES_ACTIVE': 'prod', 'SERVER_PORT': str(backend_port),
            'DB_HOST': '127.0.0.1', 'DB_PORT': mysql_port, 'DB_NAME': 'yufeichi', 'DB_USERNAME': 'restore_app', 'DB_PASSWORD': app_password,
            'REDIS_HOST': '127.0.0.1', 'REDIS_PORT': redis_port, 'REDIS_PASSWORD': redis_password,
            'JWT_SECRET': secrets.token_hex(64), 'JWT_EXPIRE_MINUTES': '45', 'UPLOAD_PATH': str(workspace / 'uploads'), 'LOG_PATH': str(logs)}
        log_file = open(workspace / 'java.log', 'wb')
        os.chmod(workspace / 'java.log', 0o600)
        process = subprocess.Popen(['runuser', '-u', 'yufeichi', '-g', 'www-data', '--',
            '/usr/lib/jvm/java-21-openjdk-amd64/bin/java', '-Xmx256m', '-XX:MaxMetaspaceSize=128m', '-jar', str(jar)],
            env=env, stdout=log_file, stderr=subprocess.STDOUT, cwd=str(workspace))
        client = ops.Client('http://127.0.0.1:' + str(backend_port))
        wait(lambda: client.api('/api/health') == 'ok', 'non-root restored backend')
        # A new JWT key prevents accepting old production tokens after restoring without Redis state.
        client.api('/api/auth/me', token=state['revoked_token'], expected=401)
        ops.check(client, state, write=True, upload=True)
        files = list((workspace / 'uploads').rglob('*.png'))
        assert any(hashlib.sha256(path.read_bytes()).hexdigest() == state['image_sha256'] for path in files)
        print('INDEPENDENT_RESTORE_PASS: V1-V10, account-role and article-tag relations, real login/read/write/upload; isolated JWT key', flush=True)
        print('Private restore evidence:', workspace, flush=True)
    finally:
        if process:
            process.terminate()
            try:
                process.wait(timeout=40)
            except subprocess.TimeoutExpired:
                process.kill()
                process.wait()
        if log_file:
            log_file.close()
        for name in reversed(containers):
            run(['docker', 'rm', '-f', '-v', name])

if __name__ == '__main__':
    main()
