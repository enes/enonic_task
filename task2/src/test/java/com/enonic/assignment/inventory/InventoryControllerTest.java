package com.enonic.assignment.inventory;

import java.util.Map;

import org.junit.jupiter.api.Test;

import com.enonic.xp.content.ContentNotFoundException;
import com.enonic.xp.content.ContentPath;
import com.enonic.xp.content.ContentQuery;
import com.enonic.xp.repository.RepositoryId;
import com.enonic.xp.repository.RepositoryNotFoundException;
import com.enonic.xp.schema.content.ContentTypeName;
import com.enonic.xp.script.ScriptValue;
import com.enonic.xp.testing.ScriptTestSupport;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import static com.enonic.assignment.inventory.InventoryFixtures.content;
import static com.enonic.assignment.inventory.InventoryFixtures.descendants;

/**
 * Runs the real controller, bean and MapSerializable implementations through XP's JavaScript runtime.
 * Only ContentService is mocked; no live XP server is required.
 */
class InventoryControllerTest extends ScriptTestSupport {
    private static final ContentPath SITE_PATH = ContentPath.from("/mysite");

    @Test
    void successfulResponsePreservesThePublicJsonShape() {
        when(contentService.getByPath(SITE_PATH)).thenReturn(content(SITE_PATH, ContentTypeName.site()));
        when(contentService.find(any(ContentQuery.class))).thenReturn(descendants(2, Map.of("media:image", 2L)));

        final ScriptValue response = get(Map.of("path", "/mysite", "project", "myproject", "branch", "master"));

        assertResponse(response, 200, Map.of(
                "project", "myproject",
                "branch", "master",
                "path", "/mysite",
                "total", 3,
                "byType", Map.of("media:image", 2, "portal:site", 1)));
    }

    @Test
    void emptyTreeSerializesAsZeroAndAnEmptyObject() {
        when(contentService.find(any(ContentQuery.class))).thenReturn(descendants(0, Map.of()));

        assertResponse(get(Map.of("path", "/")), 200, Map.of(
                "project", "default", "branch", "draft", "path", "/", "total", 0, "byType", Map.of()));
    }

    @Test
    void missingParametersReturn400WithAJsonError() {
        assertResponse(get(Map.of()), 400, Map.of("error", "Missing required parameter 'path'"));
        verifyNoInteractions(contentService);
    }

    @Test
    void invalidParametersReturn400WithAJsonError() {
        assertResponse(get(Map.of("path", "/mysite", "branch", "feature")), 400,
                Map.of("error", "Parameter 'branch' must be 'draft' or 'master'"));
        verifyNoInteractions(contentService);
    }

    @Test
    void missingContentReturns404WithAJsonError() {
        when(contentService.getByPath(SITE_PATH)).thenThrow(ContentNotFoundException.create().contentPath(SITE_PATH).build());

        assertResponse(get(Map.of("path", "/mysite")), 404, Map.of("error", "No content at /mysite"));
    }

    @Test
    void missingProjectAtTheRootReturns404WithAJsonError() {
        when(contentService.find(any(ContentQuery.class))).thenThrow(new RepositoryNotFoundException(RepositoryId.from("com.enonic.cms.nope")));

        assertResponse(get(Map.of("path", "/", "project", "nope")), 404, Map.of("error", "No content at /"));
    }

    /**
     * Calls the controller's get function. XP always gives the request a params object, also when
     * the URL has no parameters, so the test does the same.
     */
    private ScriptValue get(final Map<String, String> params) {
        return runFunction("/apis/inventory/inventory.js", "get", Map.of("params", params));
    }

    private static void assertResponse(final ScriptValue response, final int status, final Map<String, Object> body) {
        assertEquals(status, response.getMember("status").getValue(Integer.class));
        assertEquals("application/json", response.getMember("contentType").getValue(String.class));
        assertEquals(body, response.getMember("body").getMap());
    }
}
