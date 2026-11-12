package com.enonic.assignment.store;


import java.util.Optional;
import java.util.stream.Stream;


public final class InMemoryVersionedStore<K, V> implements VersionedStore<K, V> {


    @Override
    public long put(K key, V value) {
        return 0;
    }

    @Override
    public long remove(K key) {
        return 0;
    }

    @Override
    public Optional<V> get(K key) {
        return Optional.empty();
    }

    @Override
    public Optional<V> getAt(K key, long version) {
        return Optional.empty();
    }

    @Override
    public long currentVersion() {
        return 0;
    }

    @Override
    public Stream<Change<K, V>> changesSince(long sinceVersion) {
        return Stream.empty();
    }

    @Override
    public Snapshot<K, V> snapshot() {
        return null;
    }
}
