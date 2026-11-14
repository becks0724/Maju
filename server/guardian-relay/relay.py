# 운전자 무응답 확인(가칭) 베타 중계 서버 — 차 앱과 휴대폰 앱의 짝짓기 · 최신 상태 · 지시를 메모리에만 두고 sync 응답으로 넘긴다
"""규격은 contracts/guardian/protocol.md 를 따른다.

- 파이썬 3 표준 라이브러리만 쓴다.
- 모든 상태는 메모리에만 둔다(디스크 · 파일에 쓰지 않는다).
- 접근 로그는 메서드 · 경로 · 상태 코드 · 처리 시간만 남긴다(본문 · 토큰 · 위치 · 심박 없음, 가이드라인 G2).
  남용 제한에 쓰는 보낸 주소도 로그에 남기지 않는다.
- 나이 · 만료는 단조 시계로 잰다. 벽시계는 응답의 serverTime(과 그에 맞춘 receivedAt)에만 쓴다.
"""
import argparse
import collections
import ipaddress
import json
import logging
import math
import secrets
import socket
import sys
import threading
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

LOG = logging.getLogger("guardian-relay")

MAX_BODY_BYTES = 64 * 1024
PAIR_CODE_TTL_MS = 10 * 60 * 1000
PAIR_TTL_MS = 24 * 60 * 60 * 1000
# 지시 ttl 상한 — 앱은 30초를 쓴다. 문자 보낼 함(A-2)이 확인될 때까지 다시 실으므로 길게 둘 까닭이 없다(D-3, 2026-11-14 · 이전 1시간)
MAX_COMMAND_TTL_MS = 120 * 1000
# 지시 하나(JSON)의 크기 상한 — 앱의 가장 큰 지시(119 작성 · 보호자 문자 사실)도 1KB 안팎이다(D-3)
MAX_COMMAND_BYTES = 8 * 1024
# 받는 쪽마다 중복 막기 기록(지시 id) 상한 — 넘으면 먼저 만료될 것부터 버린다(D-3)
MAX_SEEN_IDS = 512
MAX_QUEUE = 256
# 받는 쪽 큐마다 지시 JSON 바이트 합 상한 — 넘으면 오래된 것부터 버린다(sec-audit 3차 — 8KB × 256 이면 응답 하나가 2MB). 앱의 가장 큰 지시는 1KB 안팎
MAX_QUEUE_BYTES = 64 * 1024
# 본문 중첩 깊이 상한 — 앱 해석기는 64 단계를 넘으면 응답 전체를 못 읽는다(Json.kt). 상대 sync 를 통째로 못 읽게 만드는 길을 막는다(sec-audit 3차)
MAX_DEPTH = 32
MAX_COMMANDS_PER_SYNC = 32
MAX_PAIRS = 64
SOCKET_TIMEOUT_S = 10
STATS_EVERY_S = 60  # 분 단위 계수 줄(D-4)
LONG_MIN, LONG_MAX = -2 ** 63, 2 ** 63 - 1  # 앱이 Kotlin Long 으로 읽는 정수의 범위
# 같은 토큰의 seq 가 이 값보다 더 작아지면 늦게 온 옛 요청이 아니라 앱 재시작(시계가 뒤로 감)으로 본다.
# sync 는 2초마다 seq 를 1씩 올리므로 늦게 도착하는 요청의 차이는 수십을 넘지 않는다.
SEQ_RESTART_GAP = 1000

ROLES = ("CAR", "PHONE", "WATCH")
JOIN_ROLES = ("PHONE", "WATCH")
DEFAULT_TO = {"CAR": "PHONE", "PHONE": "CAR", "WATCH": "PHONE"}  # "to" 가 없을 때 받는 쪽
TARGETS = ("PHONE", "CAR", "WATCH", "ALL")
STATE_KEY = {"CAR": "vehicle", "PHONE": "watch", "WATCH": "watch"}
# 지시 허용 표(계약 §4.6, D-1) — 보낸 역할 → type → 받는 쪽(기본 대상까지 풀어서). 표 밖이면 sync 전체 400.
# 공용 원본은 contracts/guardian/command-roles.json 이고 test_hardening 이 같은지 대조한다(중계는 그 파일 없이도 돈다).
COMMAND_ROLES = {
    "CAR": {"ALERT_START": {"WATCH", "PHONE"}, "ALERT_STOP": {"ALL", "WATCH", "PHONE"}, "SHOW": {"PHONE"},
            "OPEN_119_COMPOSE": {"PHONE"}, "SEND_GUARDIAN_SMS": {"PHONE"}, "TEST_REQUEST": {"PHONE"}},
    "PHONE": {"ALERT_START": {"WATCH"}, "ALERT_STOP": {"ALL", "WATCH", "CAR"}, "SPEAK": {"CAR"}, "SHOW": {"CAR"},
              "AMBIENT": {"CAR"}, "OPEN_SEARCH": {"CAR"}, "FLOW_SNAPSHOT": {"CAR"},
              "TEST_SCENARIO": {"WATCH"}, "TEST_SCENARIO_STOP": {"WATCH"}},
    "WATCH": {},  # 워치는 지시를 내지 않는다
}
TEST_ONLY_TYPES = frozenset({"TEST_REQUEST", "TEST_SCENARIO", "TEST_SCENARIO_STOP"})  # test:true 만
COMMAND_TYPES = frozenset({
    "ALERT_START", "ALERT_STOP", "SPEAK", "SHOW", "AMBIENT", "OPEN_SEARCH",
    "OPEN_119_COMPOSE", "SEND_GUARDIAN_SMS", "TEST_SCENARIO", "TEST_SCENARIO_STOP", "FLOW_SNAPSHOT",
    "TEST_REQUEST",  # 차 → 휴대폰 테스트 시작 · 멈춤 요청(2026-11-01). payload 는 들여다보지 않는다
})


def now_ms():
    """벽시계(유닉스 에포크 ms) — 응답의 serverTime 에만 쓴다."""
    return int(time.time() * 1000)


def sleep_counting_clock_id():
    """잠자기(절전) 시간도 세는 단조 시계 id — Linux CLOCK_BOOTTIME, macOS clock_gettime(CLOCK_MONOTONIC). 없으면 None.

    time.monotonic 은 macOS 에서 잠자기를 세지 않아, 중계 Mac 이 잠들었다 깨면 묵은 지시 · 상태가 신선하게 보인다(합동 점검 판2-3 · C-4).
    """
    if sys.platform.startswith("linux") and hasattr(time, "CLOCK_BOOTTIME"):
        return time.CLOCK_BOOTTIME
    if sys.platform == "darwin" and hasattr(time, "CLOCK_MONOTONIC"):
        return time.CLOCK_MONOTONIC
    return None


_SLEEP_CLOCK = sleep_counting_clock_id()


def mono_ms():
    """단조 시계(ms) — 나이 · 만료 · 빈도 제한에 쓴다. 벽시계가 뒤로 가도 뒤로 가지 않고, 할 수 있으면 잠자기도 센다(C-4)."""
    if _SLEEP_CLOCK is not None:
        try:
            return int(time.clock_gettime(_SLEEP_CLOCK) * 1000)
        except OSError:
            pass
    return int(time.monotonic() * 1000)


