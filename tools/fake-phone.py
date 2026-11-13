#!/usr/bin/env python3
"""A stand-in for Tetra on the phone, for testing Tetra Auto SE on an emulator (which reaches this computer at 10.0.2.2, its
gateway, as a head unit reaches the phone on its hotspot). It speaks Tetra Auto's wire (Wire.kt): admits any car, then sends what
you type, and answers the car's ASKs with made-up data.

    python3 tools/fake-phone.py
    > sms            an SMS from "Maria"
    > go             navigate to Syntagma
    > play           a radio station
    > call           a call ringing (end: "hangup")
    > drive          the phone's GPS driving along Panepistimiou, once a second (again: stop)
    > say <text>     something said by the car
"""
import json, socket, struct, threading, time, math, sys

PORT = 43210
HELLO, ASK, PING = 0x01, 0x05, 0x70
WELCOME, EVENT, ANSWER = 0x41, 0x47, 0x48
lock = threading.Lock()


def send(conn, kind, obj=b""):
    body = obj if isinstance(obj, bytes) else json.dumps(obj, ensure_ascii=False).encode()
    with lock:
        conn.sendall(struct.pack(">bI", kind, len(body)) + body)


def read(f):
    head = f.read(5)
    if len(head) < 5:
        raise EOFError
    kind, n = struct.unpack(">bI", head)
    return kind, f.read(n)


def answer(conn, ask):
    a = {"id": ask["id"], "ok": True}
    if ask.get("recent") == "calls":
        a["calls"] = [{"name": "Μαρία", "number": "+306900000001", "type": "missed"}, {"name": "Γιώργος", "number": "+306900000002", "type": "in"}]
    elif "assist" in ask:
        wav = ask["assist"].get("wav", "")
        a.update(text="Πάμε στο Σύνταγμα.", go={"lat": 37.9755, "lng": 23.7348, "name": "Σύνταγμα"})
        print(f"  (heard {len(wav) * 3 // 4} bytes of speech)")
    print(f"< ASK {ask}  > ANSWER {a}")
    send(conn, ANSWER, a)


driving = threading.Event()


def drive(conn):
    # Omonoia → Syntagma along Panepistimiou, 15 m/s
    a, b = (37.9841, 23.7280), (37.9768, 23.7350)
    steps = 60
    bearing = math.degrees(math.atan2((b[1] - a[1]) * math.cos(math.radians(a[0])), b[0] - a[0])) % 360
    i = 0
    while driving.is_set():
        t = (i % steps) / steps
        send(conn, EVENT, {"type": "fix", "lat": a[0] + (b[0] - a[0]) * t, "lng": a[1] + (b[1] - a[1]) * t, "accuracy": 4, "speed": 15, "bearing": bearing, "age": 300})
        i += 1
        time.sleep(1)


def serve(conn):
    f = conn.makefile("rb")
    kind, body = read(f)
    hello = json.loads(body)
    print("< HELLO", hello)
    send(conn, WELCOME, {"name": "Fake phone", "token": hello.get("token") or "fake-token", "standalone": True})
    threading.Thread(target=lambda: [time.sleep(5) or send(conn, PING) for _ in iter(int, 1)], daemon=True).start()
    while True:
        kind, body = read(f)
        if kind == ASK:
            answer(conn, json.loads(body))
        elif kind != PING:
            print(f"< frame {kind:#x} ({len(body)} bytes)")


def main():
    s = socket.socket()
    s.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
    s.bind(("0.0.0.0", PORT)); s.listen(1)
    print(f"Fake phone on port {PORT}; waiting for the car…")
    conn, addr = s.accept()
    print("Car connected from", addr)
    threading.Thread(target=serve, args=(conn,), daemon=True).start()
    events = {
        "sms": {"type": "sms", "from": "+306900000001", "name": "Μαρία", "body": "Πού είσαι; Σε περιμένουμε στο Σύνταγμα."},
        "go": {"type": "go", "lat": 37.9755, "lng": 23.7348, "name": "Σύνταγμα"},
        "play": {"type": "play", "url": "https://radiostreaming.ert.gr/ert-kosmos", "name": "ΕΡΤ Κόσμος"},
        "call": {"type": "call", "state": "ringing", "name": "Μαρία", "number": "+306900000001", "incoming": True},
        "hangup": {"type": "call", "state": "ended", "name": "Μαρία", "number": "+306900000001"},
    }
    for line in sys.stdin:
        cmd = line.strip()
        if cmd in events:
            send(conn, EVENT, events[cmd]); print("> EVENT", cmd)
        elif cmd == "drive":
            if driving.is_set():
                driving.clear()
            else:
                driving.set(); threading.Thread(target=drive, args=(conn,), daemon=True).start()
            print("> drive", "on" if driving.is_set() else "off")
        elif cmd.startswith("say "):
            send(conn, EVENT, {"type": "say", "text": cmd[4:]})


if __name__ == "__main__":
    main()
