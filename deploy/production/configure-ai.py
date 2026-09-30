#!/usr/bin/env python3
"""Root-only configuration import over trusted SSH. Never print secret values."""
import datetime
import json
import os
from pathlib import Path
import secrets
import shutil
import sys

if os.geteuid() != 0 or len(sys.argv) != 2:
    sys.exit('Usage: sudo python3 configure-ai.py <root-protected-provider-json>')
payload = Path(sys.argv[1])
if payload.stat().st_mode & 0o077:
    sys.exit('Provider input must have mode 600')
names = {'AI_API_KEY', 'AI_PROVIDER_BASE_URL', 'AI_CHAT_MODEL', 'AI_EMBEDDING_API_KEY',
         'AI_EMBEDDING_BASE_URL', 'AI_EMBEDDING_MODEL', 'AI_EMBEDDING_DIMENSIONS'}
values = json.loads(payload.read_text())
if set(values) != names or any(not isinstance(v, str) or not v.strip() or any(c in v for c in '\r\n\0') for v in values.values()):
    sys.exit('Invalid provider input fields')
if values['AI_EMBEDDING_DIMENSIONS'] != '1024':
    sys.exit('This release requires 1024 dimensions')
for name in ('AI_PROVIDER_BASE_URL', 'AI_EMBEDDING_BASE_URL'):
    from urllib.parse import urlparse
    url = urlparse(values[name])
    if url.scheme != 'https' or not url.hostname or url.username or url.password or url.query or url.fragment:
        sys.exit('Invalid provider URL')
secret = Path('/etc/yufeichi/secrets/ai-vector-password')
if not secret.exists():
    fd = os.open(secret, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o444)
    with os.fdopen(fd, 'w') as f:
        f.write(secrets.token_urlsafe(32))
values.update(AI_ENABLED='true', AI_VECTOR_ENABLED='true',
              AI_VECTOR_URL='jdbc:postgresql://127.0.0.1:15432/yufeichi_ai',
              AI_VECTOR_USERNAME='ai_vector', AI_VECTOR_PASSWORD=secret.read_text().strip(),
              AI_REQUEST_TIMEOUT_SECONDS='60', AI_HEARTBEAT_SECONDS='1',
              AI_MAX_CONCURRENT_REQUESTS='2', AI_DAILY_REQUEST_LIMIT='100')
environment = Path('/etc/yufeichi/backend.env')
backup = Path('/var/backups/yufeichi') / ('backend-env-before-ai-' + datetime.datetime.now(datetime.timezone.utc).strftime('%Y%m%dT%H%M%SZ'))
shutil.copy2(environment, backup)
os.chmod(backup, 0o600)
lines = [line for line in environment.read_text().splitlines() if line.split('=', 1)[0] not in values]
lines.extend(name + '=' + json.dumps(value, ensure_ascii=False) for name, value in sorted(values.items()))
temporary = environment.with_suffix('.ai-next')
fd = os.open(temporary, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
with os.fdopen(fd, 'w') as f:
    f.write('\n'.join(lines) + '\n')
os.replace(temporary, environment)
payload.unlink()
print('AI_CONFIGURED: protected environment updated; service restart required')
