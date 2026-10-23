#!/usr/bin/env python3
"""Small ADB front-end for this job.

  tools/tv.py 'getprop ro.build.version.sdk'      run one shell command
  tools/tv.py shell 'pm list packages -d'         same, explicit
  tools/tv.py --stream getevent -lt              stream live remote input events
  tools/tv.py --screenshot shots/01.png           capture what is on screen
  tools/tv.py --screenshot shots/02.png DPAD_RIGHT DPAD_RIGHT
                                                  press keys, then capture
  tools/tv.py --push local remote                 push a file
  tools/tv.py --pull remote local                 pull a file
  tools/tv.py --install app.apk                   install (pm install -r fallback)

Connection defaults to the relay (127.0.0.1:15555). Override with
TV_HOST / TV_PORT, e.g. TV_HOST=192.168.0.10 TV_PORT=5555.
"""

from __future__ import annotations

import os
import subprocess
import sys
import time

ADB = os.environ.get("ADB", os.path.expanduser("~/Library/Android/sdk/platform-tools/adb"))
HOST = os.environ.get("TV_HOST", "127.0.0.1")
PORT = os.environ.get("TV_PORT", "15555")
SERIAL = f"{HOST}:{PORT}"


def adb(*args: str, timeout: int = 120, check: bool = False) -> subprocess.CompletedProcess:
    cmd = [ADB, "-s", SERIAL, *args]
    return subprocess.run(cmd, capture_output=True, text=True, timeout=timeout, check=check)


def ensure_connected() -> None:
    out = subprocess.run([ADB, "devices"], capture_output=True, text=True).stdout
    if SERIAL in out and "device" in out:
        return
    r = subprocess.run([ADB, "connect", SERIAL], capture_output=True, text=True)
    msg = (r.stdout + r.stderr).strip()
    if "connected" not in msg:
        sys.exit(f"cannot connect to {SERIAL}: {msg}")
    time.sleep(0.3)


def shell(cmd: str, timeout: int = 120) -> str:
    ensure_connected()
    r = adb("shell", cmd, timeout=timeout)
    return r.stdout


def screenshot(path: str, keys: list[str]) -> int:
    ensure_connected()
    for key in keys:
        adb("shell", f"input keyevent {key}")
        time.sleep(0.9)
    os.makedirs(os.path.dirname(os.path.abspath(path)) or ".", exist_ok=True)
    r = subprocess.run(
        [ADB, "-s", SERIAL, "exec-out", "screencap -p"],
        capture_output=True, timeout=60,
    )
    data = r.stdout
    if len(data) < 1000:
        print(
            f"{path}: empty screenshot ({len(data)} bytes) - "
            "protected surface (Live TV / HDMI / Netflix). "
            "Check topResumedActivity instead.",
            file=sys.stderr,
        )
        return 1
    with open(path, "wb") as fh:
        fh.write(data)
    print(f"{path}  {len(data)} bytes")
    return 0


def stream_shell(args: list[str]) -> int:
    ensure_connected()
    try:
        return subprocess.call([ADB, "-s", SERIAL, "shell", *args])
    except KeyboardInterrupt:
        return 130


def main(argv: list[str]) -> int:
    if not argv:
        print(__doc__)
        return 2

    if argv[0] == "--screenshot":
        if len(argv) < 2:
            sys.exit("--screenshot needs a destination path")
        return screenshot(argv[1], argv[2:])

    if argv[0] == "--stream":
        if len(argv) < 2:
            sys.exit("--stream needs a shell command")
        return stream_shell(argv[1:])

    if argv[0] in ("--push", "--pull"):
        ensure_connected()
        if len(argv) != 3:
            sys.exit(f"{argv[0]} needs two arguments")
        direction = "push" if argv[0] == "--push" else "pull"
        r = adb(direction, argv[1], argv[2], timeout=600)
        print((r.stdout + r.stderr).strip())
        return 0 if "error" not in (r.stdout + r.stderr).lower() else 1

    if argv[0] == "--install":
        ensure_connected()
        apk = argv[1]
        r = adb("install", "-r", "-g", apk, timeout=300)
        print((r.stdout + r.stderr).strip())
        return 0

    cmd = " ".join(argv)
    if argv[0] == "shell":
        cmd = " ".join(argv[1:])
    out = shell(cmd)
    sys.stdout.write(out)
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
