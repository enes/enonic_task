package com.enonic.assignment.store;

import java.util.Optional;
import java.util.stream.Stream;

public interface VersionedStore<K, V> {
    /**
     * Stores value under key. Returns the version produced by this write.
     * Versions start at 1 and every successful put or remove increases
     * the version by exactly one.
     */
    long put(K key, V value);

    /**
     * Removes key. Returns the new version. Removing a key that is not
     * present is a no-op and returns the current version unchanged.
     */
    long remove(K key);

    Optional<V> get(K key);

    /**
     * The value of key as it was immediately after the given version
     * was applied. Version 0 is the empty store.
     */
    Optional<V> getAt(K key, long version);

    long currentVersion();

    /**
     * All changes with version > sinceVersion, in ascending version order.
     * Must be lazy: do not materialise the whole history up front.
     */
    Stream<Change<K, V>> changesSince(long sinceVersion);

    /**
     * Immutable point-in-time view. Taking a snapshot must be O(1)
     * and later writes must never affect it.
     */
    Snapshot<K, V> snapshot();
}
