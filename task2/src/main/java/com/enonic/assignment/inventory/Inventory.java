package com.enonic.assignment.inventory;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

import com.enonic.xp.script.serializer.MapGenerator;
import com.enonic.xp.script.serializer.MapSerializable;

/**
 * What exists at and below a content path.
 *
 * @param byType number of content items per content type name, most frequent first
 */
public record Inventory(String project, String branch, String path, long total, Map<String, Long> byType)
        implements MapSerializable {
    public Inventory {
        byType = Collections.unmodifiableMap(new LinkedHashMap<>(byType));
    }

    /**
     * Called by {@code __.toNativeObject} to turn this into a JavaScript object.
     */
    @Override
    public void serialize(final MapGenerator gen) {
        gen.value("project", project);
        gen.value("branch", branch);
        gen.value("path", path);
        gen.value("total", total);
        gen.map("byType");
        byType.forEach(gen::value);
        gen.end();
    }
}
