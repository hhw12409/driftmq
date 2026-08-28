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

**경량 단일 노드 메시지 브로커.** 순수 Java 21, 런타임 의존성 0.
append-only 로그에 쓰고, 크래시에서 복구하고, at-least-once 로 전달한다.

> *Simple first, distributed later* — 단일 노드에서 이해하기 쉬운 브로커를 먼저 끝내고
> Consumer Group · Partition · Replication 을 단계적으로 얹는다. 이 저장소는 **v0.1 (Core Broker)** 이다.

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

## ✦ 보장하는 것

| | |
|---|---|
| 🪶 **의존성 0** | 라이브러리 JAR 하나. Netty·DB·로깅 프레임워크 없음 |
| 💾 **손실 창 0** | publish 마다 fsync — 응답을 받았으면 디스크에 있다. `kill -9` 뒤 부분 프레임은 CRC 로 잘라낸다 |
| 🔁 **at-least-once** | ACK 없으면 재전달. `offset` 을 키로 멱등하게 처리하면 된다 |
| 🧵 **가상 스레드** | 연결 1개 = 스레드 1개. 비동기 콜백 없음 |

**하지 않는 것:** exactly-once · Kafka 급 처리량 · Kafka 프로토콜 호환 · Schema Registry · replication · ACL.

---

## ✦ 설치

