package com.enonic.assignment.store;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;

class ChangesTest {

    @Test
    void latestByKeyKeepsTheLastChangeForEachKey() {
        Change<String, String> a1 = new Change.Put<>(1, "a", "x");
        Change<String, String> b2 = new Change.Put<>(2, "b", "y");
        Change<String, String> a3 = new Change.Remove<>(3, "a");
        Change<String, String> b4 = new Change.Put<>(4, "b", "z");

        assertEquals(Map.of("a", a3, "b", b4), Changes.latestByKey(Stream.of(a1, b2, a3, b4)));
    }

    @Test
    void latestByKeyFollowsEncounterOrderNotVersionNumbers() {
        Change<String, String> newer = new Change.Put<>(5, "a", "new");
        Change<String, String> older = new Change.Put<>(2, "a", "old");

        assertEquals(Map.of("a", older), Changes.latestByKey(Stream.of(newer, older)));
    }

    @Test
    void latestByKeyKeepsEncounterOrderOnAParallelStream() {
        List<Change<Integer, Integer>> changes = IntStream.rangeClosed(1, 10_000)
                .mapToObj(i -> (Change<Integer, Integer>) new Change.Put<>(i, i % 10, i))
                .toList();
        var expected = new HashMap<Integer, Change<Integer, Integer>>();
        changes.forEach(change -> expected.put(change.key(), change));

        assertEquals(expected, Changes.latestByKey(changes.parallelStream()));
    }

    @Test
    void latestByKeyOfNothingIsEmpty() {
        assertEquals(Map.of(), Changes.latestByKey(Stream.<Change<String, String>>empty()));
    }

    @Test
    void latestByKeyCannotBeModified() {
        Change<String, String> change = new Change.Put<>(1, "a", "x");
        var latest = Changes.latestByKey(Stream.of(change));

        assertThrows(UnsupportedOperationException.class, () -> latest.put("b", change));
    }

    @Test
    void latestByKeyOverAStoresHistoryGivesItsCurrentState() {
        var store = new InMemoryVersionedStore<String, String>();
        store.put("a", "1");
        store.put("b", "2");
        store.remove("a");
        store.put("c", "3");
        store.put("b", "4");

        var present = new HashMap<String, String>();
        Changes.latestByKey(store.changesSince(0)).forEach((key, change) -> {
            switch (change) {
                case Change.Put<String, String>(_, _, var value) -> present.put(key, value);
                case Change.Remove<String, String> _ -> { }
            }
        });

        assertEquals(store.snapshot().asMap(), present);
    }
}
