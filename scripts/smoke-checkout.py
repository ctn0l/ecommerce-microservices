#!/usr/bin/env python3
"""Run an isolated checkout smoke test against three packaged services and fresh PostgreSQL containers."""
import json
import os
from pathlib import Path
import socket
import subprocess
import tempfile
import time
import urllib.error
import urllib.request
import uuid

ROOT = Path(__file__).resolve().parents[1]
SERVICES = ('user', 'product', 'order')


def command(*args):
    return subprocess.check_output(args, text=True).strip()


def free_port():
    with socket.socket() as sock:
        sock.bind(('127.0.0.1', 0))
        return sock.getsockname()[1]


def request(port, method, path, data=None, headers=None):
    req = urllib.request.Request(
        f'http://127.0.0.1:{port}{path}', method=method,
        data=None if data is None else json.dumps(data).encode(),
        headers={'Content-Type': 'application/json', **(headers or {})},
    )
    try:
        response = urllib.request.urlopen(req, timeout=20)
    except urllib.error.HTTPError as error:
        response = error
    with response:
        body = response.read()
        return response.status, json.loads(body) if body else None


def eventually(check, description, timeout=60):
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        try:
            if check():
                return
        except (OSError, urllib.error.URLError):
            pass
        time.sleep(0.5)
    raise AssertionError(f'Timed out: {description}')