class Limits:
    """남용 제한 값. 기본값은 같은 Wi-Fi 시연(기기 3대 · 2초 sync · 다시 짝짓기 몇 번)을 막지 않을 만큼 넉넉하다(README 「공개로 올릴 때」)."""

    def __init__(self, **kw):
        # pair/join 실패(틀린 · 만료 · 이미 쓴 번호) — 주소마다 창 안에서 이만큼 실패하면 그 주소의 pair/join 을 막는다
        self.join_fail_per_addr = 10
        self.join_fail_window_ms = 10 * 60 * 1000
        self.join_block_ms = 10 * 60 * 1000
        # 살아 있는 번호 하나에 걸린 실패(이미 쓴 역할로 다시 참여) 상한 — 넘으면 그 번호를 버린다
        self.code_fail_max = 5
        # pair/code — 짝마다 · 주소마다 창 안의 횟수
        self.code_per_pair = 10
        self.code_per_addr = 20
        self.code_window_ms = 10 * 60 * 1000
        # pair/start — 주소마다 창 안의 횟수
        self.start_per_addr = 10
        self.start_window_ms = 60 * 1000
        # 서버 전체 살아 있는 짝 수
        self.max_pairs = MAX_PAIRS
        # 주소마다 살아 있는 짝 수 — 닿으면 그 주소에서 세 역할(차 · 휴대폰 · 워치) 모두 조용한 짝 하나를 밀어내고, 그런 짝이 없으면 429.
        # 조용한 시간 문턱은 참여(휴대폰 · 워치 join)가 없던 짝 pair_idle_ms, 참여한 적 있는 짝 joined_idle_ms.
        # 차 앱은 토큰을 잃으면(재설치 · 데이터 지우기 · pair/end 실패) 옛 짝을 남긴 채 새로 pair/start 한다.
        # 기본 8 은 한 주소 시연(adb 터널 · 차 재설치 반복)용이다. 공개 운영 값은 README 「공개로 올릴 때」.
        self.pairs_per_addr = 8
        self.pair_idle_ms = 2 * 60 * 1000
        self.joined_idle_ms = 10 * 60 * 1000
        # pair/join 실패를 셀 때 IPv6 를 묶는 넓이(가입자 하나가 흔히 받는 /48 · /56 를 한 키로)
        self.join_fail_v6_prefix = 48
        # 계수기마다 기억하는 키(주소 · 짝) 수 상한(계수기가 메모리 고갈 통로가 되지 않게)
        self.max_keys = 4096
        # 연결 — 동시 연결 수 · 요청 하나(머리 + 본문)를 다 읽는 데 주는 전체 시간
        self.max_conns = 256
        self.request_deadline_s = 20.0
        # 프록시가 붙인 X-Forwarded-For 의 마지막 값을 보낸 주소로 쓸지(프록시 뒤에서만 켠다)
        self.trust_proxy = False
        # 토큰마다 sync 빈도 — 창 안 이만큼 넘으면 429. 정상 몰림(휴대폰 「보낼 것이 있으면 바로 sync」 · 워치 판정 변화 즉시 sync ·
        # ack 이어 받기)은 2초에 6번 안팎이라 넉넉히 둔다(D-3, be-dev · be-qa 3차). 429 여도 앱의 보낼 함(A-2)이 지시를 남긴다
        self.sync_per_token = 30
        self.sync_window_ms = 2 * 1000
        # 주소 키(프록시 뒤면 X-Forwarded-For) 하나가 동시에 처리받는 요청 수 — 머리를 읽은 뒤 센다(D-3, sec-audit 3차 — 켜 둠).
        # 정상 요청은 수 ms 라 공유 주소 뒤의 정상 사용자는 닿지 않는다
        self.concurrent_per_addr = 16
        for k, v in kw.items():
            if not hasattr(self, k):
                raise TypeError("unknown limit " + k)
            setattr(self, k, v)


class RateLimiter:
    """키(주소 · 짝)마다 창(window_ms) 안의 사건 수를 센다. 상한에 닿으면 block_ms 동안(0 이면 창이 밀려날 때까지) 막는다.

    기억하는 키는 max_keys 개까지다. 꽉 차면 창이 지난 키를 치우고, 그래도 꽉 차면 **막히지 않은 키 가운데 마지막 사건이 가장 오래된 것**을
    밀어내고 새 키를 받는다(막힌 키는 남긴다). 모든 키가 막혀 있으면 새 키는 세지 못한다(막지도 않는다).
    키는 마지막 사건 순서(OrderedDict)로 두어 치우기 · 밀어내기를 앞에서부터 한다 — 꽉 찬 뒤 새 키마다 전체를 훑지 않는다(D-4 보2-4).
    잠금은 부르는 쪽(RelayStore)이 잡는다.
    """

    def __init__(self, limit, window_ms, block_ms=0, max_keys=4096):
        self.limit, self.window_ms, self.block_ms, self.max_keys = limit, window_ms, block_ms, max_keys
        self._events = collections.OrderedDict()   # key -> 창 안 사건 시각 목록(길이 ≤ limit), 마지막 사건 순서
        self._blocked = collections.OrderedDict()  # key -> 풀리는 시각(막힌 순서 = 풀리는 순서)

    def __len__(self):
        return len(self._events) + len(self._blocked)

    def _prune(self, key, now):
        ev = self._events.get(key)
        if ev is not None:
            ev[:] = [t for t in ev if t > now - self.window_ms]
            if not ev:
                del self._events[key]
        until = self._blocked.get(key)
        if until is not None and until <= now:
            del self._blocked[key]

    def _is_blocked(self, key):
        return key in self._blocked or (self.block_ms == 0 and len(self._events.get(key, ())) >= self.limit)

    def _sweep_front(self, now):
        """앞(오래된 쪽)부터 창이 지난 키와 풀린 막힘을 치운다 — 순서대로라 처음으로 살아 있는 키에서 멈춘다."""
        while self._events:
            k, ev = next(iter(self._events.items()))
            if ev and ev[-1] > now - self.window_ms:
                break
            del self._events[k]
        while self._blocked:
            k, until = next(iter(self._blocked.items()))
            if until > now:
                break
            del self._blocked[k]

    def _room(self, key, now):
        """이 키를 기억할 자리를 만든다(이미 아는 키는 늘 있다). 없으면 막히지 않은 키 가운데 마지막 사건이 가장 오래된 것을 밀어낸다."""
        if key in self._events or key in self._blocked or len(self) < self.max_keys:
            return True
        self._sweep_front(now)
        if len(self) < self.max_keys:
            return True
        for k in self._events:  # 앞에서부터 — 막히지 않은 첫 키(보통 첫 키)
            if not self._is_blocked(k):
                del self._events[k]
                return True
        return False  # 모두 막힌 키 — 새 키는 세지 못한다(막힌 키는 밀어내지 않는다)

    def blocked(self, key, now):
        """처음 보는 키는 막히지 않았다(자리는 hit 이 만든다 — 성공한 요청이 남의 기록을 밀어내지 않게)."""
        self._prune(key, now)
        return self._is_blocked(key)

    def hit(self, key, now):
        """사건 하나를 센다(막힌 요청은 세지 않는다 — 먼저 blocked 를 본다)."""
        if key in self._blocked or not self._room(key, now):
            return
        ev = self._events.get(key)
        if ev is None:
            ev = self._events[key] = []
        ev.append(now)
        self._events.move_to_end(key)
        if self.block_ms and len(ev) >= self.limit:
            self._blocked[key] = now + self.block_ms
            del self._events[key]

    def allow(self, key, now):
        """막혀 있지 않으면 세고 True. 요청 수를 세는 제한(pair/start · pair/code)에 쓴다."""
        if self.blocked(key, now):
            return False
        self.hit(key, now)
        return True


