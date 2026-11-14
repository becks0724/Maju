<!-- :watch 모듈 작업 중 내린 결정과 근거, 실기기에서 닫을 항목 — 다음 세션이 먼저 읽는다 -->
# :watch 컨텍스트 노트 (app-wear)

## 결정 (2026-10-30)

- **minSdk 30 · targetSdk 36 · compileSdk 37.** 30 = Wear OS 3, Sensor SDK 최저 기종 Galaxy Watch4 의 첫 Wear OS 판이자 Health Services 최저 판. target 36 은 Wear OS 6 권한 체계(READ_HEART_RATE · READ_HEALTH_DATA_IN_BACKGROUND · 삼성 READ_ADDITIONAL_HEALTH_DATA)까지 문서로 확인한 판. Watch9(Wear OS 7)에서도 36 대상 앱은 돈다 [추정].
- **applicationId `app.maju`**(2026-11-09 사용자 결정 — 접수양식의 「계정정보 노출 금지」 때문에 개인 GitHub 계정이 든 앞 이름에서 바꿈. 2026-11-08 결정, 이전 `com.example.guardian` · namespace 는 그대로 `com.example.guardian.watch`) — Data Layer 는 휴대폰과 패키지명 · 서명이 같아야 한다.
- **Sensor SDK 경로 분리.** `watch/libs/samsung-health-sensor-api.aar` 이 있으면 `src/sensorSdk/kotlin`, 없으면 `src/noSensorSdk/kotlin`(같은 이름 `SensorSdkProvider`, 늘 null)을 넣는다. AAR 은 `.gitignore`. `SamsungSensorSource` 는 2026-11-07 AAR v1.4.1 로 컴파일 확인했고, 실기기 동작은 [미검증]이다. 감시는 `SensorSdkProvider.MONITOR_READY = false` 로 Health Services 에 묶어 두었다 — 풀기 전에 이 파일의 `getValue` 를 필드별로 감싸야 한다(키가 없으면 NPE, javap 확인).
- **관문 모드 DRIVING 고정**(`GateRunner.GATE_DRIVING`, PM 결정). 근거는 코드 주석.
- **Wear Compose 1.5.6** — composeBom 2025.06.00(compose 1.8)과 같은 compose 1.8.2 를 쓰는 안정판. 1.7.x 는 compose 1.10 을 끌어온다.
- **Health Services 해제** — `unregisterMeasureCallbackAsync` 는 ListenableFuture(runtime 범위 guava)가 컴파일 경로에 필요해, 같은 라이브러리의 suspend 판을 표준 라이브러리 `startCoroutine` 으로 부른다(새 의존성 없음).
- **손목 응답은 기본 끔**(`WristResponseDetector.WRIST_RESPONSE_ENABLED = false`, PM 확인 요청). 저역 필터 + 1초 안 40도 회전 정의는 진동 · 노면 충격 · 느린 기울어짐을 거르지만 조향(0.6초 60도)과 구별하지 못한다(시험으로 기록).
- **경보는 ALERT_STOP 까지** — 응답해도 로컬로 끄지 않고 즉시 sync 로 응답을 보낸다. 화면은 「응답 보냄 · 확인 중」, 멈춤이 오면 응답 확인됨(W08) 5초.
- **워치 직접 경로 동의** — 계약 §8 「건강 관련 동의가 없으면 워치 직접 경로를 쓰지 않는다」 때문에 워치 첫 화면에 별도 동의를 둔다(spec 에 없는 화면).
- **짝 끝내기** — 계약 §3.2(main 209e84a)대로 워치 토큰 `pair/end` 는 「동의 철회」 때만 부른다. 「끄기」는 서비스만 멈추고 짝을 유지한다. 그래서 W11 의 「연결 끊기」 버튼을 뺐다.
- **Data Layer 상수** — `:shared` `DataLayer`(PATH_WATCH · CAPABILITY_PHONE)를 쓴다. `wear.xml` 의 capability 는 `DataLayerBindingTest` 가 `CAPABILITY_WATCH` 와 묶는다.
- **켜기** — spec W04' 는 「휴대폰에서 켜세요」지만 휴대폰 → 워치 켜기 지시가 계약에 없어 워치 가장자리 버튼 「켜기」로 둔다.

