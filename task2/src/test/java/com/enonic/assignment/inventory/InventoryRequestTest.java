package com.enonic.assignment.inventory;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import com.enonic.xp.branch.Branch;
import com.enonic.xp.content.ContentConstants;
import com.enonic.xp.content.ContentPath;
import com.enonic.xp.project.ProjectName;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class InventoryRequestTest {
    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {" ", "\t"})
    void defaultsMissingOrBlankProjectAndBranch(final String value) {
        final InventoryRequest request = InventoryRequest.from(value, value, "/");

        assertEquals(ProjectName.from("default"), request.project());
        assertEquals(ContentConstants.BRANCH_DRAFT, request.branch());
        assertEquals(ContentPath.ROOT, request.path());
    }

    @Test
    void resolvesExplicitParameters() {
        final InventoryRequest request = InventoryRequest.from("myproject", "master", "/mysite");

        assertEquals(ProjectName.from("myproject"), request.project());
        assertEquals(ContentConstants.BRANCH_MASTER, request.branch());
        assertEquals(ContentPath.from("/mysite"), request.path());
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {" ", "\t"})
    void requiresAPath(final String path) {
        final IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                () -> InventoryRequest.from(null, null, path));

        assertEquals("Missing required parameter 'path'", failure.getMessage());
    }

    @Test
    void rejectsRelativePaths() {
        final IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                () -> InventoryRequest.from(null, null, "mysite"));

        assertEquals("Parameter 'path' must start with '/'", failure.getMessage());
    }

    @Test
    void rejectsUnsupportedBranches() {
        final IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                () -> InventoryRequest.from(null, "feature", "/mysite"));

        assertEquals("Parameter 'branch' must be 'draft' or 'master'", failure.getMessage());
    }

    @Test
    void rejectsMalformedProjectNames() {
        final IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                () -> InventoryRequest.from("Not A Project", null, "/mysite"));

        assertEquals("ProjectName is invalid: Not A Project", failure.getMessage());
    }

    @Test
    void directConstructionCannotBypassBranchValidation() {
        assertThrows(IllegalArgumentException.class,
                () -> new InventoryRequest(ProjectName.from("default"), Branch.from("feature"), ContentPath.ROOT));
    }
}
