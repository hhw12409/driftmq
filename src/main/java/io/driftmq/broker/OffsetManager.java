package io.driftmq.broker;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 브로커 전역 consumer 소비 위치 관리. topic 별 {@code offsets} 파일에 영속화한다.
 * 저장 설계 §6: {@code consumerId\tcommittedPosition\n} 반복, 전진 시마다 전체 rewrite
 * (tmp write → force → ATOMIC_MOVE).
 */
public final class OffsetManager {

    private final Path dataDir;
    // topic -> (consumerId -> state)
    private final Map<String, Map<String, ConsumerState>> byTopic = new ConcurrentHashMap<>();

    public OffsetManager(Path dataDir) {
        this.dataDir = dataDir;
    }

    /**
     * topic 을 등록하고 {@code offsets} 파일에서 소비 위치를 로드한다.
     * 파싱 실패한 줄부터는 무시(부분 쓰기 가능). committedPosition 은 {@code endOffset} 으로 클램프.
     */
    public void attachTopic(String topic, long endOffset) throws StorageException {
        Map<String, ConsumerState> states = byTopic.computeIfAbsent(topic, t -> new ConcurrentHashMap<>());
        Path file = offsetsFile(topic);
        if (!Files.exists(file)) return;
        try {
            for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
                if (line.isBlank()) continue;
                int tab = line.lastIndexOf('\t');
                if (tab <= 0) break; // 손상된 줄 — 이후 전부 무시
                String consumerId = line.substring(0, tab);
                long pos;
                try {
                    pos = Long.parseLong(line.substring(tab + 1).trim());
                } catch (NumberFormatException e) {
                    break;
                }
                if (pos < 0) break;
                ConsumerState st = new ConsumerState(consumerId, pos, () -> persistQuietly(topic));
                st.clampTo(endOffset);
                states.put(consumerId, st);
            }
        } catch (IOException e) {
            throw new StorageException("failed to load offsets for topic " + topic, e);
        }
    }

    /** {@code (topic, consumerId)} 상태를 반환. 없으면 committedPosition=0 으로 생성. */
    public ConsumerState state(String topic, String consumerId) {
        Map<String, ConsumerState> states = byTopic.computeIfAbsent(topic, t -> new ConcurrentHashMap<>());
        return states.computeIfAbsent(consumerId,
                cid -> new ConsumerState(cid, 0, () -> persistQuietly(topic)));
    }

    /** 해당 topic 에 알려진 모든 consumer 상태 (describe / 스캐너용). */
    public List<ConsumerState> statesOf(String topic) {
        Map<String, ConsumerState> states = byTopic.get(topic);
        return states == null ? List.of() : new ArrayList<>(states.values());
    }

    /** 모든 topic 의 모든 consumer 상태 (ack-timeout 스캐너용). */
    public List<ConsumerState> allStates() {
        List<ConsumerState> out = new ArrayList<>();
        for (Map<String, ConsumerState> m : byTopic.values()) out.addAll(m.values());
        return out;
    }

    Iterable<String> topics() {
        return byTopic.keySet();
    }

    /** topic 의 offsets 파일을 원자적으로 재작성한다. */
    public synchronized void persist(String topic) throws StorageException {
        Map<String, ConsumerState> states = byTopic.get(topic);
        if (states == null) return;
        Path file = offsetsFile(topic);
        Path tmp = file.resolveSibling("offsets.tmp");
        StringBuilder sb = new StringBuilder();
        for (ConsumerState st : states.values()) {
            sb.append(st.consumerId()).append('\t').append(st.committedForPersist()).append('\n');
        }
        try {
            Files.createDirectories(file.getParent());
            Files.write(tmp, sb.toString().getBytes(StandardCharsets.UTF_8));
            // 디렉토리 rename 은 대부분 파일시스템에서 원자적. 데이터는 위 write 로 페이지 캐시에 있음.
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            throw new StorageException("failed to persist offsets for topic " + topic, e);
        }
    }

    private void persistQuietly(String topic) {
        try {
            persist(topic);
        } catch (StorageException e) {
            throw new UncheckedIOException(new IOException(e));
        }
    }

    private Path offsetsFile(String topic) {
        return dataDir.resolve(topic).resolve("offsets");
    }

    /** 테스트용: 파일에 실제로 기록된 위치를 읽어온다. */
    public Map<String, Long> persistedPositions(String topic) throws StorageException {
        Map<String, Long> out = new java.util.LinkedHashMap<>();
        Path file = offsetsFile(topic);
        if (!Files.exists(file)) return out;
        try {
            for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
                if (line.isBlank()) continue;
                int tab = line.lastIndexOf('\t');
                if (tab <= 0) break;
                out.put(line.substring(0, tab), Long.parseLong(line.substring(tab + 1).trim()));
            }
        } catch (IOException e) {
            throw new StorageException("read offsets", e);
        }
        return out;
    }
}
