#!/usr/bin/env python3
"""Package existing verified artifacts only; never commits, deploys, or reads private config files."""
import hashlib
import io
import json
import os
from pathlib import Path
import re
import subprocess
import zipfile

ROOT = Path(__file__).resolve().parents[1]
os.chdir(ROOT)
def sha(value): return hashlib.sha256(value).hexdigest()
def git(*args): return subprocess.check_output(['git', *args])
def require(condition, message):
    if not condition: raise SystemExit(message)

require('BUILD SUCCESS' in Path('ai-day12-final-full.log').read_text(errors='replace'), 'Complete clean verify first')
require('ISOLATED_ROLLBACK_PASS' in Path('ai-day14-rollback.log').read_text(errors='replace'), 'Complete old-JAR/new-schema rehearsal first')
require(re.search(r'47 passed', Path('ai-day14-nginx-browser.log').read_text(errors='replace')), 'Complete all 47 Nginx browser tests first')
evaluation = json.loads(Path('docs/ai/evidence/day13-evaluation-final.json').read_text(encoding='utf-8'))
runtime = json.loads(Path('docs/ai/evidence/day14-runtime.json').read_text(encoding='utf-8'))
restore = json.loads(Path('docs/ai/evidence/day14-backup-restore.json').read_text(encoding='utf-8'))
demo = json.loads(Path('docs/ai/evidence/day14-demo.json').read_text(encoding='utf-8'))
require(restore.get('passed') is True and demo.get('production') is False and demo.get('agent') == 'search-detail-answer', 'Complete real demo and backup restore first')
require(len(evaluation['results']) == 60 and all(s['factsAndCitationPass'] >= 15 and s['retrievalHits'] >= 15
        and s['safeNoAnswer'] == 12 and s['errors'] == 0 and s['privateLeaks'] == 0 for s in evaluation['summaries']), 'Evaluation gates not met')
forbidden = [os.environ.get(n, '').encode() for n in ('AI_API_KEY', 'AI_EMBEDDING_API_KEY', 'AI_EMBEDDING_BASE_URL')]
require(all(forbidden), 'Load protected model variables into packaging process for exact-value scanning; no calls are made')
def scan(name, value):
    require(not any(secret in value for secret in forbidden), 'Protected configuration found in ' + name)
    require(not re.search(rb'-----BEGIN (?:RSA |OPENSSH |EC )?PRIVATE KEY-----', value), 'Private key found in ' + name)

files = {}
jar = Path('yufeichi-server/target/yufeichi-server-0.0.1-SNAPSHOT.jar')
files['backend/yufeichi-server.jar'] = jar.read_bytes()
require(sha(files['backend/yufeichi-server.jar']) == runtime['jarSha256'], 'JAR differs from tested deployment')
require(sha(Path('yufeichi-web/dist/index.html').read_bytes()) == runtime['frontendIndexSha256'], 'Frontend differs from tested deployment')
files['backend/.env.example'] = Path('yufeichi-server/.env.example').read_bytes()
scan('backend/.env.example', files['backend/.env.example'])
dataset_path = 'yufeichi-server/src/test/resources/ai-eval-v1.json'
files[dataset_path] = Path(dataset_path).read_bytes()
scan(dataset_path, files[dataset_path])
with zipfile.ZipFile(io.BytesIO(files['backend/yufeichi-server.jar'])) as archive:
    for name in archive.namelist():
        if name.startswith('BOOT-INF/classes/') and not name.endswith('/'): scan(name, archive.read(name))
for directory, prefix in [('yufeichi-web/dist', 'frontend'), ('docs/ai', 'docs/ai'), ('deploy/production', 'deploy')]:
    for path in sorted(Path(directory).rglob('*')):
        if path.is_file():
            require(path.suffix.lower() not in ('.env', '.key', '.pem', '.p12', '.pfx'), 'Unexpected private file')
            name = prefix + '/' + path.relative_to(directory).as_posix(); data = path.read_bytes(); scan(name, data); files[name] = data

source_files = git('ls-files', '-z', '--cached', '--others', '--exclude-standard').decode().split('\0')
source_manifest = {}
for name in source_files:
    path = Path(name)
    if not path.is_file() or '/.vscode/' in name: continue
    if (name.startswith(('yufeichi-server/', 'yufeichi-web/', 'scripts/', 'deploy/')) or name == '.gitignore'):
        data = path.read_bytes(); scan(name, data); source_manifest[name] = sha(data)
source_hash = sha(json.dumps(source_manifest, sort_keys=True).encode())
manifest = dict(kind='local-reviewable-candidate-not-a-git-release-or-production-deployment', clientDate='2026-11-05',
        baseHead=git('rev-parse', 'HEAD').decode().strip(), branch=git('branch', '--show-current').decode().strip(),
        dirty=True, sourceTreeSha256=source_hash, java=21, jarOriginalName=jar.name, entrypoint='backend/yufeichi-server.jar',
        defaults=dict(AI_ENABLED=False, AI_KNOWLEDGE_ENABLED=False, AI_PUBLIC_ENABLED=False, AI_TOOLS_ENABLED=False, AI_AGENT_ENABLED=False, AI_INDEX_SYNC_ENABLED=False),
        files={n:sha(d) for n,d in files.items()})
files['manifest.json'] = json.dumps(manifest, ensure_ascii=False, indent=2).encode()
output = ROOT / 'tmp' / ('ai-day14-candidate-' + source_hash[:12] + '.zip'); output.parent.mkdir(exist_ok=True)
require(not output.exists(), 'Candidate already exists; preserve it and use a changed source version')
with zipfile.ZipFile(output, 'x', compression=zipfile.ZIP_DEFLATED) as archive:
    for name, data in files.items(): archive.writestr(name, data)
with zipfile.ZipFile(output) as archive:
    require(archive.testzip() is None, 'Archive integrity check failed')
    for name, expected in manifest['files'].items(): require(sha(archive.read(name)) == expected, 'Archive hash mismatch')
checksum = sha(output.read_bytes()); output.with_suffix('.zip.sha256').write_text(checksum + '  ' + output.name + '\n')
print(json.dumps(dict(path=str(output), sha256=checksum, jarSha256=sha(jar.read_bytes()), sourceTreeSha256=source_hash, files=len(files)), indent=2))
