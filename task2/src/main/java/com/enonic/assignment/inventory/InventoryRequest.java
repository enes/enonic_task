package com.enonic.assignment.inventory;

import java.util.Objects;
import java.util.Set;

import com.enonic.xp.branch.Branch;
import com.enonic.xp.content.ContentConstants;
import com.enonic.xp.content.ContentPath;
import com.enonic.xp.project.ProjectName;

/**
 * Validated inventory parameters, with defaults resolved before any content is accessed.
 */
public record InventoryRequest(ProjectName project, Branch branch, ContentPath path) {
    private static final String DEFAULT_PROJECT = "default";

    private static final Set<Branch> CONTENT_BRANCHES = Set.of(ContentConstants.BRANCH_DRAFT, ContentConstants.BRANCH_MASTER);

    public InventoryRequest {
        Objects.requireNonNull(project, "project");
        Objects.requireNonNull(branch, "branch");
        Objects.requireNonNull(path, "path");
        if (!CONTENT_BRANCHES.contains(branch)) {
            throw new IllegalArgumentException("Parameter 'branch' must be 'draft' or 'master'");
        }
    }

    public static InventoryRequest from(final String project, final String branch, final String path) {
        if (isBlank(path)) {
            throw new IllegalArgumentException("Missing required parameter 'path'");
        }
        if (!path.startsWith("/")) {
            throw new IllegalArgumentException("Parameter 'path' must start with '/'");
        }
        return new InventoryRequest(
                ProjectName.from(isBlank(project) ? DEFAULT_PROJECT : project),
                isBlank(branch) ? ContentConstants.BRANCH_DRAFT : Branch.from(branch),
                ContentPath.from(path));
    }

    private static boolean isBlank(final String value) {
        return value == null || value.isBlank();
    }
}
