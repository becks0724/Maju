# 공개 중계 전 묶음 시험(합동 점검 D-3 · C-4) — 모든 응답 뒤 연결 닫기, 길이 머리는 숫자만, Transfer-Encoding · health 본문 거부, 잠자기를 세는 시계
import contextlib
import json
import os
import io
import socket
import sys
import threading
import time
import unittest
from contextlib import contextmanager

import relay


@contextmanager
def _serve(store=None):
    srv = relay.make_server("127.0.0.1", 0, store=store)
    t = threading.Thread(target=srv.serve_forever, kwargs={"poll_interval": 0.05}, daemon=True)
    t.start()
    try:
        yield srv
    finally:
        srv.shutdown()
        srv.server_close()
        t.join(timeout=5)


def _exchange(port, raw, wait_s=1.0):
    """원 바이트를 보내고 연결이 닫힐 때까지(또는 wait_s) 받은 것을 모두 돌려준다."""
    s = socket.create_connection(("127.0.0.1", port), timeout=wait_s)
    s.sendall(raw)
    out = b""
    try:
        while True:
            chunk = s.recv(65536)
            if not chunk:
                break
            out += chunk
    except socket.timeout:
        pass
    finally:
        s.close()
    return out


class ConnectionHardeningTest(unittest.TestCase):
    def setUp(self):
        self.server = self.enterContext(_serve())
        self.port = self.server.server_address[1]

    def test_every_response_closes_connection(self):
        # 두 요청을 한 연결에 이어 보내도 응답은 하나 — 연결 유지 끼어들기(보2-1)를 막는다
        raw = b"GET /v1/health HTTP/1.1\r\nHost: x\r\n\r\nGET /v1/health HTTP/1.1\r\nHost: x\r\n\r\n"
        out = _exchange(self.port, raw)
        self.assertEqual(out.count(b"HTTP/1.1 200"), 1, out)
        self.assertIn(b"Connection: close", out)

    def test_content_length_digits_only(self):
        for bad in (b"+2", b"2 ", b"0x2", b"-0", b"\xd9\xa2"):  # 앞 공백은 머리 문법상 허용(파서가 지움)
            with self.subTest(v=bad):
                raw = b"POST /v1/pair/start HTTP/1.1\r\nHost: x\r\nContent-Length: " + bad + b"\r\n\r\n{}"
                self.assertTrue(_exchange(self.port, raw).startswith(b"HTTP/1.1 400"))

    def test_transfer_encoding_is_rejected(self):
        raw = (b"POST /v1/pair/start HTTP/1.1\r\nHost: x\r\nTransfer-Encoding: chunked\r\n\r\n"
               b"2\r\n{}\r\n0\r\n\r\n")
        self.assertTrue(_exchange(self.port, raw).startswith(b"HTTP/1.1 400"))

    def test_health_with_body_is_rejected(self):
        raw = b"GET /v1/health HTTP/1.1\r\nHost: x\r\nContent-Length: 2\r\n\r\n{}"
        self.assertTrue(_exchange(self.port, raw).startswith(b"HTTP/1.1 400"))
        # 본문 없는 health 는 그대로
        self.assertTrue(_exchange(self.port, b"GET /v1/health HTTP/1.1\r\nHost: x\r\n\r\n").startswith(b"HTTP/1.1 200"))


class SleepCountingClockTest(unittest.TestCase):
    """C-4 — 중계 시계가 잠자기를 세지 않으면 깬 뒤 묵은 지시 · 상태가 신선하게 보인다(판2-3)."""

    def test_clock_counts_sleep_where_possible(self):
        cid = relay.sleep_counting_clock_id()
        if sys.platform.startswith("linux"):
            self.assertEqual(cid, time.CLOCK_BOOTTIME)
        elif sys.platform == "darwin":
            self.assertEqual(cid, time.CLOCK_MONOTONIC)  # macOS 의 clock_gettime(CLOCK_MONOTONIC) 은 잠자기를 센다
        a = relay.mono_ms()
        b = relay.mono_ms()
        self.assertGreaterEqual(b, a)


class _Clock:
    def __init__(self, t=1_800_000_000_000):
        self.t = t

    def __call__(self):
        return self.t


def _cmd(i, frm="CAR", type_="SHOW", ttl=30_000, payload=None, to=None):
    c = {"id": i, "type": type_, "payload": payload if payload is not None else {"line": "x", "button": None},
         "ttlMs": ttl, "test": False, "from": frm}
    if to is not None:
        c["to"] = to
    return c


