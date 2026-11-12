package com.enonic.assignment.store;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

class InMemoryVersionedStoreTest {

    private final InMemoryVersionedStore<String, String> store = new InMemoryVersionedStore<>();

    @Nested
    class VersionNumbering {

        @Test
        void everyPutAndRemoveAddsExactlyOne() {
            assertEquals(1, store.put("a", "1"));
            assertEquals(2, store.put("b", "2"));
            assertEquals(3, store.put("a", "3"));
            assertEquals(4, store.remove("a"));
            assertEquals(4, store.currentVersion());
        }

        @Test
        void putOfTheValueAlreadyStoredIsStillANewVersion() {
            store.put("a", "1");

            assertEquals(2, store.put("a", "1"));
        }

        @Test
        void removeOnAnEmptyStoreReturnsZero() {
            assertEquals(0, store.remove("a"));
            assertEquals(0, store.currentVersion());
        }
    }
}
