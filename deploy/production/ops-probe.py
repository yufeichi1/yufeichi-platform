#!/usr/bin/env python3
"""Explicit labelled production fixtures for backup/restore and recreation acceptance.
Secrets are read only from root-owned files, never CLI values or stdout.
"""
import argparse
import hashlib
import json
import os
from pathlib import Path
import struct
import time
import urllib.error
import urllib.request
import urllib.parse
import uuid
import zlib

STATE = Path('/var/lib/yufeichi-ops/probe.json')
ADMIN = Path('/etc/yufeichi/acceptance-admin.json')

def private_json(path):
    stat = path.stat()
    assert stat.st_uid == 0 and stat.st_mode & 0o077 == 0, 'Expected root-only JSON file'
    return json.loads(path.read_text())

class Client:
    def __init__(self, base):
        target = urllib.parse.urlsplit(base)
        assert not target.username and not target.password and not target.query and not target.fragment
        assert target.path in ('', '/')
        assert (target.scheme == 'https' and target.hostname == 'yufeichi.com' and target.port in (None, 443)) or (
            target.scheme == 'http' and target.hostname == '127.0.0.1' and target.port is not None), 'Only production or isolated loopback endpoints are allowed'
        self.base = base.rstrip('/')
    def raw(self, path, method='GET', body=None, token=None, expected=200, content_type='application/json'):
        headers = {}
        if token:
            headers['Authorization'] = 'Bearer ' + token
        if body is not None:
            headers['Content-Type'] = content_type
            if not isinstance(body, bytes):
                body = json.dumps(body).encode()
        req = urllib.request.Request(self.base + path, data=body, headers=headers, method=method)
        try:
            response = urllib.request.urlopen(req, timeout=40)
        except urllib.error.HTTPError as exc:
            response = exc
        data = response.read()
        assert response.status == expected, f'{method} {path}: {response.status}, expected {expected}'
        return data, response.headers
    def api(self, path, method='GET', body=None, token=None, expected=200):
        data, headers = self.raw(path, method, body, token, expected)
        assert 'application/json' in headers.get('Content-Type', ''), path
        result = json.loads(data)
        if expected == 200:
            assert result['code'] == 0, path
        return result.get('data')
    def login(self):
        admin = private_json(ADMIN)
        return self.api('/api/auth/login', 'POST', {'username': admin['username'], 'password': admin['password']})['token']
    def upload(self, token):
        def chunk(kind, data):
            return struct.pack('>I', len(data)) + kind + data + struct.pack('>I', zlib.crc32(kind + data))
        png = b'\x89PNG\r\n\x1a\n' + chunk(b'IHDR', struct.pack('>IIBBBBB', 8, 8, 8, 2, 0, 0, 0)) + chunk(b'IDAT', zlib.compress((b'\0' + b'\x40\x80\xc0' * 8) * 8)) + chunk(b'IEND', b'')
        boundary = 'yufeichi-ops-' + uuid.uuid4().hex
        body = (f'--{boundary}\r\nContent-Disposition: form-data; name="bizType"\r\n\r\narticle\r\n'
            f'--{boundary}\r\nContent-Disposition: form-data; name="file"; filename="ops-probe.png"\r\nContent-Type: image/png\r\n\r\n').encode() + png + f'\r\n--{boundary}--\r\n'.encode()
        data, _ = self.raw('/api/admin/files/upload', 'POST', body, token, content_type='multipart/form-data; boundary=' + boundary)
        return json.loads(data)['data']['fileUrl']

def save(state):
    fd = os.open(STATE, os.O_WRONLY | os.O_CREAT | os.O_TRUNC, 0o600)
    with os.fdopen(fd, 'w') as file:
        json.dump(state, file)

