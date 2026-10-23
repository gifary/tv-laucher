#!/usr/bin/env python3
"""Prove network ADB is really closed.

Run this in the same Terminal that ran tools/relay.py (or any Terminal that
can reach the LAN). Do NOT use nc: macOS Local Network privacy makes nc print
"No route to host" for reasons that have nothing to do with the TV.

Only "Connection refused" counts as closed. "No route to host", a timeout, or
"Network is unreachable" prove nothing about the TV.

    python3 tools/adb-closed.py 192.168.0.10
"""

import socket
import sys


def main() -> int:
    if len(sys.argv) < 2:
        print(__doc__)
        return 2
    host = sys.argv[1]
    port = int(sys.argv[2]) if len(sys.argv) > 2 else 5555

    s = socket.socket()
    s.settimeout(6)
    try:
        s.connect((host, port))
    except ConnectionRefusedError:
        print(f"CLOSED: {host}:{port} refused the connection")
        return 0
    except Exception as exc:  # noqa: BLE001
        print(f"INCONCLUSIVE: {host}:{port} -> {exc}")
        print("This does not mean the port is closed. Re-run from a Terminal")
        print("with Local Network permission, or over the relay.")
        return 2
    else:
        print(f"OPEN: {host}:{port} accepted a connection - ADB is still reachable")
        return 1
    finally:
        s.close()


if __name__ == "__main__":
    sys.exit(main())
