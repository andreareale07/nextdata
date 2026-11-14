"""Malicious WebDAV/HTTP server for security tests (127.0.0.1 only). usage: evil_dav.py <port> <port2>
Behaviours are selected by the first path segment of the request:
  /xxe/        PROPFIND answers with DOCTYPE + external entity
  /bomb/       PROPFIND answers with a billion-laughs DOCTYPE
  /huge/       PROPFIND answers with an endless multistatus (>64 MiB)
  /trav/       PROPFIND lists hrefs with ../ traversal, absolute hosts and encoded separators
  /redir/      every request 302-redirects to http://127.0.0.1:<port2>/capture (other origin)
  /lie/        PROPFIND lists f.bin with size 10; GET sends 5000 bytes (more than declared)
  /short/      PROPFIND lists f.bin with size 5000; GET sends 10 bytes then closes (less than declared)
  /anything    the second port records the request headers it receives to <port2>.log
"""
import sys, threading, json
from http.server import HTTPServer, BaseHTTPRequestHandler
from socketserver import ThreadingMixIn

port, port2 = int(sys.argv[1]), int(sys.argv[2])
LOG = f"{sys.argv[0]}.{port2}.log"


class T(ThreadingMixIn, HTTPServer):
    daemon_threads = True


def ms(items):
    out = '<?xml version="1.0"?><D:multistatus xmlns:D="DAV:">'
    for href, is_dir, size in items:
        rt = "<D:collection/>" if is_dir else ""
        sz = "" if is_dir else f"<D:getcontentlength>{size}</D:getcontentlength>"
        out += f"<D:response><D:href>{href}</D:href><D:propstat><D:prop><D:resourcetype>{rt}</D:resourcetype>{sz}</D:prop></D:propstat></D:response>"
    return (out + "</D:multistatus>").encode()


class H(BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"

    def log_message(self, *a):
        pass

    def send_body(self, code, body, ctype="application/xml"):
        self.send_response(code)
        self.send_header("Content-Type", ctype)
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def do_ANY(self):
        n = int(self.headers.get("Content-Length") or 0)
        if n:
            self.rfile.read(n)
        seg = self.path.split("/")[1] if self.path.count("/") >= 1 else ""
        if seg == "redir":
            self.send_response(302)
            self.send_header("Location", f"http://127.0.0.1:{port2}/capture")
            self.send_header("Content-Length", "0")
            self.end_headers()
            return
        if self.command == "PROPFIND":
            if seg == "xxe":
                body = b'<?xml version="1.0"?><!DOCTYPE x [<!ENTITY e SYSTEM "file:///c:/windows/win.ini">]><D:multistatus xmlns:D="DAV:"><D:response><D:href>/xxe/&e;</D:href></D:response></D:multistatus>'
                return self.send_body(207, body)
            if seg == "bomb":
                ents = '<!ENTITY a "aaaaaaaaaa">' + "".join(f'<!ENTITY b{i} "&{"a" if i==0 else "b"+str(i-1)};&{"a" if i==0 else "b"+str(i-1)};">' for i in range(1, 10))
                return self.send_body(207, f'<?xml version="1.0"?><!DOCTYPE l [{ents}]><D:multistatus xmlns:D="DAV:"><D:response><D:href>/bomb/&b9;</D:href></D:response></D:multistatus>'.encode())
            if seg == "huge":
                self.send_response(207)
                self.send_header("Content-Type", "application/xml")
                self.send_header("Transfer-Encoding", "chunked")
                self.end_headers()
                try:
                    chunk = b"<D:response><D:href>/huge/" + b"x" * 1000 + b"</D:href></D:response>" * 1
                    head = b'<?xml version="1.0"?><D:multistatus xmlns:D="DAV:">'
                    self.wfile.write(b"%x\r\n" % len(head) + head + b"\r\n")
                    for _ in range(80 * 1024):  # ~80 MiB
                        self.wfile.write(b"%x\r\n" % len(chunk) + chunk + b"\r\n")
                except Exception:
                    pass
                return
            if seg == "trav":
                return self.send_body(207, ms([
                    ("/trav/", True, 0),
                    ("/trav/ok.txt", False, 3),
                    ("/trav/../../etc/passwd", False, 1),
                    ("/trav/..%2f..%2fevil.txt", False, 1),
                    ("/trav/a%2fb.txt", False, 1),
                    ("http://evil.example/trav/x.txt", False, 1),
                    ("/other/leak.txt", False, 1),
                    ("/trav/CON", False, 1),
                    ("/trav/..", True, 0),
                    ("/trav/dup.txt", False, 1),
                    ("/trav/dup.txt", False, 2),
                ]))
            if seg in ("lie", "short"):
                if self.path.rstrip("/") == f"/{seg}":
                    return self.send_body(207, ms([(f"/{seg}/", True, 0), (f"/{seg}/f.bin", False, 10 if seg == "lie" else 5000)]))
                return self.send_body(207, ms([(self.path, False, 10 if seg == "lie" else 5000)]))
            return self.send_body(207, ms([(self.path if self.path.endswith("/") else self.path + "/", True, 0)]))
        if self.command == "GET" and seg in ("lie", "short"):
            if seg == "lie":
                body = b"A" * 5000
                self.send_response(200)
                self.send_header("Content-Length", "5000")
                self.end_headers()
                self.wfile.write(body)
            else:
                self.send_response(200)
                self.send_header("Content-Length", "5000")
                self.end_headers()
                self.wfile.write(b"B" * 10)
                self.close_connection = True
            return
        self.send_body(404, b"")

    do_GET = do_PUT = do_POST = do_DELETE = do_PROPFIND = do_MKCOL = do_MOVE = do_HEAD = do_OPTIONS = do_ANY


class Capture(BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"

    def log_message(self, *a):
        pass

    def do_ANY(self):
        with open(LOG, "a") as f:
            f.write(json.dumps({"method": self.command, "path": self.path, "headers": {k.lower(): v for k, v in self.headers.items()}}) + "\n")
        self.send_response(200)
        self.send_header("Content-Length", "0")
        self.end_headers()

    do_GET = do_PUT = do_POST = do_PROPFIND = do_MOVE = do_ANY


open(LOG, "w").close()
threading.Thread(target=T(("127.0.0.1", port2), Capture).serve_forever, daemon=True).start()
srv = T(("127.0.0.1", port), H)
print("ready", flush=True)
srv.serve_forever()
