<!-- :phone 휴대폰 앱 작업 중 내린 결정과 근거 · 권한 보호 수준 표(app-phone) -->
# :phone 컨텍스트 노트

## 결정과 근거

| 날짜 | 결정 | 근거 |
|---|---|---|
| 2026-10-30 | 차 → 휴대폰 주인 넘김 때 차의 마지막 flow 는 **60초(`PhoneEngine.HANDOFF_MAX_AGE_MS`) 안의 것만** 이어 간다. 그보다 오래되면 새 상태 기계(OFF)에서 시작한다 | 중계는 마지막 상태를 짝 24시간 동안 지우지 않고 내려 준다. 앱을 다시 켰을 때 몇 시간 전 S4 같은 끝난 사건을 되살려 문자 · 119 화면을 다시 내지 않으려는 상한이다. 값은 [추정] — 차 링크 끊김(10초) + sync 몇 번 실패를 덮을 만큼 길고, 사건 하나(경보 35초 + 정차 관찰)보다는 짧게 잡았다. 실기기 끊김 시험으로 다시 정한다 |
| 2026-10-30 | 평소 sync 의 flow 는 휴대폰이 주인이면 중계 시각(SERVER) 스냅숏, 차가 주인이거나 모르면 설정만 담은 OFF 스냅숏(owner CAR) | PM 예고(차가 켬 · 동의를 알게) · 시계를 섞지 않게. 계약 §2 와 하나의 규칙으로 맞추는 일은 be-dev(PM 결정 4) |
| 2026-10-30 | 끄기는 짝을 남기고 OFF 스냅숏으로 마지막 sync, 동의 거부 · 철회는 `pair/end` | PM 결정 3 |
| 2026-10-30 | `OPEN_119_COMPOSE` 는 P12 만 띄우고 문자 앱은 사람이 버튼을 눌러야 연다. 주행 중에 받으면 받아 두었다가 정지로 바뀌는 순간 P12, 그 전에 ALERT_STOP 이면 버린다 | G4 · 백그라운드 액티비티 시작 제한 · app-qa 1차 4 |
| 2026-10-30 | 켜기 필수 — 등록 · 동의 3종 · 위치 · 문자 · 알림 · 전체 화면 알림(Android 14 이상). 방해 금지 접근 · 차와 연결은 「빠진 조건」 표시만 | 알림 · 전체 화면 알림이 없으면 잠금 · 화면 꺼짐에서 경보 화면이 뜨지 않는다(app-qa 1차 1 · 3). 방해 금지는 막지 않음(1차 2), 중계 없이 휴대폰 혼자도 돈다 |
| 2026-10-30 | 포그라운드 서비스 시작이 거부되면 「감시가 멈췄습니다 · 앱을 열어 다시 켜세요」 일반 알림 | `START_STICKY` 재시작이 Android 14 이상에서 위치 유형으로 거부될 수 있다 [미검증] · app-qa 1차 7 |
| 2026-10-30 | Data Layer 는 워치 → 휴대폰 `/guardian/watch` 수신만, capability `guardian_phone`. 휴대폰 → 워치 지시는 중계(to WATCH)로만 | app-wear 확정 · PM 지시(경로 하나로) |
| 2026-10-30 | 본문은 휴대폰이 만든다 — 지시에는 사건 사실(WireFacts)만, `IncidentComposer` + 등록 정보(`Registration.profile()`) · 차 링크가 살아 있을 때만 `peer.vehicle` · 지금 기준 ageMs 로 고친 워치 · 휴대폰 위치. 실패면 보내지 않고 안내 · 문자 기록에 남김 | main 209e84a 계약 변경(G2 — 개인정보가 중계 · 차를 거치지 않음) |
| 2026-10-30 | 휴대폰이 주인이면 `toCommands(.., offsetMs)` → `splitLocal` — 자기 대상은 받은 지시와 같은 `CommandExecutor.execute` 로, ALL 은 휴대폰 몫도 바로 실행하고 중계로도 보냄 | 중계는 자기 대상이 섞이면 요청 전체를 400 으로 거른다. ALL 은 중계가 보낸 쪽을 빼고 전한다 |
| 2026-10-30 | 차가 주인일 때 정차 중 「괜찮아요」는 누른 순간 벽시계를 한 번만 잡아 sync `responseAt` 에 싣고, ALERT_STOP 이 올 때까지 「응답 보냄 · 확인 중」 | 워치의 매 sync 재계산 결함 재발 방지(PM 지시) |
| 2026-10-30 | 휴대폰 GPS 정지 판정은 `SpeedSource.PHONE_GPS`(1km/h 이하 + 디바운스 5초) | 출처를 빼면 CAR 로 보아 속도 0 만 정지 — S4 · 119 작성을 놓친다 |
| 2026-10-30 | 워치 응답은 :shared `ResponseChange` 에 지금까지 본 가장 늦은 responseAt 을 넣는다 | 중계 경유 · Data Layer 값이 번갈아 오면 기준이 옛 값으로 되돌아가 거짓 응답이 날 수 있다 |

## 권한 보호 수준

| 권한 | 보호 수준 | 쓰는 곳 | 켜기 필수 |
|---|---|---|---|
| `INTERNET` | normal | 중계 sync | — |
| `FOREGROUND_SERVICE` | normal | 감시 서비스 | — |
| `FOREGROUND_SERVICE_LOCATION` | normal(Android 14 이상 유형 권한) | 위치 유형 포그라운드 서비스 | — |
| `WAKE_LOCK` | normal | 화면이 꺼져도 2초 sync [추정] | — |
| `USE_FULL_SCREEN_INTENT` | normal(13 이하) · 특수 앱 접근(14 이상, 통화 · 알람 앱이 아니면 사용자가 허용) | 잠금 화면 위 경보(P10 · P11 · P12) | 14 이상 필수 |
| `ACCESS_NOTIFICATION_POLICY` | normal + 사용자가 설정에서 접근 허용 | 방해 금지 중 알람 음량 | 표시만 |
| `ACCESS_FINE_LOCATION` · `ACCESS_COARSE_LOCATION` | dangerous | 휴대폰 위치(문자 · 정지 판정) | 필수 |
| `SEND_SMS` | dangerous · Play 제한 권한(공개 배포 때 예외 심사) | 보호자 문자만(119 는 `ACTION_SENDTO` 작성 화면) | 필수 |
| `POST_NOTIFICATIONS` | dangerous(13 이상) | 경보 · 감시 알림 | 필수(13 이하는 앱 알림 켜짐 여부) |
