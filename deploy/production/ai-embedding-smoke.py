#!/usr/bin/env python3
"""One explicit paid embedding call from the server. No business/vector writes or private content."""
import json
import math
import os
from pathlib import Path
import urllib.request

if os.geteuid() != 0:
    raise SystemExit('Run as root')
names = {'AI_EMBEDDING_BASE_URL', 'AI_EMBEDDING_API_KEY', 'AI_EMBEDDING_MODEL', 'AI_EMBEDDING_DIMENSIONS'}
values = {}
for line in Path('/etc/yufeichi/backend.env').read_text().splitlines():
    name, separator, value = line.partition('=')
    if separator and name in names:
        values[name] = json.loads(value)
assert values.keys() == names
dimensions = int(values['AI_EMBEDDING_DIMENSIONS'])
base = values['AI_EMBEDDING_BASE_URL'].rstrip('/')
body = {'model': values['AI_EMBEDDING_MODEL'], 'dimensions': dimensions, 'encoding_format': 'float',
        'input': ['公开部署验收文本：Java21和Spring Boot开发网站后端，MySQL保存业务内容。']}
request = urllib.request.Request(base + ('/embeddings' if base.endswith('/v1') else '/v1/embeddings'),
    data=json.dumps(body).encode(), headers={'Content-Type': 'application/json', 'Authorization': 'Bearer ' + values['AI_EMBEDDING_API_KEY']})
try:
    with urllib.request.urlopen(request, timeout=60) as response:
        data = json.load(response)['data']
    assert len(data) == 1 and data[0]['index'] == 0
    vector = data[0]['embedding']
    assert len(vector) == dimensions == 1024 and all(math.isfinite(n) for n in vector)
    assert sum(n * n for n in vector) > 0
    print('SERVER_EMBEDDING_SMOKE_PASS dimensions=1024 calls=1 no_database_writes')
except Exception:
    raise SystemExit('SERVER_EMBEDDING_SMOKE_FAILED: provider details suppressed')
