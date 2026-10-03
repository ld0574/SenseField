#!/usr/bin/env python3
"""Exercise real loopback HTTPS/WSS with synthetic pixels and mock inference.

Creates temporary TLS credentials and a device token, never contacts GLM, and
never loads game recordings. This is a transport check, not a model/phone SLA.
"""
from __future__ import annotations
import argparse
import asyncio
import base64
import io
import json
from pathlib import Path
import secrets
import socket
import ssl
import subprocess
import tempfile
import threading
import time

from PIL import Image
import httpx
import uvicorn
from websockets.asyncio.client import connect
from mapassist.assistant_gateway.app import create_app
from mapassist.assistant_gateway.asr import MockStreamingRecognizer
from mapassist.assistant_gateway.config import GatewaySettings


class SyntheticVision:
    async def complete(self, **_kwargs):
        return '{"kind":"ui_text","answer":"界面显示开始游戏。","uncertain":false}'
    async def close(self):
        pass


async def exercise(base: str, context: ssl.SSLContext, token: str):
    headers = {"Authorization": "Bearer " + token}
    async with httpx.AsyncClient(base_url=base, verify=context, timeout=5) as client:
        health = await client.get('/health')
        assert health.status_code == 200 and health.json()['mode'] == 'development_mock'
        unauthorized = await client.post('/v1/visual', json={})
        assert unauthorized.status_code == 401
        image = Image.new('RGB', (640, 360), '#102030')
        stream = io.BytesIO(); image.save(stream, 'JPEG')
        payload = dict(session_id='synthetic-transport', generation=1, turn_id='g1-t1', frame_id='7',
                       question='读当前界面的文字', frame_age_ms=20, proactive=False,
                       image_base64=base64.b64encode(stream.getvalue()).decode('ascii'))
        response = await client.post('/v1/visual', json=payload, headers=headers)
        assert response.status_code == 200
        result = response.json()
        assert result['frame_id'] == '7' and result['turn_id'] == 'g1-t1' and not result['uncertain']
    async with connect(base.replace('https:', 'wss:') + '/v1/audio', ssl=context,
                       additional_headers=headers, open_timeout=5) as ws:
        await ws.send(json.dumps(dict(type='start', session_id='synthetic-transport', generation=1, sample_rate=16000)))
        ready = json.loads(await asyncio.wait_for(ws.recv(), 5))
        assert ready['type'] == 'status' and ready['reason'] == 'ready'
        await ws.send(json.dumps(dict(type='speech_start', generation=1, turn_id='g1-t2')))
        await ws.send(bytes(3200))
        await ws.send(json.dumps(dict(type='speech_end', generation=1, turn_id='g1-t2')))
        while True:
            event = json.loads(await asyncio.wait_for(ws.recv(), 5))
            if event['type'] == 'final': break
        assert event['generation'] == 1 and event['turn_id'] == 'g1-t2'
        await ws.send(json.dumps(dict(type='reset', generation=2, turn_id='reset-g2')))
        reset = json.loads(await asyncio.wait_for(ws.recv(), 5))
        assert reset['type'] == 'status' and reset['generation'] == 2 and reset['reason'] == 'reset'
    return dict(schema_version=1, status='passed', transport='real loopback HTTPS/WSS',
                inference='mock adapters only', synthetic_input=True, real_glm=False, real_asr=False,
                checks=['TLS certificate verification', 'bearer authentication', 'visual request/ID/response',
                        'WSS PCM/final', 'generation-advancing reset'])


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--output', type=Path)
    args = parser.parse_args()
    with tempfile.TemporaryDirectory(prefix='sensefield-transport-') as directory:
        folder = Path(directory); cert = folder / 'cert.pem'; key = folder / 'key.pem'
        subprocess.run(['openssl', 'req', '-x509', '-newkey', 'rsa:2048', '-nodes', '-days', '1',
                        '-subj', '/CN=localhost', '-addext', 'subjectAltName=DNS:localhost,IP:127.0.0.1',
                        '-keyout', str(key), '-out', str(cert)], check=True, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
        key.chmod(0o600)
        with socket.socket() as probe:
            probe.bind(('127.0.0.1', 0)); port = probe.getsockname()[1]
        token = secrets.token_urlsafe(32)
        app = create_app(GatewaySettings(device_tokens=(token,), mode='development_mock', require_tls=True),
                         recognizer=MockStreamingRecognizer(), vision_client=SyntheticVision())
        server = uvicorn.Server(uvicorn.Config(app, host='127.0.0.1', port=port,
            ssl_certfile=str(cert), ssl_keyfile=str(key), log_level='critical', access_log=False))
        thread = threading.Thread(target=server.run, daemon=True); thread.start()
        try:
            deadline = time.monotonic() + 10
            while not server.started and thread.is_alive() and time.monotonic() < deadline: time.sleep(.05)
            if not server.started: raise RuntimeError('Local TLS service failed to start')
            context = ssl.create_default_context(cafile=str(cert))
            report = asyncio.run(exercise(f'https://localhost:{port}', context, token))
            text = json.dumps(report, ensure_ascii=False, indent=2) + '\n'
            if args.output:
                args.output.parent.mkdir(parents=True, exist_ok=True); args.output.write_text(text, encoding='utf-8')
            print(text, end='')
        finally:
            server.should_exit = True; thread.join(5)


if __name__ == '__main__':
    main()