def _is_loopback(host):
    try:
        return ipaddress.ip_address(host).is_loopback
    except ValueError:
        return host == "localhost"


def parse_forwarded_host(value):
    """X-Forwarded-For 값 하나를 주소로 — 「주소」 · 「주소:포트」 · 「[v6]」 · 「[v6]:포트」 · 「v6」 꼴(D-4 보2-3). 아니면 None."""
    v = value.strip()
    if v.startswith("["):
        end = v.find("]")
        if end < 0:
            return None
        host, rest = v[1:end], v[end + 1:]
        if rest and not (rest.startswith(":") and rest[1:].isdigit()):
            return None
    elif v.count(":") == 1:
        host, port = v.split(":")
        if not port.isdigit():
            return None
    else:
        host = v
    try:
        ipaddress.ip_address(host)
    except ValueError:
        return None
    return host


def client_key(sock_host, forwarded=(), trust_proxy=False, on_bad=None):
    """빈도 제한에 쓰는 보낸 쪽 키. 기본은 소켓 주소, trust_proxy 면 X-Forwarded-For 의 마지막 값(프록시가 붙인 것).

    IPv6 는 /64 로 묶는다(한 가입자가 주소를 바꿔 가며 제한을 피하지 못하게).
    trust_proxy 여도 소켓 상대가 루프백(같은 기계의 프록시)이 아니면 헤더를 보지 않는다. 헤더가 없거나 해석이 안 되면
    on_bad 를 부르고(주소 없이 세기, D-4) 소켓 주소를 쓴다.
    """
    cands = []
    if trust_proxy and _is_loopback(sock_host):
        last = forwarded[-1].split(",")[-1] if forwarded else None
        host = parse_forwarded_host(last) if last is not None else None
        if host is None:
            if on_bad is not None:
                on_bad()
        else:
            cands.append(host)
    cands.append(sock_host)
    for host in cands:
        try:
            ip = ipaddress.ip_address(host)
        except ValueError:
            continue
        if ip.version == 6:
            if ip.ipv4_mapped is not None:
                return str(ip.ipv4_mapped)
            return str(ipaddress.ip_network((ip, 64), strict=False))
        return str(ip)
    return str(sock_host)


def wider_key(key, v6_prefix):
    """client_key 가 낸 IPv6 /64 키를 /v6_prefix 로 더 넓게 묶는다. IPv4 · 주소가 아닌 키는 그대로."""
    try:
        net = ipaddress.ip_network(key, strict=False)
    except ValueError:
        return key
    if net.version == 6 and net.prefixlen > v6_prefix:
        return str(net.supernet(new_prefix=v6_prefix))
    return key


class ApiError(Exception):
    """HTTP 상태 코드와 오류 코드(`{"error": code}`)를 함께 싣는 예외."""

    def __init__(self, status, code):
        super().__init__(code)
        self.status = status
        self.code = code


def _bad(cond):
    if cond:
        raise ApiError(400, "BAD_REQUEST")


def _is_int(v):
    return isinstance(v, int) and not isinstance(v, bool)


def _is_long(v):
    return _is_int(v) and LONG_MIN <= v <= LONG_MAX


def _obj_or_null(v):
    return v is None or isinstance(v, dict)


def _reject_constant(name):
    raise ValueError("non-finite number")  # NaN · Infinity 는 JSON 이 아니다


def _finite_float(text):
    v = float(text)
    if not math.isfinite(v):  # 1e999 · -1e400 처럼 범위를 넘어 무한이 되는 수
        raise ValueError("non-finite number")
    return v


def _has_lone_surrogate(obj):
    """문자열(키 포함)에 짝 없는 서로게이트(U+D800–DFFF)가 있는지. 깊은 중첩에도 재귀하지 않는다."""
    stack = [obj]
    while stack:
        v = stack.pop()
        if isinstance(v, str):
            if any("\ud800" <= ch <= "\udfff" for ch in v):
                return True
        elif isinstance(v, dict):
            stack.extend(v.keys())
            stack.extend(v.values())
        elif isinstance(v, list):
            stack.extend(v)
    return False


def parse_body(raw):
    """요청 본문 bytes → dict. 형식이 틀리면 ApiError(400)."""
    if not raw.strip():
        return {}
    try:
        body = json.loads(raw.decode("utf-8"), parse_constant=_reject_constant, parse_float=_finite_float)
    except (UnicodeDecodeError, ValueError, RecursionError):
        raise ApiError(400, "BAD_REQUEST")
    if not isinstance(body, dict) or _has_lone_surrogate(body) or _too_deep(body):
        raise ApiError(400, "BAD_REQUEST")
    return body


def _too_deep(obj, limit=None):
    """중첩(객체 · 배열) 깊이가 MAX_DEPTH 를 넘는지. 재귀하지 않는다."""
    limit = MAX_DEPTH if limit is None else limit
    stack = [(obj, 1)]
    while stack:
        v, d = stack.pop()
        if isinstance(v, (dict, list)):
            if d > limit:
                return True
            stack.extend((x, d + 1) for x in (v.values() if isinstance(v, dict) else v))
    return False


def _list_or_empty(v):
    """생략 · null 은 빈 목록과 같다."""
    return [] if v is None else v


def _validate_sync(role, body):
    """sync 요청 형식 검사(잠금 밖). 틀리면 400. 지시 · ack 목록을 돌려준다."""
    state_key = STATE_KEY[role]
    _bad(not _is_long(body.get("seq")) or not _is_long(body.get("sentAt")))
    if role == "PHONE":
        _bad(not _obj_or_null(body.get("watch")))   # 워치가 중계에 직접 붙으면 null
        # 옛 휴대폰 빌드 호환으로 형식만 본다. 저장 · 전달하지 않는다(감사 중4 · G2 최소 전송, 계약 §5)
        _bad(not _obj_or_null(body.get("phoneLocation")))
        # 휴대폰 화면 응답 시각(정수 또는 null, 휴대폰 벽시계 · 변화 표시 전용) — 차 peer 로 그대로 넘긴다
        _bad(body.get("responseAt") is not None and not _is_long(body.get("responseAt")))
        # 차 화면 표시 설정(객체 또는 null) — 안쪽은 보지 않고 차 peer 로 그대로 넘긴다(2026-11-01)
        _bad(not _obj_or_null(body.get("carDisplay")))
        # 건강 동의(bool 또는 null, 2026-11-08 감사 하4) — 생략 · null 은 옛 휴대폰 빌드
        _bad(body.get("healthConsent") is not None and not isinstance(body.get("healthConsent"), bool))
    else:
        _bad(not isinstance(body.get(state_key), dict))
    if role != "WATCH":
        _bad(not _obj_or_null(body.get("flow")))
    commands = _list_or_empty(body.get("commands"))
    acks = _list_or_empty(body.get("acks"))
    _bad(not isinstance(commands, list) or len(commands) > MAX_COMMANDS_PER_SYNC)
    _bad(not isinstance(acks, list) or not all(isinstance(a, str) for a in acks))
    for c in commands:
        _bad(not isinstance(c, dict))
        _bad(not isinstance(c.get("id"), str) or not c["id"] or len(c["id"]) > 64)
        _bad(c.get("type") not in COMMAND_TYPES)
        _bad(not _obj_or_null(c.get("payload")))
        _bad(len(json.dumps(c, ensure_ascii=False, separators=(",", ":")).encode("utf-8")) > MAX_COMMAND_BYTES)
        # 이중 방어 — 파랑 · 빨강 깜빡임은 법률 검토 전 금지(G6). 다른 payload 는 판정하지 않는다.
        mode = (c.get("payload") or {}).get("mode")
        _bad(c["type"] == "AMBIENT" and isinstance(mode, str) and mode.upper() == "BLUE_RED_BLINK")
        _bad(not _is_int(c.get("ttlMs")) or not 0 < c["ttlMs"] <= MAX_COMMAND_TTL_MS)
        _bad(not isinstance(c.get("test"), bool))
        _bad(c.get("from") != role)
        to = c.get("to")  # 생략 · null 만 기본 대상. "" · 0 · false 등은 400
        _bad(to is not None and (not isinstance(to, str) or to not in TARGETS or to == role))
        # 허용 표(D-1) — 기본 대상까지 풀어서 본다. 시험용 지시는 test:true 만
        _bad((to or DEFAULT_TO[role]) not in COMMAND_ROLES[role].get(c["type"], ()))
        _bad(c["type"] in TEST_ONLY_TYPES and c["test"] is not True)
    return commands, acks


