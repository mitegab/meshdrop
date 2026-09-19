#!/usr/bin/env python3
"""CI subprocess interoperability + a genuinely killed/restarted receiver, not a mock."""
import json
import os
from pathlib import Path
import socket
import subprocess
import sys
import tempfile
import threading

CLI = str(Path(sys.argv[1] if len(sys.argv) > 1 else "cli/build/install/meshdrop/bin/meshdrop").resolve())


def run(home, *args, ok=True):
    result = subprocess.run([CLI, *map(str, args), "--home", str(home)], capture_output=True, text=True, timeout=90)
    if ok and result.returncode:
        raise AssertionError(result.stdout + result.stderr)
    return result


def receive(home, out):
    proc = subprocess.Popen([CLI, "receive", "--home", str(home), "--out", str(out), "--port", "0", "--accept-verified"],
                            stdout=subprocess.PIPE, stderr=subprocess.DEVNULL, text=True, bufsize=1)
    line = proc.stdout.readline()
    assert line, "Receiver did not start"
    return proc, json.loads(line)["port"]


def cut_upload_proxy(destination_port):
    listener = socket.socket()
    listener.bind(("127.0.0.1", 0))
    listener.listen()
    port = listener.getsockname()[1]

    def exchange(client):
        try:
            with client, socket.create_connection(("127.0.0.1", destination_port)) as remote:
                client.settimeout(20)
                remote.settimeout(20)
                header = bytearray()
                while not header.endswith(b"\r\n\r\n"):
                    byte = client.recv(1)
                    if not byte:
                        return
                    header += byte
                    assert len(header) < 32768
                remote.sendall(header)
                length = next((int(line.split(b":", 1)[1]) for line in header.split(b"\r\n") if line.lower().startswith(b"content-length:")), 0)
                if header.startswith(b"PUT "):
                    length = min(length, 150000)  # two authenticated GCM frames + a torn third frame
                while length:
                    block = client.recv(min(length, 65536))
                    if not block:
                        break
                    remote.sendall(block)
                    length -= len(block)
                remote.shutdown(socket.SHUT_WR)
                while True:
                    block = remote.recv(65536)
                    if not block:
                        break
                    client.sendall(block)
        except (OSError, AssertionError):
            pass  # the intentionally truncated connection is expected to reset

    def accept():
        try:
            while True:
                client, _ = listener.accept()
                threading.Thread(target=exchange, args=(client,), daemon=True).start()
        except OSError:
            pass

    threading.Thread(target=accept, daemon=True).start()
    return listener, port


with tempfile.TemporaryDirectory(prefix="meshdrop-cli-") as directory:
    root = Path(directory)
    source, destination, out = root / "source", root / "destination", root / "downloads"
    sender_identity = json.loads(run(source, "identity").stdout)
    receiver_identity = json.loads(run(destination, "identity").stdout)
    run(source, "trust", receiver_identity["pairingCode"])
    run(destination, "trust", sender_identity["pairingCode"])
    payload = root / "payload.bin"
    payload.write_bytes(bytes(range(251)) * 24000)
    receiver = proxy = None
    try:
        receiver, port = receive(destination, out)
        proxy, proxy_port = cut_upload_proxy(port)
        interrupted = run(source, "send", "--host", "127.0.0.1", "--port", proxy_port, payload, ok=False)
        assert interrupted.returncode != 0, "Truncated upload was reported successful"
        queue = run(source, "queue").stdout.strip()
        batch = queue.split("\t")[0]
        assert batch and "Retry" in queue, queue
        parts = list((destination / "incoming").glob("*.part"))
        assert parts and 0 < parts[0].stat().st_size < payload.stat().st_size, "No authenticated durable prefix"
        assert not list(out.glob("payload.bin")), "Partial file was published"
        proxy.close()
        receiver.kill()  # no shutdown/finally; only the persisted journal can recover it
        receiver.wait(timeout=10)
        receiver, port = receive(destination, out)
        resumed = run(source, "retry", batch, "--host", "127.0.0.1", "--port", port)
        assert "destination confirmed integrity" in resumed.stdout, resumed.stdout
        assert (out / "payload.bin").read_bytes() == payload.read_bytes()
        assert run(source, "queue").stdout.strip() == "", "Completed queue entry survived"
        assert len(list(out.glob("*.bin"))) == 1, "Duplicate publication"
        print("CLI smoke passed: encrypted desktop exchange, killed receiver, fresh-key prefix resume, exact content, durable queue cleanup")
    finally:
        if proxy:
            proxy.close()
        if receiver:
            receiver.terminate()
            receiver.wait(timeout=10)