- **보내는 시각은 한 번만 벽시계로** — `responseAt` 은 응답 순간에 벽시계 값을 잡아 그대로 싣고, `lastPulseAt` 은 새 박동(단조 값이 바뀔 때)에만 벽시계로 바꾼다. sync 마다 `toWall` 로 다시 바꾸면 ±1ms · NTP 보정만큼 흔들려 받는 쪽이 새 응답으로 읽는다(app-qa 1차 불합격 사유).
- **API 36+ 백그라운드 건강 권한** — 앞의 건강 권한이 허용된 뒤 감시를 켜면서 따로 묻는다. 거절해도 감시는 켠다 [미검증 — 포그라운드 서비스를 화면에서 시작하면 측정이 이어지는지 실기기 확인].
- **TEST_SCENARIO_STOP**(main 7cc7015 `:shared` 규칙) — `WatchAction.StopScenario` → `WatchEngine.stopScenario`, 화면 「멈추기」와 같은 함수. 시나리오가 없으면 그대로 두고 ack 만. 같은 id 가 다시 와도 실행 기억(seen)으로 새 시나리오를 끄지 않는다.
- **건너뛴 지시 ack** — `WatchSyncResponse.skippedCommandIds` 를 `CommandProcessor.ackOnly` 로 다음 sync acks 에 싣는다(계약 §4.6 「건너뛰고 ack」). ack 순서는 뜻이 없다.
- **철회 `pair/end` 재시도(보안 감사 11-08 하2)** — 철회 때 토큰을 짝 자리에서 지우며 `endPendingTokens`(토큰 · 저장 벽시계 시각만)로 옮기고 바로 부른다. 실패하면 앱 실행(`MainActivity.onCreate`) · 감시 시작(`MonitorService.begin`) · 네트워크 복구(`PairEndJob`, JobScheduler `NETWORK_TYPE_ANY` · 최소 30초 뒤 · 재부팅 유지) 때 다시 부른다. 성공 · 401 이면 지우고, 저장 24시간(짝 만료)이면 부르지 않고 지운다. 재부팅을 넘어 재야 해서 24시간은 벽시계로 잰다. 로그는 HTTP 상태 코드만. WorkManager 는 새 의존성이라 쓰지 않는다.
- **HS 세션 구분(코드 리뷰 11-08 관찰 ① · ②)** — 운동 세션 「끝남」은 시작 시도 번호에 묶인 리스너로만 받는다. `AndroidHsClient` 는 이미 거둔 콜백의 업데이트와, 시작 요청보다 2초 넘게 앞서 시작한 세션(남은 우리 세션)의 업데이트를 버리고 자기 콜백만 거둔다. 새 콜백에 끝난 앞 세션의 마지막 업데이트가 실제로 오는지는 [미검증]이다. 콜백 필드는 여러 스레드가 쓰므로 `AtomicReference` 로 둔다.
- **직접 경로 확인(계약 §4.7)** — 워치 지시는 직접 경로로만 받는다. `MonitorService` 는 감시 중 2초마다 `/v1/watch/sync` 를 돌리고 Data Layer(`PhoneLink`)는 상태를 보내기만 한다. Data Layer 만 쓰는 설정은 없다. 직접 경로가 꺼지는 경우는 중계 주소 없음(release 빌드 · `guardian.relayBase` 빈 값) · 토큰 없음 · 건강 관련 동의 없음(계약 §8) 셋뿐이다.

## 권한 보호 수준 (app-qa 확인 2026-10-30 · 매니페스트 대조 2026-11-08)

| 수준 | 권한 |
|---|---|
| normal(설치 때 자동) | INTERNET · ACCESS_NETWORK_STATE · CHANGE_NETWORK_STATE · FOREGROUND_SERVICE · FOREGROUND_SERVICE_HEALTH · WAKE_LOCK · RECEIVE_BOOT_COMPLETED(재부팅 뒤 감시 다시 켜기 · ResumeReceiver) · VIBRATE · USE_FULL_SCREEN_INTENT |
| dangerous(런타임 요청) | POST_NOTIFICATIONS · BODY_SENSORS(maxSdk 35) · health.READ_HEART_RATE · health.READ_HEALTH_DATA_IN_BACKGROUND · ACTIVITY_RECOGNITION |
| 삼성 전용 | com.samsung.android.hardware.sensormanager.permission.READ_ADDITIONAL_HEALTH_DATA — dangerous [추정], AAR 이 있을 때 API 36+ 에서만 요청. 2026-11-14 부터 debug 매니페스트에만 선언(C-8) |
| debug 전용(`src/debug/AndroidManifest.xml`, release 에 없음) | dangerous — ACCESS_FINE_LOCATION · ACCESS_COARSE_LOCATION, normal — FOREGROUND_SERVICE_LOCATION. GPS 측정 화면(GpsProbeActivity) · 생체 기록 서비스(BioProbeService, health\|location 유형)용 |

## 실기기에서 닫을 것

- [ ] `getSupportHealthTrackerTypes()` 첫 실행 기록(PairStore `sensorSdkCapabilities`, logcat 태그 `GuardianWatch`)
- [x] AAR 넣고 `SamsungSensorSource` 컴파일 · ValueKey 이름 대조(2026-11-07)
- [ ] 가속도 환산식 · IBI 상태값 대조(「생체 기록」 원자료로)
- [ ] Health Services MeasureClient 가 포그라운드 서비스 · 화면 꺼짐에서 계속 오는지
- [ ] 경보 화면 띄우기(백그라운드 시작 제한 · 전체 화면 인텐트) · 손바닥 덮기 · 경보 진동 패턴
- [ ] 감시 중 Wi-Fi 요청 · wake lock 배터리 영향, 휴대폰 경유로 Mac LAN 중계에 닿는지
- [ ] 조향 · 경보 진동 중 손목 동작 실측 → 손목 응답 정의 재검토

## 야간 1차(2026-11-14, 합동 점검 결정 반영)