def _car_body(tok, seq, commands=()):
    return {"token": tok, "seq": seq, "sentAt": 1_800_000_000_000 + seq, "vehicle": {"speedKmh": 0.0},
            "flow": None, "commands": list(commands), "acks": []}


class StoreLimitsTest(unittest.TestCase):
    """D-3 — 지시 8KB · ttl 120초 · 받는 쪽마다 중복 막기 기록 512 · 토큰별 sync 빈도(정상 몰림은 통과)."""

    def setUp(self):
        self.clock = _Clock()
        self.s = relay.RelayStore(clock=self.clock)
        start = self.s.pair_start()
        self.car = start["carToken"]
        self.phone = self.s.pair_join({"pairCode": start["pairCode"]})["phoneToken"]
        self.seq = 0

    def sync_car(self, commands=()):
        self.seq += 1
        return self.s.sync("CAR", _car_body(self.car, self.seq, commands))

    def assertBad(self, commands):
        with self.assertRaises(relay.ApiError) as cm:
            self.sync_car(commands)
        self.assertEqual(cm.exception.status, 400)

    def test_command_size_cap_8kb(self):
        self.assertEqual(relay.MAX_COMMAND_BYTES, 8 * 1024)
        base = len(relay.json.dumps(_cmd("ok", payload={"line": ""}), ensure_ascii=False, separators=(",", ":")).encode())
        self.sync_car([_cmd("ok", payload={"line": "a" * (relay.MAX_COMMAND_BYTES - base)})])
        self.assertBad([_cmd("no", payload={"line": "a" * (relay.MAX_COMMAND_BYTES - base + 1)})])

    def test_ttl_cap_120s(self):
        self.assertEqual(relay.MAX_COMMAND_TTL_MS, 120_000)
        self.sync_car([_cmd("t-max", ttl=120_000)])
        self.assertBad([_cmd("t-over", ttl=120_001)])

    def test_seen_record_cap_512_per_target(self):
        self.assertEqual(relay.MAX_SEEN_IDS, 512)
        n = 0
        while n < relay.MAX_SEEN_IDS + 40:
            self.sync_car([_cmd("c%d" % (n + k)) for k in range(relay.MAX_COMMANDS_PER_SYNC)])
            n += relay.MAX_COMMANDS_PER_SYNC
            self.clock.t += 1_000
        pair = next(iter(self.s._pairs.values()))
        self.assertLessEqual(len(pair.seen_ids["PHONE"]), relay.MAX_SEEN_IDS)

    def test_sync_rate_per_token(self):
        L = self.s.limits
        for _ in range(L.sync_per_token):
            self.sync_car()
        with self.assertRaises(relay.ApiError) as cm:
            self.sync_car()
        self.assertEqual((cm.exception.status, cm.exception.code), (429, "RATE_LIMITED"))
        # 다른 토큰(휴대폰)은 막히지 않는다
        self.s.sync("PHONE", {"token": self.phone, "seq": 1, "sentAt": 1, "watch": None, "flow": None, "commands": [], "acks": []})
        self.clock.t += L.sync_window_ms
        self.sync_car()

    def test_normal_burst_passes(self):
        # 휴대폰 「보낼 것이 있으면 바로 sync」 + 워치 판정 변화 즉시 sync 몰림 — 2초에 6번은 통과(be-qa · be-dev 3차)
        for _ in range(6):
            self.sync_car()
            self.clock.t += 300


class ConcurrencyPerAddressTest(unittest.TestCase):
    """D-3 — 주소 키 기준 동시 처리 상한(켜 둠). 머리를 읽은 뒤 센다(프록시 뒤에서는 주소 키 = X-Forwarded-For)."""

    def test_third_concurrent_request_from_same_address_is_429(self):
        store = relay.RelayStore(limits=relay.Limits(concurrent_per_addr=2))
        with _serve(store) as srv:
            port = srv.server_address[1]
            held = []
            for _ in range(2):
                s = socket.create_connection(("127.0.0.1", port), timeout=5)
                s.sendall(b"POST /v1/pair/start HTTP/1.1\r\nHost: x\r\nContent-Length: 10\r\n\r\n")  # 본문을 덜 보냄
                held.append(s)
            time.sleep(0.3)
            out = _exchange(port, b"POST /v1/pair/start HTTP/1.1\r\nHost: x\r\nContent-Length: 2\r\n\r\n{}")
            self.assertTrue(out.startswith(b"HTTP/1.1 429"), out[:40])
            for s in held:
                s.close()
            time.sleep(0.3)
            out = _exchange(port, b"POST /v1/pair/start HTTP/1.1\r\nHost: x\r\nContent-Length: 2\r\n\r\n{}")
            self.assertTrue(out.startswith(b"HTTP/1.1 200"), out[:40])

    def test_default_is_on(self):
        # 계약 §3.1.1 값(be-qa — 기본값을 바꿔도 시험이 통과하던 것)
        L = relay.Limits()
        self.assertEqual(L.concurrent_per_addr, 16)
        self.assertEqual((L.sync_per_token, L.sync_window_ms), (30, 2_000))


