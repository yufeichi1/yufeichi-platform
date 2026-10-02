#!/usr/bin/env python3
"""Restore a verified PG dump only into an owned disposable container, with no network or ports."""
import gzip
import hashlib
import os
from pathlib import Path
import subprocess
import sys
import time
import uuid

if os.geteuid() != 0 or len(sys.argv) != 2:
    raise SystemExit('Usage: sudo python3 verify-ai-vector-backup.py <verified-backup-directory>')
backup = Path(sys.argv[1]).resolve()
assert backup.is_relative_to('/var/backups/yufeichi') and backup.is_dir()
dump = backup / 'ai-vector.sql.gz'
expected = dict((name, checksum) for checksum, name in (line.split() for line in (backup / 'SHA256SUMS').read_text().splitlines()))
assert hashlib.sha256(dump.read_bytes()).hexdigest() == expected['ai-vector.sql.gz']
name = 'yufeichi-ai-restore-' + uuid.uuid4().hex[:12]
image = 'pgvector/pgvector:0.8.6-pg17@sha256:cf134a767f474095eeba57e0117be8e568e011a63f33fbf252f14c9b760f8e6f'
created = False
def run(args, **options):
    return subprocess.check_output(args, text=True, stderr=subprocess.PIPE, **options).strip()
try:
    run(['docker', 'run', '-d', '--rm', '--name', name, '--label', 'yufeichi.ai.restore=' + name,
         '--network', 'none', '--memory', '256m', '--cpus', '0.5', '-e', 'POSTGRES_HOST_AUTH_METHOD=trust',
         '-e', 'POSTGRES_USER=restore', '-e', 'POSTGRES_DB=restore', image])
    created = True
    for attempt in range(90):
        try:
            run(['docker', 'exec', name, 'pg_isready', '-U', 'restore', '-d', 'restore'])
            break
        except subprocess.CalledProcessError:
            time.sleep(1)
    else:
        raise RuntimeError('Independent PG restore did not start')
    importer = subprocess.Popen(['docker', 'exec', '-i', name, 'psql', '-U', 'restore', '-d', 'restore', '-v', 'ON_ERROR_STOP=1'],
                                stdin=subprocess.PIPE, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
    with gzip.open(dump, 'rb') as source:
        while chunk := source.read(65536):
            importer.stdin.write(chunk)
    importer.stdin.close()
    assert importer.wait(timeout=90) == 0, 'PG dump could not be restored'
    def sql(query):
        return run(['docker', 'exec', name, 'psql', '-U', 'restore', '-d', 'restore', '-Atc', query])
    assert sql('SELECT count(*) FROM yufeichi_ai.vector_schema_history WHERE success AND version IS NOT NULL') == '2'
    assert sql('SELECT dimensions FROM yufeichi_ai.embedding_profile WHERE singleton') == '1024'
    rows = sql('SELECT count(*) FROM yufeichi_ai.vector_store')
    assert sql("SELECT count(*) FROM yufeichi_ai.vector_store WHERE metadata->>'indexVersion' IS NULL") == '0'
    print('AI_VECTOR_RESTORE_PASS rows=' + rows + ' migrations=2 dimensions=1024; isolated network and owned container removed')
finally:
    if created:
        label = run(['docker', 'inspect', '--format', '{{ index .Config.Labels "yufeichi.ai.restore" }}', name])
        assert label == name
        subprocess.run(['docker', 'rm', '-f', name], check=True, stdout=subprocess.DEVNULL)
