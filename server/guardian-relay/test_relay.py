# 중계 서버 단위 시험 — 짝짓기 · sync · 지시 전달 · TTL · 토큰 · 본문 상한 · 로그에 개인정보 없음을 확인한다
import http.client
import io
import json
import logging
import socket
import threading
import time
import unittest
from contextlib import contextmanager, redirect_stderr

import relay
from relay import ApiError, RelayStore


@contextmanager
def _serve(store=None):
    """임시 포트에 실제 서버를 띄웠다가 끈다(시험 전용 — 빨리 끄려고 poll 0.05초)."""
    srv = relay.make_server("127.0.0.1", 0, store=store)
    t = threading.Thread(target=srv.serve_forever, kwargs={"poll_interval": 0.05}, daemon=True)
    t.start()
    try:
        yield srv
    finally:
        srv.shutdown()
        srv.server_close()
        t.join(timeout=5)


class FakeClock:
    def __init__(self, t=1_800_000_000_000):
        self.t = t

    def __call__(self):
        return self.t


# 아래 위치 값은 모두 지어낸 것이다 — 내비 좌표는 자릿수를 늘어놓은 값, 휴대폰 위치는 주소(서울시청)와 같은 공공 지점
VEHICLE = {"speedKmh": 87.5, "gear": "D", "parkingBrake": False, "brakePedal": False,
           "airbagDeployed": False, "make": "Hyundai", "model": "Grandeur", "year": 2026,
           "naviAddress": "경부고속도로 서울방향 123.4km", "naviLat": 36.1234567, "naviLon": 127.7654321}
WATCH = {"verdict": "PULSE_WEAK", "hr": 173, "lastPulseAt": 1_800_000_000_000, "worn": True,
         "source": "SIMULATION", "test": True, "responseAt": None}
LOC = {"lat": 37.5665, "lon": 126.978, "accuracyM": 8.0, "speedKmh": 0.0,
       "at": 1_800_000_000_000, "address": "서울특별시 중구 세종대로 110"}
FLOW = {"state": "S1", "since": 1_800_000_000_000, "owner": "CAR", "test": True, "snapshot": {"k": 1}}


def cmd(i, frm, type_="SHOW", ttl=30_000):  # 기본은 허용 표(D-1) 안 — 차 ↔ 휴대폰 SHOW
    return {"id": i, "type": type_, "payload": {"text": "안전한 곳에 차를 세우세요"},
            "ttlMs": ttl, "test": True, "from": frm}


def car_body(tok, seq, commands=(), acks=(), vehicle=VEHICLE, flow=None):
    return {"token": tok, "seq": seq, "sentAt": 1_800_000_000_000 + seq, "vehicle": vehicle,
            "flow": flow, "commands": list(commands), "acks": list(acks)}


def phone_body(tok, seq, commands=(), acks=(), watch=WATCH, loc=LOC, flow=None):
    return {"token": tok, "seq": seq, "sentAt": 1_800_000_000_000 + seq, "watch": watch,
            "phoneLocation": loc, "flow": flow, "commands": list(commands), "acks": list(acks)}


class _StoreBase(unittest.TestCase):
    def setUp(self):
        self.clock = FakeClock()
        self.s = RelayStore(clock=self.clock)

    def paired(self):
        start = self.s.pair_start()
        join = self.s.pair_join({"pairCode": start["pairCode"]})
        return start["carToken"], join["phoneToken"]

    def assertApi(self, status, code, fn, *a):
        with self.assertRaises(ApiError) as cm:
            fn(*a)
        self.assertEqual((cm.exception.status, cm.exception.code), (status, code))


