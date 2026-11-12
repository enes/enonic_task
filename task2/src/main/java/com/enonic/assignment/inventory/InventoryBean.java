package com.enonic.assignment.inventory;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Supplier;

import com.enonic.assignment.inventory.InventoryResult.Failure;
import com.enonic.assignment.inventory.InventoryResult.FailureReason;
import com.enonic.assignment.inventory.InventoryResult.Success;
import com.enonic.xp.aggregation.Bucket;
import com.enonic.xp.aggregation.BucketAggregation;
import com.enonic.xp.content.Content;
import com.enonic.xp.content.ContentPropertyNames;
import com.enonic.xp.content.ContentQuery;
import com.enonic.xp.content.ContentService;
import com.enonic.xp.content.FindContentIdsByQueryResult;
import com.enonic.xp.context.ContextAccessor;
import com.enonic.xp.context.ContextBuilder;
import com.enonic.xp.exception.NotFoundException;
import com.enonic.xp.query.aggregation.TermsAggregationQuery;
import com.enonic.xp.script.bean.BeanContext;
import com.enonic.xp.script.bean.ScriptBean;

/**
 * Counts the content at and below a path, per content type. Created by
 * {@code apis/inventory/inventory.js} through {@code __.newBean(...)}, one instance per request.
 *
 * <p>The content at the path itself is counted, as in the assignment's example where
 * {@code /mysite} reports one {@code portal:site}. The root path {@code /} is not a content item,
 * so for it only the descendants are counted.
 *
 * <p>The index does the counting: one query with {@code size(0)} and a terms aggregation on the
 * content type. No descendant is loaded. The only content loaded is the one at the path, to learn
 * its type and whether it exists.
 */
public final class InventoryBean
        implements ScriptBean {
    static final String AGGREGATION_NAME = "byType";

    /**
     * No limit on the number of types. Without a size the index returns only the first 10.
     */
    static final int ALL_TYPES = Integer.MAX_VALUE;

    private Supplier<ContentService> contentService;

    private String project;

    private String branch;

    private String path;

    @Override
    public void initialize(final BeanContext context) {
        // Keep the supplier, not the service: it looks the OSGi service up when it is used.
        this.contentService = context.getService(ContentService.class);
    }

    public void setProject(final String project) {
        this.project = project;
    }

    public void setBranch(final String branch) {
        this.branch = branch;
    }

    public void setPath(final String path) {
        this.path = path;
    }

    /**
     * Validates once, then counts in the caller's requested repository and branch.
     * Expected failures are explicit results; unexpected service failures propagate to XP.
     */
    public InventoryResult execute() {
        final InventoryRequest request;
        try {
            request = InventoryRequest.from(project, branch, path);
        } catch (IllegalArgumentException e) {
            return new Failure(FailureReason.INVALID_INPUT, e.getMessage());
        }

        // The request context has the caller's session but no repository and no branch. Keep the
        // caller, add only the repository and branch, and run the service calls inside that.
        return ContextBuilder.from(ContextAccessor.current())
                .repositoryId(request.project().getRepoId())
                .branch(request.branch())
                .build()
                .callWith(() -> count(request));
    }

    private InventoryResult count(final InventoryRequest request) {
        final ContentService service = contentService.get();
        final Map<String, Long> byType = new LinkedHashMap<>();
        long total = 0;

        try {
            if (!request.path().isRoot()) {
                final Content self = service.getByPath(request.path());
                byType.put(self.getType().toString(), 1L);
                total = 1;
            }

            final FindContentIdsByQueryResult descendants = service.find(ContentQuery.create()
                    .parentPath(request.path())
                    .recursive(true)
                    .size(0)
                    .aggregationQuery(typeAggregation())
                    .build());
            total += descendants.getTotalHits();
            final BucketAggregation types = (BucketAggregation) descendants.getAggregations().get(AGGREGATION_NAME);
            for (final Bucket bucket : types.getBuckets()) {
                byType.merge(bucket.getKey(), bucket.getDocCount(), Long::sum);
            }
        } catch (NotFoundException e) {
            // ContentNotFoundException for the path, RepositoryNotFoundException for the project.
            return new Failure(FailureReason.NOT_FOUND, "No content at " + request.path());
        }

        return new Success(new Inventory(request.project().toString(), request.branch().getValue(), request.path().toString(), total,
                sortedByCount(byType)));
    }

    private static TermsAggregationQuery typeAggregation() {
        return TermsAggregationQuery.create(AGGREGATION_NAME)
                .fieldName(ContentPropertyNames.TYPE)
                .size(ALL_TYPES)
                .build();
    }

    /**
     * Most frequent type first, ties in alphabetical order, so the output is stable.
     */
    private static Map<String, Long> sortedByCount(final Map<String, Long> counts) {
        final Map<String, Long> sorted = new LinkedHashMap<>();
        counts.entrySet()
                .stream()
                .sorted(Map.Entry.<String, Long>comparingByValue().reversed().thenComparing(Map.Entry.comparingByKey()))
                .forEach(entry -> sorted.put(entry.getKey(), entry.getValue()));
        return sorted;
    }
}
