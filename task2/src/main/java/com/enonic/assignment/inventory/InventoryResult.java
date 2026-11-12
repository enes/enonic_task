package com.enonic.assignment.inventory;

import java.util.Objects;

import com.enonic.xp.script.serializer.MapGenerator;
import com.enonic.xp.script.serializer.MapSerializable;

/**
 * Explicit outcomes for the Java–JavaScript bridge. The controller maps them to HTTP responses.
 */
public sealed interface InventoryResult extends MapSerializable {
    record Success(Inventory inventory) implements InventoryResult {
        public Success {
            Objects.requireNonNull(inventory, "inventory");
        }

        @Override
        public void serialize(final MapGenerator gen) {
            gen.value("status", "SUCCESS");
            gen.value("inventory", inventory);
        }
    }

    enum FailureReason {
        INVALID_INPUT, NOT_FOUND
    }

    record Failure(FailureReason reason, String message) implements InventoryResult {
        public Failure {
            Objects.requireNonNull(reason, "reason");
            Objects.requireNonNull(message, "message");
        }

        @Override
        public void serialize(final MapGenerator gen) {
            gen.value("status", reason.name());
            gen.value("message", message);
        }
    }
}