class StoreTest(_StoreBase):

    # ---- 짝짓기 ----
    def test_pair_start_shape(self):
        r = self.s.pair_start()
        self.assertRegex(r["pairCode"], r"^\d{6}$")
        self.assertRegex(r["carToken"], r"^[0-9a-f]{32}$")
        self.assertEqual(r["expiresInSec"], 600)

    def test_pair_join_success(self):
        r = self.s.pair_start()
        j = self.s.pair_join({"pairCode": r["pairCode"]})
        self.assertRegex(j["phoneToken"], r"^[0-9a-f]{32}$")
        self.assertRegex(j["pairId"], r"^[0-9a-f]{16}$")
        self.assertNotEqual(j["phoneToken"], r["carToken"])

    def test_pair_code_expires_after_10_minutes(self):
        r = self.s.pair_start()
        self.clock.t += 600_000
        self.assertApi(404, "PAIR_CODE_INVALID", self.s.pair_join, {"pairCode": r["pairCode"]})

    def test_pair_code_just_before_expiry_ok(self):
        r = self.s.pair_start()
        self.clock.t += 599_999
        self.s.pair_join({"pairCode": r["pairCode"]})

    def test_pair_code_single_use(self):
        r = self.s.pair_start()
        self.s.pair_join({"pairCode": r["pairCode"]})
        self.assertApi(404, "PAIR_CODE_INVALID", self.s.pair_join, {"pairCode": r["pairCode"]})

    def test_pair_code_wrong_or_missing(self):
        self.s.pair_start()
        self.assertApi(404, "PAIR_CODE_INVALID", self.s.pair_join, {"pairCode": "abc"})
        self.assertApi(404, "PAIR_CODE_INVALID", self.s.pair_join, {})
        self.assertApi(404, "PAIR_CODE_INVALID", self.s.pair_join, {"pairCode": 123456})

    def test_token_invalid(self):
        car, phone = self.paired()
        self.assertApi(401, "TOKEN_INVALID", self.s.sync, "CAR", car_body("0" * 32, 1))
        self.assertApi(401, "TOKEN_INVALID", self.s.sync, "CAR", car_body(None, 1))
        # 역할이 다른 토큰도 거부
        self.assertApi(401, "TOKEN_INVALID", self.s.sync, "CAR", car_body(phone, 1))
        self.assertApi(401, "TOKEN_INVALID", self.s.sync, "PHONE", phone_body(car, 1))

    def test_pair_ttl_24h(self):
        car, phone = self.paired()
        self.clock.t += 24 * 3600 * 1000 - 1
        self.s.sync("CAR", car_body(car, 1))
        self.clock.t += 1
        self.assertApi(401, "TOKEN_INVALID", self.s.sync, "CAR", car_body(car, 2))
        self.assertApi(401, "TOKEN_INVALID", self.s.sync, "PHONE", phone_body(phone, 1))

    def test_pair_end_invalidates_both_tokens(self):
        car, phone = self.paired()
        self.assertEqual(self.s.pair_end({"token": phone}), {})
        self.assertApi(401, "TOKEN_INVALID", self.s.sync, "CAR", car_body(car, 1))
        self.assertApi(401, "TOKEN_INVALID", self.s.sync, "PHONE", phone_body(phone, 1))
        self.assertApi(401, "TOKEN_INVALID", self.s.pair_end, {"token": car})

    def test_pair_end_before_join_kills_code(self):
        r = self.s.pair_start()
        self.s.pair_end({"token": r["carToken"]})
        self.assertApi(404, "PAIR_CODE_INVALID", self.s.pair_join, {"pairCode": r["pairCode"]})

    # ---- sync ----
    def test_bidirectional_state(self):
        car, phone = self.paired()
        r = self.s.sync("CAR", car_body(car, 1, flow=FLOW))
        self.assertIsNone(r["peer"])
        self.assertEqual(r["commands"], [])
        self.assertEqual(r["serverTime"], self.clock.t)

        self.clock.t += 500
        r = self.s.sync("PHONE", phone_body(phone, 7))
        self.assertEqual(r["peer"], {"seq": 1, "sentAt": 1_800_000_000_001,
                                     "receivedAt": self.clock.t - 500, "vehicle": VEHICLE, "flow": FLOW})

        self.clock.t += 500
        r = self.s.sync("CAR", car_body(car, 2))
        self.assertEqual(r["peer"], {"seq": 7, "sentAt": 1_800_000_000_007,
                                     "receivedAt": self.clock.t - 500, "watch": WATCH, "flow": None,
                                     "responseAt": None, "carDisplay": None})

    def test_state_overwritten_latest_only(self):
        car, phone = self.paired()
        self.s.sync("CAR", car_body(car, 1, vehicle=dict(VEHICLE, speedKmh=10)))
        self.s.sync("CAR", car_body(car, 2, vehicle=dict(VEHICLE, speedKmh=0)))
        r = self.s.sync("PHONE", phone_body(phone, 1))
        self.assertEqual(r["peer"]["seq"], 2)
        self.assertEqual(r["peer"]["vehicle"]["speedKmh"], 0)

    def test_seq_regression_ignored(self):
        car, phone = self.paired()
        self.s.sync("CAR", car_body(car, 5, vehicle=dict(VEHICLE, speedKmh=5)))
        self.clock.t += 100
        r = self.s.sync("CAR", car_body(car, 4, vehicle=dict(VEHICLE, speedKmh=99)))
        self.assertEqual(r["serverTime"], self.clock.t)  # 응답은 정상으로 준다
        peer = self.s.sync("PHONE", phone_body(phone, 1))["peer"]
        self.assertEqual(peer["seq"], 5)
        self.assertEqual(peer["vehicle"]["speedKmh"], 5)
        self.assertEqual(peer["receivedAt"], self.clock.t - 100)

    def test_seq_equal_overwrites(self):
        car, phone = self.paired()
        self.s.sync("CAR", car_body(car, 5, vehicle=dict(VEHICLE, speedKmh=5)))
        self.s.sync("CAR", car_body(car, 5, vehicle=dict(VEHICLE, speedKmh=6)))
        self.assertEqual(self.s.sync("PHONE", phone_body(phone, 1))["peer"]["vehicle"]["speedKmh"], 6)

    def test_command_delivery_and_redelivery_until_ack(self):
        car, phone = self.paired()
        self.s.sync("CAR", car_body(car, 1, commands=[cmd("c1", "CAR"), cmd("c2", "CAR", "ALERT_START")]))
        r1 = self.s.sync("PHONE", phone_body(phone, 1))
        self.assertEqual([c["id"] for c in r1["commands"]], ["c1", "c2"])
        self.assertEqual(r1["commands"][0], dict(cmd("c1", "CAR"), to="PHONE"))
        r2 = self.s.sync("PHONE", phone_body(phone, 2))
        self.assertEqual([c["id"] for c in r2["commands"]], ["c1", "c2"])  # ack 전까지 다시
        r3 = self.s.sync("PHONE", phone_body(phone, 3, acks=["c1"]))
        self.assertEqual([c["id"] for c in r3["commands"]], ["c2"])
        r4 = self.s.sync("PHONE", phone_body(phone, 4, acks=["c2", "unknown"]))
        self.assertEqual(r4["commands"], [])
        # 보낸 쪽 자신은 자기 지시를 받지 않는다
        self.assertEqual(self.s.sync("CAR", car_body(car, 2))["commands"], [])

    def test_phone_to_car_commands(self):
        car, phone = self.paired()
        self.s.sync("PHONE", phone_body(phone, 1, commands=[cmd("p1", "PHONE")]))  # 휴대폰 → 차 SHOW(허용 표 안)
        r = self.s.sync("CAR", car_body(car, 1))
        self.assertEqual([(c["id"], c["from"]) for c in r["commands"]], [("p1", "PHONE")])

    def test_command_ttl_expiry(self):
        car, phone = self.paired()
        self.s.sync("CAR", car_body(car, 1, commands=[cmd("short", "CAR", ttl=1000), cmd("long", "CAR", ttl=5000)]))
        self.clock.t += 999
        self.assertEqual([c["id"] for c in self.s.sync("PHONE", phone_body(phone, 1))["commands"]], ["short", "long"])
        self.clock.t += 1
        self.assertEqual([c["id"] for c in self.s.sync("PHONE", phone_body(phone, 2))["commands"]], ["long"])
        self.clock.t += 4000
        self.assertEqual(self.s.sync("PHONE", phone_body(phone, 3))["commands"], [])

    def test_command_sent_before_join_is_delivered(self):
        start = self.s.pair_start()
        self.s.sync("CAR", car_body(start["carToken"], 1, commands=[cmd("early", "CAR")]))
        phone = self.s.pair_join({"pairCode": start["pairCode"]})["phoneToken"]
        self.assertEqual([c["id"] for c in self.s.sync("PHONE", phone_body(phone, 1))["commands"]], ["early"])

    def test_duplicate_command_id_enqueued_once(self):
        car, phone = self.paired()
        self.s.sync("CAR", car_body(car, 1, commands=[cmd("c1", "CAR")]))
        self.s.sync("PHONE", phone_body(phone, 1, acks=["c1"]))
        self.s.sync("CAR", car_body(car, 1, commands=[cmd("c1", "CAR")]))  # 재전송
        self.assertEqual(self.s.sync("PHONE", phone_body(phone, 2))["commands"], [])

    def test_commands_from_regressed_seq_still_delivered(self):
        car, phone = self.paired()
        self.s.sync("CAR", car_body(car, 5))
        self.s.sync("CAR", car_body(car, 3, commands=[cmd("late", "CAR")]))
        self.assertEqual([c["id"] for c in self.s.sync("PHONE", phone_body(phone, 1))["commands"]], ["late"])

    def test_queue_cap_drops_oldest(self):
        car, phone = self.paired()
        n = relay.MAX_QUEUE + 10
        seq = 0
        for i in range(0, n, relay.MAX_COMMANDS_PER_SYNC):
            seq += 1
            batch = [cmd("c%d" % k, "CAR") for k in range(i, min(n, i + relay.MAX_COMMANDS_PER_SYNC))]
            self.s.sync("CAR", car_body(car, seq, commands=batch))
        ids = [c["id"] for c in self.s.sync("PHONE", phone_body(phone, 1))["commands"]]
        self.assertEqual(len(ids), relay.MAX_QUEUE)
        self.assertEqual(ids[0], "c10")

    def test_queue_exact_256_and_257(self):
        for n, first in ((relay.MAX_QUEUE, "c0"), (relay.MAX_QUEUE + 1, "c1")):
            with self.subTest(n=n):
                car, phone = self.paired()
                seq = 0
                for i in range(0, n, relay.MAX_COMMANDS_PER_SYNC):
                    seq += 1
                    batch = [cmd("c%d" % k, "CAR") for k in range(i, min(n, i + relay.MAX_COMMANDS_PER_SYNC))]
                    self.s.sync("CAR", car_body(car, seq, commands=batch))
                ids = [c["id"] for c in self.s.sync("PHONE", phone_body(phone, 1))["commands"]]
                self.assertEqual((len(ids), ids[0], ids[-1]), (relay.MAX_QUEUE, first, "c%d" % (n - 1)))

    def test_ttl_bounds_1_and_max(self):
        car, phone = self.paired()
        self.s.sync("CAR", car_body(car, 1, commands=[cmd("t1", "CAR", ttl=1), cmd("tmax", "CAR", ttl=relay.MAX_COMMAND_TTL_MS)]))
        self.assertEqual([c["id"] for c in self.s.sync("PHONE", phone_body(phone, 1))["commands"]], ["t1", "tmax"])
        self.clock.t += 1
        self.assertEqual([c["id"] for c in self.s.sync("PHONE", phone_body(phone, 2))["commands"]], ["tmax"])
        self.clock.t += relay.MAX_COMMAND_TTL_MS - 2  # D-3 상한 120초(이전 1시간)
        self.assertEqual([c["id"] for c in self.s.sync("PHONE", phone_body(phone, 3))["commands"]], ["tmax"])
        self.clock.t += 1
        self.assertEqual(self.s.sync("PHONE", phone_body(phone, 4))["commands"], [])

    def test_id_length_64_ok_65_bad(self):
        car, phone = self.paired()
        self.s.sync("CAR", car_body(car, 1, commands=[cmd("a" * 64, "CAR")]))
        self.assertApi(400, "BAD_REQUEST", self.s.sync, "CAR", car_body(car, 2, commands=[cmd("a" * 65, "CAR")]))

    def test_omitted_optional_fields_equal_null_or_empty(self):
        car, phone = self.paired()
        self.s.sync("CAR", {"token": car, "seq": 1, "sentAt": 1, "vehicle": VEHICLE})
        self.s.sync("CAR", {"token": car, "seq": 2, "sentAt": 2, "vehicle": VEHICLE, "flow": None,
                            "commands": None, "acks": None})
        self.s.sync("CAR", car_body(car, 3, commands=[{"id": "n1", "type": "SHOW", "ttlMs": 1000, "test": False,
                                                      "from": "CAR", "payload": None, "to": None}]))
        r = self.s.sync("PHONE", {"token": phone, "seq": 1, "sentAt": 1})
        self.assertEqual(r["commands"], [{"id": "n1", "type": "SHOW", "payload": {}, "ttlMs": 1000,
                                          "test": False, "from": "CAR", "to": "PHONE"}])
        peer = self.s.sync("CAR", car_body(car, 4))["peer"]
        self.assertEqual((peer["watch"], peer["flow"]), (None, None))
        self.assertNotIn("phoneLocation", peer)
        # 필수 — token · seq · sentAt(그리고 차 vehicle)
        for missing in ("seq", "sentAt", "vehicle"):
            b = car_body(car, 5)
            del b[missing]
            with self.subTest(missing=missing):
                self.assertApi(400, "BAD_REQUEST", self.s.sync, "CAR", b)
        b = car_body(car, 5)
        del b["token"]
        self.assertApi(401, "TOKEN_INVALID", self.s.sync, "CAR", b)

    def test_seen_ids_cleared_after_ttl(self):
        car, phone = self.paired()
        self.s.sync("CAR", car_body(car, 1, commands=[cmd("c1", "CAR", ttl=1000)]))
        self.s.sync("PHONE", phone_body(phone, 1, acks=["c1"]))
        pair = next(iter(self.s._pairs.values()))
        self.assertIn("c1", pair.seen_ids["PHONE"])
        self.clock.t += 999
        self.s.sync("CAR", car_body(car, 2, commands=[cmd("c1", "CAR", ttl=1000)]))  # ttl 안 재전송은 무시
        self.assertEqual(self.s.sync("PHONE", phone_body(phone, 2))["commands"], [])
        self.clock.t += 1
        self.s.sync("PHONE", phone_body(phone, 3))
        self.assertEqual(pair.seen_ids["PHONE"], {})

    def test_pair_capacity_64(self):
        tokens = [self.s.pair_start()["carToken"] for _ in range(relay.MAX_PAIRS)]
        self.assertApi(503, "CAPACITY", self.s.pair_start)
        self.s.pair_end({"token": tokens[0]})
        self.s.pair_start()
        self.assertApi(503, "CAPACITY", self.s.pair_start)
        # 24시간이 지나 만료된 짝은 정리되고 새로 받는다
        self.clock.t += relay.PAIR_TTL_MS
        self.s.pair_start()
        self.assertEqual(len(self.s._pairs), 1)

    def test_bad_requests(self):
        car, phone = self.paired()
        bad = [
            dict(car_body(car, 1), seq="1"),
            dict(car_body(car, 1), seq=True),
            dict(car_body(car, 1), sentAt=None),
            dict(car_body(car, 1), vehicle=None),
            dict(car_body(car, 1), flow="S1"),
            dict(car_body(car, 1), commands={}),
            dict(car_body(car, 1), acks=[1]),
            car_body(car, 1, commands=[cmd("x", "PHONE")]),             # from 불일치
            car_body(car, 1, commands=[dict(cmd("x", "CAR"), type="CALL_119")]),
            car_body(car, 1, commands=[cmd("x", "CAR", ttl=0)]),
            car_body(car, 1, commands=[cmd("x", "CAR", ttl=relay.MAX_COMMAND_TTL_MS + 1)]),
            car_body(car, 1, commands=[dict(cmd("x", "CAR"), test="yes")]),
            car_body(car, 1, commands=[dict(cmd("", "CAR"))]),
            car_body(car, 1, commands=[cmd("c%d" % i, "CAR") for i in range(relay.MAX_COMMANDS_PER_SYNC + 1)]),
        ]
        for b in bad:
            with self.subTest(b=b):
                self.assertApi(400, "BAD_REQUEST", self.s.sync, "CAR", b)
        self.assertApi(400, "BAD_REQUEST", self.s.sync, "PHONE", dict(phone_body(phone, 1), watch="NORMAL"))
        self.assertApi(400, "BAD_REQUEST", self.s.sync, "PHONE", dict(phone_body(phone, 1), phoneLocation=[1, 2]))
        # 위치 없음 · 워치 없음(null)은 허용
        self.s.sync("PHONE", phone_body(phone, 1, loc=None, watch=None))
        # "to" 검사
        self.assertApi(400, "BAD_REQUEST", self.s.sync, "CAR", car_body(car, 2, commands=[dict(cmd("x", "CAR"), to="CAR")]))
        self.assertApi(400, "BAD_REQUEST", self.s.sync, "CAR", car_body(car, 2, commands=[dict(cmd("x", "CAR"), to="SERVER")]))
        # 생략 · null 이 아닌 빈 값 · 다른 형은 기본 대상으로 바꾸지 않고 거부
        for to in ("", 0, False, [], {}, "phone"):
            with self.subTest(to=to):
                self.assertApi(400, "BAD_REQUEST", self.s.sync, "CAR",
                               car_body(car, 2, commands=[dict(cmd("x", "CAR"), to=to)]))


