"""Stalling backend behind Caddy's /slow route (reverse_proxy 127.0.0.1:8444).

Accepts an HTTP request, replies with 200 + headers immediately, then never sends
a body byte. Caddy forwards the headers right away; the client's body read stalls
until its read timeout fires. Stop with: pkill -f slow-backend.py
"""

import socket
import threading
import time

RESPONSE = (
    b"HTTP/1.1 200 OK\r\n"
    b"Content-Length: 1000000\r\n"
    b"Content-Type: application/octet-stream\r\n"
    b"X-Accel-Buffering: no\r\n\r\n"
)


def stall(conn: socket.socket) -> None:
    try:
        data = b""
        while b"\r\n\r\n" not in data:
            chunk = conn.recv(4096)
            if not chunk:
                return
            data += chunk
        conn.sendall(RESPONSE)
        time.sleep(3600)
    except OSError:
        pass
    finally:
        conn.close()


def main() -> None:
    srv = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
    srv.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
    srv.bind(("127.0.0.1", 8444))
    srv.listen(16)
    while True:
        conn, _ = srv.accept()
        threading.Thread(target=stall, args=(conn,), daemon=True).start()


if __name__ == "__main__":
    main()
