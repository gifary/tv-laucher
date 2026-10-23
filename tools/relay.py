#!/usr/bin/env python3
"""TCP relay: 127.0.0.1:15555 -> <tv ip>:5555

Use when the agent's sandbox cannot reach the LAN (macOS Local Network
privacy). Run this in a normal Terminal window and leave it running:

    python3 tools/relay.py 192.168.0.10

Then the agent talks to 127.0.0.1:15555 as if it were the TV.

ADB opens several short-lived connections, so every accepted socket gets
its own thread and its own upstream connection.
"""

import argparse
import socket
import sys
import threading

BUF = 65536


def pipe(src: socket.socket, dst: socket.socket) -> None:
    try:
        while True:
            data = src.recv(BUF)
            if not data:
                break
            dst.sendall(data)
    except OSError:
        pass
    finally:
        try:
            dst.shutdown(socket.SHUT_WR)
        except OSError:
            pass


def handle(client: socket.socket, target: tuple[str, int]) -> None:
    try:
        upstream = socket.create_connection(target, timeout=10)
    except OSError as exc:
        print(f"  upstream connect failed: {exc}", flush=True)
        client.close()
        return

    upstream.settimeout(None)
    client.settimeout(None)
    t = threading.Thread(target=pipe, args=(client, upstream), daemon=True)
    t.start()
    pipe(upstream, client)
    t.join(timeout=5)
    for s in (client, upstream):
        try:
            s.close()
        except OSError:
            pass


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("tv_ip", help="TV address, e.g. 192.168.0.10")
    ap.add_argument("--tv-port", type=int, default=5555)
    ap.add_argument("--listen-port", type=int, default=15555)
    args = ap.parse_args()

    target = (args.tv_ip, args.tv_port)
    srv = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
    srv.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
    srv.bind(("127.0.0.1", args.listen_port))
    srv.listen(64)

    print(f"relay: 127.0.0.1:{args.listen_port} -> {target[0]}:{target[1]}")
    print("leave this running; Ctrl-C to stop", flush=True)

    try:
        while True:
            client, _ = srv.accept()
            threading.Thread(target=handle, args=(client, target), daemon=True).start()
    except KeyboardInterrupt:
        print("\nstopped")
    finally:
        srv.close()
    return 0


if __name__ == "__main__":
    sys.exit(main())
