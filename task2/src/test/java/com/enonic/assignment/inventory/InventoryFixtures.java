package com.enonic.assignment.inventory;

import java.util.Map;

import com.enonic.xp.aggregation.Aggregation;
import com.enonic.xp.aggregation.Aggregations;
import com.enonic.xp.aggregation.Bucket;
import com.enonic.xp.aggregation.Buckets;
import com.enonic.xp.content.Content;
import com.enonic.xp.content.ContentIds;
import com.enonic.xp.content.ContentPath;
import com.enonic.xp.content.FindContentIdsByQueryResult;
import com.enonic.xp.schema.content.ContentTypeName;

final class InventoryFixtures {
    private InventoryFixtures() {
    }

    static Content content(final ContentPath path, final ContentTypeName type) {
        return Content.create().name(path.getName()).parentPath(path.getParentPath()).type(type).build();
    }

    static FindContentIdsByQueryResult descendants(final long total, final Map<String, Long> countByType) {
        final Buckets.Builder buckets = Buckets.create();
        countByType.forEach((type, count) -> buckets.add(Bucket.create().key(type).docCount(count).build()));
        final Aggregation byType = Aggregation.bucketAggregation(InventoryBean.AGGREGATION_NAME).buckets(buckets.build()).build();
        return FindContentIdsByQueryResult.create()
                .contents(ContentIds.empty())
                .totalHits(total)
                .aggregations(Aggregations.from(byType))
                .build();
    }
}
