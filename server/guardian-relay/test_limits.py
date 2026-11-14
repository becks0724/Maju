# 중계 공개 요건 시험 — 짝 번호 대입 · pair/start · pair/code 빈도 제한, 프록시 주소 선택, 연결 상한 · 읽기 마감, 단조 시계를 확인한다
import http.client
import io
import json
import logging
import socket
import time
import unittest
from contextlib import redirect_stderr

import relay
from relay import ApiError, Limits, RateLimiter, RelayStore, client_key, wider_key
from test_relay import FakeClock, _serve, car_body, phone_body, watch_body

A, B = "198.51.100.7", "203.0.113.9"
MIN = 60 * 1000


class _Base(unittest.TestCase):
    def setUp(self):
        self.clock = FakeClock()
        self.s = RelayStore(clock=self.clock)

    def assertApi(self, status, code, fn, *a):
        with self.assertRaises(ApiError) as cm:
            fn(*a)
        self.assertEqual((cm.exception.status, cm.exception.code), (status, code))

    def fail_join(self, n, addr=A):
        for _ in range(n):
            self.assertApi(404, "PAIR_CODE_INVALID", self.s.pair_join, {"pairCode": "000000x"}, addr)


class RateLimiterTest(unittest.TestCase):
    def test_sliding_window_below_at_and_release(self):
        rl = RateLimiter(3, 1000)
        self.assertEqual([rl.allow("k", t) for t in (0, 10, 20)], [True] * 3)   # 문턱 바로 아래까지 통과
        self.assertFalse(rl.allow("k", 30))                                      # 문턱에서 막힘
        self.assertFalse(rl.allow("k", 999))
        self.assertTrue(rl.allow("k", 1000))                                     # 첫 사건이 창에서 빠지면 풀림

    def test_refused_requests_are_not_counted(self):
        rl = RateLimiter(2, 1000)
        rl.allow("k", 0), rl.allow("k", 500)
        for t in range(501, 1000, 50):
            self.assertFalse(rl.allow("k", t))
        self.assertTrue(rl.allow("k", 1000))  # 막힌 요청이 창을 늘리지 않는다

    def test_block_duration(self):
        rl = RateLimiter(3, 1000, block_ms=5000)
        rl.hit("k", 0), rl.hit("k", 1)
        self.assertFalse(rl.blocked("k", 2))
        rl.hit("k", 2)
        self.assertTrue(rl.blocked("k", 2))
        self.assertTrue(rl.blocked("k", 5001))
        self.assertFalse(rl.blocked("k", 5002))

    def test_key_cap_evicts_oldest_unblocked_key(self):
        rl = RateLimiter(5, 1000, max_keys=3)
        rl.hit("a", 0), rl.hit("b", 5), rl.hit("c", 3)
        self.assertFalse(rl.blocked("d", 10))  # 처음 보는 키는 꽉 차도 막히지 않는다
        self.assertEqual(len(rl), 3)           # 보기만 해서는 남의 기록을 밀어내지 않는다
        rl.hit("d", 10)
        self.assertEqual(len(rl), 3)           # 상한을 넘겨 기억하지 않는다
        self.assertNotIn("a", rl._events)      # 마지막 사건이 가장 오래된 키(a)를 밀어냈다
        self.assertEqual(set(rl._events), {"b", "c", "d"})

    def test_key_cap_keeps_blocked_keys(self):
        rl = RateLimiter(2, 10_000, block_ms=10_000, max_keys=3)
        rl.hit("bad", 0), rl.hit("bad", 1)       # 막힘
        rl.hit("x", 2), rl.hit("y", 3)
        for i in range(50):                      # 새 키가 계속 와도
            rl.hit("n%d" % i, 10 + i)
        self.assertTrue(rl.blocked("bad", 100))  # 막힌 키는 밀려나지 않는다
        self.assertEqual(len(rl), 3)

    def test_all_blocked_new_key_is_not_tracked_but_not_blocked(self):
        rl = RateLimiter(1, 10_000, block_ms=10_000, max_keys=2)
        rl.hit("a", 0), rl.hit("b", 0)
        rl.hit("c", 1)
        self.assertFalse(rl.blocked("c", 2))
        self.assertEqual(len(rl), 2)

    def test_stale_keys_are_freed_first(self):
        rl = RateLimiter(5, 1000, block_ms=0, max_keys=2)
        rl.hit("a", 0), rl.hit("b", 0)
        rl.hit("c", 1000)
        self.assertEqual(set(rl._events), {"c"})

    def test_many_addresses_never_exceed_cap(self):
        rl = RateLimiter(2, 10_000, block_ms=10_000, max_keys=100)
        for i in range(5000):
            rl.hit("198.18.%d.%d" % (i // 256, i % 256), i)
            self.assertLessEqual(len(rl), 100)


class JoinLimitTest(_Base):
    def test_below_threshold_correct_code_still_joins(self):
        start = self.s.pair_start()
        self.fail_join(9)
        self.assertIn("phoneToken", self.s.pair_join({"pairCode": start["pairCode"]}, A))

    def test_at_threshold_blocks_even_correct_code_then_releases(self):
        start = self.s.pair_start()
        self.fail_join(10)
        self.assertApi(429, "RATE_LIMITED", self.s.pair_join, {"pairCode": start["pairCode"]}, A)
        self.assertIn("phoneToken", self.s.pair_join({"pairCode": start["pairCode"]}, B))  # 다른 주소는 안 막힌다
        self.clock.t += 10 * MIN - 1
        self.assertApi(429, "RATE_LIMITED", self.s.pair_join, {"pairCode": "1"}, A)
        self.clock.t += 1
        start2 = self.s.pair_start()
        self.assertIn("phoneToken", self.s.pair_join({"pairCode": start2["pairCode"]}, A))

    def test_failures_outside_window_do_not_add_up(self):
        self.fail_join(9)
        self.clock.t += 10 * MIN
        self.fail_join(9)
        start = self.s.pair_start()
        self.assertIn("phoneToken", self.s.pair_join({"pairCode": start["pairCode"]}, A))

    def test_success_does_not_reset_failures(self):
        self.fail_join(9)
        start = self.s.pair_start()
        self.s.pair_join({"pairCode": start["pairCode"]}, A)  # 제 짝에 참여해도 계수기는 그대로
        self.fail_join(1)
        self.assertApi(429, "RATE_LIMITED", self.s.pair_join, {"pairCode": start["pairCode"], "role": "WATCH"}, A)

    def test_brute_force_100_guesses_stops_at_threshold(self):
        start = self.s.pair_start()
        codes = ["%06d" % i for i in range(100) if "%06d" % i != start["pairCode"]][:100]
        statuses = []
        for c in codes:
            try:
                self.s.pair_join({"pairCode": c}, A)
                statuses.append(200)
            except ApiError as e:
                statuses.append(e.status)
        self.assertEqual(statuses[:10], [404] * 10)
        self.assertEqual(set(statuses[10:]), {429})

    def test_no_address_means_no_address_limit(self):
        self.fail_join(50, addr=None)
        start = self.s.pair_start()
        self.assertIn("phoneToken", self.s.pair_join({"pairCode": start["pairCode"]}))

    def test_code_failure_cap_drops_code(self):
        start = self.s.pair_start()
        code = start["pairCode"]
        self.s.pair_join({"pairCode": code}, "198.51.100.1")
        for i in range(4):  # 이미 쓴 역할(휴대폰)로 다시 — 번호에 걸린 실패 4번(문턱 바로 아래)
            self.assertApi(404, "PAIR_CODE_INVALID", self.s.pair_join, {"pairCode": code}, "203.0.113.%d" % i)
        self.assertIn("watchToken", self.s.pair_join({"pairCode": code, "role": "WATCH"}, "198.51.100.1"))

    def test_code_failure_cap_at_threshold(self):
        start = self.s.pair_start()
        code, car = start["pairCode"], start["carToken"]
        self.s.pair_join({"pairCode": code}, "198.51.100.1")
        for i in range(5):
            self.assertApi(404, "PAIR_CODE_INVALID", self.s.pair_join, {"pairCode": code}, "203.0.113.%d" % i)
        # 번호를 버렸다 — 워치도 이 번호로는 못 붙고, 차가 새 번호를 받는다. 짝(차 토큰)은 그대로
        self.assertApi(404, "PAIR_CODE_INVALID", self.s.pair_join, {"pairCode": code, "role": "WATCH"}, "198.51.100.1")
        new = self.s.pair_code({"token": car}, "198.51.100.1")["pairCode"]
        self.assertIn("watchToken", self.s.pair_join({"pairCode": new, "role": "WATCH"}, "198.51.100.1"))
        self.assertIn("serverTime", self.s.sync("CAR", car_body(car, 1)))


class PairStartLimitTest(_Base):
    def test_per_address_per_minute(self):
        self.s = RelayStore(clock=self.clock, limits=Limits(pairs_per_addr=100))  # 이 시험은 빈도만 본다
        for _ in range(10):
            self.s.pair_start(A)
        self.assertApi(429, "RATE_LIMITED", self.s.pair_start, A)
        self.s.pair_start(B)  # 다른 주소는 안 막힌다
        self.clock.t += MIN - 1
        self.assertApi(429, "RATE_LIMITED", self.s.pair_start, A)
        self.clock.t += 1
        self.s.pair_start(A)

    def test_max_pairs_option(self):
        s = RelayStore(clock=self.clock, limits=Limits(max_pairs=2))
        s.pair_start(A), s.pair_start(B)
        with self.assertRaises(ApiError) as cm:
            s.pair_start("192.0.2.1")
        self.assertEqual((cm.exception.status, cm.exception.code), (503, "CAPACITY"))


class PairCodeLimitTest(_Base):
    def test_per_pair(self):
        start = self.s.pair_start()
        car = start["carToken"]
        self.s.pair_join({"pairCode": start["pairCode"]})  # 참여한 짝(빈 짝은 첫 번호 만료 때 지워진다)
        for i in range(10):
            self.s.pair_code({"token": car}, "198.51.100.%d" % i)
        self.assertApi(429, "RATE_LIMITED", self.s.pair_code, {"token": car}, "203.0.113.9")
        self.assertIn("serverTime", self.s.sync("CAR", car_body(car, 1)))  # 429 는 짝을 지우지 않는다
        self.clock.t += 10 * MIN
        self.s.sync("CAR", car_body(car, 2))
        self.s.pair_code({"token": car}, A)

    def test_per_address(self):
        cars = [self.s.pair_start()["carToken"] for _ in range(3)]
        for i in range(20):
            self.s.pair_code({"token": cars[i % 3]}, A)
        self.assertApi(429, "RATE_LIMITED", self.s.pair_code, {"token": cars[2]}, A)
        self.s.pair_code({"token": cars[2]}, B)

    def test_bad_token_is_still_401(self):
        self.assertApi(401, "TOKEN_INVALID", self.s.pair_code, {"token": "f" * 32}, A)


class DemoFlowTest(_Base):
    """같은 Wi-Fi 시연 — 기기 3대가 한 주소(공유기)로 2초마다 sync, 다시 짝짓기 몇 번, 번호 잘못 누르기 몇 번. 기본값은 막지 않는다."""

    def test_demo_is_never_limited(self):
        for round_ in range(4):  # 차가 처음부터 다시 짝짓기 4번
            start = self.s.pair_start(A)
            car = start["carToken"]
            self.fail_join(2)  # 번호를 잘못 누름
            phone = self.s.pair_join({"pairCode": start["pairCode"]}, A)["phoneToken"]
            for again in range(3):  # 워치 다시 붙이기 3번
                code = self.s.pair_code({"token": car}, A)["pairCode"]
                watch = self.s.pair_join({"pairCode": code, "role": "WATCH"}, A)["watchToken"]
            for seq in range(1, 300):  # 10분 동안 2초마다
                self.clock.t += 2000
                self.s.sync("CAR", car_body(car, seq))
                self.s.sync("PHONE", phone_body(phone, seq))
                self.s.sync("WATCH", watch_body(watch, seq))


class MonotonicClockTest(unittest.TestCase):
    def setUp(self):
        self.wall = FakeClock(1_800_000_000_000)
        self.mono = FakeClock(5_000)
        self.s = RelayStore(clock=self.wall, mono=self.mono)
        start = self.s.pair_start()
        self.code = start["pairCode"]
        self.car = start["carToken"]
        self.phone = self.s.pair_join({"pairCode": self.code})["phoneToken"]

    def step(self, mono_ms, wall_ms):
        self.mono.t += mono_ms
        self.wall.t += wall_ms

    def test_wall_clock_back_gives_no_negative_age(self):
        watch = self.s.pair_join({"pairCode": self.code, "role": "WATCH"})["watchToken"]
        self.s.sync("CAR", car_body(self.car, 1))
        self.s.sync("WATCH", watch_body(watch, 1))
        self.step(500, -3_600_000)  # 벽시계가 한 시간 뒤로
        r = self.s.sync("PHONE", phone_body(self.phone, 1))
        self.assertEqual(r["serverTime"], self.wall.t)
        self.assertEqual(r["serverTime"] - r["peer"]["receivedAt"], 500)
        self.assertEqual(r["watchDirectAgeMs"], 500)
        self.step(300, -10_000)
        r = self.s.sync("CAR", car_body(self.car, 2))
        self.assertEqual(r["watch"]["via"], "PHONE")  # 받은 순서도 단조 시계로 고른다
        self.assertEqual((r["watch"]["ageMs"], r["serverTime"] - r["watch"]["receivedAt"]), (300, 300))
        self.assertEqual(r["serverTime"] - r["peer"]["receivedAt"], 300)
        self.assertEqual(r["watchDirectAgeMs"], 800)
        for v in (r["watch"]["ageMs"], r["watchDirectAgeMs"]):
            self.assertGreaterEqual(v, 0)

    def test_wall_clock_forward_does_not_expire_pair_or_code(self):
        self.step(1000, 2 * 24 * 3600 * 1000)  # 벽시계가 이틀 앞으로
        self.assertIn("serverTime", self.s.sync("CAR", car_body(self.car, 1)))
        self.assertIn("watchToken", self.s.pair_join({"pairCode": self.code, "role": "WATCH"}))

    def test_expiry_follows_monotonic_clock(self):
        self.step(relay.PAIR_TTL_MS - 1, -5_000_000)
        self.s.sync("CAR", car_body(self.car, 1))
        self.step(1, 0)
        with self.assertRaises(ApiError) as cm:
            self.s.sync("CAR", car_body(self.car, 2))
        self.assertEqual(cm.exception.status, 401)

    def test_code_expiry_follows_monotonic_clock(self):
        self.step(relay.PAIR_CODE_TTL_MS, -1_000_000)  # 벽시계는 뒤로 갔어도 번호는 10분 지나 만료
        with self.assertRaises(ApiError) as cm:
            self.s.pair_join({"pairCode": self.code, "role": "WATCH"})
        self.assertEqual(cm.exception.status, 404)

    def test_command_ttl_follows_monotonic_clock(self):
        self.s.sync("CAR", car_body(self.car, 1, commands=[
            {"id": "c1", "type": "SHOW", "payload": {}, "ttlMs": 1000, "test": True, "from": "CAR"}]))
        self.step(999, -60_000)
        self.assertEqual([c["id"] for c in self.s.sync("PHONE", phone_body(self.phone, 1))["commands"]], ["c1"])
        self.step(1, 60_000_000)
        self.assertEqual(self.s.sync("PHONE", phone_body(self.phone, 2))["commands"], [])

    def test_default_store_uses_real_monotonic(self):
        s = RelayStore()
        self.assertIs(s._mono, relay.mono_ms)
        self.assertIs(s._clock, relay.now_ms)


class ClientKeyTest(unittest.TestCase):
    def test_header_ignored_without_trust_proxy(self):
        self.assertEqual(client_key("127.0.0.1", ["1.2.3.4"]), "127.0.0.1")

    def test_last_value_with_trust_proxy(self):
        self.assertEqual(client_key("127.0.0.1", ["9.9.9.9, 1.2.3.4"], True), "1.2.3.4")  # 앞 값은 클라이언트가 지어낼 수 있다
        self.assertEqual(client_key("127.0.0.1", ["9.9.9.9", "5.6.7.8"], True), "5.6.7.8")  # 여러 줄이면 마지막 줄
        self.assertEqual(client_key("127.0.0.1", [" 1.2.3.4 "], True), "1.2.3.4")

    def test_bad_or_missing_header_falls_back_to_socket(self):
        self.assertEqual(client_key("127.0.0.1", [], True), "127.0.0.1")
        self.assertEqual(client_key("127.0.0.1", ["unknown"], True), "127.0.0.1")
        self.assertEqual(client_key("127.0.0.1", ["1.2.3.4, "], True), "127.0.0.1")

    def test_ipv6_grouped_by_64(self):
        a = client_key("2001:db8:1:2:aaaa::1")
        self.assertEqual(a, "2001:db8:1:2::/64")
        self.assertEqual(client_key("2001:db8:1:2:bbbb::9"), a)
        self.assertNotEqual(client_key("2001:db8:1:3::1"), a)
        self.assertEqual(client_key("::ffff:1.2.3.4"), "1.2.3.4")


class PairsPerAddrTest(_Base):
    """주소별 살아 있는 짝 상한 — 세 역할 모두 조용한 짝만 밀어낸다(미참여 2분 · 참여한 적 있음 10분). 여기서는 상한을 3 으로 둔다."""
    UNJOINED = Limits().pair_idle_ms
    JOINED = Limits().joined_idle_ms

    def setUp(self):
        super().setUp()
        self.s = RelayStore(clock=self.clock, limits=Limits(pairs_per_addr=3))

    def start_joined(self, addr=A, watch=False):
        st = self.s.pair_start(addr)
        ph = self.s.pair_join({"pairCode": st["pairCode"]}, addr)["phoneToken"]
        out = {"car": st["carToken"], "phone": ph}
        if watch:
            out["watch"] = self.s.pair_join({"pairCode": st["pairCode"], "role": "WATCH"}, addr)["watchToken"]
        return out

    def test_below_cap_then_429_at_cap_other_address_unaffected(self):
        cars = [self.start_joined()["car"] for _ in range(3)]  # 상한 바로 아래까지(3번째) 통과
        self.assertApi(429, "RATE_LIMITED", self.s.pair_start, A)
        self.start_joined(B)
        for c in cars:
            self.s.sync("CAR", car_body(c, 1))  # 429 는 쓰는 중인 짝을 건드리지 않는다

    def test_pair_end_frees_slot(self):
        cars = [self.start_joined()["car"] for _ in range(3)]
        self.assertApi(429, "RATE_LIMITED", self.s.pair_start, A)
        self.s.pair_end({"token": cars[0]})
        self.assertIn("carToken", self.s.pair_start(A))

    def test_car_quiet_but_phone_or_watch_syncing_is_not_evicted(self):
        """① 차가 주차 · 터널로 조용해도 휴대폰 또는 워치가 sync 하는 참여 짝은 밀려나지 않는다."""
        pairs = [self.start_joined(watch=True) for _ in range(3)]
        for minute in range(30):
            self.clock.t += 60_000
            self.s.sync("PHONE", phone_body(pairs[0]["phone"], minute + 1))   # 0번은 휴대폰만
            self.s.sync("WATCH", watch_body(pairs[1]["watch"], minute + 1))   # 1번은 워치만
            self.s.sync("CAR", car_body(pairs[2]["car"], minute + 1))         # 2번은 차만
            self.assertApi(429, "RATE_LIMITED", self.s.pair_start, A)
        for i, p in enumerate(pairs):
            self.s.sync("CAR", car_body(p["car"], 100 + i))

    def test_joined_quiet_pair_boundary(self):
        """② 세 역할이 모두 조용한 참여 짝은 문턱 −1ms 에 안 밀리고 문턱에 밀린다."""
        pairs = [self.start_joined() for _ in range(3)]
        self.clock.t += self.JOINED - 1
        self.s.sync("CAR", car_body(pairs[1]["car"], 1))
        self.s.sync("CAR", car_body(pairs[2]["car"], 1))
        self.assertApi(429, "RATE_LIMITED", self.s.pair_start, A)
        self.clock.t += 1
        self.assertIn("carToken", self.s.pair_start(A))
        self.assertApi(401, "TOKEN_INVALID", self.s.sync, "PHONE", phone_body(pairs[0]["phone"], 2))
        self.s.sync("CAR", car_body(pairs[1]["car"], 2))

    def test_join_counts_as_activity(self):
        """참여도 활동이다 — 참여한 뒤 첫 sync 전에 짝이 밀려나지 않는다(검증 3차 하 1)."""
        st = self.s.pair_start(A)  # 차가 sync 하지 않는 짝
        others = [self.start_joined() for _ in range(2)]
        self.clock.t += self.UNJOINED - 1
        phone = self.s.pair_join({"pairCode": st["pairCode"]}, A)["phoneToken"]
        self.clock.t += self.JOINED - 1  # 참여 뒤 10분 −1ms(만든 지는 12분 가까이)
        for o in others:
            self.s.sync("CAR", car_body(o["car"], 1))
        self.assertApi(429, "RATE_LIMITED", self.s.pair_start, A)
        self.s.sync("PHONE", phone_body(phone, 1))  # 첫 sync 가 401 이 아니다

    def test_pair_code_counts_as_activity(self):
        """차의 새 번호 요청도 활동이다 — 그 짝은 문턱이 다시 선다."""
        pairs = [self.start_joined() for _ in range(3)]
        self.clock.t += self.JOINED - 1
        self.s.pair_code({"token": pairs[0]["car"]}, A)
        self.s.sync("CAR", car_body(pairs[1]["car"], 1))
        self.s.sync("CAR", car_body(pairs[2]["car"], 1))
        self.clock.t += 1  # 번호를 받지 않았다면 여기서 밀려났을 시각
        self.assertApi(429, "RATE_LIMITED", self.s.pair_start, A)
        self.s.sync("CAR", car_body(pairs[0]["car"], 1))

    def test_unjoined_pair_keeps_two_minutes(self):
        """③ 미참여 짝은 2분 기준 그대로."""
        joined = [self.start_joined()["car"] for _ in range(2)]
        bare = self.s.pair_start(A)["carToken"]
        self.clock.t += self.UNJOINED - 1
        for c in joined:
            self.s.sync("CAR", car_body(c, 1))
        self.assertApi(429, "RATE_LIMITED", self.s.pair_start, A)
        self.clock.t += 1
        self.assertIn("carToken", self.s.pair_start(A))
        self.assertApi(401, "TOKEN_INVALID", self.s.sync, "CAR", car_body(bare, 1))

    def test_unjoined_goes_before_joined(self):
        """④ 둘 다 조용하면 미참여 짝이 참여 짝보다 먼저 밀린다(참여 짝이 더 오래 조용해도)."""
        old = self.start_joined()["car"]
        self.clock.t += 1000
        bare = self.s.pair_start(A)["carToken"]
        third = self.start_joined()["car"]
        self.clock.t += self.JOINED
        self.s.sync("CAR", car_body(third, 1))
        self.s.pair_start(A)
        self.assertApi(401, "TOKEN_INVALID", self.s.sync, "CAR", car_body(bare, 1))
        self.s.sync("CAR", car_body(old, 1))

    def test_neighbor_pairs_survive_attacker_on_same_address(self):
        """⑤ 검증 재현 — 같은 주소의 이웃 3집이 주차 중(차 조용) · 휴대폰 sync 계속, 공격자가 pair/start 를 되풀이해도 이웃은 200."""
        neighbors = [self.start_joined() for _ in range(3)]
        for i in range(200):
            self.clock.t += 6_000
            for n in neighbors:
                self.assertIn("serverTime", self.s.sync("PHONE", phone_body(n["phone"], i + 1)))
            try:
                self.s.pair_start(A)
                self.fail("이웃 짝을 밀어냈다")
            except ApiError as e:
                self.assertEqual(e.status, 429)

    def test_one_address_cannot_fill_capacity(self):
        """⑦ 한 주소가 360초 동안 128번 pair/start 하고 제 번호로 참여 · 차 sync 로 살려 두어도 상한(3)을 넘지 못한다."""
        mine, codes = [], []
        for i in range(128):
            try:
                st = self.s.pair_start(A)
                self.s.pair_join({"pairCode": st["pairCode"]}, A)
                mine.append(st["carToken"])
            except ApiError as e:
                codes.append(e.status)
            for c in list(mine):
                try:
                    self.s.sync("CAR", car_body(c, i + 1))
                except ApiError:
                    mine.remove(c)
            self.clock.t += 360_000 // 128
        self.assertLessEqual(len(mine), 3)
        self.assertEqual(set(codes), {429})
        self.assertIn("carToken", self.s.pair_start(B))

    def test_one_address_default_cap_is_8(self):
        s = RelayStore(clock=self.clock)
        for _ in range(8):
            st = s.pair_start(A)
            s.pair_join({"pairCode": st["pairCode"]}, A)
        with self.assertRaises(ApiError) as cm:
            s.pair_start(A)
        self.assertEqual(cm.exception.status, 429)

    def test_demo_car_reinstalls_on_one_address_default(self):
        """⑥ 기본값 · adb 터널 시연 — 모든 기기가 한 주소. 차 앱을 90초마다 재설치(옛 짝을 남긴 채 새 pair/start)하고
        매번 휴대폰 · 워치가 새 번호로 다시 참여해 2초마다 sync 해도 pair/start 가 한 번도 막히지 않는다."""
        s = RelayStore(clock=self.clock)
        for round_ in range(20):
            st = s.pair_start(A)  # 429 면 ApiError 로 시험 실패
            car = st["carToken"]
            ph = s.pair_join({"pairCode": st["pairCode"]}, A)["phoneToken"]
            wt = s.pair_join({"pairCode": st["pairCode"], "role": "WATCH"}, A)["watchToken"]
            for seq in range(45):
                self.clock.t += 2000
                s.sync("CAR", car_body(car, seq + 1))
                s.sync("PHONE", phone_body(ph, seq + 1))
                s.sync("WATCH", watch_body(wt, seq + 1))

    def test_owner_address_not_in_responses(self):
        st = self.s.pair_start(A)
        join = self.s.pair_join({"pairCode": st["pairCode"]}, A)
        r = self.s.sync("PHONE", phone_body(join["phoneToken"], 1))
        r2 = self.s.sync("CAR", car_body(st["carToken"], 1))
        self.assertNotIn(A, json.dumps([st, join, r, r2]))


class JoinFailWideKeyTest(_Base):
    def test_wider_key(self):
        self.assertEqual(wider_key("2001:db8:1:2::/64", 48), "2001:db8:1::/48")
        self.assertEqual(wider_key("1.2.3.4", 48), "1.2.3.4")
        self.assertEqual(wider_key("x", 48), "x")

    def test_v6_join_failures_count_per_48(self):
        for i in range(10):  # 같은 /48 안의 /64 열 개에서 한 번씩
            self.assertApi(404, "PAIR_CODE_INVALID", self.s.pair_join, {"pairCode": "x"},
                           client_key("2001:db8:1:%x::1" % i))
        st = self.s.pair_start()
        self.assertApi(429, "RATE_LIMITED", self.s.pair_join, {"pairCode": st["pairCode"]}, client_key("2001:db8:1:ff::1"))
        self.assertIn("phoneToken", self.s.pair_join({"pairCode": st["pairCode"]}, client_key("2001:db8:2::1")))

    def test_v6_pair_start_stays_per_64(self):
        s = RelayStore(clock=self.clock)
        for i in range(4):  # /64 마다 3개 · 분당 10번 — 같은 /48 이라도 다른 /64 는 따로
            for _ in range(3):
                s.pair_start(client_key("2001:db8:1:%x::1" % i))

    def test_counter_flood_does_not_block_correct_code(self):
        """검증 재현 — 처음 보는 키 4,096개가 틀린 번호를 한 번씩 보내 계수기를 채워도 정상 사용자의 맞는 번호는 통과한다."""
        for i in range(4096):  # /48 안 /64 4,096개 — 지금은 한 키로 묶여 10번째부터 막힌다
            try:
                self.s.pair_join({"pairCode": "x"}, client_key("2001:db8:7:%x::1" % i))
            except ApiError:
                pass
        for i in range(4096):  # 서로 다른 IPv4 4,096개 — 계수기가 꽉 찬다
            try:
                self.s.pair_join({"pairCode": "x"}, "198.18.%d.%d" % (i // 256, i % 256))
            except ApiError:
                pass
        self.assertLessEqual(len(self.s._join_fail), Limits().max_keys)
        st = self.s.pair_start()
        self.assertIn("phoneToken", self.s.pair_join({"pairCode": st["pairCode"]}, "192.0.2.77"))
        self.assertApi(429, "RATE_LIMITED", self.s.pair_join, {"pairCode": "x"}, client_key("2001:db8:7:1::1"))  # 막힌 키는 남음


class ReaperTest(unittest.TestCase):
    class Sock:
        def __init__(self):
            self.shut = 0

        def shutdown(self, how):
            self.shut += 1

    def test_rearmed_deadline_is_not_cut(self):
        with _serve() as srv:
            sock = self.Sock()
            srv._deadlines[sock] = 1.0      # 지난 마감
            srv.arm_deadline(sock)          # 다음 요청이 새 마감을 걸었다
            srv.reap_once(time.monotonic())
            self.assertEqual(sock.shut, 0)
            srv.disarm_deadline(sock)

    def test_expired_deadline_is_cut_once(self):
        with _serve() as srv:
            sock = self.Sock()
            srv._deadlines[sock] = 1.0
            srv.reap_once(time.monotonic())
            srv.reap_once(time.monotonic())
            self.assertEqual(sock.shut, 1)


class HttpLimitTest(unittest.TestCase):
    def call(self, port, method, path, body=None, headers=None):
        conn = http.client.HTTPConnection("127.0.0.1", port, timeout=5)
        h = {"Content-Type": "application/json"}
        h.update(headers or {})
        conn.request(method, path, body=json.dumps(body).encode() if body is not None else b"", headers=h)
        resp = conn.getresponse()
        out = resp.status, json.loads(resp.read() or b"null")
        conn.close()
        return out

    def _flood(self, port, xff=None):
        h = {"X-Forwarded-For": xff} if xff else {}
        return [self.call(port, "POST", "/v1/pair/join", {"pairCode": "%06d" % i}, h)[0] for i in range(11)]

    def test_trust_proxy_uses_forwarded_address(self):
        with _serve(RelayStore(limits=Limits(trust_proxy=True))) as srv:
            port = srv.server_address[1]
            self.assertEqual(self._flood(port, "9.9.9.9, " + A), [404] * 10 + [429])
            self.assertEqual(self._flood(port, B)[0], 404)  # 다른 주소로 온 요청은 막히지 않는다

    def test_without_trust_proxy_header_is_ignored(self):
        with _serve() as srv:
            port = srv.server_address[1]
            st = [self.call(port, "POST", "/v1/pair/join", {"pairCode": "%06d" % i},
                            {"X-Forwarded-For": "198.51.100.%d" % i})[0] for i in range(11)]
            self.assertEqual(st, [404] * 10 + [429])  # 헤더를 바꿔 보내도 소켓 주소 하나로 센다

    def test_429_body_and_logs_have_no_address(self):
        stderr = io.StringIO()
        with _serve(RelayStore(limits=Limits(trust_proxy=True))) as srv, \
                self.assertLogs("guardian-relay", level=logging.DEBUG) as logs, redirect_stderr(stderr):
            port = srv.server_address[1]
            self._flood(port, A)
            self.assertEqual(self.call(port, "POST", "/v1/pair/join", {"pairCode": "123456"},
                                       {"X-Forwarded-For": A}), (429, {"error": "RATE_LIMITED"}))
        text = "\n".join(logs.output) + stderr.getvalue()
        self.assertIn("POST /v1/pair/join 429", text)
        for v in (A, "198.51.100", "123456", "000010"):
            self.assertNotIn(v, text)

    def test_connection_limit_closes_extra_then_recovers(self):
        with _serve(RelayStore(limits=Limits(max_conns=2))) as srv, \
                self.assertLogs("guardian-relay", level=logging.WARNING) as logs:
            port = srv.server_address[1]
            idle = [socket.create_connection(("127.0.0.1", port), timeout=5) for _ in range(2)]
            time.sleep(0.2)
            self.assertEqual(srv.active_connections(), 2)
            extra = socket.create_connection(("127.0.0.1", port), timeout=5)
            extra.sendall(b"GET /v1/health HTTP/1.1\r\nHost: x\r\n\r\n")
            try:
                self.assertEqual(extra.recv(4096), b"")  # 응답 없이 닫는다
            except ConnectionResetError:
                pass
            extra.close()
            idle[0].close()
            deadline = time.monotonic() + 3
            while srv.active_connections() > 1 and time.monotonic() < deadline:
                time.sleep(0.05)
            self.assertEqual(self.call(port, "GET", "/v1/health"), (200, {"ok": True}))
            idle[1].close()
        self.assertTrue(any("connection limit" in line for line in logs.output))
        self.assertFalse(any("127.0.0.1" in line for line in logs.output))

    def test_slowloris_is_cut_by_request_deadline(self):
        # 바이트를 0.2초마다 하나씩 — 소켓 timeout(10초)으로는 끊기지 않지만 전체 마감(0.6초)으로 끊긴다
        with _serve(RelayStore(limits=Limits(request_deadline_s=0.6))) as srv, \
                self.assertLogs("guardian-relay", level=logging.WARNING):
            sock = socket.create_connection(("127.0.0.1", srv.server_address[1]), timeout=5)
            t0 = time.monotonic()
            cut = False
            for ch in b"POST /v1/car/sync HTTP/1.1\r\nHost: xxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx\r\n":
                try:
                    sock.sendall(bytes([ch]))
                except OSError:
                    cut = True
                    break
                time.sleep(0.2)
                if time.monotonic() - t0 > 3:
                    break
            if not cut:
                sock.settimeout(2)
                try:
                    cut = sock.recv(4096) == b""
                except ConnectionResetError:
                    cut = True
            self.assertTrue(cut)
            self.assertLess(time.monotonic() - t0, 3.5)
            sock.close()

    def test_normal_keepalive_requests_are_not_cut(self):
        with _serve(RelayStore(limits=Limits(request_deadline_s=0.6))) as srv:
            conn = http.client.HTTPConnection("127.0.0.1", srv.server_address[1], timeout=5)
            for _ in range(4):  # 한 연결로 0.3초 간격 요청 — 마감은 요청마다 새로 잡는다
                conn.request("GET", "/v1/health")
                resp = conn.getresponse()
                self.assertEqual(resp.status, 200)
                resp.read()
                time.sleep(0.3)
            conn.close()

    def test_main_parses_limit_options(self):
        captured = {}

        class Stop(Exception):
            pass

        def fake_make_server(bind, port, store):
            captured["store"] = store
            raise Stop

        old, old_cfg = relay.make_server, relay.logging.basicConfig
        relay.make_server = fake_make_server
        relay.logging.basicConfig = lambda **kw: None  # 시험 중 뿌리 로거에 처리기를 더하지 않는다
        try:
            with self.assertRaises(Stop):
                relay.main(["--port", "18001", "--trust-proxy", "--join-fail-per-addr", "3", "--join-block-sec", "60",
                            "--start-per-addr", "4", "--max-pairs", "7", "--max-conns", "9",
                            "--request-deadline-sec", "1.5"])
        finally:
            relay.make_server, relay.logging.basicConfig = old, old_cfg
        L = captured["store"].limits
        self.assertEqual((L.trust_proxy, L.join_fail_per_addr, L.join_block_ms, L.start_per_addr, L.max_pairs,
                          L.max_conns, L.request_deadline_s), (True, 3, 60_000, 4, 7, 9, 1.5))
        with redirect_stderr(io.StringIO()), self.assertRaises(SystemExit):
            relay.main(["--max-pairs", "0"])

    def test_defaults_keep_demo_open(self):
        L = Limits()
        self.assertFalse(L.trust_proxy)
        self.assertGreaterEqual(L.start_per_addr * L.start_window_ms / MIN, 6)  # 차가 실패 때 10초마다 다시 = 분당 6
        self.assertGreaterEqual(L.max_conns, 16)
        self.assertGreater(L.request_deadline_s, relay.SOCKET_TIMEOUT_S)


if __name__ == "__main__":
    unittest.main()