class ForwardedHeaderTest(unittest.TestCase):
    """D-4 보2-3 — 「주소:포트」 · 「[v6]:포트」 꼴 해석, 해석 실패는 주소 없이 세고, 루프백이 아닌 소켓의 헤더는 무시, 루프백 아닌 bind 와 함께면 시작 거부."""

    def test_host_port_forms(self):
        self.assertEqual(relay.client_key("127.0.0.1", ["203.0.113.5:443"], True), "203.0.113.5")
        self.assertEqual(relay.client_key("127.0.0.1", ["[2001:db8::1]:443"], True), "2001:db8::/64")
        self.assertEqual(relay.client_key("127.0.0.1", ["[2001:db8::1]"], True), "2001:db8::/64")
        self.assertEqual(relay.client_key("::1", ["198.51.100.7"], True), "198.51.100.7")

    def test_bad_header_is_counted_without_address(self):
        bad = []
        for v in ([], ["unknown"], ["1.2.3.4:x"], ["[2001:db8::1"]):
            self.assertEqual(relay.client_key("127.0.0.1", v, True, on_bad=lambda: bad.append(1)), "127.0.0.1")
        self.assertEqual(len(bad), 4)

    def test_header_ignored_when_socket_peer_is_not_loopback(self):
        self.assertEqual(relay.client_key("198.51.100.9", ["203.0.113.5"], True), "198.51.100.9")

    def test_trust_proxy_with_public_bind_refuses_to_start(self):
        # 규칙이 빠지면 서버를 띄우는 대신 바로 실패하게 make_server 를 막는다(be-qa — 빠지면 0.0.0.0 에 떠서 멈췄다)
        def no_server(*a, **kw):
            raise AssertionError("시작을 거부해야 하는데 서버를 만들었다")
        orig = relay.make_server
        relay.make_server = no_server
        try:
            with self.assertRaises(SystemExit) as cm:
                with contextlib.redirect_stderr(io.StringIO()):
                    relay.main(["--trust-proxy", "--bind", "0.0.0.0", "--port", "0"])
        finally:
            relay.make_server = orig
        self.assertNotEqual(cm.exception.code, 0)


class JoinIdempotencyTest(unittest.TestCase):
    """D-4 판2-4 — 참여 응답이 유실돼 같은 requestId 로 다시 참여하면 같은 토큰(실패로 세지 않음), 미참여 짝의 새 번호는 남은 시간을 사실대로."""

    def setUp(self):
        self.clock = _Clock()
        self.s = relay.RelayStore(clock=self.clock)
        self.start = self.s.pair_start()

    def test_same_request_id_returns_same_token(self):
        body = {"pairCode": self.start["pairCode"], "requestId": "req-1"}
        a = self.s.pair_join(body, "198.51.100.1")
        b = self.s.pair_join(body, "198.51.100.1")
        self.assertEqual(a, b)
        self.assertEqual(len(self.s._join_fail), 0)
        # 다른 requestId 는 지금처럼 404(이미 쓴 역할)
        with self.assertRaises(relay.ApiError) as cm:
            self.s.pair_join({"pairCode": self.start["pairCode"], "requestId": "req-2"}, "198.51.100.1")
        self.assertEqual(cm.exception.status, 404)
        # requestId 형식이 틀리면 400
        for bad in ("", "x" * 65, 7):
            with self.assertRaises(relay.ApiError) as cm:
                self.s.pair_join({"pairCode": self.start["pairCode"], "requestId": bad, "role": "WATCH"})
            self.assertEqual(cm.exception.status, 400)

    def test_code_for_never_joined_pair_reports_true_remaining_time(self):
        self.clock.t += 9 * 60 * 1000
        r = self.s.pair_code({"token": self.start["carToken"]})
        self.assertLessEqual(r["expiresInSec"], 60)