def watch_body(tok, seq, acks=(), watch=WATCH, commands=()):
    return {"token": tok, "seq": seq, "sentAt": 1_800_000_000_000 + seq, "watch": watch,
            "acks": list(acks), "commands": list(commands)}


class PhoneResponseTest(_StoreBase):
    """휴대폰 화면 응답(responseAt) 전달 · 설정 전달(꺼짐 flow 스냅숏)."""

    def test_phone_response_at_reaches_car_peer(self):
        car, phone = self.paired()
        body = phone_body(phone, 1)
        body["responseAt"] = 1_800_000_000_500
        self.s.sync("PHONE", body)
        peer = self.s.sync("CAR", car_body(car, 1))["peer"]
        self.assertEqual(peer["responseAt"], 1_800_000_000_500)
        # 생략하면 null
        self.s.sync("PHONE", phone_body(phone, 2))
        self.assertIsNone(self.s.sync("CAR", car_body(car, 2))["peer"]["responseAt"])
        # 휴대폰 쪽 응답에는 실리지 않는다(차의 peer 전용)
        self.assertNotIn("responseAt", self.s.sync("PHONE", phone_body(phone, 3))["peer"])

    def test_phone_response_at_bad_types(self):
        car, phone = self.paired()
        for v in ("1", 1.5, True, {}, [1]):
            body = phone_body(phone, 1)
            body["responseAt"] = v
            self.assertApi(400, "BAD_REQUEST", self.s.sync, "PHONE", body)
        self.assertIsNone(self.s.sync("CAR", car_body(car, 1))["peer"])

    def test_phone_self_target_rejected(self):
        """휴대폰이 자기 자신(PHONE)에게 낸 지시는 400 — 휴대폰 주인은 자기 대상 지시를 중계로 보내지 않는다(splitLocal)."""
        car, phone = self.paired()
        self.assertApi(400, "BAD_REQUEST", self.s.sync, "PHONE",
                       phone_body(phone, 1, commands=[dict(cmd("x", "PHONE"), to="PHONE")]))
        self.assertIsNone(self.s.sync("CAR", car_body(car, 1))["peer"])

    def test_phone_off_flow_snapshot_passes_to_car(self):
        """휴대폰이 꺼짐(OFF)이어도 flow 스냅숏은 막지 않고 차 peer 로 넘긴다 — 차 앱이 켬 · 동의 · 테스트 모드를 읽는다."""
        car, phone = self.paired()
        off = {"state": "OFF", "since": 5, "owner": "CAR", "test": True,
               "snapshot": {"clock": "LOCAL", "powerOn": False, "consents": {"emergencyInfo": True, "health": False, "location": True}}}
        self.s.sync("PHONE", phone_body(phone, 1, flow=off))
        self.assertEqual(self.s.sync("CAR", car_body(car, 1))["peer"]["flow"], off)


class CarScreenTest(_StoreBase):
    """차 화면 표시 설정(carDisplay)과 차 → 휴대폰 테스트 요청(TEST_REQUEST) — 2026-11-01."""

    def test_car_display_reaches_car_peer_only(self):
        car, phone = self.paired()
        body = phone_body(phone, 1)
        body["carDisplay"] = {"hideHr": True}
        self.s.sync("PHONE", body)
        self.assertEqual(self.s.sync("CAR", car_body(car, 1))["peer"]["carDisplay"], {"hideHr": True})
        # 최신 1건 — 바꾸면 덮는다
        body = phone_body(phone, 2)
        body["carDisplay"] = {"hideHr": False}
        self.s.sync("PHONE", body)
        self.assertEqual(self.s.sync("CAR", car_body(car, 2))["peer"]["carDisplay"], {"hideHr": False})
        # 생략하면 null(차는 모름 → 숨김)
        self.s.sync("PHONE", phone_body(phone, 3))
        self.assertIsNone(self.s.sync("CAR", car_body(car, 3))["peer"]["carDisplay"])
        # 휴대폰이 받는 차 peer 에는 없다
        self.assertNotIn("carDisplay", self.s.sync("PHONE", phone_body(phone, 4))["peer"])

    def test_car_display_not_overwritten_by_older_seq(self):
        car, phone = self.paired()
        body = phone_body(phone, 10)
        body["carDisplay"] = {"hideHr": True}
        self.s.sync("PHONE", body)
        old = phone_body(phone, 5)
        old["carDisplay"] = {"hideHr": False}
        self.s.sync("PHONE", old)
        self.assertEqual(self.s.sync("CAR", car_body(car, 1))["peer"]["carDisplay"], {"hideHr": True})

    def test_car_display_inner_not_checked(self):
        """안쪽은 보지 않는다(겉 형만) — 차의 :shared 파서가 모르는 값을 「모름(숨김)」으로 읽는다."""
        car, phone = self.paired()
        body = phone_body(phone, 1)
        body["carDisplay"] = {"hideHr": "yes", "future": 1}
        self.s.sync("PHONE", body)
        self.assertEqual(self.s.sync("CAR", car_body(car, 1))["peer"]["carDisplay"], {"hideHr": "yes", "future": 1})

    def test_car_display_bad_types(self):
        car, phone = self.paired()
        for v in (True, 1, "hide", [True]):
            with self.subTest(v=v):
                body = phone_body(phone, 1)
                body["carDisplay"] = v
                self.assertApi(400, "BAD_REQUEST", self.s.sync, "PHONE", body)
        self.assertIsNone(self.s.sync("CAR", car_body(car, 1))["peer"])

    def test_test_request_car_to_phone(self):
        car, phone = self.paired()
        start = dict(cmd("tr-1", "CAR", "TEST_REQUEST"),
                     payload={"action": "START", "scenario": "PULSE_LOST", "durationMs": 120000})
        stop = dict(cmd("tr-2", "CAR", "TEST_REQUEST"), payload={"action": "STOP"}, to="PHONE")
        self.s.sync("CAR", car_body(car, 1, commands=[start, stop]))
        got = self.s.sync("PHONE", phone_body(phone, 1))["commands"]
        self.assertEqual([c["id"] for c in got], ["tr-1", "tr-2"])
        self.assertEqual(got[0]["to"], "PHONE")  # 생략하면 차 → 휴대폰
        self.assertEqual(got[0]["payload"], start["payload"])
        self.assertTrue(all(c["type"] == "TEST_REQUEST" and c["test"] for c in got))
        # ack 하면 지운다
        self.assertEqual(self.s.sync("PHONE", phone_body(phone, 2, acks=["tr-1", "tr-2"]))["commands"], [])

    def test_test_request_payload_not_checked(self):
        """payload 는 들여다보지 않는다 — 형식 오류는 받는 휴대폰이 그 지시만 건너뛴다(§4.6)."""
        car, phone = self.paired()
        odd = dict(cmd("tr-x", "CAR", "TEST_REQUEST"), payload={"action": "DANCE"})
        self.s.sync("CAR", car_body(car, 1, commands=[odd]))
        self.assertEqual([c["id"] for c in self.s.sync("PHONE", phone_body(phone, 1))["commands"]], ["tr-x"])