def main():
    jars = {service: ROOT / f'{service}-service/target/{service}-service-0.0.1-SNAPSHOT.jar'
            for service in SERVICES}
    for jar in jars.values():
        if not jar.exists():
            raise SystemExit(f'Package the services first with ./mvnw package: missing {jar}')
    processes, containers, streams, environments = {}, [], [], {}
    ports = {service: free_port() for service in SERVICES}
    logs = Path(tempfile.mkdtemp(prefix='checkout-smoke-'))
    print(f'Isolated smoke-test logs: {logs}', flush=True)

    def start(service):
        stream = (logs / f'{service}.log').open('a')
        streams.append(stream)
        processes[service] = subprocess.Popen(
            ['java', '-jar', str(jars[service])], env=environments[service], stdout=stream, stderr=stream)

    def stop(service):
        process = processes.pop(service, None)
        if process is not None:
            process.terminate()
            try:
                process.wait(timeout=15)
            except subprocess.TimeoutExpired:
                process.kill()
                process.wait(timeout=5)

    def healthy(service):
        if processes[service].poll() is not None:
            raise RuntimeError(f'{service} exited; see {logs}')
        return request(ports[service], 'GET', '/actuator/health')[0] == 200

    try:
        for service in SERVICES:
            name = f'checkout-smoke-{service}-{uuid.uuid4().hex[:10]}'
            command('docker', 'run', '-d', '--name', name, '-p', '127.0.0.1::5432',
                    '-e', 'POSTGRES_DB=smoke', '-e', 'POSTGRES_USER=smoke',
                    '-e', 'POSTGRES_PASSWORD=smoke_test_only', 'postgres:18-alpine')
            containers.append(name)
            db_port = command('docker', 'port', name, '5432/tcp').rsplit(':', 1)[1]
            environments[service] = {
                **os.environ,
                'SERVER_PORT': str(ports[service]),
                'SPRING_DATASOURCE_URL': f'jdbc:postgresql://127.0.0.1:{db_port}/smoke',
                'SPRING_DATASOURCE_USERNAME': 'smoke',
                'SPRING_DATASOURCE_PASSWORD': 'smoke_test_only',
                'JPA_SHOW_SQL': 'false',
                'USER_SERVICE_URL': f'http://127.0.0.1:{ports["user"]}',
                'PRODUCT_SERVICE_URL': f'http://127.0.0.1:{ports["product"]}',
                'CHECKOUT_RECOVERY_ENABLED': 'true',
                'CHECKOUT_RECOVERY_DELAY': '500',
                'SERVICE_CONNECT_TIMEOUT': '1s',
                'SERVICE_READ_TIMEOUT': '2s',
            }
            eventually(lambda n=name: subprocess.run(
                ['docker', 'exec', n, 'pg_isready', '-U', 'smoke', '-d', 'smoke'],
                stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL).returncode == 0,
                f'{service} database ready')
            start(service)
        for service in SERVICES:
            eventually(lambda s=service: healthy(s), f'{service} health')

        status, user = request(ports['user'], 'POST', '/api/users', {
            'firstName': 'Smoke', 'lastName': 'Test', 'email': 'smoke@example.test'})
        assert status == 201, (status, user)
        product_body = {'name': 'Keyboard', 'price': 39.99, 'stockQuantity': 5, 'category': 'Test'}
        status, product = request(ports['product'], 'POST', '/api/products', product_body)
        assert status == 201, (status, product)
        headers = {'X-User-ID': str(user['id'])}
        cart_body = {'productId': product['id'], 'quantity': 2}
        assert request(ports['order'], 'POST', '/api/cart', cart_body, headers)[0] == 201
        key = str(uuid.uuid4())
        checkout_headers = {**headers, 'Idempotency-Key': key}
        status, order = request(ports['order'], 'POST', '/api/orders', headers=checkout_headers)
        assert status == 201 and order['status'] == 'CONFIRMED', (status, order)
        assert order['totalAmount'] == 79.98
        replay_status, replay = request(ports['order'], 'POST', '/api/orders', headers=checkout_headers)
        assert replay_status == 201 and replay['id'] == order['id']
        assert request(ports['product'], 'GET', f'/api/products/{product["id"]}')[1]['stockQuantity'] == 3
        assert request(ports['order'], 'GET', '/api/cart', headers=headers)[1] == []
        print('PASS: checkout, HTTP contracts, price snapshots and idempotent replay', flush=True)

        assert request(ports['order'], 'POST', '/api/cart', cart_body, headers)[0] == 201
        stop('product')
        pending_headers = {**headers, 'Idempotency-Key': str(uuid.uuid4())}
        status, pending = request(ports['order'], 'POST', '/api/orders', headers=pending_headers)
        assert status == 202 and pending['phase'] == 'RESERVING', (status, pending)
        assert request(ports['order'], 'DELETE', f'/api/cart/items/{product["id"]}', headers=headers)[0] == 409
        # Restart both sides: recovery must use persisted state, not in-memory state.
        stop('order')
        start('product')
        eventually(lambda: healthy('product'), 'restarted product health')
        start('order')
        eventually(lambda: healthy('order'), 'restarted order health')
        eventually(lambda: request(ports['order'], 'GET', f'/api/orders/{pending["id"]}', headers=headers)[1]['status'] == 'CONFIRMED',
                   'durable checkout recovery')
        assert request(ports['product'], 'GET', f'/api/products/{product["id"]}')[1]['stockQuantity'] == 1
        assert request(ports['order'], 'POST', '/api/orders', headers=pending_headers)[1]['id'] == pending['id']
        print('PASS: product outage, cart freeze and recovery after restarting order-service', flush=True)

        assert request(ports['order'], 'POST', '/api/cart', {**cart_body, 'quantity': 1}, headers)[0] == 201
        assert request(ports['product'], 'PUT', f'/api/products/{product["id"]}', {**product_body, 'stockQuantity': 0})[0] == 200
        status, rejected = request(ports['order'], 'POST', '/api/orders',
                                   headers={**headers, 'Idempotency-Key': str(uuid.uuid4())})
        assert status == 409 and rejected['status'] == 'CANCELLED', (status, rejected)
        assert len(request(ports['order'], 'GET', '/api/cart', headers=headers)[1]) == 1
        assert request(ports['order'], 'DELETE', f'/api/cart/items/{product["id"]}', headers=headers)[0] == 204
        print('PASS: insufficient stock, compensation and retained editable cart', flush=True)
    finally:
        for service in list(processes):
            stop(service)
        for stream in streams:
            stream.close()
        for container in containers:
            subprocess.run(['docker', 'rm', '-f', '-v', container], check=False,
                           stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)


if __name__ == '__main__':
    main()