- **C-8 빌드 유형별 Sensor SDK** — 공급자 소스 묶음(`src/sensorSdk` · `src/noSensorSdk`)과 AAR 의존성을 main 이 아니라 debug · release 소스 묶음에 붙인다. debug 는 파일이 있으면 넣는다. release 는 처음에 `-Pguardian.release.sensorSdk=true` 일 때만 넣게 했다가, 2차에서 그 속성 경로를 없애 늘 넣지 않는다(아래 2차). product flavor 는 작업 이름이 바뀌어 쓰지 않았다. 추가 건강 데이터 권한은 debug 매니페스트로 옮겼다 — release 에 SDK 를 켜는 날 함께 되돌린다. 확인(파일 임시 복사 뒤 지움) — release dex 의 `samsung/android/service/health` 0, debug 207, 속성을 준 release 90. 신체 활동 권한은 기기 확인 전이라 그대로.
- **B-1 어댑터 유예 3초** — AVAILABLE 에서 측정 중 · 쓸 수 없음으로 떠난 단조 시각부터 3초 이하는 마지막 유효 심박을 atMs=지금으로 보류, 넘으면 −8. 둘 사이를 오가도 다시 재지 않는다(유예가 늘어나지 않게). 미착용은 바로 −3. 관문은 「OK 아님」을 즉시 반영하고 자체 유지가 없어 겹치지 않는다(be-dev 확인). 보류 표본의 `HrSample.held = true` 와 HS 경로의 `GateParams(bradyExitBpm = 45)` 는 be-dev 관문 통합 뒤 2차.
- **B-3** — 간격 표시값 `coerceAtLeast(250)` 만. 위쪽을 묶으면 30회/분 미만의 박동 공백 판정이 4초 → 10초로 늦어진다(be-qa 2차 실행).
- **D-1 보낸 쪽 검사** — 경보 시작 · 정지는 CAR · PHONE, 테스트 시나리오 · 멈춤은 PHONE 만 실행, 그 밖은 ack 만(`CommandProcessor`).
- **C-1** — 「멈추기」 뒤 바로 `requestSync()` 와 tick(Data Layer 보내기). 휴대폰 규칙(test=false 10초면 세션 끝, 시작 뒤 15초 안 test=true 없음이면 끝)은 app-phone 과 맞췄다.
- **C-3 응답 뒤 출력 멈춤** — 경보 중 응답 뒤 10초 동안 증거(응답 뒤에 보낸 중계 sync 성공 · Data Layer 보내기 성공)가 없으면 진동 · 소리 · 최대 음량 · 최대 밝기만 멈춘다. 판단 · 멈춤 · 새 지시의 되돌림은 서비스 `life` 잠금 안에서 해, 막 도착한 새 경보 출력을 끄지 않는다. Data Layer 성공은 노드 큐에 들어갔다는 뜻일 뿐이고(app-qa 2차), 「증거는 있는데 주인 없음」은 C-2 몫이다.

## 야간 2차(2026-11-14)

- **release 는 늘 Sensor SDK 없음(codex 재검증 1차 지적 · PM 결정)** — `-Pguardian.release.sensorSdk=true` 경로를 없앴다. 속성 하나로 SDK 가 release 에 들어가는데 추가 건강 데이터 권한은 debug 에만 있어 구성이 맞지 않았고, 재배포할 수 없는 SDK 가 출품판에 들어갈 길이 남았다. release 에서 SDK 를 켜는 일은 `MONITOR_READY` 잠금 해제(사용자 결정)와 함께 한 번에 바꾼다 — 그때 release 소스 묶음 · 의존성 · `SENSOR_SDK` 값과 추가 건강 데이터 권한(main 매니페스트로)을 같이 옮긴다.
- **B-1 2차** — 보류 표본(묶음 사이 · 유예)에 `held = true`. 실제 출처 관문은 `WatchEngine.realGate()` 가 만들고 Health Services 면 `GateParams(bradyExitBpm = 45)`, Sensor SDK · 시뮬레이션은 기본값. be-qa 열을 어댑터 + 엔진 시험(`SlowPulseHsPathTest`)으로 고정했다.
- **계약 한 판 준비(C-2 · B-5)** — ALERT_START · STOP payload 의 선택 `eventId`(문자열)를 원 map 에서 읽는다(공용 `Payload` 에 필드가 생기면 그쪽으로). 규칙은 `WatchEngine.handle` — 정지한 사건 id 최근 16개 기억 · 그 시작은 단계와 무관하게 버림 · 같은 사건 · 단계 · 강도가 울리는 중이면 다시 걸지 않음 · 사건 id 있는 정지는 같은 사건만 듣되 울리는 경보가 사건 id 없는 옛 지시면 끔(비교할 id 가 없어 끝없이 울리는 것보다 낫다). 응답 번호는 `PairStore.nextResponseSeq()`(commit) → `WatchEngine.responseSeq`, 감시 시작 때 저장값을 넣는다. `WatchEngine.alerting` 이 `{eventId, stage, level}` 을 쥔다. 값(`Watch`)에 싣는 한 줄은 be-dev 가 공용 필드를 올린 뒤.
