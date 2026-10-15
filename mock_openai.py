#!/usr/bin/env python3
"""Mock OpenAI-compatible endpoint (ignores stream:true).

model == "zwsp" -> reply whose only prose is zero-width spaces, which
    rasterises to no ink at all: the exact case that used to strand the
    diary in THINKING forever.
anything else  -> a normal prose reply.
"""
import json
from http.server import BaseHTTPRequestHandler, HTTPServer

NORMAL = ("I remember you well, and the ink is still wet on that page.\n"
          "\u2042 hello from the test page")
ZWSP = "\u200b\u200b\n\u2042 zwsp probe"


class H(BaseHTTPRequestHandler):
    def do_POST(self):
        n = int(self.headers.get('Content-Length', 0) or 0)
        body = self.rfile.read(n)
        model = "?"
        try:
            req = json.loads(body.decode('utf-8', 'replace'))
            model = req.get('model', '?')
            with open('/tmp/mock_last_request.json', 'wb') as f:
                f.write(body)
        except Exception:
            pass
        reply = ZWSP if model == "zwsp" else NORMAL
        out = json.dumps({
            "id": "mock-1", "object": "chat.completion",
            "choices": [{"index": 0, "finish_reason": "stop",
                         "message": {"role": "assistant", "content": reply}}],
        }).encode()
        self.send_response(200)
        self.send_header('Content-Type', 'application/json')
        self.send_header('Content-Length', str(len(out)))
        self.end_headers()
        self.wfile.write(out)

    def log_message(self, *a):
        pass


if __name__ == '__main__':
    print("mock listening on 0.0.0.0:18899", flush=True)
    HTTPServer(('0.0.0.0', 18899), H).serve_forever()
