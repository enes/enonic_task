package com.enonic.assignment.store;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

class InMemoryVersionedStoreTest {

    private final InMemoryVersionedStore<String, String> store = new InMemoryVersionedStore<>();

    @Nested
    class VersionNumbering {

        @Test
        void emptyStoreIsAtVersionZero() {
            assertEquals(0, store.currentVersion());
            assertEquals(List.of(), store.changesSince(0).toList());
        }

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
        void removingAnAbsentKeyIsANoOp() {
            store.put("a", "1");

            assertEquals(1, store.remove("missing"));
            assertEquals(1, store.currentVersion());
            assertEquals(List.of(new Change.Put<>(1, "a", "1")), store.changesSince(0).toList());
            assertEquals(2, store.put("b", "2"), "a no-op remove must not leave a gap");
        }

        @Test
        void removingTheSameKeyTwiceIsANoOpTheSecondTime() {
            store.put("a", "1");

            assertEquals(2, store.remove("a"));
            assertEquals(2, store.remove("a"));
            assertEquals(
                    List.of(new Change.Put<>(1, "a", "1"), new Change.Remove<>(2, "a")),
                    store.changesSince(0).toList());
        }

        @Test
        void removeOnAnEmptyStoreReturnsZero() {
            assertEquals(0, store.remove("a"));
            assertEquals(0, store.currentVersion());
        }
    }

    @Nested
    class GetAt {

        @Test
        void followsOverwritesAndRemovesOfTheSameKey() {
            store.put("k", "a");     // 1
            store.put("other", "x"); // 2
            store.put("k", "b");     // 3
            store.remove("k");       // 4
            store.put("k", "c");     // 5
            store.remove("k");       // 6

            assertEquals(Optional.empty(), store.getAt("k", 0));
            assertEquals(Optional.of("a"), store.getAt("k", 1));
            assertEquals(Optional.of("a"), store.getAt("k", 2), "a write to another key does not change k");
            assertEquals(Optional.of("b"), store.getAt("k", 3));
            assertEquals(Optional.empty(), store.getAt("k", 4));
            assertEquals(Optional.of("c"), store.getAt("k", 5));
            assertEquals(Optional.empty(), store.getAt("k", 6));
            assertEquals(Optional.empty(), store.get("k"));
            assertEquals(Optional.of("x"), store.getAt("other", 6));
        }

        @Test
        void getIsGetAtTheCurrentVersion() {
            store.put("a", "1");
            store.put("b", "2");
            store.put("a", "3");
            store.remove("b");

            for (var key : List.of("a", "b", "missing")) {
                assertEquals(store.getAt(key, store.currentVersion()), store.get(key), key);
            }
            assertEquals(Optional.of("3"), store.get("a"));
        }

        @Test
        void unknownKeyIsEmptyAtEveryVersion() {
            store.put("a", "1");
            store.put("a", "2");

            assertEquals(Optional.empty(), store.getAt("missing", 0));
            assertEquals(Optional.empty(), store.getAt("missing", 1));
            assertEquals(Optional.empty(), store.getAt("missing", 2));
        }

        @Test
        void rejectsVersionsOutsideTheHistory() {
            store.put("k", "a");

            assertThrows(IllegalArgumentException.class, () -> store.getAt("k", -1));
            assertThrows(IllegalArgumentException.class, () -> store.getAt("k", 2));
        }
    }

    @Nested
    class ChangesSince {

        @Test
        void returnsLaterChangesInVersionOrder() {
            store.put("a", "1");
            store.put("b", "2");
            store.remove("a");
            store.put("a", "3");

            assertEquals(
                    List.of(new Change.Put<>(2, "b", "2"), new Change.Remove<>(3, "a"), new Change.Put<>(4, "a", "3")),
                    store.changesSince(1).toList());
        }

        @Test
        void isEmptyFromTheCurrentVersionOn() {
            store.put("a", "1");

            assertEquals(List.of(), store.changesSince(1).toList());
            assertEquals(List.of(), store.changesSince(5).toList());
            assertEquals(List.of(), store.changesSince(Long.MAX_VALUE).toList());
        }

        @Test
        void doesNotIncludeWritesMadeAfterTheCall() {
            store.put("a", "1");
            var changes = store.changesSince(0);

            store.put("b", "2");
            store.remove("a");

            assertEquals(List.of(new Change.Put<>(1, "a", "1")), changes.toList());
        }

        @Test
        void rejectsNegativeVersions() {
            assertThrows(IllegalArgumentException.class, () -> store.changesSince(-1));
        }
    }
}