class WatchDirectTest(_StoreBase):
    """워치가 중계에 직접 붙는 경로(Note10+ 가 Android 12 라 Data Layer 를 못 쓸 수 있음)."""

    def tripled(self):
        start = self.s.pair_start()
        phone = self.s.pair_join({"pairCode": start["pairCode"]})["phoneToken"]
        j = self.s.pair_join({"pairCode": start["pairCode"], "role": "WATCH"})
        self.assertEqual(set(j), {"watchToken", "pairId"})
        self.assertRegex(j["watchToken"], r"^[0-9a-f]{32}$")
        return start["carToken"], phone, j["watchToken"]

    def test_join_role_default_phone_and_bad_role(self):
        start = self.s.pair_start()
        self.assertApi(400, "BAD_REQUEST", self.s.pair_join, {"pairCode": start["pairCode"], "role": "CAR"})
        j = self.s.pair_join({"pairCode": start["pairCode"]})
        self.assertEqual(set(j), {"phoneToken", "pairId"})

    def test_code_single_use_per_role(self):
        start = self.s.pair_start()
        w = self.s.pair_join({"pairCode": start["pairCode"], "role": "WATCH"})
        self.assertApi(404, "PAIR_CODE_INVALID", self.s.pair_join, {"pairCode": start["pairCode"], "role": "WATCH"})
        p = self.s.pair_join({"pairCode": start["pairCode"], "role": "PHONE"})
        self.assertEqual(w["pairId"], p["pairId"])
        self.assertApi(404, "PAIR_CODE_INVALID", self.s.pair_join, {"pairCode": start["pairCode"]})

    def test_code_expiry_applies_to_watch(self):
        start = self.s.pair_start()
        self.s.pair_join({"pairCode": start["pairCode"]})
        self.clock.t += 600_000
        self.assertApi(404, "PAIR_CODE_INVALID", self.s.pair_join, {"pairCode": start["pairCode"], "role": "WATCH"})

    def test_watch_token_only_on_watch_sync(self):
        car, phone, watch = self.tripled()
        self.assertApi(401, "TOKEN_INVALID", self.s.sync, "WATCH", watch_body(phone, 1))
        self.assertApi(401, "TOKEN_INVALID", self.s.sync, "PHONE", phone_body(watch, 1))
        self.assertApi(401, "TOKEN_INVALID", self.s.sync, "CAR", car_body(watch, 1))

    def test_watch_sync_shape_and_direct_watch_reaches_car_and_phone(self):
        car, phone, watch = self.tripled()
        r = self.s.sync("WATCH", watch_body(watch, 1))
        self.assertEqual(r, {"serverTime": self.clock.t, "commands": []})
        t0 = self.clock.t
        rc = self.s.sync("CAR", car_body(car, 1))
        self.assertEqual(rc["watch"], dict(WATCH, via="WATCH", receivedAt=t0, ageMs=0))
        self.clock.t += 1500
        rp = self.s.sync("PHONE", phone_body(phone, 1, watch=None))
        self.assertEqual(rp["watch"], dict(WATCH, via="WATCH", receivedAt=t0, ageMs=1500))
        # 휴대폰 경유 워치 값은 그대로 peer.watch 에도 남는다(null)
        self.assertIsNone(self.s.sync("CAR", car_body(car, 2))["peer"]["watch"])

    def test_chosen_watch_is_latest_received(self):
        car, phone, watch = self.tripled()
        self.assertIsNone(self.s.sync("CAR", car_body(car, 1))["watch"])
        self.s.sync("WATCH", watch_body(watch, 1, watch=dict(WATCH, verdict="NORMAL")))
        self.clock.t += 1000
        self.s.sync("PHONE", phone_body(phone, 1, watch=dict(WATCH, verdict="PULSE_ABSENT")))
        self.assertEqual(self.s.sync("CAR", car_body(car, 2))["watch"]["via"], "PHONE")
        self.assertEqual(self.s.sync("CAR", car_body(car, 3))["watch"]["verdict"], "PULSE_ABSENT")
        self.clock.t += 1000
        self.s.sync("WATCH", watch_body(watch, 2, watch=dict(WATCH, verdict="CHECK_WEAR")))
        w = self.s.sync("PHONE", phone_body(phone, 2, watch=None))["watch"]
        self.assertEqual((w["via"], w["verdict"]), ("WATCH", "CHECK_WEAR"))
        # 휴대폰이 null 을 보내도 이전 직접 값은 남는다
        self.clock.t += 1000
        self.assertEqual(self.s.sync("CAR", car_body(car, 4))["watch"]["via"], "WATCH")

    def test_command_to_watch_and_default_from_watch(self):
        car, phone, watch = self.tripled()
        self.s.sync("CAR", car_body(car, 1, commands=[dict(cmd("w1", "CAR", "ALERT_START"), to="WATCH")]))
        self.assertEqual(self.s.sync("PHONE", phone_body(phone, 1))["commands"], [])
        r = self.s.sync("WATCH", watch_body(watch, 1))
        self.assertEqual([(c["id"], c["from"], c["to"]) for c in r["commands"]], [("w1", "CAR", "WATCH")])
        self.assertEqual(self.s.sync("WATCH", watch_body(watch, 2, acks=["w1"]))["commands"], [])
        # 워치는 지시를 내지 않는다(허용 표 D-1) — 지시가 하나라도 있으면 sync 전체 400, 아무 큐에도 들어가지 않는다
        self.assertApi(400, "BAD_REQUEST", self.s.sync, "WATCH", watch_body(watch, 3, commands=[cmd("r1", "WATCH", "ALERT_STOP")]))
        self.assertEqual(self.s.sync("PHONE", phone_body(phone, 2))["commands"], [])
        self.assertEqual(self.s.sync("CAR", car_body(car, 2))["commands"], [])

    def test_command_to_all(self):
        car, phone, watch = self.tripled()
        self.s.sync("PHONE", phone_body(phone, 1, commands=[dict(cmd("a1", "PHONE", "ALERT_STOP"), to="ALL")]))
        for role, body in (("CAR", car_body(car, 1)), ("WATCH", watch_body(watch, 1))):
            with self.subTest(role=role):
                self.assertEqual([c["id"] for c in self.s.sync(role, body)["commands"]], ["a1"])
        self.assertEqual(self.s.sync("PHONE", phone_body(phone, 2))["commands"], [])
        # 한 쪽의 ack 는 다른 쪽 큐에 영향 없음
        self.s.sync("CAR", car_body(car, 2, acks=["a1"]))
        self.assertEqual([c["id"] for c in self.s.sync("WATCH", watch_body(watch, 2))["commands"]], ["a1"])

    def test_watch_seq_regression_ignored(self):
        car, phone, watch = self.tripled()
        self.s.sync("WATCH", watch_body(watch, 9, watch=dict(WATCH, hr=60)))
        self.s.sync("WATCH", watch_body(watch, 8, watch=dict(WATCH, hr=10)))
        self.assertEqual(self.s.sync("CAR", car_body(car, 1))["watch"]["hr"], 60)

    def test_watch_bad_requests(self):
        car, phone, watch = self.tripled()
        self.assertApi(400, "BAD_REQUEST", self.s.sync, "WATCH", dict(watch_body(watch, 1), watch=None))
        self.assertApi(400, "BAD_REQUEST", self.s.sync, "WATCH", watch_body(watch, 1, commands=[cmd("x", "PHONE")]))
        self.assertApi(400, "BAD_REQUEST", self.s.sync, "WATCH",
                       watch_body(watch, 1, commands=[dict(cmd("x", "WATCH"), to="WATCH")]))
        # commands 는 생략할 수 있다
        b = watch_body(watch, 1)
        del b["commands"]
        self.s.sync("WATCH", b)

    def test_watch_pair_end_keeps_car_phone(self):
        car, phone, watch = self.tripled()
        self.s.sync("WATCH", watch_body(watch, 1))
        self.s.sync("CAR", car_body(car, 1, commands=[dict(cmd("w1", "CAR", "ALERT_START"), to="WATCH")]))
        self.assertEqual(self.s.pair_end({"token": watch}), {})
        self.assertApi(401, "TOKEN_INVALID", self.s.sync, "WATCH", watch_body(watch, 2))
        r = self.s.sync("CAR", car_body(car, 2))
        self.assertIsNone(r["watch"])
        self.s.sync("PHONE", phone_body(phone, 1))

    def test_watch_age_after_one_hour_silence(self):
        car, phone, watch = self.tripled()
        t0 = self.clock.t
        self.s.sync("WATCH", watch_body(watch, 1))
        self.clock.t += 3_600_000
        for role, body in (("CAR", car_body(car, 1)), ("PHONE", phone_body(phone, 1, watch=None))):
            with self.subTest(role=role):
                w = self.s.sync(role, body)["watch"]
                self.assertEqual((w["receivedAt"], w["ageMs"]), (t0, 3_600_000))

    def test_chosen_watch_tie_prefers_direct(self):
        car, phone, watch = self.tripled()
        self.s.sync("PHONE", phone_body(phone, 1, watch=dict(WATCH, verdict="NORMAL")))
        self.s.sync("WATCH", watch_body(watch, 1, watch=dict(WATCH, verdict="CHECK_WEAR")))
        w = self.s.sync("CAR", car_body(car, 1))["watch"]
        self.assertEqual((w["via"], w["verdict"]), ("WATCH", "CHECK_WEAR"))
        # 순서를 바꿔도(같은 시각) 직접 값
        self.s.sync("PHONE", phone_body(phone, 2, watch=dict(WATCH, verdict="PULSE_WEAK")))
        self.assertEqual(self.s.sync("CAR", car_body(car, 2))["watch"]["via"], "WATCH")

    def test_watch_join_at_599999_ok(self):
        start = self.s.pair_start()
        self.clock.t += 599_999
        self.assertIn("watchToken", self.s.pair_join({"pairCode": start["pairCode"], "role": "WATCH"}))

    def test_pair_code_rejoins_watch_only(self):
        car, phone, watch = self.tripled()
        self.s.pair_end({"token": watch})
        r = self.s.pair_code({"token": car})
        self.assertRegex(r["pairCode"], r"^\d{6}$")
        self.assertEqual(r["expiresInSec"], 600)
        self.assertEqual(set(r), {"pairCode", "expiresInSec"})
        # (2026-11-08 바뀜) 참여 중인 휴대폰도 새 코드로 다시 붙을 수 있다 — ReviewFix20261108Test 참고
        w2 = self.s.pair_join({"pairCode": r["pairCode"], "role": "WATCH"})["watchToken"]
        self.s.sync("WATCH", watch_body(w2, 1))
        self.assertEqual(self.s.sync("CAR", car_body(car, 1))["watch"]["via"], "WATCH")
        self.assertApi(404, "PAIR_CODE_INVALID", self.s.pair_join, {"pairCode": r["pairCode"], "role": "WATCH"})

    def test_pair_code_replaces_old_code_and_needs_car_token(self):
        start = self.s.pair_start()
        self.assertApi(401, "TOKEN_INVALID", self.s.pair_code, {"token": "0" * 32})
        r = self.s.pair_code({"token": start["carToken"]})
        self.assertApi(404, "PAIR_CODE_INVALID", self.s.pair_join, {"pairCode": start["pairCode"]})
        phone = self.s.pair_join({"pairCode": r["pairCode"]})["phoneToken"]
        self.assertApi(401, "TOKEN_INVALID", self.s.pair_code, {"token": phone})
        # 새 코드도 10분 만료
        r2 = self.s.pair_code({"token": start["carToken"]})
        self.clock.t += 600_000
        self.assertApi(404, "PAIR_CODE_INVALID", self.s.pair_join, {"pairCode": r2["pairCode"], "role": "WATCH"})

    def test_blue_red_blink_rejected_on_every_path(self):
        car, phone, watch = self.tripled()
        bad = {"type": "AMBIENT", "payload": {"on": True, "mode": "BLUE_RED_BLINK"}}
        ok_cmd = dict(cmd("ok1", "PHONE", "SPEAK"), to="CAR")
        cases = (
            ("CAR", lambda c: car_body(car, 5, vehicle=dict(VEHICLE, speedKmh=1),
                                       commands=[dict(cmd("b-car", "CAR"), to="PHONE", **bad)])),
            ("PHONE", lambda c: phone_body(phone, 5, watch=dict(WATCH, hr=1),
                                           commands=[ok_cmd, dict(cmd("b-ph", "PHONE"), to="CAR", **bad)])),
            ("WATCH", lambda c: watch_body(watch, 5, watch=dict(WATCH, hr=2),
                                           commands=[dict(cmd("b-w", "WATCH"), to="CAR", **bad)])),
        )
        for role, body in cases:
            with self.subTest(role=role):
                self.assertApi(400, "BAD_REQUEST", self.s.sync, role, body(None))
        # 상태도 덮지 않고 지시도 큐에 넣지 않았다(같은 요청의 정상 지시 포함)
        pair = next(iter(self.s._pairs.values()))
        self.assertEqual(pair.state, {"CAR": None, "PHONE": None, "WATCH": None})
        self.assertEqual(pair.queue, {"CAR": [], "PHONE": [], "WATCH": []})
        # 대소문자를 바꿔도 거부 — 허용 표 안 경로(휴대폰 → 차 AMBIENT)로 보내야 깜빡임 검사 자체를 본다(sec-audit 3차)
        for i, mode in enumerate(("blue_red_blink", "Blue_Red_Blink", "BLUE_red_BLINK")):
            with self.subTest(mode=mode):
                self.assertApi(400, "BAD_REQUEST", self.s.sync, "PHONE", phone_body(phone, 5, commands=[
                    dict(cmd("m%d" % i, "PHONE", "AMBIENT"), payload={"on": True, "mode": mode}, to="CAR")]))
        self.assertEqual(pair.queue, {"CAR": [], "PHONE": [], "WATCH": []})
        # 다른 앰비언트 mode 와 끄기는 그대로 통과(앰비언트는 휴대폰 주인 → 차, 허용 표 D-1)
        self.s.sync("PHONE", phone_body(phone, 6, commands=[
            dict(cmd("a1", "PHONE", "AMBIENT"), payload={"on": True, "mode": "INTERIOR_ALERT"}),
            dict(cmd("a2", "PHONE", "AMBIENT"), payload={"on": False, "mode": None}),
            dict(cmd("a3", "PHONE", "AMBIENT"), payload=None)]))
        self.assertEqual([c["id"] for c in self.s.sync("CAR", car_body(car, 6))["commands"]], ["a1", "a2", "a3"])

    def test_car_pair_end_kills_watch(self):
        car, phone, watch = self.tripled()
        self.s.pair_end({"token": car})
        self.assertApi(401, "TOKEN_INVALID", self.s.sync, "WATCH", watch_body(watch, 1))

    # ---- 워치 직접 경로 신선도(watchDirectAgeMs) · 워치 테스트 멈춤(TEST_SCENARIO_STOP), 2026-10-30 ----

    def test_watch_direct_age_even_when_phone_via_is_newer(self):
        car, phone, watch = self.tripled()
        # 직접 값이 없으면 null(휴대폰 경유만 있어도)
        self.s.sync("PHONE", phone_body(phone, 1))
        self.assertIsNone(self.s.sync("PHONE", phone_body(phone, 2))["watchDirectAgeMs"])
        self.assertIsNone(self.s.sync("CAR", car_body(car, 1))["watchDirectAgeMs"])
        t0 = self.clock.t
        self.s.sync("WATCH", watch_body(watch, 1))
        self.clock.t += 1_000
        # 고른 값은 더 늦은 휴대폰 경유지만 직접 경로 나이는 따로 온다
        rp = self.s.sync("PHONE", phone_body(phone, 3))
        self.assertEqual((rp["watch"]["via"], rp["watchDirectAgeMs"]), ("PHONE", 1_000))
        self.clock.t += 29_000
        self.assertEqual(self.s.sync("CAR", car_body(car, 2))["watchDirectAgeMs"], 30_000)
        self.clock.t += 1
        self.assertEqual(self.s.sync("PHONE", phone_body(phone, 4))["watchDirectAgeMs"], 30_001)
        self.assertEqual(self.clock.t - t0, 30_001)

    def test_watch_direct_age_cleared_by_watch_pair_end(self):
        car, phone, watch = self.tripled()
        self.s.sync("WATCH", watch_body(watch, 1))
        self.assertEqual(self.s.sync("PHONE", phone_body(phone, 1))["watchDirectAgeMs"], 0)
        self.s.pair_end({"token": watch})
        self.assertIsNone(self.s.sync("PHONE", phone_body(phone, 2))["watchDirectAgeMs"])

    def test_watch_response_has_no_direct_age(self):
        car, phone, watch = self.tripled()
        self.assertEqual(set(self.s.sync("WATCH", watch_body(watch, 1))), {"serverTime", "commands"})

    def test_test_scenario_stop_phone_to_watch(self):
        car, phone, watch = self.tripled()
        stop = dict(cmd("ts1", "PHONE", "TEST_SCENARIO_STOP"), payload={}, to="WATCH")
        self.s.sync("PHONE", phone_body(phone, 1, commands=[stop]))
        r = self.s.sync("WATCH", watch_body(watch, 1))
        self.assertEqual([(c["id"], c["type"], c["from"], c["to"], c["test"]) for c in r["commands"]],
                         [("ts1", "TEST_SCENARIO_STOP", "PHONE", "WATCH", True)])
        self.assertEqual(self.s.sync("CAR", car_body(car, 1))["commands"], [])
        # 자기 대상 규칙은 type 과 무관하다 — 휴대폰 → PHONE · 워치 → WATCH 는 400
        self.assertApi(400, "BAD_REQUEST", self.s.sync, "PHONE",
                       phone_body(phone, 2, commands=[dict(stop, id="ts2", to="PHONE")]))
        self.assertApi(400, "BAD_REQUEST", self.s.sync, "WATCH",
                       watch_body(watch, 2, commands=[dict(stop, id="ts3", **{"from": "WATCH"})]))
        # 워치가 직접 붙지 않았으면 큐에 남아 ttl 이 지나면 버려진다(Data Layer 로 넘기지 않는다)
        self.s.sync("PHONE", phone_body(phone, 3, commands=[dict(stop, id="ts4", ttlMs=5_000)]))
        self.clock.t += 5_000
        self.assertEqual([c["id"] for c in self.s.sync("WATCH", watch_body(watch, 3))["commands"]], ["ts1"])


