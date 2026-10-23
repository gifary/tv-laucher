# Relay (sandbox cannot reach the LAN)

Symptom: the agent's shell answers `No route to host` / `Errno 65` for every
LAN host, including the router, while the internet works and mDNS discovery
still sees the TV. macOS Local Network privacy is applied per-process, and the
agent's process is not on the allow list.

Fix: forward the port through a process you started in Terminal.

```sh
cd ~/tv-debloat
python3 tools/relay.py 192.168.0.10
```

Leave it running. Then:

```sh
export TV_HOST=127.0.0.1 TV_PORT=15555
adb connect $TV_HOST:$TV_PORT
adb devices -l
```

Notes:
- One relay covers port 5555 only. If wireless-debugging pairing is needed,
  restart it as `python3 tools/relay.py <ip> --tv-port 5555 --listen-port 15555`
  for the ADB service, and a second one with `--tv-port 37xxx --listen-port 15556`
  for the pairing port.
- The relay dies with the Terminal window. If ADB starts reporting
  `connection reset`, check the relay is still alive before blaming the TV.
- Stop the relay when finished: Ctrl-C.