**라이브러리** — Maven Central ([artifact page](https://central.sonatype.com/artifact/io.github.hhw12409/driftmq))

```kotlin
// build.gradle.kts
implementation("io.github.hhw12409:driftmq:0.1.0")
```
```xml
<dependency>
  <groupId>io.github.hhw12409</groupId>
  <artifactId>driftmq</artifactId>
  <version>0.1.0</version>
</dependency>
```

**브로커 실행 파일** — [GitHub Releases](https://github.com/hhw12409/driftmq/releases)

```bash
curl -LO https://github.com/hhw12409/driftmq/releases/latest/download/driftmq.jar
java -jar driftmq.jar start --data-dir ./data --port 7644
```

---

## ✦ 빠른 시작

필요한 것은 **JDK 21** 뿐이다.

```bash
# 빌드·테스트 — 둘 중 아무 경로나
./gradlew build      # 또는  ./build.sh   (Gradle 없이 순수 JDK)
./gradlew miniTest   # 또는  ./test.sh    (단위 + MVP 시나리오, 59개)

# 브로커 실행
./driftmq start --data-dir ./data --port 7644

# 다른 터미널에서
./driftmq topic create orders
java -cp driftmq.jar io.driftmq.example.Demo   # 10개 publish → consume → ack
./driftmq topic describe orders                # 메시지 수 · 다음 offset · consumer 위치
```

브로커를 껐다 켜도 메시지와 각 consumer 의 위치가 복구되고, ACK 되지 않은 메시지는 재전달된다.

---

## ✦ Java 클라이언트

```java
import io.driftmq.client.*;

try (DriftClient client = DriftClient.connect("localhost", 7644)) {

    Producer producer = client.newProducer();
    for (int i = 0; i < 100; i++) {
        long offset = producer.publish("orders", "order-" + i).offset();  // 반환 = fsync 완료
    }

    Consumer consumer = client.newConsumer("orders", "billing-service");
    consumer.run(record -> {
        System.out.printf("#%d  %s%n", record.offset(), record.payloadAsString());
        // 여기서 예외를 던지면 이 메시지는 ACK 되지 않고 나중에 다시 온다
    });
}
```

---

## ✦ CLI

| 명령 | 하는 일 |
|------|---------|
| `driftmq start [--data-dir DIR] [--port N] [--ack-timeout SEC]` | 브로커 실행 (포그라운드, Ctrl-C = graceful shutdown) |
| `driftmq topic create <name>` | 토픽 생성 |
| `driftmq topic list [--json]` | 토픽 + 메시지 수 |
| `driftmq topic describe <name> [--json]` | 메시지 수 · 다음 offset · consumer 별 위치 |

원격 브로커는 `--addr host:port`. exit code: `0` 성공 · `1` 사용자 오류 · `2` 연결 실패.

---

## ✦ 동작 원리

```
publish  →  레코드 직렬화 → 파일 끝에 write → fsync → 인메모리 인덱스(offset→위치) → PUBLISH_OK
consume  →  deliveredCursor 부터 N개 예약(in-flight) → 로그 read (CRC 검증) → FETCH_OK
ack      →  in-flight 제거 → 연속 구간만큼 committedPosition 전진 → offsets 파일 원자적 rewrite
recover  →  로그를 처음부터 스캔 → CRC 불일치/길이 부족 지점에서 truncate → 인덱스 재구축
```

- **저장 레코드** `[recordLen][crc32c][timestamp][headers][payload]` — offset 은 파일 내 순서
- **재전달 트리거** ack-timeout(기본 30초) · consumer 연결 종료 · 브로커 재시작
- **동시성** topic 로그는 단일 writer 직렬화, 소비 위치는 상태 객체 단위 `synchronized`
- **wire 프로토콜** TCP 길이-prefix 바이너리, 정수는 전부 big-endian — `io.driftmq.protocol.Codec` (broker·client 공유)

---

## ✦ 벤치마크 <sub>Apple M1 Air · JDK 21 · APFS SSD</sub>

```bash
./bench.sh   # 결과는 bench/results/ 에 환경 정보와 함께 저장
```

| | `fsync=always` (기본) | `fsync=none` (참고 상한) |
|---|---|---|
| 처리량 (1 KB) | ~245 msgs/s | ~29,000 msgs/s |
| publish 지연 p50 / p99 | 3.99 / 5.54 ms | 0.03 / 0.05 ms |
| 크래시 복구 (1만 레코드) | ~46 ms | — |

`always` 는 publish 1건 ≈ 1 fsync ≈ 4 ms 로 IOPS 에 묶여 메시지 크기와 무관하게 평탄하다.
v0.1 은 손실 창 0 을 택해 `always` 고정 — 배치 fsync 는 v0.2.

---

## ✦ 구조

```
src/main/java/io/driftmq/
├── protocol/   Codec, WireMessage, 프레이밍       (broker·client 공유)
├── common/     Header, Topics
├── broker/     MessageStore · TopicManager · OffsetManager/ConsumerState
│               · AckManager · Server · ConnectionHandler · Broker
├── client/     DriftClient · Producer · Consumer
├── cli/        Main
└── example/    Demo · bench/ Bench
```

`build.gradle.kts` 는 배포·의존성용, `build.sh`/`test.sh` 는 JDK 만으로 도는 최소 경로 — 둘 다 유지된다.

---

## ✦ 로드맵

```
▸ v0.1  Core Broker      ← 현재   Broker · Topic · Publish · Consume · Persistence + 기본 ACK/Retry
  v0.2  Delivery                per-message 재시도·백오프, DLQ, 배치 fsync
  v0.3  Consumer Groups         파티션 분배, rebalancing 기초
  v0.4  Partitioning            multi-partition topic, 병렬 소비
  v0.5  Storage                 세그먼트 롤링, retention
  v0.6  Reliability             replication, leader/follower
```

**v0.1 의 의도된 한계** — topic = 단일 파티션·단일 세그먼트 · fsync 는 `always` 만 ·
재전달은 bulk 되감기 (per-message 백오프·DLQ 없음) · in-flight 비영속 (재시작 시 미커밋 전량 재전달) ·
offset 이 int 인덱스 (topic 당 2³¹ 상한).

---

<sub>Apache-2.0 · [central.sonatype.com/artifact/io.github.hhw12409/driftmq](https://central.sonatype.com/artifact/io.github.hhw12409/driftmq)</sub>
