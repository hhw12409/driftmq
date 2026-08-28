```
     _      _  __ _   __  __  ___
  __| |_ __(_)/ _| |_|  \/  |/ _ \
 / _` | '__| | |_| __| |\/| | | | |
| (_| | |  | |  _| |_| |  | | |_| |
 \__,_|_|  |_|_|  \__|_|  |_|\__\_\
  ~ ~ ~   messages drift through, then stick to disk   ~ ~ ~
```

# DriftMQ

[![Maven Central](https://img.shields.io/maven-central/v/io.github.hhw12409/driftmq?label=Maven%20Central)](https://central.sonatype.com/artifact/io.github.hhw12409/driftmq)
[![CI](https://github.com/hhw12409/driftmq/actions/workflows/ci.yml/badge.svg)](https://github.com/hhw12409/driftmq/actions/workflows/ci.yml)
[![License](https://img.shields.io/badge/license-Apache--2.0-blue.svg)](LICENSE)
[![JDK](https://img.shields.io/badge/JDK-21-orange.svg)](https://adoptium.net/)

**경량 단일 노드 메시지 브로커.** 순수 Java 21 — 런타임 의존성 0.
append-only 로그에 저장하고, 크래시에서 복구하고, at-least-once 로 전달한다.

> **Simple first, distributed later.** 처음부터 Kafka 를 만들지 않는다.
> 단일 노드에서 *이해하기 쉽고 실행하기 쉬운* 브로커를 먼저 완성하고,
> Consumer Group · Partition · Replication 을 단계적으로 붙인다.

이 저장소는 로드맵의 **v0.1 (Core Broker)** 을 구현한다.

```
   ┌─────────────────────────── DriftMQ ───────────────────────────┐
   │                                                               │
   │   producer  ──PUBLISH──▶  ≈≈≈≈≈▶  [ append-only log + fsync ]  │
   │                                          │                    │
   │                                          ▼   (offset 0,1,2…)   │
   │   consumer  ◀──FETCH────  ≈≈≈≈≈◀  [ per-consumer position ]    │
   │        └────────ACK────────────────────▶ (advance / redeliver) │
   │                                                               │
   └───────────────────────────────────────────────────────────────┘
             재시작해도 메시지와 소비 위치가 그대로 살아있다
```

---

## ✦ 특징

| | |
|---|---|
| 🪶 **가볍다** | 라이브러리 JAR 하나. **런타임 의존성 0** — Netty·DB·로깅 프레임워크 없음 |
| 💾 **안 잃는다** | publish 마다 fsync — 응답을 받았으면 디스크에 확정된 것. `kill -9` 후 부분 프레임은 CRC 로 감지·truncate |
| 🔁 **at-least-once** | ACK 안 하면 재전달. consumer 는 멱등하게 (`offset` = 중복 감지 키) |
| 🧵 **단순한 서버** | 연결 1개 = 가상 스레드 1개. 비동기 콜백 지옥 없음 |
| 📈 **재현 가능한 벤치** | `./bench.sh` — always vs no-fsync 비교까지 |

## ✦ Non-goals (기대 설정)

DriftMQ 는 다음을 **하지 않는다**:

- Kafka 급 초대규모 처리량 — v0.1 은 안전(publish 마다 fsync) 우선, 처리량 후순위
- **exactly-once** — delivery 는 at-least-once. 같은 메시지가 두 번 올 수 있다
- Kafka 프로토콜 호환 · Schema Registry · multi-region replication · 복잡한 ACL

---

## ✦ 설치

### 라이브러리 (클라이언트/임베디드 브로커) — Maven Central

**Gradle** (`build.gradle.kts`)
```kotlin
dependencies {
    implementation("io.github.hhw12409:driftmq:0.1.0")
}
```

**Maven** (`pom.xml`)
```xml
<dependency>
  <groupId>io.github.hhw12409</groupId>
  <artifactId>driftmq</artifactId>
  <version>0.1.0</version>
</dependency>
```

전이 의존성이 없다 — 이 JAR 하나가 전부다.

### 브로커 실행 파일 — GitHub Releases

```bash
curl -LO https://github.com/hhw12409/driftmq/releases/latest/download/driftmq-0.1.0.jar
java -jar driftmq-0.1.0.jar start --data-dir ./data --port 7644
```

의존성이 없으므로 이 JAR 자체가 실행 가능한 fat JAR 이다.

---

## ✦ 빠른 시작

### 0. 요구사항

**JDK 21** (`java -version` 이 21 이상). 그 외 아무것도 필요 없다.

### 1. 빌드 & 테스트

```bash
./gradlew build          # 컴파일 + 테스트 + driftmq JAR
# 또는 Gradle 없이 순수 JDK 로:
./build.sh               # javac → driftmq.jar
./test.sh                # 단위 + MVP 인수 시나리오 — 59개
```

두 경로 모두 유지된다. `./gradlew` 는 배포·의존성 관리용, `build.sh` 는 "JDK 만 있으면 된다"는
원칙을 지키는 최소 경로다.

### 2. 브로커 실행

```bash
./driftmq start --data-dir ./data --port 7644
# DriftMQ v0.1 listening on :7644  (data-dir=…, ack-timeout=30s, fsync=always)
# press Ctrl-C to stop
```

### 3. 토픽 생성 (다른 터미널)

```bash
./driftmq topic create orders --addr localhost:7644
# created topic 'orders'
```

### 4. 30초 데모

```bash
java -cp driftmq.jar io.driftmq.example.Demo localhost 7644
# published offset=0 … published offset=9
# consumed #0: hello-0 … consumed #9: hello-9
# done — consumed 10 messages
```

### 5. 상태 확인 · 재시작

```bash
./driftmq topic describe orders --addr localhost:7644
#   topic:        orders
#   messages:     10
#   end offset:   10
#   consumers:    1
#     - demo-consumer            position=10

# 브로커를 Ctrl-C 로 끄고 다시 start 하면 —
# 저장된 메시지와 각 consumer 의 소비 위치가 복구되고,
# ACK 되지 않았던 메시지는 재전달된다.
```

---

## ✦ Java 클라이언트

```java
import io.driftmq.client.*;

try (DriftClient client = DriftClient.connect("localhost", 7644)) {

    // ── 발행: 응답을 받으면 디스크 fsync 까지 끝난 것
    Producer producer = client.newProducer();
    for (int i = 0; i < 100; i++) {
        PublishResult r = producer.publish("orders", "order-" + i);
        System.out.println("stored at offset " + r.offset());
    }

    // ── 소비: offset 순서대로, 처리 성공 시 ACK, 실패(예외) 시 나중에 재전달
    Consumer consumer = client.newConsumer("orders", "billing-service");
    consumer.run(record -> {
        System.out.printf("#%d  %s%n", record.offset(), record.payloadAsString());
        // 여기서 예외를 던지면 이 메시지는 ACK 되지 않는다 (at-least-once)
    });
}
```

---

## ✦ CLI 치트시트

| 명령 | 설명 |
|------|------|
| `driftmq start [--data-dir DIR] [--port N] [--ack-timeout SEC]` | 브로커 실행 (포그라운드, Ctrl-C 로 graceful shutdown) |
| `driftmq topic create <name> [--addr host:port]` | 토픽 생성 |
| `driftmq topic list [--addr] [--json]` | 토픽 + 메시지 수 |
| `driftmq topic describe <name> [--addr] [--json]` | 메시지 수 · 다음 offset · consumer 별 위치 |

**exit code:** `0` 성공 · `1` 사용자 오류 · `2` 브로커 연결 실패

---

## ✦ 동작 원리 (요약)

```
publish  →  레코드 직렬화 → 파일 끝에 write → fsync → 인메모리 인덱스(offset→위치) → PUBLISH_OK
consume  →  consumer 의 deliveredCursor 부터 N개 예약(in-flight 등록) → 로그 read(CRC 검증) → FETCH_OK
ack      →  in-flight 제거 → 연속 ACK 구간만큼 committedPosition 전진 → offsets 파일 원자적 rewrite
recover  →  로그를 처음부터 스캔, CRC 불일치/길이 부족 지점에서 truncate → 인덱스 재구축
```

- **저장 레코드**: `[recordLen][crc32c][timestamp][headers][payload]` — offset 은 파일 내 순서
- **재전달 트리거**: ack-timeout(기본 30초) 초과 · consumer 연결 종료 · 브로커 재시작
- **동시성**: topic 당 로그는 단일 writer 직렬화, 소비 위치는 상태 객체 단위 `synchronized`
- **wire 프로토콜**: TCP 길이-prefix 바이너리, 모든 정수 big-endian. 구현: `io.driftmq.protocol.Codec` (broker·client 공유 단일 소스)

---

## ✦ 벤치마크 (Apple M1 Air · JDK 21 · APFS SSD)

```bash
./bench.sh     # 결과는 bench/results/ 에 환경 정보와 함께 저장
```

| 메시지 크기 | `fsync=always` (기본) | `fsync=none` (참고 상한) |
|-------------|----------------------|-------------------------|
| 100 B | ~250 msgs/s | ~27,000 msgs/s |
| 1 KB  | ~245 msgs/s | ~29,000 msgs/s |
| 10 KB | ~250 msgs/s (2.5 MB/s) | ~21,700 msgs/s (212 MB/s) |

| 지연 (ms) | p50 | p99 |
|-----------|-----|-----|
| publish (always, fsync 포함) | 3.99 | 5.54 |
| publish (none) | 0.03 | 0.05 |

| 크래시 복구 | 시간 |
|-------------|------|
| 로그 1만 레코드 스캔 + CRC + truncate | ~46 ms |

**해석:** `always` 는 publish 1건 ≈ 1 fsync ≈ 4ms 로 IOPS 에 바운드 → 메시지 크기와 무관하게
~250 msgs/s 로 평탄. fsync 를 빼면 100배 — 이것이 배치 fsync(v0.2)가 노릴 상한.
v0.1 은 "이해하기 쉽고 안전"(손실 창 0)을 택해 `always` 고정.

---

## ✦ 프로젝트 구조

```
src/main/java/io/driftmq/
├── protocol/   Codec (broker·client 공유 단일 소스), WireMessage, 프레이밍
├── common/     Header, Topics
├── broker/     MessageStore, TopicManager, OffsetManager/ConsumerState,
│               AckManager, Server, ConnectionHandler, Broker
├── client/     DriftClient, Producer, Consumer
├── cli/        Main
├── bench/      Bench
└── example/    Demo
src/test/java/  59개 테스트 (단위 + MVP 시나리오 + 정확성)

build.gradle.kts · gradle.properties   배포/의존성 (Maven Central)
build.sh · test.sh · driftmq           순수 JDK 경로 (Gradle 불필요)
RELEASING.md                           릴리스 절차
```

---

## ✦ 로드맵

```
▸ v0.1  Core Broker        ← 현재  Broker · Topic · Publish · Consume · Persistence + 기본 ACK/Retry
  v0.2  Delivery           per-message 재시도·백오프, DLQ, 배치 fsync
  v0.3  Consumer Groups    파티션 나눠 처리, rebalancing 기초
  v0.4  Partitioning       multi-partition topic, 병렬 소비
  v0.5  Storage            세그먼트 롤링, retention
  v0.6  Reliability        replication, leader/follower
```

### v0.1 의 의도된 한계

topic = 단일 파티션·단일 세그먼트 · fsync 는 `always` 만 (`batch:N` 은 v0.2) ·
재전달은 bulk 되감기 (per-message 백오프·DLQ 없음) · in-flight 비영속 (재시작 시 미커밋 전량 재전달) ·
offset 이 int 인덱스 (topic 당 2³¹ 상한 — 세그먼트 도입 시 해소)