class ReviewFix20261108Test(_StoreBase):
    """2026-11-08 리뷰(중계 하4 · 하5 · 하6 · 공통 하5)와 11-02 중 8 — 고치기 전 실패하던 재현 시험."""

    # ---- 하4 · 무인증 pair/start 로 자리 채우기 ----
    def test_unjoined_pairs_freed_when_code_expires(self):
        start = self.s.pair_start()
        car, phone = start["carToken"], self.s.pair_join({"pairCode": start["pairCode"]})["phoneToken"]
        idle = [self.s.pair_start()["carToken"] for _ in range(relay.MAX_PAIRS - 1)]
        self.assertApi(503, "CAPACITY", self.s.pair_start)
        # 코드가 살아 있는 동안(10분 미만)은 아무도 안 붙었어도 지우지 않는다
        self.clock.t += relay.PAIR_CODE_TTL_MS - 1
        self.assertApi(503, "CAPACITY", self.s.pair_start)
        self.s.sync("CAR", car_body(idle[0], 1))
        # 코드가 만료되면 참여한 역할이 없는 짝은 지워져 자리가 난다(24시간을 기다리지 않음)
        self.clock.t += 1
        self.s.pair_start()
        self.assertEqual(len(self.s._pairs), 2)
        self.assertApi(401, "TOKEN_INVALID", self.s.sync, "CAR", car_body(idle[0], 2))
        # 휴대폰이 참여한 짝은 그대로
        self.s.sync("CAR", car_body(car, 1))
        self.s.sync("PHONE", phone_body(phone, 1))

    def test_watch_only_pair_kept_after_code_expiry(self):
        start = self.s.pair_start()
        watch = self.s.pair_join({"pairCode": start["pairCode"], "role": "WATCH"})["watchToken"]
        self.clock.t += relay.PAIR_CODE_TTL_MS
        self.s.sync("WATCH", watch_body(watch, 1))
        self.s.sync("CAR", car_body(start["carToken"], 1))

    def test_never_joined_pair_dropped_at_first_code_expiry_despite_pair_code(self):
        """be-qa 우회 — pair/code 를 거듭 불러도 아무도 참여한 적 없는 짝은 첫 코드 만료 때 지운다."""
        cars = [self.s.pair_start()["carToken"] for _ in range(relay.MAX_PAIRS)]
        self.clock.t += relay.PAIR_CODE_TTL_MS - 1
        for c in cars:
            self.s.pair_code({"token": c})  # 새 코드로 10분 더 버티려는 시도
        self.assertApi(503, "CAPACITY", self.s.pair_start)
        self.clock.t += 1
        self.s.pair_start()
        self.assertEqual(len(self.s._pairs), 1)
        self.assertApi(401, "TOKEN_INVALID", self.s.pair_code, {"token": cars[0]})

    def test_pair_code_join_before_first_expiry_then_kept(self):
        start = self.s.pair_start()
        self.clock.t += relay.PAIR_CODE_TTL_MS - 1
        r = self.s.pair_code({"token": start["carToken"]})
        self.s.pair_join({"pairCode": r["pairCode"]})
        self.clock.t += relay.PAIR_CODE_TTL_MS
        self.s.sync("CAR", car_body(start["carToken"], 1))

    def test_once_joined_pair_kept_by_pair_code_after_watch_ends(self):
        """한 번 참여했던 짝(워치만 붙었다 끊김)은 pair/code 로 받은 코드가 사는 동안 남는다."""
        start = self.s.pair_start()
        w = self.s.pair_join({"pairCode": start["pairCode"], "role": "WATCH"})["watchToken"]
        self.s.pair_end({"token": w})
        r = self.s.pair_code({"token": start["carToken"]})
        self.clock.t += relay.PAIR_CODE_TTL_MS - 1
        self.s.pair_join({"pairCode": r["pairCode"], "role": "WATCH"})
        self.clock.t += relay.PAIR_CODE_TTL_MS
        self.s.sync("CAR", car_body(start["carToken"], 1))

    def test_watch_only_pair_dropped_after_watch_ends_and_code_gone(self):
        start = self.s.pair_start()
        w = self.s.pair_join({"pairCode": start["pairCode"], "role": "WATCH"})["watchToken"]
        self.clock.t += relay.PAIR_CODE_TTL_MS
        self.s.pair_end({"token": w})  # 휴대폰 없는 짝에서 워치가 끊고 코드도 없음 → 짝 전체 정리
        self.assertApi(401, "TOKEN_INVALID", self.s.sync, "CAR", car_body(start["carToken"], 1))

    # ---- 하5 · role null = 생략 ----
    def test_join_role_null_is_phone(self):
        start = self.s.pair_start()
        j = self.s.pair_join({"pairCode": start["pairCode"], "role": None})
        self.assertEqual(set(j), {"phoneToken", "pairId"})
        for bad in ("", "phone", 0, False, "CAR"):
            with self.subTest(role=bad):
                self.assertApi(400, "BAD_REQUEST", self.s.pair_join, {"pairCode": start["pairCode"], "role": bad})

    # ---- 하6 · 정수 범위(Kotlin Long) ----
    def test_int_fields_must_fit_long(self):
        car, phone = self.paired()
        start2 = self.s.pair_start()
        watch = self.s.pair_join({"pairCode": start2["pairCode"], "role": "WATCH"})["watchToken"]
        lo, hi = -2 ** 63, 2 ** 63 - 1
        for field in ("seq", "sentAt"):
            for role, make, tok in (("CAR", car_body, car), ("PHONE", phone_body, phone), ("WATCH", watch_body, watch)):
                for v in (hi + 1, lo - 1, 10 ** 30):
                    with self.subTest(field=field, role=role, v=v):
                        self.assertApi(400, "BAD_REQUEST", self.s.sync, role, dict(make(tok, 1), **{field: v}))
        for v in (hi + 1, lo - 1):
            with self.subTest(responseAt=v):
                self.assertApi(400, "BAD_REQUEST", self.s.sync, "PHONE", dict(phone_body(phone, 1), responseAt=v))
        self.assertIsNone(self.s.sync("CAR", car_body(car, 1))["peer"])  # 400 은 상태를 덮지 않는다
        # 경계값은 받는다(음수도 계약상 막지 않는다)
        self.s.sync("CAR", dict(car_body(car, lo), sentAt=lo))
        self.s.sync("CAR", dict(car_body(car, hi), sentAt=hi))
        self.s.sync("PHONE", dict(phone_body(phone, hi), sentAt=lo, responseAt=hi))
        peer = self.s.sync("CAR", dict(car_body(car, hi), sentAt=0))["peer"]
        self.assertEqual((peer["seq"], peer["sentAt"], peer["responseAt"]), (hi, lo, hi))
        self.s.sync("PHONE", dict(phone_body(phone, hi), sentAt=0, responseAt=lo))

    # ---- 11-02 중 8 · 재설치 뒤 새 코드로 다시 참여 ----
    def test_phone_rejoin_with_new_code_replaces_token(self):
        car, phone = self.paired()
        self.s.sync("PHONE", phone_body(phone, 1))
        self.s.sync("CAR", car_body(car, 1, commands=[cmd("c1", "CAR")]))
        r = self.s.pair_code({"token": car})
        j = self.s.pair_join({"pairCode": r["pairCode"]})
        phone2 = j["phoneToken"]
        self.assertNotEqual(phone2, phone)
        # 옛 토큰은 401 — 옛 휴대폰이 짝을 끝낼 수도 없다
        self.assertApi(401, "TOKEN_INVALID", self.s.sync, "PHONE", phone_body(phone, 2))
        self.assertApi(401, "TOKEN_INVALID", self.s.pair_end, {"token": phone})
        # 차 토큰 · 짝 유지, 큐에 있던 지시는 새 휴대폰이 받는다
        self.assertEqual([c["id"] for c in self.s.sync("PHONE", phone_body(phone2, 3))["commands"]], ["c1"])
        self.s.sync("CAR", car_body(car, 2))
        self.assertEqual(len(self.s._pairs), 1)
        # 코드는 역할마다 한 번 — 같은 코드로 휴대폰이 또 붙지 못하고, 워치는 붙는다
        self.assertApi(404, "PAIR_CODE_INVALID", self.s.pair_join, {"pairCode": r["pairCode"]})
        self.s.pair_join({"pairCode": r["pairCode"], "role": "WATCH"})

    def test_watch_rejoin_with_new_code_replaces_token(self):
        start = self.s.pair_start()
        w1 = self.s.pair_join({"pairCode": start["pairCode"], "role": "WATCH"})["watchToken"]
        r = self.s.pair_code({"token": start["carToken"]})
        w2 = self.s.pair_join({"pairCode": r["pairCode"], "role": "WATCH"})["watchToken"]
        self.assertApi(401, "TOKEN_INVALID", self.s.sync, "WATCH", watch_body(w1, 1))
        self.s.sync("WATCH", watch_body(w2, 1))

    def test_first_code_still_once_per_role(self):
        start = self.s.pair_start()
        self.s.pair_join({"pairCode": start["pairCode"]})
        self.assertApi(404, "PAIR_CODE_INVALID", self.s.pair_join, {"pairCode": start["pairCode"]})

    # ---- 공통 하5 · seq 재시작 ----
    def test_rejoin_resets_seq(self):
        car, phone = self.paired()
        self.s.sync("PHONE", phone_body(phone, 1_800_000_000_000, watch=dict(WATCH, hr=61)))
        r = self.s.pair_code({"token": car})
        phone2 = self.s.pair_join({"pairCode": r["pairCode"]})["phoneToken"]
        # 새 기기 시계가 크게 뒤라도, 조금 뒤라도 첫 sync 는 덮는다
        self.s.sync("PHONE", phone_body(phone2, 1_799_999_999_999, watch=dict(WATCH, hr=62)))
        peer = self.s.sync("CAR", car_body(car, 1))["peer"]
        self.assertEqual((peer["seq"], peer["watch"]["hr"]), (1_799_999_999_999, 62))

    def test_seq_large_regression_on_same_token_is_restart(self):
        car, phone = self.paired()
        base = 1_800_000_000_000
        self.s.sync("CAR", car_body(car, base, vehicle=dict(VEHICLE, speedKmh=1)))
        # 경계 — 1000 까지 작은 것은 늦게 온 옛 요청으로 보고 덮지 않는다
        self.s.sync("CAR", car_body(car, base - relay.SEQ_RESTART_GAP, vehicle=dict(VEHICLE, speedKmh=2)))
        self.assertEqual(self.s.sync("PHONE", phone_body(phone, 1))["peer"]["vehicle"]["speedKmh"], 1)
        # 1000 넘게 작으면 앱 재시작(시계가 뒤로 감)으로 보고 덮는다
        self.s.sync("CAR", car_body(car, base - relay.SEQ_RESTART_GAP - 1, vehicle=dict(VEHICLE, speedKmh=3)))
        self.assertEqual(self.s.sync("PHONE", phone_body(phone, 2))["peer"]["vehicle"]["speedKmh"], 3)
        # 그 뒤로는 새 seq 를 기준으로 다시 단조
        new = base - relay.SEQ_RESTART_GAP - 1
        self.s.sync("CAR", car_body(car, new + 1, vehicle=dict(VEHICLE, speedKmh=4)))
        self.s.sync("CAR", car_body(car, new, vehicle=dict(VEHICLE, speedKmh=5)))
        self.assertEqual(self.s.sync("PHONE", phone_body(phone, 3))["peer"]["vehicle"]["speedKmh"], 4)

    def test_seq_restart_applies_to_watch_direct(self):
        start = self.s.pair_start()
        watch = self.s.pair_join({"pairCode": start["pairCode"], "role": "WATCH"})["watchToken"]
        self.s.sync("WATCH", watch_body(watch, 1_800_000_000_000, watch=dict(WATCH, hr=60)))
        self.s.sync("WATCH", watch_body(watch, 1_700_000_000_000, watch=dict(WATCH, hr=70)))
        self.assertEqual(self.s.sync("CAR", car_body(start["carToken"], 1))["watch"]["hr"], 70)