def check(client, state, revoked=False, write=False, upload=False):
    token = client.login()
    try:
        me = client.api('/api/auth/me', token=token)
        assert 'article:publish' in me['permissions'] and me['roles']
        article = client.api('/api/articles/' + str(state['article']))
        assert article['categoryId'] == state['category'] and article['tagIds'] == [state['tag']]
        assert article['coverUrl'] == state['image'] and state['marker'] in article['content']
        project = client.api('/api/projects/' + str(state['project']))
        assert state['marker'] in project['description'] and project['coverUrl'] == state['image']
        data, headers = client.raw(state['image'])
        assert hashlib.sha256(data).hexdigest() == state['image_sha256']
        assert headers.get('Content-Type', '').startswith('image/png')
        if revoked:
            assert time.time() - state['revoked_at'] < 30 * 60, 'Refresh probe: old token could already be expired'
            client.api('/api/auth/me', token=state['revoked_token'], expected=401)
        if write:
            body = state['article_body'].copy()
            body['summary'] = 'V1.0 recovery/rollback real write verification'
            client.api('/api/admin/articles/' + str(state['article']), 'PUT', body, token)
            assert client.api('/api/articles/' + str(state['article']))['summary'] == body['summary']
            pbody = state['project_body'].copy()
            pbody['sortOrder'] = 97
            client.api('/api/admin/projects/' + str(state['project']), 'PUT', pbody, token)
            assert client.api('/api/projects/' + str(state['project']))['sortOrder'] == 97
        if upload:
            image = client.upload(token)
            data, _ = client.raw(image)
            assert data.startswith(b'\x89PNG'), 'Uploaded image not readable'
        print('OPS_CHECK_PASS: account/roles/permissions, published article/category/tag, project, image hash'
              + ('; unexpired revoked token 401' if revoked else '')
              + ('; article/project API writes' if write else '') + ('; fresh upload readable' if upload else ''), flush=True)
    finally:
        client.api('/api/auth/logout', 'POST', token=token)

def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('mode', choices=['prepare', 'check', 'cleanup'])
    parser.add_argument('--base', default='https://yufeichi.com')
    parser.add_argument('--revoked', action='store_true')
    parser.add_argument('--write', action='store_true')
    parser.add_argument('--upload', action='store_true')
    args = parser.parse_args()
    assert os.geteuid() == 0
    client = Client(args.base)
    if args.mode == 'prepare':
        STATE.parent.mkdir(mode=0o700, exist_ok=True)
        assert not STATE.parent.is_symlink()
        parent_stat = STATE.parent.stat()
        assert parent_stat.st_uid == 0 and parent_stat.st_mode & 0o777 == 0o700
        assert not STATE.exists(), 'Finish existing probe before creating another'
        token = client.login()
        marker = 'v1-ops-' + uuid.uuid4().hex[:12]
        state = {'marker': marker}
        save(state)
        try:
            state['category'] = client.api('/api/admin/categories', 'POST', {'name': marker, 'slug': marker, 'status': 1}, token)['id']; save(state)
            state['tag'] = client.api('/api/admin/tags', 'POST', {'name': marker, 'slug': marker, 'status': 1}, token)['id']; save(state)
            state['image'] = client.upload(token); save(state)
            state['image_sha256'] = hashlib.sha256(client.raw(state['image'])[0]).hexdigest()
            state['article_body'] = {'title': 'V1.0 运维验收（临时）', 'content': '# ' + marker + '\n\n![image](' + state['image'] + ')',
                'summary': '验收完成后删除', 'categoryId': state['category'], 'tagIds': [state['tag']], 'coverUrl': state['image']}
            state['article'] = client.api('/api/admin/articles', 'POST', state['article_body'], token)['id']; save(state)
            client.api('/api/articles/' + str(state['article']), expected=404)
            client.api('/api/admin/articles/' + str(state['article']) + '/publish', 'POST', token=token)
            state['project_body'] = {'name': 'V1.0 运维验收（临时）', 'description': marker, 'coverUrl': state['image'], 'techStack': 'Java 21', 'sortOrder': 99, 'status': 1}
            state['project'] = client.api('/api/admin/projects', 'POST', state['project_body'], token)['id']; save(state)
            client.api('/api/auth/logout', 'POST', token=token)
            state['revoked_token'] = token
            state['revoked_at'] = time.time()
            save(state)
            print('OPS_PREPARE_PASS; labelled fixtures recorded in root-only state', flush=True)
            check(client, state, revoked=True)
        except BaseException:
            print('Probe interrupted; run cleanup before preparing again', flush=True)
            raise
    elif args.mode == 'check':
        check(client, private_json(STATE), args.revoked, args.write, args.upload)
    else:
        state = private_json(STATE)
        token = client.login()
        try:
            if 'article' in state:
                client.api('/api/admin/articles/' + str(state['article']) + '/unpublish', 'POST', token=token)
                client.api('/api/admin/articles/' + str(state['article']), 'DELETE', token=token)
                client.api('/api/articles/' + str(state['article']), expected=404)
            if 'project' in state:
                client.api('/api/admin/projects/' + str(state['project']), 'DELETE', token=token)
                client.api('/api/projects/' + str(state['project']), expected=404)
            for singular, plural in [('category', 'categories'), ('tag', 'tags')]:
                if singular in state:
                    client.api('/api/admin/' + plural + '/' + str(state[singular]), 'DELETE', token=token)
            STATE.unlink()
            print('OPS_CLEANUP_PASS: only labelled fixtures removed; upload evidence retained', flush=True)
        finally:
            client.api('/api/auth/logout', 'POST', token=token)

if __name__ == '__main__':
    main()
