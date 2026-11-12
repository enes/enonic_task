package com.enonic.assignment.store;

import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Utilities for {@link Change}.
 */
public final class Changes {

    private Changes() {
    }

    /**
     * The last change for each key, in the stream's encounter order (also for a parallel stream).
     * For a stream from {@link VersionedStore#changesSince(long)} that is the change with the highest
     * version. The stream is consumed, not closed.
     *
     * @return an unmodifiable map
     */
    public static <K, V> Map<K, Change<K, V>> latestByKey(Stream<Change<K, V>> changes) {
        Objects.requireNonNull(changes, "changes");
        return changes.collect(Collectors.toUnmodifiableMap(Change::key, Function.identity(), (earlier, later) -> later));
    }

    /**
     * A short readable form, such as {@code #7 put user:42 = Alice} or {@code #8 remove user:42}.
     * Keys and values are written with {@code toString}.
     */
    public static String describe(Change<?, ?> change) {
        Objects.requireNonNull(change, "change");
        // No default branch: a new kind of Change does not compile until it is handled here.
        return switch (change) {
            case Change.Put(var version, var key, var value) -> "#" + version + " put " + key + " = " + value;
            case Change.Remove(var version, var key) -> "#" + version + " remove " + key;
        };
    }
}
