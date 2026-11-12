package com.enonic.assignment.inventory;

import java.util.Collection;
import java.util.OptionalInt;

import org.osgi.service.component.annotations.Component;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.enonic.xp.event.Event;
import com.enonic.xp.event.EventConstants;
import com.enonic.xp.event.EventListener;

/**
 * Logs how many nodes each {@code node.pushed} event affected. A push copies nodes from one branch
 * to another, so in practice this runs when content is published.
 *
 * <p>An OSGi declarative-services component: XP's event publisher is given every registered
 * {@link EventListener}, so this is called as soon as the app has started.
 */
@Component(immediate = true)
public final class PushedNodesLogger
        implements EventListener {
    static final String NODE_PUSHED = "node.pushed";

    private static final Logger LOG = LoggerFactory.getLogger(PushedNodesLogger.class);

    @Override
    public void onEvent(final Event event) {
        affectedNodes(event).ifPresent(
                count -> LOG.info("{}: {} node(s) affected, pushed on {} cluster node", NODE_PUSHED, count,
                        event.isLocalOrigin() ? "this" : "another"));
    }

    /**
     * Empty for any other event. A node event has one entry per node under {@link EventConstants#NODES_FIELD}.
     */
    static OptionalInt affectedNodes(final Event event) {
        if (!event.isType(NODE_PUSHED)) {
            return OptionalInt.empty();
        }
        final int count = event.getValue(EventConstants.NODES_FIELD).map(nodes -> ((Collection<?>) nodes).size()).orElse(0);
        return OptionalInt.of(count);
    }
}
