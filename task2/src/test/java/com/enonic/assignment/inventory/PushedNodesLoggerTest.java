package com.enonic.assignment.inventory;

import java.util.List;
import java.util.Map;
import java.util.OptionalInt;

import org.junit.jupiter.api.Test;

import com.enonic.xp.event.Event;

import static org.junit.jupiter.api.Assertions.assertEquals;

class PushedNodesLoggerTest {
    @Test
    void countsTheNodesOfAPushEvent() {
        final Event event = Event.create("node.pushed")
                .value("nodes", List.of(Map.of("id", "a"), Map.of("id", "b"), Map.of("id", "c")))
                .build();

        assertEquals(OptionalInt.of(3), PushedNodesLogger.affectedNodes(event));
    }

    @Test
    void aPushEventWithoutNodesCountsZero() {
        assertEquals(OptionalInt.of(0), PushedNodesLogger.affectedNodes(Event.create("node.pushed").build()));
    }

    @Test
    void ignoresOtherEvents() {
        final Event created = Event.create("node.created").value("nodes", List.of(Map.of("id", "a"))).build();

        assertEquals(OptionalInt.empty(), PushedNodesLogger.affectedNodes(created));
    }
}
