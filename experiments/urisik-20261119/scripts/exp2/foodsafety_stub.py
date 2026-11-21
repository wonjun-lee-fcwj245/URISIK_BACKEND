"""식품안전나라 외부 API 대역(stub). 앱 코드는 `http://openapi.foodsafetykorea.go.kr/api`로 하드코딩돼 있어
컨테이너 호스트 매핑(extra_hosts)으로 이 서버를 가리키게 한다. 어떤 GET이든 200과 빈 결과를 고정 지연 후 돌려준다.
사용: python foodsafety_stub.py --port 80 --delay-ms 20
"""
import argparse
import json
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

BODY = json.dumps({"COOKRCP01": {"total_count": "0", "row": [], "RESULT": {"MSG": "해당하는 데이터가 없습니다.", "CODE": "INFO-200"}}},
                  ensure_ascii=False).encode('utf-8')
DELAY = 0.0
COUNT = 0


class H(BaseHTTPRequestHandler):
    def do_GET(self):
        global COUNT
        COUNT += 1
        if DELAY:
            time.sleep(DELAY)
        self.send_response(200)
        self.send_header('Content-Type', 'application/json; charset=utf-8')
        self.send_header('Content-Length', str(len(BODY)))
        self.end_headers()
        self.wfile.write(BODY)

    def log_message(self, *a):
        pass


if __name__ == '__main__':
    p = argparse.ArgumentParser()
    p.add_argument('--port', type=int, default=80)
    p.add_argument('--delay-ms', type=float, default=20)
    a = p.parse_args()
    DELAY = a.delay_ms / 1000
    ThreadingHTTPServer(('0.0.0.0', a.port), H).serve_forever()
