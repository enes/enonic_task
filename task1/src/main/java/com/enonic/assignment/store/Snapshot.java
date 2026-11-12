package com.enonic.assignment.store;

import java.util.Map;
import java.util.Optional;

public interface Snapshot<K, V> {
    long version();

    Optional<V> get(K key);

    /**
     * Unmodifiable.
     */
    Map<K, V> asMap();
}