class StatsLineTest(unittest.TestCase):
    """D-4 · 보안 13 — 분 단위 계수 줄. 주소 · 토큰 없이 상태 코드 · 짝 · 참여 실패 · 헤더 실패 · 상한 거절 · 밀어낸 짝 · seq 역행을 센다."""

    def test_counts_and_warn_without_address(self):
        clock = _Clock()
        s = relay.RelayStore(clock=clock)
        for _ in range(31):
            s.note_status(404)
        s.note("xff_bad")
        start = s.pair_start("198.51.100.1")
        phone = s.pair_join({"pairCode": start["pairCode"]})["phoneToken"]
        body = {"token": phone, "seq": 2000, "sentAt": 1, "watch": None, "flow": None, "commands": [], "acks": []}
        s.sync("PHONE", body)
        s.sync("PHONE", dict(body, seq=1999))  # 늦게 온 옛 요청 — 덮지 않음
        line = s.stats_line()
        for part in ("404=31", "xff_bad=1", "seq_stale=1", "pairs=1", "WARN 404"):
            self.assertIn(part, line)
        self.assertNotIn("198.51.100.1", line)
        self.assertNotIn(phone, line)
        s.stats_line(reset=True)
        self.assertIn("404=0", s.stats_line())

    def test_seq_stale_is_not_in_response(self):
        s = relay.RelayStore(clock=_Clock())
        start = s.pair_start()
        phone = s.pair_join({"pairCode": start["pairCode"]})["phoneToken"]
        body = {"token": phone, "seq": 2000, "sentAt": 1, "watch": None, "flow": None, "commands": [], "acks": []}
        s.sync("PHONE", body)
        r = s.sync("PHONE", dict(body, seq=1999))
        self.assertNotIn("stateAccepted", r)  # sec-audit 3차 — 응답에 싣지 않고 계수만


ROLES_JSON = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "..", "contracts", "guardian", "command-roles.json")


class CommandRolesTest(unittest.TestCase):
    """D-1 — 허용 표를 공용 JSON 하나로(`contracts/guardian/command-roles.json`), CAR → PHONE FLOW_SNAPSHOT 은 없다. 표 밖이면 sync 전체 400."""

    def setUp(self):
        # 건너뛰지 않는다 — 공개 묶음(export-maju-public.sh)도 contracts/guardian 을 함께 담는다. 없으면 허용 표 시험이 조용히 빠지지 않게 실패
        self.assertTrue(os.path.exists(ROLES_JSON), "공용 허용 표 파일이 없다: " + ROLES_JSON)
        with open(ROLES_JSON, encoding="utf-8") as f:
            self.table = json.load(f)
        self.clock = _Clock()
        self.s = relay.RelayStore(clock=self.clock)
        start = self.s.pair_start()
        self.tok = {"CAR": start["carToken"]}
        self.tok["PHONE"] = self.s.pair_join({"pairCode": start["pairCode"]})["phoneToken"]
        self.tok["WATCH"] = self.s.pair_join({"pairCode": start["pairCode"], "role": "WATCH"})["watchToken"]
        self.seq = 0

    def body(self, role, commands):
        self.seq += 1
        b = {"token": self.tok[role], "seq": self.seq, "sentAt": 1, "commands": commands, "acks": []}
        if role == "CAR":
            b.update(vehicle={}, flow=None)
        elif role == "PHONE":
            b.update(watch=None, flow=None)
        else:
            b.update(watch={})
        return b

    def test_relay_table_matches_shared_json(self):
        allow = {r: {t: set(v) for t, v in m.items()} for r, m in self.table["allow"].items()}
        self.assertEqual(relay.COMMAND_ROLES, allow)
        self.assertEqual(relay.TEST_ONLY_TYPES, frozenset(self.table["testOnly"]))
        self.assertNotIn("FLOW_SNAPSHOT", relay.COMMAND_ROLES["CAR"])
        self.assertEqual(relay.COMMAND_ROLES["WATCH"], {})

    def test_every_allowed_combination_passes(self):
        n = 0
        for role, types in self.table["allow"].items():
            for type_, targets in types.items():
                for to in targets:
                    n += 1
                    c = _cmd("ok-%d" % n, frm=role, type_=type_, payload={}, to=to)
                    c["test"] = type_ in self.table["testOnly"]
                    self.s.sync(role, self.body(role, [c]))
                    self.clock.t += 1_000

    def assertBad(self, role, c):
        with self.assertRaises(relay.ApiError) as cm:
            self.s.sync(role, self.body(role, [c]))
        self.assertEqual(cm.exception.status, 400)

    def test_outside_table_is_400(self):
        self.assertBad("WATCH", _cmd("w1", frm="WATCH", type_="SEND_GUARDIAN_SMS", payload={}))
        self.assertBad("CAR", _cmd("c1", frm="CAR", type_="FLOW_SNAPSHOT", payload={}))
        self.assertBad("CAR", _cmd("c2", frm="CAR", type_="SPEAK", payload={}))  # 기본 대상 PHONE — 차 음성을 휴대폰에 내지 않는다
        self.assertBad("PHONE", _cmd("p1", frm="PHONE", type_="SEND_GUARDIAN_SMS", payload={}))  # 휴대폰 문자는 자기 안에서
        pair = next(iter(self.s._pairs.values()))
        self.assertEqual(pair.queue["PHONE"], [])

    def test_test_only_types_need_test_true(self):
        c = _cmd("tr", frm="CAR", type_="TEST_REQUEST", payload={"action": "START"})
        self.assertBad("CAR", dict(c, test=False))
        self.s.sync("CAR", self.body("CAR", [dict(c, test=True)]))


