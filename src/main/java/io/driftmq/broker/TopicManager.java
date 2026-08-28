package io.driftmq.broker;

import io.driftmq.common.Topics;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Stream;

/**
 * Topic 생성/조회/목록 + metadata. 각 topic 은 하나의 {@link MessageStore} 를 소유한다.
 * 시작 시 {@code data-dir} 하위의 기존 topic 디렉토리를 스캔하여 복구·로드한다.
 */
public final class TopicManager implements AutoCloseable {

    /** topic 이름 + 그 로그. */
    public record TopicHandle(String name, MessageStore store) {}

    private final Path dataDir;
    private final OffsetManager offsets;
    private final Map<String, TopicHandle> topics = new ConcurrentHashMap<>();

    public TopicManager(Path dataDir, OffsetManager offsets) {
        this.dataDir = dataDir;
        this.offsets = offsets;
    }

    /** 기존 topic 디렉토리를 스캔하여 복구·등록한다. */
    public void init() throws StorageException {
        try {
            Files.createDirectories(dataDir);
            if (!Files.isDirectory(dataDir)) return;
            try (Stream<Path> entries = Files.list(dataDir)) {
                List<Path> dirs = entries.filter(Files::isDirectory)
                        .filter(p -> Topics.isValid(p.getFileName().toString()))
                        .sorted()
                        .toList();
                for (Path dir : dirs) {
                    String name = dir.getFileName().toString();
                    MessageStore store = MessageStore.openOrRecover(dir);
                    offsets.attachTopic(name, store.endOffset());
                    topics.put(name, new TopicHandle(name, store));
                }
            }
        } catch (IOException e) {
            throw new StorageException("failed to scan data dir " + dataDir, e);
        }
    }

    /** 새 topic 생성. 이름 규칙 위반 시 {@link IllegalArgumentException}, 중복 시 {@link TopicExistsException}. */
    public synchronized TopicHandle create(String name) throws TopicExistsException, StorageException {
        Topics.requireValid(name);
        if (topics.containsKey(name)) throw new TopicExistsException(name);
        Path dir = dataDir.resolve(name);
        MessageStore store = MessageStore.openOrRecover(dir);
        offsets.attachTopic(name, store.endOffset());
        TopicHandle handle = new TopicHandle(name, store);
        topics.put(name, handle);
        return handle;
    }

    public TopicHandle get(String name) {
        return topics.get(name);
    }

    public boolean exists(String name) {
        return topics.containsKey(name);
    }

    /** 이름 오름차순 목록. */
    public List<TopicHandle> list() {
        List<TopicHandle> out = new ArrayList<>(topics.values());
        out.sort(Comparator.comparing(TopicHandle::name));
        return out;
    }

    @Override
    public void close() {
        for (TopicHandle h : topics.values()) {
            try {
                h.store().close();
            } catch (StorageException e) {
                System.err.println("[topic-manager] close " + h.name() + ": " + e.getMessage());
            }
        }
    }
}
