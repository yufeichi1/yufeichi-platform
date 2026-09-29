#!/usr/bin/env python3
"""Explicit production smoke test. Creates a labelled temporary article, then removes it.
Run 'before' and 'after' around a controlled service/container restart. Secrets stay root-only.
"""
import base64
import hashlib
import json
import os
from pathlib import Path
import struct
import sys
import urllib.error
import urllib.request
import zlib

BASE = 'https://yufeichi.com'
STATE = Path('/var/lib/yufeichi/day6-acceptance.json')
if os.geteuid() != 0 or len(sys.argv) != 2 or sys.argv[1] not in ('before', 'after'):
    sys.exit('Usage: sudo python3 acceptance.py before|after')
mode = sys.argv[1]

def request(path, method='GET', body=None, token=None, expected=200, content_type='application/json'):
    headers = {}
    if token:
        headers['Authorization'] = 'Bearer ' + token
    if body is not None:
        headers['Content-Type'] = content_type
        if not isinstance(body, bytes):
            body = json.dumps(body).encode()
    req = urllib.request.Request(BASE + path, data=body, headers=headers, method=method)
    try:
        response = urllib.request.urlopen(req, timeout=30)
    except urllib.error.HTTPError as failure:
        response = failure
    data = response.read()
    assert response.status == expected, f'{method} {path}: expected {expected}, got {response.status}'
    return data, response.headers

def api(path, method='GET', body=None, token=None, expected=200):
    data, headers = request(path, method, body, token, expected)
    assert 'application/json' in headers.get('Content-Type', ''), path
    return json.loads(data).get('data')

def login():
    admin = json.loads(Path('/etc/yufeichi/initial-admin.json').read_text())
    return api('/api/auth/login', 'POST', {'username': admin['username'], 'password': admin['password']})['token']

def png():
    def chunk(kind, data):
        return struct.pack('>I', len(data)) + kind + data + struct.pack('>I', zlib.crc32(kind + data))
    return b'\x89PNG\r\n\x1a\n' + chunk(b'IHDR', struct.pack('>IIBBBBB', 8, 8, 8, 2, 0, 0, 0)) + chunk(b'IDAT', zlib.compress((b'\0' + b'\x40\x80\xc0' * 8) * 8)) + chunk(b'IEND', b'')

if mode == 'before':
    if STATE.exists():
        sys.exit('Acceptance state exists; finish its after phase before creating another probe')
    token = login()
    permissions = api('/api/auth/me', token=token)['permissions']
    assert 'article:publish' in permissions
    boundary = 'yufeichi-day6-multipart'
    payload = (f'--{boundary}\r\nContent-Disposition: form-data; name="bizType"\r\n\r\narticle\r\n'
        f'--{boundary}\r\nContent-Disposition: form-data; name="file"; filename="day6-probe.png"\r\n'
        'Content-Type: image/png\r\n\r\n').encode() + png() + f'\r\n--{boundary}--\r\n'.encode()
    data, _ = request('/api/admin/files/upload', 'POST', payload, token, content_type='multipart/form-data; boundary=' + boundary)
    image = json.loads(data)['data']['fileUrl']
    article = api('/api/admin/articles', 'POST', {'title': 'Day6 部署验收（临时）', 'content': '# 部署验收\n\n![持久化图片](' + image + ')',
        'summary': '自动化部署验证完成后下架删除。', 'tagIds': [], 'coverUrl': image}, token)['id']
    # Save immediately so an interrupted probe can be cleaned up without creating a duplicate.
    state = dict(article=article, image=image, token=token)
    fd = os.open(STATE, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
    with os.fdopen(fd, 'w') as f:
        json.dump(state, f)
    api('/api/articles/' + str(article), expected=404)
    api(f'/api/admin/articles/{article}/publish', 'POST', token=token)
    assert api('/api/articles/' + str(article))['id'] == article
    image_data, image_headers = request(image)
    assert image_headers.get('Content-Type', '').startswith('image/png')
    assert image_headers.get('X-Content-Type-Options') == 'nosniff'
    state['image_sha256'] = hashlib.sha256(image_data).hexdigest()
    api('/api/auth/logout', 'POST', token=token)
    api('/api/auth/me', token=token, expected=401)
    STATE.write_text(json.dumps(state))
    api('/api/admin/articles', expected=401)
    api('/api', expected=404)
    api('/api/articles/9223372036854775807', expected=404)
    request('/uploads/article/00000000-0000-0000-0000-000000000000.png', expected=404)
    request('/uploads/article/.upload-private.tmp', expected=404)
    request('/assets/missing-day6.js', expected=404)
    request('/v3/api-docs', expected=404)
    _, headers = request('/index.html')
    assert 'no-store' in headers.get('Cache-Control', '')
    html, _ = request('/admin/articles/123/edit')
    assert b'<html' in html.lower()
    request('/api/admin/files/upload', 'POST', b'x' * (6 * 1024 * 1024 + 1), expected=413)
    print('PASS: login, permissions, draft isolation, upload, publish, anonymous read, logout revocation, API/static boundaries, SPA and 413')
    print('Probe article ID:', article, 'Image URL:', image)
else:
    state = json.loads(STATE.read_text())
    article, image = state['article'], state['image']
    assert api('/api/articles/' + str(article))['id'] == article
    data, _ = request(image)
    assert hashlib.sha256(data).hexdigest() == state['image_sha256']
    api('/api/auth/me', token=state['token'], expected=401)
    token = login()
    api(f'/api/admin/articles/{article}/unpublish', 'POST', token=token)
    api('/api/articles/' + str(article), expected=404)
    api(f'/api/admin/articles/{article}', 'DELETE', token=token)
    api('/api/articles/' + str(article), expected=404)
    api('/api/auth/logout', 'POST', token=token)
    STATE.unlink()
    print('PASS: database/image/revocation survive restart; probe article unpublished and deleted; sessions revoked')