class RateLimiterCostTest(unittest.TestCase):
    """D-4 보2-4 — 계수기를 꽉 채운 뒤 새 키 1,000건의 처리 시간이 빈 계수기의 10배 안(지금 약 390배). 느린 기계를 생각해 문턱을 넉넉히 둔다."""

    def _time_new_keys(self, rl, base, n=1000):
        t0 = time.perf_counter()
        for i in range(n):
            rl.hit("new-%d" % i, base + i)
        return time.perf_counter() - t0

    def test_full_limiter_new_key_cost(self):
        empty = relay.RateLimiter(10, 600_000, 600_000, max_keys=4096)
        t_empty = self._time_new_keys(empty, 0)
        full = relay.RateLimiter(10, 600_000, 600_000, max_keys=4096)
        for i in range(4096):
            full.hit("k-%d" % i, i)
        t_full = self._time_new_keys(full, 5000)
        self.assertLessEqual(len(full), 4096)
        self.assertLess(t_full, max(t_empty, 0.002) * 10, (t_full, t_empty))


class SecAuditFollowupTest(unittest.TestCase):
    """sec-audit 3차 중계 판정 — 받는 쪽 큐 바이트 상한(64KB, 오래된 것부터 버림), 중첩 깊이 32 넘으면 400(앱 해석기 64 단계 — 상대 sync 를 통째로 못 읽게 만드는 길)."""

    def setUp(self):
        self.clock = _Clock()
        self.s = relay.RelayStore(clock=self.clock)
        start = self.s.pair_start()
        self.car = start["carToken"]
        self.phone = self.s.pair_join({"pairCode": start["pairCode"]})["phoneToken"]

    def test_queue_bytes_capped(self):
        self.assertEqual(relay.MAX_QUEUE_BYTES, 64 * 1024)
        big = "a" * 7_000
        for i in range(12):
            self.s.sync("CAR", _car_body(self.car, i + 1, [_cmd("b%d-%d" % (i, k), payload={"line": big}) for k in range(2)]))
            self.clock.t += 1_000
        r = self.s.sync("PHONE", {"token": self.phone, "seq": 1, "sentAt": 1, "watch": None, "flow": None, "commands": [], "acks": []})
        size = sum(len(relay.json.dumps(c, ensure_ascii=False, separators=(",", ":")).encode()) for c in r["commands"])
        self.assertLessEqual(size, relay.MAX_QUEUE_BYTES)
        self.assertEqual(r["commands"][-1]["id"], "b11-1")  # 새 것은 남고 오래된 것부터 버림

    def test_nesting_depth_over_32_is_400(self):
        def nest(d):
            v = {}
            for _ in range(d):
                v = {"x": v}
            return v
        ok = relay.json.dumps({"vehicle": nest(30)}).encode()  # 바깥 객체 1 + 31 = 32 단계(경계)
        relay.parse_body(ok)
        with self.assertRaises(relay.ApiError) as cm:
            relay.parse_body(relay.json.dumps({"vehicle": nest(40)}).encode())
        self.assertEqual(cm.exception.status, 400)
        v = [1]
        for _ in range(40):
            v = [v]
        deep_list = relay.json.dumps({"payload": v}).encode()
        with self.assertRaises(relay.ApiError):
            relay.parse_body(deep_list)


if __name__ == "__main__":
    unittest.main()