class PhoneLocationDroppedTest(_StoreBase):
    """감사 중4(A 안) — 휴대폰 위치는 형식만 보고 저장 · 전달하지 않는다(옛 휴대폰 빌드 호환)."""

    def test_phone_location_accepted_but_not_stored_or_sent(self):
        car, phone = self.paired()
        r = self.s.sync("PHONE", phone_body(phone, 1, loc=LOC))  # 400 아님
        self.assertEqual(r["serverTime"], self.clock.t)
        self.assertNotIn("phoneLocation", self.s._pairs[next(iter(self.s._pairs))].state["PHONE"])
        peer = self.s.sync("CAR", car_body(car, 1))["peer"]
        self.assertNotIn("phoneLocation", peer)
        self.assertNotIn("37.555", json.dumps(peer))

    def test_phone_location_null_or_omitted_ok(self):
        car, phone = self.paired()
        self.s.sync("PHONE", phone_body(phone, 1, loc=None))
        b = phone_body(phone, 2)
        del b["phoneLocation"]
        self.s.sync("PHONE", b)
        self.assertNotIn("phoneLocation", self.s.sync("CAR", car_body(car, 1))["peer"])

    def test_phone_location_bad_type_still_400(self):
        car, phone = self.paired()
        for bad in ([1, 2], "37.5,126.9", 1, True):
            with self.subTest(bad=bad):
                self.assertApi(400, "BAD_REQUEST", self.s.sync, "PHONE", phone_body(phone, 1, loc=bad))