class _Pair:
    def __init__(self, pair_id, car_token, created_at, owner=None):
        self.pair_id = pair_id
        # 짝을 만든 보낸 쪽 키(주소별 짝 상한용 — 로그 · 응답에 싣지 않는다)와 마지막 활동(세 역할 어느 쪽 sync 든, 단조, 없으면 만든 시각)
        self.owner = owner
        self.last_seen = created_at
        self.tokens = {"CAR": car_token, "PHONE": None, "WATCH": None}
        self.expires_at = created_at + PAIR_TTL_MS
        # 휴대폰 · 워치가 한 번도 참여하지 않았으면 첫 코드 만료 시각에 지운다(pair/code 로 늘리지 못함)
        self.first_code_until = created_at + PAIR_CODE_TTL_MS
        self.ever_joined = False
        self.state = {r: None for r in ROLES}       # 보낸 쪽의 최신 상태 1건
        self.last_seq = {r: None for r in ROLES}
        self.queue = {r: [] for r in ROLES}         # 그 쪽이 받을 지시
        # 그 쪽 큐에 들어간 지시 id -> 그 지시의 만료 시각(중복 전송 방지, 만료 뒤 정리)
        self.seen_ids = {r: {} for r in ROLES}
        # 휴대폰이 마지막으로 보낸 건강 동의(true · false · 옛 빌드면 None). false 면 워치 값을 보관하지 않는다(감사 하4, 계약 §5)
        self.health_consent = None

    def chosen_watch(self, mono, wall):
        """워치 직접(WATCH) · 휴대폰 경유(PHONE) 가운데 중계가 더 늦게 받은 워치 상태.

        "via" · "receivedAt"(중계 시계) · "ageMs"(그 응답의 serverTime − receivedAt)를 붙인다. 받은 시각이 같으면 직접(WATCH)을 고른다.
        받은 순서 · 나이는 단조 시계로 잰다(_age · _received_at).
        """
        best = None
        for via in ("WATCH", "PHONE"):
            st = self.state[via]
            if st is not None and st.get("watch") is not None:
                if best is None or st["_rx"] > best[0]:
                    best = (st["_rx"], via, st["watch"])
        if best is None:
            return None
        return dict(best[2], via=best[1], receivedAt=_received_at(best[0], mono, wall), ageMs=_age(best[0], mono))

    def peer_out(self, role, mono, wall):
        """보관한 상태 1건을 응답 꼴로 — 안쪽 단조 시각(_rx)을 빼고 receivedAt(중계 벽시계 기준)을 붙인다."""
        st = self.state[role]
        if st is None:
            return None
        out = {k: v for k, v in st.items() if k != "_rx"}
        out["receivedAt"] = _received_at(st["_rx"], mono, wall)
        return out

    def watch_direct_age(self, now):
        """워치가 중계에 직접 보낸 마지막 값의 나이(ms). 직접 값이 없으면 None.

        고른 값(chosen_watch)은 더 최근 것만 싣기 때문에 직접 경로가 살아 있어도 via=PHONE 일 수 있다.
        워치에 가는 지시는 직접 경로로만 닿으므로 이 나이를 따로 내려 준다(계약 §4.3 · §4.7).
        """
        st = self.state["WATCH"]
        return None if st is None else _age(st["_rx"], now)


def _age(rx_mono, mono):
    """단조 시계로 잰 나이(ms). 음수가 되지 않는다."""
    return max(0, mono - rx_mono)


def _received_at(rx_mono, mono, wall):
    """응답에 싣는 receivedAt — 이 응답의 serverTime(벽시계)에서 단조 나이를 뺀 값.

    벽시계가 흔들리지 않으면 받은 순간의 벽시계 값과 같고, 흔들려도 serverTime − receivedAt 은 늘 단조 나이(≥ 0)다.
    """
    return wall - _age(rx_mono, mono)


