package com.enonic.assignment.store;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ConcurrentNavigableMap;
import java.util.concurrent.ConcurrentSkipListMap;
import java.util.concurrent.locks.ReentrantLock;
import java.util.stream.LongStream;
import java.util.stream.Stream;

public final class InMemoryVersionedStore<K, V> implements VersionedStore<K, V> {

    private final ReentrantLock lock = new ReentrantLock();

    /**
     * Every change ever made to a key. Append-only.
     */
    private final ConcurrentMap<K, History<K, V>> histories = new ConcurrentHashMap<>();

    /**
     * Every committed change, by version. Append-only.
     */
    private final ConcurrentMap<Long, Change<K, V>> log = new ConcurrentHashMap<>();

    /**
     * The last committed version. Written only under {@link #lock}, as the last step of a write.
     */
    private volatile long version;

    @Override
    public long put(K key, V value) {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(value, "value");
        lock.lock();
        try {
            return commit(new Change.Put<>(version + 1, key, value));
        } finally {
            lock.unlock();
        }
    }

    @Override
    public long remove(K key) {
        Objects.requireNonNull(key, "key");
        lock.lock();
        try {
            long current = version;
            if (valueAt(key, current).isEmpty()) {
                return current;
            }
            return commit(new Change.Remove<>(current + 1, key));
        } finally {
            lock.unlock();
        }
    }

    /**
     * Adds the change to both indexes, then publishes its version. The caller holds the write lock,
     * so looking the history up and then adding it is not a race. The only caller-supplied code on
     * this path, the key's {@code hashCode} and {@code equals}, runs in that lookup and insert; if it
     * throws, no change has been recorded.
     */
    private long commit(Change<K, V> change) {
        long next = change.version();
        var history = histories.get(change.key());
        if (history == null) {
            // Built with its first change before it is published: readers never see an empty history.
            histories.put(change.key(), new History<>(change));
        } else {
            history.append(change);
        }
        log.put(next, change);
        version = next; // the commit: from here on readers see this change
        return next;
    }

    @Override
    public Optional<V> get(K key) {
        Objects.requireNonNull(key, "key");
        return valueAt(key, version);
    }

    /**
     * {@inheritDoc}
     *
     * @throws IllegalArgumentException if {@code version} is negative or newer than {@link #currentVersion()}
     */
    @Override
    public Optional<V> getAt(K key, long version) {
        Objects.requireNonNull(key, "key");
        long current = this.version;
        if (version < 0 || version > current) {
            throw new IllegalArgumentException("version " + version + " is not in [0, " + current + "]");
        }
        return valueAt(key, version);
    }

    @Override
    public long currentVersion() {
        return version;
    }

    /**
     * {@inheritDoc}
     *
     * <p>The upper bound is fixed when this method is called: the stream returns exactly the changes
     * in {@code (sinceVersion, currentVersion()]} as of the call, however long it is held and however
     * many writes follow. It can be consumed once, needs no closing and never throws
     * {@code ConcurrentModificationException}. It references the whole change log, not just that
     * range, so holding it keeps the log reachable even after the store itself is dropped.
     *
     * @throws IllegalArgumentException if {@code sinceVersion} is negative
     */
    @Override
    public Stream<Change<K, V>> changesSince(long sinceVersion) {
        if (sinceVersion < 0) {
            throw new IllegalArgumentException("sinceVersion must not be negative: " + sinceVersion);
        }
        long upTo = version;
        if (sinceVersion >= upTo) {
            return Stream.empty();
        }
        // log::get binds the log, not this store.
        return LongStream.rangeClosed(sinceVersion + 1, upTo).mapToObj(log::get);
    }

    @Override
    public Snapshot<K, V> snapshot() {
        return new PointInTimeSnapshot(version);
    }

    /**
     * The key's value at a version the calling thread has seen committed.
     */
    private Optional<V> valueAt(K key, long atVersion) {
        var history = histories.get(key);
        return history == null ? Optional.empty() : history.valueAt(atVersion);
    }

    private Map<K, V> materialize(long atVersion) {
        var result = new HashMap<K, V>();
        histories.forEach((key, history) -> history.valueAt(atVersion).ifPresent(value -> result.put(key, value)));
        return Map.copyOf(result);
    }

    /**
     * Every change made to one key, plus the newest of them for the common read. Written only under
     * the write lock.
     */
    private static final class History<K, V> {

        private final ConcurrentNavigableMap<Long, Change<K, V>> byVersion = new ConcurrentSkipListMap<>();

        /**
         * The newest change to this key, possibly not committed yet. Never null.
         */
        private volatile Change<K, V> latest;

        History(Change<K, V> first) {
            append(first);
        }

        void append(Change<K, V> change) {
            byVersion.put(change.version(), change);
            latest = change;
        }

        Optional<V> valueAt(long atVersion) {
            var newest = latest; // read once: the check and the result must be the same change
            if (newest.version() <= atVersion) {
                return valueOf(newest);
            }
            var entry = byVersion.floorEntry(atVersion);
            return entry == null ? Optional.empty() : valueOf(entry.getValue());
        }

        private static <K, V> Optional<V> valueOf(Change<K, V> change) {
            return switch (change) {
                case Change.Put<K, V>(_, _, var value) -> Optional.of(value);
                case Change.Remove<K, V> _ -> Optional.empty();
            };
        }
    }

    private final class PointInTimeSnapshot implements Snapshot<K, V> {

        private final long atVersion;

        /**
         * Built on first use. Two threads may both build it; the results are equal and immutable.
         */
        private volatile Map<K, V> map;

        PointInTimeSnapshot(long atVersion) {
            this.atVersion = atVersion;
        }

        @Override
        public long version() {
            return atVersion;
        }

        @Override
        public Optional<V> get(K key) {
            Objects.requireNonNull(key, "key");
            return valueAt(key, atVersion);
        }

        @Override
        public Map<K, V> asMap() {
            var result = map;
            if (result == null) {
                result = materialize(atVersion);
                map = result;
            }
            return result;
        }

        @Override
        public String toString() {
            return "Snapshot@" + atVersion;
        }
    }
}