class HealthConsentTest(_StoreBase):
    """감사 하4 — 휴대폰의 최근 healthConsent 가 false 면 워치 값을 저장 · 전달하지 않는다(G2 · G3, 계약 §5)."""

    def tripled(self):
        start = self.s.pair_start()
        phone = self.s.pair_join({"pairCode": start["pairCode"]})["phoneToken"]
        watch = self.s.pair_join({"pairCode": start["pairCode"], "role": "WATCH"})["watchToken"]
        return start["carToken"], phone, watch

    def consent(self, phone, seq, value, watch=None):
        b = phone_body(phone, seq, watch=watch, loc=None)
        b["healthConsent"] = value
        return self.s.sync("PHONE", b)

    def stored_direct_watch(self):
        return self.s._pairs[next(iter(self.s._pairs))].state["WATCH"]

    def test_false_blocks_new_direct_values(self):
        car, phone, watch = self.tripled()
        self.consent(phone, 1, False)
        t0 = self.clock.t
        r = self.s.sync("WATCH", watch_body(watch, 1))
        self.assertEqual(r, {"serverTime": t0, "commands": []})  # 워치 sync 는 지금처럼 200
        self.assertIsNone(self.stored_direct_watch()["watch"])
        self.clock.t += 700
        rc = self.s.sync("CAR", car_body(car, 1))
        self.assertIsNone(rc["watch"])
        self.assertIsNone(rc["peer"]["watch"])
        # 워치 직접 경로가 살아 있다는 사실(지시 가능 판단, §4.3)은 그대로 남는다
        self.assertEqual(rc["watchDirectAgeMs"], 700)
        rp = self.consent(phone, 2, False)
        self.assertIsNone(rp["watch"])
        self.assertNotIn("173", json.dumps(rc) + json.dumps(rp))

    def test_false_erases_already_stored_direct_value(self):
        car, phone, watch = self.tripled()
        self.s.sync("WATCH", watch_body(watch, 1))
        self.assertEqual(self.s.sync("CAR", car_body(car, 1))["watch"]["hr"], 173)
        self.consent(phone, 1, False)
        self.assertIsNone(self.stored_direct_watch()["watch"])
        self.assertIsNone(self.s.sync("CAR", car_body(car, 2))["watch"])

    def test_false_also_drops_phone_via_value(self):
        # 휴대폰은 false 때 watch:null 을 보내지만, 실려 와도 저장하지 않는다
        car, phone, watch = self.tripled()
        self.consent(phone, 1, False, watch=WATCH)
        rc = self.s.sync("CAR", car_body(car, 1))
        self.assertIsNone(rc["watch"])
        self.assertIsNone(rc["peer"]["watch"])

    def test_true_or_omitted_or_null_behaves_as_before(self):
        for value in (True, "omit", None):
            with self.subTest(value=value):
                self.setUp()
                car, phone, watch = self.tripled()
                b = phone_body(phone, 1, watch=None)
                if value != "omit":
                    b["healthConsent"] = value
                self.s.sync("PHONE", b)
                t0 = self.clock.t
                self.s.sync("WATCH", watch_body(watch, 1))
                rc = self.s.sync("CAR", car_body(car, 1))
                self.assertEqual(rc["watch"], dict(WATCH, via="WATCH", receivedAt=t0, ageMs=0))
                self.assertNotIn("healthConsent", rc["peer"])  # 차 peer 로 넘기지 않는다

    def test_false_then_true_uses_only_new_values(self):
        car, phone, watch = self.tripled()
        self.s.sync("WATCH", watch_body(watch, 1, watch=dict(WATCH, hr=80)))
        self.consent(phone, 1, False)
        self.s.sync("WATCH", watch_body(watch, 2, watch=dict(WATCH, hr=90)))
        self.consent(phone, 2, True)
        # 다시 켠 직후 — 끈 동안의 값 · 끄기 전 값은 되살아나지 않는다
        self.assertIsNone(self.s.sync("CAR", car_body(car, 1))["watch"])
        self.clock.t += 1000
        self.s.sync("WATCH", watch_body(watch, 3, watch=dict(WATCH, hr=100)))
        self.assertEqual(self.s.sync("CAR", car_body(car, 2))["watch"]["hr"], 100)

    def test_old_phone_after_false_restores_previous_behavior(self):
        # 같은 짝에서 healthConsent 를 안 보내는 옛 빌드로 바뀌면 지금처럼 동작한다(가장 최근 값 = 없음)
        car, phone, watch = self.tripled()
        self.consent(phone, 1, False)
        self.s.sync("PHONE", phone_body(phone, 2, watch=None))
        self.s.sync("WATCH", watch_body(watch, 1))
        self.assertEqual(self.s.sync("CAR", car_body(car, 1))["watch"]["hr"], 173)

    def test_stale_phone_request_does_not_change_consent(self):
        # 늦게 온 옛 요청(seq 1 ~ 1,000 작음)은 상태와 함께 동의 값도 덮지 않는다(§5 seq 규칙)
        car, phone, watch = self.tripled()
        self.consent(phone, 10, True)
        self.consent(phone, 9, False)
        self.s.sync("WATCH", watch_body(watch, 1))
        self.assertEqual(self.s.sync("CAR", car_body(car, 1))["watch"]["hr"], 173)

    def test_non_bool_is_400_and_changes_nothing(self):
        car, phone, watch = self.tripled()
        self.s.sync("WATCH", watch_body(watch, 1))
        for bad in ("false", 0, 1, [], {}, "true"):
            with self.subTest(bad=bad):
                self.assertApi(400, "BAD_REQUEST", self.consent, phone, 1, bad)
        self.assertEqual(self.s.sync("CAR", car_body(car, 1))["watch"]["hr"], 173)

    def test_seq_gap_boundary_1000_vs_1001(self):
        # 정확히 1,000 작으면 늦은 요청(false 무시), 1,001 작으면 재시작(false 적용)
        base = 1_800_000_000_000
        for gap, applied in ((relay.SEQ_RESTART_GAP, False), (relay.SEQ_RESTART_GAP + 1, True)):
            with self.subTest(gap=gap):
                self.setUp()
                car, phone, watch = self.tripled()
                self.consent(phone, base, True)
                self.consent(phone, base - gap, False)
                pair = self.s._pairs[next(iter(self.s._pairs))]
                self.assertIs(pair.health_consent, False if applied else True)
                self.s.sync("WATCH", watch_body(watch, 1))
                w = self.s.sync("CAR", car_body(car, 1))["watch"]
                if applied:
                    self.assertIsNone(w)
                else:
                    self.assertEqual(w["hr"], 173)

    def test_direct_age_follows_latest_watch_sync_while_false(self):
        car, phone, watch = self.tripled()
        self.consent(phone, 1, False)
        self.s.sync("WATCH", watch_body(watch, 1))
        self.clock.t += 2000
        self.s.sync("WATCH", watch_body(watch, 2))
        self.clock.t += 300
        rc = self.s.sync("CAR", car_body(car, 1))
        self.assertEqual((rc["watchDirectAgeMs"], rc["watch"]), (300, None))
        self.assertEqual(self.stored_direct_watch()["seq"], 2)

    def test_non_bool_keeps_existing_consent_and_seq(self):
        for prior in (True, False):
            with self.subTest(prior=prior):
                self.setUp()
                car, phone, watch = self.tripled()
                self.consent(phone, 5, prior)
                pair = self.s._pairs[next(iter(self.s._pairs))]
                for bad in (0, 1, "false", "true", [], {}):
                    self.assertApi(400, "BAD_REQUEST", self.consent, phone, 6, bad)
                    self.assertIs(pair.health_consent, prior)
                    self.assertEqual(pair.last_seq["PHONE"], 5)

    def test_over_http(self):
        car, phone, watch = self.tripled()
        with _serve(self.s) as srv:
            def call(path, body):
                c = http.client.HTTPConnection("127.0.0.1", srv.server_address[1], timeout=5)
                c.request("POST", path, json.dumps(body), {"Content-Type": "application/json"})
                resp = c.getresponse()
                out = (resp.status, json.loads(resp.read()))
                c.close()
                return out
            self.assertEqual(call("/v1/phone/sync", dict(phone_body(phone, 1, watch=None), healthConsent="no")),
                             (400, {"error": "BAD_REQUEST"}))
            self.assertEqual(call("/v1/phone/sync", dict(phone_body(phone, 1, watch=None), healthConsent=False))[0], 200)
            self.assertEqual(call("/v1/watch/sync", watch_body(watch, 1))[0], 200)
            st, r = call("/v1/car/sync", car_body(car, 1))
            self.assertEqual((st, r["watch"]), (200, None))


class ParseBodyTest(unittest.TestCase):
    def assertBad(self, raw):
        with self.assertRaises(ApiError) as cm:
            relay.parse_body(raw)
        self.assertEqual((cm.exception.status, cm.exception.code), (400, "BAD_REQUEST"))

    def test_lone_surrogates_rejected(self):
        for raw in (b'{"a":"\\ud800"}', b'{"a":"x\\udfff"}', b'{"\\udc00":1}', b'{"a":[{"b":"\\ud83d"}]}'):
            with self.subTest(raw=raw):
                self.assertBad(raw)

    def test_surrogate_pair_ok(self):
        self.assertEqual(relay.parse_body(b'{"a":"\\ud83d\\ude00"}'), {"a": "\U0001F600"})

    def test_nan_infinity_rejected(self):
        for raw in (b'{"a":NaN}', b'{"a":Infinity}', b'{"a":-Infinity}', b'{"a":[1,NaN]}'):
            with self.subTest(raw=raw):
                self.assertBad(raw)

    def test_out_of_range_floats_rejected(self):
        for raw in (b'{"a":1e999}', b'{"a":-1e400}', b'{"v":{"speedKmh":1.8e308}}', b'{"a":[0.5,1E+999]}'):
            with self.subTest(raw=raw):
                self.assertBad(raw)
        self.assertEqual(relay.parse_body(b'{"a":1.7e308,"b":-2.5e-300}'), {"a": 1.7e308, "b": -2.5e-300})

    def test_deep_nesting_does_not_crash(self):
        # 64KB 안의 깊은 중첩 — 400 이든 통과든 ApiError 밖의 예외가 나오면 안 된다
        try:
            relay.parse_body(b'{"a":' + b"[" * 30_000 + b"]" * 30_000 + b"}")
        except ApiError:
            pass

    def test_empty_body_is_empty_object(self):
        self.assertEqual(relay.parse_body(b""), {})
        self.assertEqual(relay.parse_body(b"  \n"), {})