class RelayStore:
    """짝 · 토큰 · 최신 상태 · 지시 큐. 모든 공개 메서드는 Lock 안에서 돈다."""

    def __init__(self, clock=now_ms, mono=None, limits=None):
        """clock 은 벽시계(serverTime), mono 는 단조 시계(나이 · 만료 · 제한).

        mono 를 주지 않으면 기본 벽시계일 때는 mono_ms, 시험용 가짜 시계를 주었으면 그 시계 하나를 둘 다로 쓴다.
        """
        self._clock = clock
        self._mono = mono if mono is not None else (mono_ms if clock is now_ms else clock)
        self.limits = limits or Limits()
        L = self.limits
        self._lock = threading.Lock()
        self._codes = {}    # pairCode -> [pair_id, expires_at(단조), 이미 쓴 역할 set, 이 번호에 걸린 실패 수]
        self._pairs = {}    # pair_id -> _Pair
        self._tokens = {}   # token -> (pair_id, role)
        self._join_fail = RateLimiter(L.join_fail_per_addr, L.join_fail_window_ms, L.join_block_ms, L.max_keys)
        self._start_addr = RateLimiter(L.start_per_addr, L.start_window_ms, 0, L.max_keys)
        self._code_addr = RateLimiter(L.code_per_addr, L.code_window_ms, 0, L.max_keys)
        self._code_pair = RateLimiter(L.code_per_pair, L.code_window_ms, 0, L.max_keys)
        self._sync_token = RateLimiter(L.sync_per_token, L.sync_window_ms, 0, L.max_keys)
        # 분 단위 계수 줄(D-4 · 보안 13) — 주소 · 토큰 없이 개수만
        self._stats = {}
        self._max_ms = 0.0
        # 참여 멱등(D-4 판2-4) — (번호, 역할) -> (requestId, 응답, 만료). 번호가 지워져도 만료까지 같은 응답을 다시 준다
        self._join_replies = {}

    # ---- 계수 ----
    def note(self, name, n=1):
        """분 단위 계수 하나를 센다(주소 · 토큰 없이)."""
        with self._lock:
            self._note(name, n)

    def _note(self, name, n=1):
        self._stats[name] = self._stats.get(name, 0) + n

    def note_status(self, status, ms=0.0):
        with self._lock:
            self._note(str(status))
            self._max_ms = max(self._max_ms, ms)

    STAT_KEYS = ("200", "400", "401", "404", "429", "503", "join_fail", "xff_bad", "evicted", "seq_stale")

    def stats_line(self, reset=False, conns=None):
        """한 줄 요약 — 상태 코드별 · 참여 실패 · 헤더 실패 · 밀어낸 짝 · seq 역행 개수, 짝 · 번호 수. 404 가 분당 30, 429 가 60,
        짝이 상한의 80% 를 넘으면 WARN 을 붙인다. 주소 · 토큰은 담지 않는다."""
        with self._lock:
            st = self._stats
            parts = ["%s=%d" % (k, st.get(k, 0)) for k in self.STAT_KEYS]
            parts += ["pairs=%d" % len(self._pairs), "codes=%d" % len(self._codes), "max_ms=%.0f" % self._max_ms]
            if conns is not None:
                parts.append("conns=%d" % conns)
            warn = []
            if st.get("404", 0) > 30:
                warn.append("WARN 404")
            if st.get("429", 0) > 60:
                warn.append("WARN 429")
            if len(self._pairs) > 0.8 * self.limits.max_pairs:
                warn.append("WARN pairs")
            if reset:
                self._stats = {}
                self._max_ms = 0.0
            return "stats " + " ".join(parts + warn)

    # ---- 내부 ----
    def _purge(self, now):
        for k in [k for k, v in self._join_replies.items() if v[2] <= now]:
            del self._join_replies[k]
        for code in [c for c, v in self._codes.items() if v[1] <= now]:
            del self._codes[code]
        for pid in [p for p, pair in self._pairs.items() if pair.expires_at <= now]:
            self._drop_pair(pid)
        # 참여한 역할(휴대폰 · 워치)이 없고 살아 있는 코드도 없는 짝 — 아무도 붙을 수 없으니 지운다(무인증 pair/start 로 자리를 24시간 채우지 못하게)
        # 한 번도 참여한 적 없는 짝은 첫 코드가 만료되면 pair/code 와 관계없이 지운다(새 코드를 거듭 받아 자리를 붙잡지 못하게)
        live = {v[0] for v in self._codes.values()}
        for pid in [p for p, pair in self._pairs.items()
                    if all(pair.tokens[r] is None for r in JOIN_ROLES)
                    and (p not in live or (not pair.ever_joined and pair.first_code_until <= now))]:
            self._drop_pair(pid)

    def _drop_codes_of(self, pid):
        for code in [c for c, v in self._codes.items() if v[0] == pid]:
            del self._codes[code]

    def _drop_pair(self, pid):
        pair = self._pairs.pop(pid, None)
        if pair is None:
            return
        for tok in pair.tokens.values():
            if tok is not None:
                self._tokens.pop(tok, None)
        self._drop_codes_of(pid)

    def _auth(self, token, role=None):
        if not isinstance(token, str):
            raise ApiError(401, "TOKEN_INVALID")
        hit = self._tokens.get(token)
        if hit is None or (role is not None and hit[1] != role):
            raise ApiError(401, "TOKEN_INVALID")
        return self._pairs[hit[0]], hit[1]

    def _new_code(self):
        while True:
            code = "%06d" % secrets.randbelow(1_000_000)
            if code not in self._codes:
                return code

    # ---- 짝짓기 ----
    def pair_start(self, addr=None):
        with self._lock:
            now = self._mono()
            self._purge(now)
            if addr is not None and not self._start_addr.allow(addr, now):
                raise ApiError(429, "RATE_LIMITED")
            if addr is not None:
                self._make_room_for(addr, now)
            if len(self._pairs) >= self.limits.max_pairs:
                # 살아 있는 짝은 밀어내지 않는다(쓰는 중인 연결이 끊긴다)
                raise ApiError(503, "CAPACITY")
            pid = secrets.token_hex(8)
            car_token = secrets.token_hex(16)
            self._pairs[pid] = _Pair(pid, car_token, now, addr)
            self._tokens[car_token] = (pid, "CAR")
            code = self._new_code()
            self._codes[code] = [pid, now + PAIR_CODE_TTL_MS, set(), 0]
            return {"pairCode": code, "carToken": car_token, "expiresInSec": PAIR_CODE_TTL_MS // 1000}

    def _make_room_for(self, addr, now):
        """주소별 짝 상한. 닿았으면 그 주소의 조용한 짝 하나를 지운다 — 세 역할 모두 sync 가 문턱(미참여 짝 pair_idle_ms ·
        참여한 적 있는 짝 joined_idle_ms) 넘게 없는 짝만. 참여 안 한 것 먼저, 그다음 가장 오래 조용한 것.
        조용한 짝이 없으면 429(쓰는 중인 짝은 밀어내지 않는다)."""
        L = self.limits
        mine = [p for p in self._pairs.values() if p.owner == addr]
        if len(mine) < L.pairs_per_addr:
            return
        idle = [p for p in mine
                if now - p.last_seen >= (L.joined_idle_ms if p.ever_joined else L.pair_idle_ms)]
        if len(mine) - len(idle) >= L.pairs_per_addr:
            raise ApiError(429, "RATE_LIMITED")
        idle.sort(key=lambda p: (p.ever_joined, p.last_seen))
        for p in idle[: len(mine) - L.pairs_per_addr + 1]:
            self._drop_pair(p.pair_id)
            self._note("evicted")

    def pair_code(self, body, addr=None):
        """차가 새 짝 코드를 받는다(워치 다시 붙이기 · 휴대폰 재설치 등). 이미 참여한 역할도 이 코드로 다시 붙으면 토큰이 바뀐다.

        주소마다 · 짝마다 빈도를 제한한다(429, 짝은 그대로 둔다).
        """
        with self._lock:
            now = self._mono()
            self._purge(now)
            if addr is not None and self._code_addr.blocked(addr, now):
                raise ApiError(429, "RATE_LIMITED")
            pair, _ = self._auth(body.get("token"), "CAR")
            if not self._code_pair.allow(pair.pair_id, now):
                raise ApiError(429, "RATE_LIMITED")
            if addr is not None:
                self._code_addr.hit(addr, now)
            pair.last_seen = now  # 새 번호 요청도 활동이다(주소별 짝 상한의 「쓰는 중」 판단)
            self._drop_codes_of(pair.pair_id)  # 짝마다 살아 있는 코드는 하나
            code = self._new_code()
            expires = now + PAIR_CODE_TTL_MS
            if not pair.ever_joined:
                expires = min(expires, pair.first_code_until)  # 참여한 적 없는 짝은 첫 번호 만료에 지워진다 — 남은 시간을 사실대로(D-4 판2-4)
            self._codes[code] = [pair.pair_id, expires, set(), 0]
            return {"pairCode": code, "expiresInSec": max(0, (expires - now) // 1000)}

    def pair_join(self, body, addr=None):
        """짝 참여. 실패(틀린 · 만료 · 이미 쓴 번호)를 주소마다 세어 문턱에 닿으면 그 주소의 pair/join 을 막는다(429).

        막힌 주소는 맞는 번호도 막는다(맞을 때만 통과시키면 대입이 계속된다). 성공해도 실패 수를 지우지 않는다
        (제 짝을 만들어 참여하는 것으로 계수기를 비우지 못하게). 살아 있는 번호에 걸린 실패가 상한에 닿으면 그 번호를 버린다.
        """
        code = body.get("pairCode")
        role = body.get("role")
        role = "PHONE" if role is None else role  # 생략 · null 은 휴대폰(선택 필드 규칙)
        _bad(role not in JOIN_ROLES)
        # 선택 requestId — 앱이 시도마다 만들고 다시 시도할 때 그대로 쓴다(D-4 판2-4). 1~64자 문자열
        request_id = body.get("requestId")
        _bad(request_id is not None and (not isinstance(request_id, str) or not 0 < len(request_id) <= 64))
        with self._lock:
            now = self._mono()
            self._purge(now)
            fail_key = None if addr is None else wider_key(addr, self.limits.join_fail_v6_prefix)
            if fail_key is not None and self._join_fail.blocked(fail_key, now):
                raise ApiError(429, "RATE_LIMITED")
            if request_id is not None and isinstance(code, str):
                again = self._join_replies.get((code, role))
                if again is not None and again[0] == request_id:
                    reply = again[1]
                    tok = reply.get("phoneToken") or reply.get("watchToken")
                    if self._tokens.get(tok, (None, None))[1] == role:
                        return dict(reply)  # 응답 유실 뒤 같은 시도 — 같은 토큰, 실패로 세지 않음
            hit = self._codes.get(code) if isinstance(code, str) else None
            # 코드는 역할마다 한 번만 쓴다(휴대폰 한 번 · 워치 한 번)
            pair = self._pairs.get(hit[0]) if hit is not None else None
            if pair is None or role in hit[2]:
                self._note("join_fail")
                if fail_key is not None:
                    self._join_fail.hit(fail_key, now)
                if hit is not None:
                    hit[3] += 1
                    if hit[3] >= self.limits.code_fail_max:
                        del self._codes[code]  # 차는 pair/code 로 새 번호를 받는다
                raise ApiError(404, "PAIR_CODE_INVALID")
            hit[2].add(role)
            if hit[2] >= set(JOIN_ROLES):
                del self._codes[code]
            # 이미 참여한 역할이 다시 붙으면(재설치 등) 옛 토큰을 지우고 seq 기준을 새로 잡는다
            self._tokens.pop(pair.tokens[role], None)
            pair.last_seq[role] = None
            pair.ever_joined = True
            pair.last_seen = now  # 참여도 활동이다 — 참여 직후 첫 sync 전에 밀려나지 않게(검증 3차 하 1)
            token = secrets.token_hex(16)
            pair.tokens[role] = token
            self._tokens[token] = (pair.pair_id, role)
            reply = {("phoneToken" if role == "PHONE" else "watchToken"): token, "pairId": pair.pair_id}
            if request_id is not None:
                self._join_replies[(code, role)] = (request_id, dict(reply), hit[1])
            return reply

    def pair_end(self, body):
        with self._lock:
            self._purge(self._mono())
            pair, role = self._auth(body.get("token"))
            if role == "WATCH":
                # 워치 토큰은 워치 연결만 끊는다(차 · 휴대폰 짝은 유지)
                self._tokens.pop(pair.tokens["WATCH"], None)
                pair.tokens["WATCH"] = None
                pair.state["WATCH"] = pair.last_seq["WATCH"] = None
                pair.queue["WATCH"] = []
            else:
                self._drop_pair(pair.pair_id)
            return {}

    # ---- sync ----
    def sync(self, role, body):
        state_key = STATE_KEY[role]
        commands, acks = _validate_sync(role, body)

        with self._lock:
            now = self._mono()
            wall = self._clock()
            self._purge(now)
            pair, _ = self._auth(body.get("token"), role)
            if not self._sync_token.allow(body["token"], now):
                raise ApiError(429, "RATE_LIMITED")
            pair.last_seen = now  # 주소별 짝 상한에서 「쓰는 중」 판단 — 차가 조용해도 휴대폰 · 워치가 sync 하면 쓰는 중
            seq = body["seq"]

            # 최신 상태 1건 — seq 가 이전보다 조금 작으면(늦게 온 옛 요청) 덮지 않고, 크게 작으면(재시작) 덮는다
            last = pair.last_seq[role]
            if not (last is None or seq >= last or last - seq > SEQ_RESTART_GAP):
                self._note("seq_stale")  # 덮지 않음 — 응답에는 싣지 않는다(sec-audit 3차, 진짜 값의 위치를 좁히지 못하게)
            if last is None or seq >= last or last - seq > SEQ_RESTART_GAP:
                pair.last_seq[role] = seq
                if role == "PHONE":
                    pair.health_consent = body.get("healthConsent")
                    if pair.health_consent is False and pair.state["WATCH"] is not None:
                        pair.state["WATCH"]["watch"] = None  # 이미 보관한 워치 직접 값도 지운다
                st = {"seq": seq, "sentAt": body["sentAt"], "_rx": now,  # _rx — 받은 단조 시각(응답에서 receivedAt 으로 바뀜)
                      state_key: body.get(state_key)}
                if state_key == "watch" and pair.health_consent is False:
                    # 워치 값은 버리고 받은 시각만 남긴다(워치 직접이면 watchDirectAgeMs 는 그대로, §4.3)
                    st["watch"] = None
                if role != "WATCH":
                    st["flow"] = body.get("flow")
                if role == "PHONE":
                    st["responseAt"] = body.get("responseAt")
                    st["carDisplay"] = body.get("carDisplay")
                pair.state[role] = st

            # 내 큐 — ack 된 지시 제거, ttl 지난 지시 버림
            ack_set = set(acks)
            mine = pair.queue[role]
            mine[:] = [q for q in mine if q["cmd"]["id"] not in ack_set and q["expiresAt"] > now]
            for seen in pair.seen_ids.values():  # 중복 막기 기록은 그 지시 ttl 이 지나면 지운다
                for i in [i for i, exp in seen.items() if exp <= now]:
                    del seen[i]

            # 보낸 지시 → 받는 쪽 큐(같은 id 는 받는 쪽마다 한 번만)
            for c in commands:
                to = c.get("to") or DEFAULT_TO[role]  # 앞 검사가 "" · 0 · false 를 이미 400 으로 막았다
                payload = c.get("payload")
                cmd = {"id": c["id"], "type": c["type"], "payload": {} if payload is None else payload,
                       "ttlMs": c["ttlMs"], "test": c["test"], "from": role, "to": to}
                for target in ([r for r in ROLES if r != role] if to == "ALL" else [to]):
                    if c["id"] in pair.seen_ids[target]:
                        continue
                    seen = pair.seen_ids[target]
                    seen[c["id"]] = now + c["ttlMs"]
                    if len(seen) > MAX_SEEN_IDS:
                        for old in sorted(seen, key=seen.get)[: len(seen) - MAX_SEEN_IDS]:
                            del seen[old]
                    q = pair.queue[target]
                    q.append({"cmd": cmd, "expiresAt": now + c["ttlMs"],
                              "bytes": len(json.dumps(cmd, ensure_ascii=False, separators=(",", ":")).encode("utf-8"))})
                    if len(q) > MAX_QUEUE:
                        del q[: len(q) - MAX_QUEUE]  # 오래된 것부터 버린다
                    total = sum(x["bytes"] for x in q)
                    while total > MAX_QUEUE_BYTES and len(q) > 1:
                        total -= q.pop(0)["bytes"]  # 바이트 합 상한 — 오래된 것부터

            out = {"serverTime": wall}
            if role != "WATCH":
                out["peer"] = pair.peer_out("PHONE" if role == "CAR" else "CAR", now, wall)
                out["watch"] = pair.chosen_watch(now, wall)
                out["watchDirectAgeMs"] = pair.watch_direct_age(now)
            out["commands"] = [q["cmd"] for q in mine]
            return out


ROUTES = {  # (저장소, 본문, 보낸 쪽 키)
    "/v1/pair/start": lambda s, b, a: s.pair_start(a),
    "/v1/pair/code": lambda s, b, a: s.pair_code(b, a),
    "/v1/pair/join": lambda s, b, a: s.pair_join(b, a),
    "/v1/pair/end": lambda s, b, a: s.pair_end(b),
    "/v1/car/sync": lambda s, b, a: s.sync("CAR", b),
    "/v1/phone/sync": lambda s, b, a: s.sync("PHONE", b),
    "/v1/watch/sync": lambda s, b, a: s.sync("WATCH", b),
}


def make_handler(store):
    class Handler(BaseHTTPRequestHandler):
        server_version = "guardian-relay"
        sys_version = ""
        protocol_version = "HTTP/1.1"
        timeout = SOCKET_TIMEOUT_S  # 소켓 읽기 제한 시간(느리거나 멈춘 클라이언트가 스레드를 붙잡지 않게)

        def _send(self, status, data):
            self.send_response(status)
            self.send_header("Content-Type", "application/json; charset=utf-8")
            self.send_header("Cache-Control", "no-store")
            self.send_header("Content-Length", str(len(data)))
            # 모든 응답 뒤 연결을 닫는다(D-3 · 보2-1) — 한 연결에 이어 붙인 요청이 다음 요청으로 읽히지 않게
            self.close_connection = True
            self.send_header("Connection", "close")
            self.end_headers()
            self.wfile.write(data)

        def _length(self):
            """Content-Length — ASCII 숫자만(부호 · 공백 · 다른 숫자 꼴은 400). Transfer-Encoding 은 받지 않는다(D-3)."""
            if self.headers.get("Transfer-Encoding") is not None:
                raise ApiError(400, "BAD_REQUEST")
            v = self.headers.get_all("Content-Length") or []
            if len(v) > 1:
                raise ApiError(400, "BAD_REQUEST")
            if not v:
                return 0
            text = v[0]
            if not text or not text.isascii() or not text.isdigit():
                raise ApiError(400, "BAD_REQUEST")
            return int(text)

        def _route(self, method, path):
            length = self._length()
            if path == "/v1/health":
                if method != "GET":
                    raise ApiError(405, "METHOD_NOT_ALLOWED")
                if length > 0:
                    raise ApiError(400, "BAD_REQUEST")  # health 는 본문을 읽지 않으므로 받지 않는다(D-3)
                return {"ok": True}
            fn = ROUTES.get(path)
            if fn is None:
                raise ApiError(404, "NOT_FOUND")
            if method != "POST":
                raise ApiError(405, "METHOD_NOT_ALLOWED")
            if length > MAX_BODY_BYTES:
                raise ApiError(413, "BODY_TOO_LARGE")
            raw = self.rfile.read(length) if length > 0 else b""
            if len(raw) < length:
                raise ConnectionError("short body")  # 본문이 덜 온 채 연결이 닫힘
            return fn(store, parse_body(raw), self._client())

        def _client(self):
            """빈도 제한용 보낸 쪽 키(로그에 남기지 않는다)."""
            return client_key(self.client_address[0], self.headers.get_all("X-Forwarded-For") or (),
                              store.limits.trust_proxy, on_bad=lambda: store.note("xff_bad"))

        def handle_one_request(self):
            # 요청 하나(기다림 · 머리 · 본문 · 응답)에 전체 마감을 건다. 소켓 timeout 은 한 번 읽기마다라서
            # 조금씩 보내며 버티는 느린 클라이언트(slowloris)를 끊지 못한다. 마감이 지나면 감시 스레드가 소켓을 닫는다.
            arm = getattr(self.server, "arm_deadline", None)
            if arm is not None:
                arm(self.connection)
            try:
                super().handle_one_request()
            finally:
                disarm = getattr(self.server, "disarm_deadline", None)
                if disarm is not None:
                    disarm(self.connection)

        def _handle(self, method):
            t0 = time.monotonic()
            path = self.path.split("?", 1)[0]
            addr = None
            try:
                addr = self._client()
                enter = getattr(self.server, "enter_addr", None)
                if enter is not None and not enter(addr):
                    addr = None
                    raise ApiError(429, "RATE_LIMITED")  # 주소 키 동시 처리 상한(D-3)
                result = self._route(method, path)
                status = 200
                data = json.dumps(result, ensure_ascii=False, separators=(",", ":"),
                                  allow_nan=False).encode("utf-8")  # 무한 · NaN 은 500(이중 방어)
            except ApiError as e:
                status, data = e.status, json.dumps({"error": e.code}).encode()
                self.close_connection = True  # 읽지 않은 본문이 다음 요청으로 읽히지 않게 끊는다
            except (TimeoutError, ConnectionError):
                raise  # 응답 없이 끊는다(서버 handle_error 가 종류만 남김)
            except Exception as e:  # 응답 인코딩 실패 포함. 본문 · 토큰을 남기지 않도록 예외 종류만 적는다
                LOG.error("internal error %s", type(e).__name__)
                status, data = 500, b'{"error":"INTERNAL"}'
                self.close_connection = True
            finally:
                leave = getattr(self.server, "leave_addr", None)
                if addr is not None and leave is not None:
                    leave(addr)
            self._send(status, data)
            store.note_status(status, (time.monotonic() - t0) * 1000)
            known = path == "/v1/health" or path in ROUTES
            LOG.info("%s %s %d %.1fms", method, path if known else "-", status,
                     (time.monotonic() - t0) * 1000)

        def do_GET(self):
            self._handle("GET")

        def do_POST(self):
            self._handle("POST")

        def log_request(self, code="-", size="-"):
            pass  # 접근 로그는 _handle 이 형식을 정해 남긴다

        def log_message(self, format, *args):
            # 기본 구현은 요청 줄 · 인자를 그대로 찍는다. 개인정보가 섞이지 않게 내용 없이 남긴다.
            LOG.warning("http protocol message suppressed")

    return Handler


class RelayServer(ThreadingHTTPServer):
    """동시 연결 수 상한(넘으면 응답 없이 닫음)과 요청 읽기 마감 감시를 더한 서버."""
    daemon_threads = True

    def __init__(self, addr, handler, limits):
        self.limits = limits
        self._conn_lock = threading.Lock()
        self._active = 0
        self._deadlines = {}  # 소켓 -> 마감(단조 초)
        self._per_addr = {}   # 주소 키 -> 지금 처리 중인 요청 수(로그에 남기지 않는다)
        self._stop = threading.Event()
        super().__init__(addr, handler)
        threading.Thread(target=self._reap, name="relay-deadline", daemon=True).start()

    def process_request(self, request, client_address):
        with self._conn_lock:
            full = self._active >= self.limits.max_conns
            if not full:
                self._active += 1
        if full:
            LOG.warning("connection limit")  # 주소는 남기지 않는다
            self.shutdown_request(request)
            return
        try:
            super().process_request(request, client_address)
        except Exception:
            self._release()
            raise

    def process_request_thread(self, request, client_address):
        try:
            super().process_request_thread(request, client_address)
        finally:
            self.disarm_deadline(request)
            self._release()

    def enter_addr(self, key):
        """주소 키 하나의 동시 처리 수를 센다. 상한이면 False(D-3)."""
        with self._conn_lock:
            n = self._per_addr.get(key, 0)
            if n >= self.limits.concurrent_per_addr:
                return False
            self._per_addr[key] = n + 1
            return True

    def leave_addr(self, key):
        with self._conn_lock:
            n = self._per_addr.get(key, 0) - 1
            if n > 0:
                self._per_addr[key] = n
            else:
                self._per_addr.pop(key, None)

    def _release(self):
        with self._conn_lock:
            self._active -= 1

    def active_connections(self):
        with self._conn_lock:
            return self._active

    def arm_deadline(self, sock):
        with self._conn_lock:
            self._deadlines[sock] = time.monotonic() + self.limits.request_deadline_s

    def disarm_deadline(self, sock):
        with self._conn_lock:
            self._deadlines.pop(sock, None)

    def _reap(self):
        next_stats = time.monotonic() + STATS_EVERY_S
        while not self._stop.wait(0.1):
            now = time.monotonic()
            self.reap_once(now)
            store = getattr(self, "store", None)
            if store is not None and now >= next_stats:
                next_stats = now + STATS_EVERY_S
                LOG.info("%s", store.stats_line(reset=True, conns=self.active_connections()))  # 주소 · 토큰 없음(D-4 · 보안 13)

    def reap_once(self, now):
        # 고르기와 닫기를 한 잠금 안에서 한다 — 고른 뒤 잠금을 놓으면 그 사이 처리 스레드가 다음 요청의 마감을 새로 걸고,
        # 새 요청이 옛 마감으로 잘릴 수 있다. shutdown 은 막히지 않는다.
        with self._conn_lock:
            for sock in [k for k, d in self._deadlines.items() if d <= now]:
                del self._deadlines[sock]
                try:
                    sock.shutdown(socket.SHUT_RDWR)  # 막힌 읽기를 깨워 처리 스레드가 끝나게 한다
                except OSError:
                    pass

    def server_close(self):
        self._stop.set()
        super().server_close()

    def handle_error(self, request, client_address):
        # 기본 구현은 추적 전체를 stderr 에 찍는다. 예외 종류만 남긴다.
        LOG.warning("connection error %s", type(sys.exc_info()[1]).__name__)


def make_server(bind="127.0.0.1", port=8095, store=None):
    store = store or RelayStore()
    srv = RelayServer((bind, port), make_handler(store), store.limits)
    srv.store = store
    return srv


def _positive(kind):
    def conv(text):
        v = kind(text)
        if v <= 0:
            raise argparse.ArgumentTypeError("0 보다 커야 한다")
        return v
    return conv


def main(argv=None):
    ap = argparse.ArgumentParser(description="운전자 무응답 확인(가칭) 베타 중계 서버")
    ap.add_argument("--bind", default="127.0.0.1", help="듣는 주소(같은 Wi-Fi 휴대폰은 0.0.0.0, 공개는 프록시 뒤 127.0.0.1)")
    ap.add_argument("--port", type=int, default=8095)
    d = Limits()
    pi, pf = _positive(int), _positive(float)
    ap.add_argument("--trust-proxy", action="store_true",
                    help="X-Forwarded-For 의 마지막 값을 보낸 주소로 쓴다(TLS 프록시 뒤 127.0.0.1 에서만)")
    ap.add_argument("--join-fail-per-addr", type=pi, default=d.join_fail_per_addr, help="주소마다 pair/join 실패 문턱")
    ap.add_argument("--join-fail-window-sec", type=pi, default=d.join_fail_window_ms // 1000, help="실패를 세는 창(초)")
    ap.add_argument("--join-block-sec", type=pi, default=d.join_block_ms // 1000, help="문턱에 닿은 주소를 막는 시간(초)")
    ap.add_argument("--code-fail-max", type=pi, default=d.code_fail_max, help="번호 하나에 걸린 실패 상한(닿으면 번호를 버림)")
    ap.add_argument("--code-per-pair", type=pi, default=d.code_per_pair, help="짝마다 pair/code 횟수(창 안)")
    ap.add_argument("--code-per-addr", type=pi, default=d.code_per_addr, help="주소마다 pair/code 횟수(창 안)")
    ap.add_argument("--code-window-sec", type=pi, default=d.code_window_ms // 1000, help="pair/code 창(초)")
    ap.add_argument("--start-per-addr", type=pi, default=d.start_per_addr, help="주소마다 pair/start 횟수(창 안)")
    ap.add_argument("--start-window-sec", type=pi, default=d.start_window_ms // 1000, help="pair/start 창(초)")
    ap.add_argument("--max-pairs", type=pi, default=d.max_pairs, help="살아 있는 짝 수 상한")
    ap.add_argument("--pairs-per-addr", type=pi, default=d.pairs_per_addr, help="주소마다 살아 있는 짝 수 상한")
    ap.add_argument("--pair-idle-sec", type=pi, default=d.pair_idle_ms // 1000,
                    help="주소별 상한에서 밀어낼 수 있는 미참여 짝 — 세 역할 sync 가 이만큼 없는 짝(초)")
    ap.add_argument("--joined-idle-sec", type=pi, default=d.joined_idle_ms // 1000,
                    help="주소별 상한에서 밀어낼 수 있는 참여한 적 있는 짝 — 세 역할 sync 가 이만큼 없는 짝(초)")
    ap.add_argument("--join-fail-v6-prefix", type=int, choices=range(32, 65), default=d.join_fail_v6_prefix,
                    metavar="32-64", help="pair/join 실패를 셀 때 IPv6 를 묶는 넓이")
    ap.add_argument("--max-keys", type=pi, default=d.max_keys, help="계수기마다 기억하는 주소 · 짝 수 상한")
    ap.add_argument("--max-conns", type=pi, default=d.max_conns, help="동시 연결 수 상한")
    ap.add_argument("--request-deadline-sec", type=pf, default=d.request_deadline_s, help="요청 하나를 다 읽는 전체 시간(초)")
    ap.add_argument("--sync-per-token", type=pi, default=d.sync_per_token, help="토큰마다 sync 횟수(창 안)")
    ap.add_argument("--sync-window-sec", type=pi, default=d.sync_window_ms // 1000, help="sync 빈도 창(초)")
    ap.add_argument("--concurrent-per-addr", type=pi, default=d.concurrent_per_addr, help="주소 키마다 동시 처리 요청 수")
    args = ap.parse_args(argv)
    if args.trust_proxy and not _is_loopback(args.bind):
        # 프록시를 믿는데 바깥에서 바로 닿으면 누구나 X-Forwarded-For 로 주소를 지어낸다(D-4 보2-3)
        ap.error("--trust-proxy 는 루프백 --bind(127.0.0.1 · ::1)와만 쓸 수 있다")
    limits = Limits(
        trust_proxy=args.trust_proxy,
        join_fail_per_addr=args.join_fail_per_addr, join_fail_window_ms=args.join_fail_window_sec * 1000,
        join_block_ms=args.join_block_sec * 1000, code_fail_max=args.code_fail_max,
        code_per_pair=args.code_per_pair, code_per_addr=args.code_per_addr, code_window_ms=args.code_window_sec * 1000,
        start_per_addr=args.start_per_addr, start_window_ms=args.start_window_sec * 1000,
        max_pairs=args.max_pairs, pairs_per_addr=args.pairs_per_addr, pair_idle_ms=args.pair_idle_sec * 1000,
        joined_idle_ms=args.joined_idle_sec * 1000,
        join_fail_v6_prefix=args.join_fail_v6_prefix, max_keys=args.max_keys, max_conns=args.max_conns,
        request_deadline_s=args.request_deadline_sec, sync_per_token=args.sync_per_token,
        sync_window_ms=args.sync_window_sec * 1000, concurrent_per_addr=args.concurrent_per_addr)
    logging.basicConfig(level=logging.INFO, format="%(asctime)s %(message)s")
    server = make_server(args.bind, args.port, RelayStore(limits=limits))
    LOG.info("listening %s:%d (memory only)", args.bind, server.server_address[1])
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        pass
    finally:
        server.server_close()


if __name__ == "__main__":
    main()
