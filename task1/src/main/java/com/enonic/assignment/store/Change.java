package com.enonic.assignment.store;

public sealed interface Change<K, V> permits Change.Put, Change.Remove {
    long version();

    K key();

    record Put<K, V>(long version, K key, V value) implements Change<K, V> {
    }

    record Remove<K, V>(long version, K key) implements Change<K, V> {
    }
}
