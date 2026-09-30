#!/usr/bin/env python3
"""Opt-in HTTPS smoke: no business content writes; bounded paid summary and index requests."""
import json
import os
from pathlib import Path
import time
import urllib.error
import urllib.request

if os.geteuid() != 0:
    raise SystemExit('Run as root with temporary protected administrator credentials')
BASE = 'https://yufeichi.com'
credential = Path('/etc/yufeichi/acceptance-admin.json')
if credential.stat().st_mode & 0o077:
    raise SystemExit('Administrator file must be mode 600')

def request(path, method='GET', body=None, token=None, expected=200):
    headers = {'Content-Type': 'application/json'}
    if token:
        headers['Authorization'] = 'Bearer ' + token
    req = urllib.request.Request(BASE + path, data=json.dumps(body).encode() if body is not None else None,
                                 headers=headers, method=method)
    try:
        response = urllib.request.urlopen(req, timeout=75)
    except urllib.error.HTTPError as failure:
        response = failure
    if response.status != expected:
        raise RuntimeError('Unexpected HTTP status for ' + path + ': ' + str(response.status))
    return response

def api(path, method='GET', body=None, token=None, expected=200):
    with request(path, method, body, token, expected) as response:
        return json.load(response).get('data')

try:
    for path in ('/api/health', '/api/articles', '/api/projects'):
        api(path)
    admin = json.loads(credential.read_text())
    token = api('/api/auth/login', 'POST', admin)['token']
    api('/api/auth/me', token=token)
    api('/api/admin/ai/knowledge', expected=401)
    api('/api/admin/ai/summary', 'POST', {'content': '权限检查'}, expected=401)
    api('/api/admin/ai/summary', 'POST', {}, token=token, expected=400)
    stream_started = time.monotonic()
    with request('/api/admin/ai/summary/stream', 'POST', {'content': 'Java 21 和 Spring Boot 用于开发本站后端。MySQL 保存业务数据，Redis 处理安全会话。'}, token) as response:
        assert 'text/event-stream' in response.headers.get('Content-Type', '')
        events, delta_count, first_frame, started = [], 0, None, stream_started
        for raw in response:
            line = raw.decode('utf-8').strip()
            if line.startswith('event:'):
                if first_frame is None:
                    first_frame = time.monotonic() - started
                events.append(line[6:].strip())
                delta_count += line == 'event:delta'
        assert events[0] == 'meta' and 'done' in events and 'error' not in events and delta_count > 0
        assert first_frame < 3, 'First SSE frame was buffered'
        print('HTTPS_SUMMARY_SSE_PASS frames=' + str(len(events)) + ' delta=' + str(delta_count))
    def await_job(job_id):
        for _ in range(170):
            state = api('/api/admin/ai/knowledge', token=token)
            job = next(j for j in state['jobs'] if j['id'] == job_id)
            if job['status'] in ('SUCCEEDED', 'FAILED'):
                assert job['status'] == 'SUCCEEDED', 'Index failed: ' + str(job['error_code'])
                return state, job
            time.sleep(2)
        raise RuntimeError('Index smoke timed out')
    state, job = await_job(api('/api/admin/ai/knowledge/reindex', 'POST', token=token)['jobId'])
    version = state['state']['active_version']
    state2, job2 = await_job(api('/api/admin/ai/knowledge/reindex', 'POST', token=token)['jobId'])
    assert state2['state']['active_version'] == version and job2['chunk_count'] == job['chunk_count']
    print('HTTPS_INDEX_PASS version=' + str(version) + ' sources=' + str(job['source_count']) + ' chunks=' + str(job['chunk_count']))
    for path in ('/api/health', '/api/articles', '/api/projects'):
        api(path)
    api('/api/auth/logout', 'POST', token=token)
    print('AI_PRODUCTION_ACCEPTANCE_PASS')
finally:
    # This is only the dedicated temporary verification credential, never the user's original file.
    credential.unlink(missing_ok=True)