class HttpTest(unittest.TestCase):
    """임시 포트에 실제 서버를 띄워 HTTP 경로 · 상태 코드 · 로그를 확인한다."""

    def setUp(self):
        self.server = self.enterContext(_serve())
        self.port = self.server.server_address[1]

    def call(self, method, path, body=None, raw=None, headers=None):
        conn = http.client.HTTPConnection("127.0.0.1", self.port, timeout=5)
        data = raw if raw is not None else (json.dumps(body).encode() if body is not None else None)
        conn.request(method, path, body=data, headers=headers or {"Content-Type": "application/json"})
        resp = conn.getresponse()
        out = resp.status, json.loads(resp.read() or b"null")
        conn.close()
        return out

    def test_health(self):
        self.assertEqual(self.call("GET", "/v1/health"), (200, {"ok": True}))

    def test_unknown_path_and_method(self):
        self.assertEqual(self.call("GET", "/nope"), (404, {"error": "NOT_FOUND"}))
        self.assertEqual(self.call("GET", "/v1/car/sync"), (405, {"error": "METHOD_NOT_ALLOWED"}))
        self.assertEqual(self.call("POST", "/v1/health", {}), (405, {"error": "METHOD_NOT_ALLOWED"}))

    def test_full_flow_over_http(self):
        st, start = self.call("POST", "/v1/pair/start", raw=b"")
        self.assertEqual(st, 200)
        st, join = self.call("POST", "/v1/pair/join", {"pairCode": start["pairCode"]})
        self.assertEqual(st, 200)
        self.assertEqual(self.call("POST", "/v1/pair/join", {"pairCode": start["pairCode"]}),
                         (404, {"error": "PAIR_CODE_INVALID"}))
        st, r = self.call("POST", "/v1/car/sync", car_body(start["carToken"], 1, commands=[cmd("c1", "CAR")]))
        self.assertEqual((st, r["peer"], r["commands"]), (200, None, []))
        st, r = self.call("POST", "/v1/phone/sync", phone_body(join["phoneToken"], 1))
        self.assertEqual(st, 200)
        self.assertEqual(r["peer"]["vehicle"], VEHICLE)
        self.assertEqual([c["id"] for c in r["commands"]], ["c1"])
        self.assertEqual(self.call("POST", "/v1/car/sync", car_body("f" * 32, 2)),
                         (401, {"error": "TOKEN_INVALID"}))
        st, wj = self.call("POST", "/v1/pair/join", {"pairCode": start["pairCode"], "role": "WATCH"})
        self.assertEqual(st, 200)
        st, r = self.call("POST", "/v1/watch/sync", watch_body(wj["watchToken"], 1))
        self.assertEqual((st, set(r)), (200, {"serverTime", "commands"}))
        st, r = self.call("POST", "/v1/car/sync", car_body(start["carToken"], 3))
        self.assertEqual(r["watch"]["via"], "WATCH")
        self.assertNotIn("phoneLocation", r["peer"])  # 휴대폰이 보내도 차 응답 JSON 에 키 자체가 없다(중4)
        self.assertEqual(self.call("POST", "/v1/pair/end", {"token": start["carToken"]}), (200, {}))
        self.assertEqual(self.call("POST", "/v1/phone/sync", phone_body(join["phoneToken"], 2)),
                         (401, {"error": "TOKEN_INVALID"}))

    def test_bad_json(self):
        self.assertEqual(self.call("POST", "/v1/pair/join", raw=b"{not json"), (400, {"error": "BAD_REQUEST"}))
        self.assertEqual(self.call("POST", "/v1/pair/join", raw=b"[1,2]"), (400, {"error": "BAD_REQUEST"}))
        self.assertEqual(self.call("POST", "/v1/pair/join", raw=b'{"pairCode":"\\ud800"}'),
                         (400, {"error": "BAD_REQUEST"}))
        self.assertEqual(self.call("POST", "/v1/pair/join", raw=b'{"pairCode":NaN}'), (400, {"error": "BAD_REQUEST"}))

    def test_pair_code_over_http(self):
        _, start = self.call("POST", "/v1/pair/start")
        st, r = self.call("POST", "/v1/pair/code", {"token": start["carToken"]})
        self.assertEqual((st, set(r)), (200, {"pairCode", "expiresInSec"}))

    def test_body_exact_limit(self):
        _, start = self.call("POST", "/v1/pair/start", {})
        base = json.dumps(car_body(start["carToken"], 1)).encode()
        for size, status in ((relay.MAX_BODY_BYTES, 200), (relay.MAX_BODY_BYTES + 1, 413)):
            raw = base + b" " * (size - len(base))  # JSON 뒤 공백은 허용
            self.assertEqual(len(raw), size)
            with self.subTest(size=size):
                self.assertEqual(self.call("POST", "/v1/car/sync", raw=raw)[0], status)

    def test_capacity_503_over_http(self):
        # 한 주소에서 64번 보내므로 주소별 pair/start 제한(기본 분당 10 · 살아 있는 짝 3)은 넉넉히 푼다 — 이 시험은 전체 짝 상한만 본다
        with _serve(RelayStore(limits=relay.Limits(start_per_addr=1000, pairs_per_addr=1000))) as srv:
            self.port = srv.server_address[1]
            for _ in range(relay.MAX_PAIRS):
                self.call("POST", "/v1/pair/start")
            self.assertEqual(self.call("POST", "/v1/pair/start"), (503, {"error": "CAPACITY"}))

    def test_body_size_limit(self):
        st, start = self.call("POST", "/v1/pair/start", {})
        body = car_body(start["carToken"], 1)
        body["vehicle"] = dict(VEHICLE, naviAddress="가" * (relay.MAX_BODY_BYTES // 3 + 10))
        raw = json.dumps(body, ensure_ascii=False).encode("utf-8")
        self.assertGreater(len(raw), relay.MAX_BODY_BYTES)
        self.assertEqual(self.call("POST", "/v1/car/sync", raw=raw), (413, {"error": "BODY_TOO_LARGE"}))
        # 상한 이하는 받는다
        body["vehicle"] = dict(VEHICLE, naviAddress="가" * 1000)
        self.assertEqual(self.call("POST", "/v1/car/sync", body)[0], 200)

    def test_default_socket_timeout_10s(self):
        self.assertEqual(relay.make_handler(RelayStore()).timeout, 10)

    def test_response_encoding_failure_returns_500(self):
        class Broken(RelayStore):
            def pair_start(self, addr=None):
                return {"x": "\ud800"}  # UTF-8 로 인코딩할 수 없다

        with _serve(Broken()) as srv:
            conn = http.client.HTTPConnection("127.0.0.1", srv.server_address[1], timeout=5)
            with self.assertLogs("guardian-relay", level=logging.ERROR) as logs:
                conn.request("POST", "/v1/pair/start", body=b"")
                resp = conn.getresponse()
                self.assertEqual((resp.status, json.loads(resp.read())), (500, {"error": "INTERNAL"}))
            self.assertIn("internal error UnicodeEncodeError", logs.output[0])
            conn.close()

    def test_non_finite_in_response_returns_500(self):
        class Broken(RelayStore):
            def pair_start(self, addr=None):
                return {"x": float("inf")}  # allow_nan=False 로 인코딩을 거부해야 한다

        with _serve(Broken()) as srv:
            conn = http.client.HTTPConnection("127.0.0.1", srv.server_address[1], timeout=5)
            with self.assertLogs("guardian-relay", level=logging.ERROR):
                conn.request("POST", "/v1/pair/start", body=b"")
                resp = conn.getresponse()
                self.assertEqual((resp.status, json.loads(resp.read())), (500, {"error": "INTERNAL"}))
            conn.close()

    def test_out_of_range_float_over_http(self):
        self.assertEqual(self.call("POST", "/v1/pair/join", raw=b'{"pairCode":"1","x":1e999}'),
                         (400, {"error": "BAD_REQUEST"}))

    def test_slow_client_is_cut_by_timeout(self):
        old = relay.SOCKET_TIMEOUT_S
        relay.SOCKET_TIMEOUT_S = 0.3  # make_server 때 한 번 읽힌다
        try:
            cm = _serve()
            srv = cm.__enter__()
        finally:
            relay.SOCKET_TIMEOUT_S = old
        try:
            for partial in (b"POST /v1/car/sync HTTP/1.1\r\nHost: x\r\n",                      # 머리가 덜 옴
                            b"POST /v1/car/sync HTTP/1.1\r\nContent-Length: 100\r\n\r\n{\"seq\""):  # 본문이 덜 옴
                with self.subTest(partial=partial[:30]), self.assertLogs("guardian-relay", level=logging.WARNING):
                    sock = socket.create_connection(("127.0.0.1", srv.server_address[1]), timeout=5)
                    sock.sendall(partial)
                    t0 = time.monotonic()
                    self.assertEqual(sock.recv(4096), b"")  # 응답 없이 끊긴다
                    self.assertLess(time.monotonic() - t0, 3)
                    sock.close()
        finally:
            cm.__exit__(None, None, None)

    def test_logs_have_no_health_location_or_token(self):
        stderr = io.StringIO()
        with self.assertLogs("guardian-relay", level=logging.DEBUG) as logs, redirect_stderr(stderr):
            _, start = self.call("POST", "/v1/pair/start", {})
            _, join = self.call("POST", "/v1/pair/join", {"pairCode": start["pairCode"]})
            self.call("POST", "/v1/car/sync", car_body(start["carToken"], 1, flow=FLOW))
            self.call("POST", "/v1/phone/sync", phone_body(join["phoneToken"], 1))
            self.call("POST", "/v1/phone/sync", phone_body(join["phoneToken"], 2))
            _, wj = self.call("POST", "/v1/pair/join", {"pairCode": start["pairCode"], "role": "WATCH"})
            self.call("POST", "/v1/watch/sync", watch_body(wj["watchToken"], 1))
            self.call("POST", "/v1/car/sync", car_body(start["carToken"], 3))
            self.call("POST", "/v1/car/sync?token=" + start["carToken"], car_body(start["carToken"], 2))
            self.call("POST", "/v1/" + join["phoneToken"], {})          # 경로에 토큰을 넣어도
            self.call("POST", "/v1/car/sync", car_body("bad" + start["carToken"], 3))
            # 깨진 요청 줄(기본 처리기는 요청 줄을 그대로 로그에 찍는다)
            s = socket.create_connection(("127.0.0.1", self.port), timeout=5)
            s.sendall(("GARBAGE " + join["phoneToken"] + " 37.5665 HTTP/9.9\r\n\r\n").encode())
            while s.recv(4096):
                pass
            s.close()
        text = "\n".join(logs.output) + stderr.getvalue()
        secrets_in_play = [start["carToken"], join["phoneToken"], wj["watchToken"], start["pairCode"], join["pairId"],
                           "173", "37.566", "126.978", "36.123", "127.765", "세종대로", "경부고속도로",
                           "PULSE_WEAK", "Grandeur", "speedKmh", "lastPulseAt"]
        for s_ in secrets_in_play:
            with self.subTest(value=s_):
                self.assertNotIn(s_, text)
        self.assertIn("POST /v1/phone/sync 200", text)
        self.assertIn("POST /v1/watch/sync 200", text)
        self.assertIn("POST - 404", text)
        self.assertIn("POST /v1/car/sync 401", text)


if __name__ == "__main__":
    unittest.main()
