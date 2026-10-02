"""Streaming backend behind Caddy's /slow and /sse routes (reverse_proxy 127.0.0.1:8444).

Accepts an HTTP request, replies with 200 + headers immediately, then streams according to the
request path:

- "/sse"  emits SSE events with real gaps between them, then closes. The gaps are far wider than
  any bridge read buffer worth filling, so a client that withheld a partial chunk to fill that
  buffer would only see the first event once the whole stream had ended.
- anything else  never sends a body byte. Caddy forwards the headers right away; the client's body
  read stalls until its read timeout fires (readTimeout testing).

Stop with: pkill -f slow-backend.py
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

# No Content-Length: this body is delimited by connection close, so the client sees an unbounded
# stream rather than a fixed-length one.
SSE_HEADERS = (
    b"HTTP/1.1 200 OK\r\n"
    b"Content-Type: text/event-stream\r\n"
    b"Cache-Control: no-cache\r\n"
    b"X-Accel-Buffering: no\r\n"
    b"Connection: close\r\n\r\n"
)

# The gaps must exceed any read buffer worth filling. SSE_GAP_SECONDS * SSE_EVENTS is also the full
# stream duration, which is what a withholding client would measure as its first-event delay.
SSE_EVENTS = 3
SSE_GAP_SECONDS = 2.0


def request_path(data: bytes) -> bytes:
    """First path segment of the request line: b"/sse" for "GET /sse?x=1 HTTP/1.1"."""
    parts = data.split(b" ", 2)
    if len(parts) < 2:
        return b""
    return parts[1].split(b"?", 1)[0]


def serve(conn: socket.socket, path: bytes) -> None:
    try:
        if path == b"/sse":
            conn.sendall(SSE_HEADERS)
            for i in range(SSE_EVENTS):
                if i:
                    time.sleep(SSE_GAP_SECONDS)
                conn.sendall(b"data: event-%d\n\n" % i)
        else:
            conn.sendall(RESPONSE)
            time.sleep(3600)
    except OSError:
        pass
    finally:
        conn.close()


def handle(conn: socket.socket) -> None:
    try:
        data = b""
        while b"\r\n\r\n" not in data:
            chunk = conn.recv(4096)
            if not chunk:
                conn.close()
                return
            data += chunk
        serve(conn, request_path(data))
    except OSError:
        conn.close()


def main() -> None:
    srv = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
    srv.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
    srv.bind(("127.0.0.1", 8444))
    srv.listen(16)
    while True:
        conn, _ = srv.accept()
        threading.Thread(target=handle, args=(conn,), daemon=True).start()


if __name__ == "__main__":
    main()