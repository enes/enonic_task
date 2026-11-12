package com.enonic.assignment.inventory;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import com.enonic.assignment.inventory.InventoryResult.Failure;
import com.enonic.assignment.inventory.InventoryResult.FailureReason;
import com.enonic.assignment.inventory.InventoryResult.Success;
import com.enonic.xp.app.ApplicationKey;
import com.enonic.xp.content.ContentConstants;
import com.enonic.xp.content.ContentNotFoundException;
import com.enonic.xp.content.ContentPath;
import com.enonic.xp.content.ContentQuery;
import com.enonic.xp.content.ContentService;
import com.enonic.xp.context.Context;
import com.enonic.xp.context.ContextAccessor;
import com.enonic.xp.context.ContextBuilder;
import com.enonic.xp.query.aggregation.AggregationQuery;
import com.enonic.xp.query.aggregation.TermsAggregationQuery;
import com.enonic.xp.repository.RepositoryId;
import com.enonic.xp.repository.RepositoryNotFoundException;
import com.enonic.xp.resource.ResourceKey;
import com.enonic.xp.schema.content.ContentTypeName;
import com.enonic.xp.script.bean.BeanContext;
import com.enonic.xp.security.PrincipalKey;
import com.enonic.xp.security.RoleKeys;
import com.enonic.xp.security.User;
import com.enonic.xp.security.auth.AuthenticationInfo;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import static com.enonic.assignment.inventory.InventoryFixtures.content;
import static com.enonic.assignment.inventory.InventoryFixtures.descendants;

class InventoryBeanTest {
    private static final ContentPath SITE_PATH = ContentPath.from("/mysite");

    private ContentService contentService;

    private InventoryBean bean;

    @BeforeEach
    void setUp() {
        contentService = mock(ContentService.class);
        bean = new InventoryBean();
        bean.initialize(new StubBeanContext(contentService));
    }

    // Required 1: a known set of content, including the per-type counts.
    @Test
    void countsTheContentItselfAndItsDescendantsPerType() {
        when(contentService.getByPath(SITE_PATH)).thenReturn(content(SITE_PATH, ContentTypeName.site()));
        when(contentService.find(any(ContentQuery.class))).thenReturn(
                descendants(41, Map.of("portal:page-template", 3L, "com.example.myapp:article", 38L)));

        bean.setPath("/mysite");
        final Inventory inventory = success(bean.execute());

        assertEquals("default", inventory.project());
        assertEquals("draft", inventory.branch());
        assertEquals("/mysite", inventory.path());
        assertEquals(42, inventory.total());
        assertEquals(Map.of("portal:site", 1L, "portal:page-template", 3L, "com.example.myapp:article", 38L), inventory.byType());
        assertEquals(List.of("com.example.myapp:article", "portal:page-template", "portal:site"),
                List.copyOf(inventory.byType().keySet()), "most frequent type first");
    }

    @Test
    void addsTheContentItselfToTheBucketOfItsOwnType() {
        final ContentPath folder = ContentPath.from("/folder");
        when(contentService.getByPath(folder)).thenReturn(content(folder, ContentTypeName.folder()));
        when(contentService.find(any(ContentQuery.class))).thenReturn(descendants(2, Map.of("base:folder", 2L)));

        bean.setPath("/folder");
        final Inventory inventory = success(bean.execute());

        assertEquals(3, inventory.total());
        assertEquals(Map.of("base:folder", 3L), inventory.byType());
    }

    @Test
    void theRootPathCountsOnlyDescendantsBecauseTheRootIsNotAContent() {
        when(contentService.find(any(ContentQuery.class))).thenReturn(descendants(5, Map.of("base:folder", 5L)));

        bean.setPath("/");
        final Inventory inventory = success(bean.execute());

        assertEquals(5, inventory.total());
        assertEquals("/", inventory.path());
        verify(contentService, never()).getByPath(any());
    }

    @Test
    void letsTheIndexCountInsteadOfLoadingContent() {
        when(contentService.getByPath(SITE_PATH)).thenReturn(content(SITE_PATH, ContentTypeName.site()));
        when(contentService.find(any(ContentQuery.class))).thenReturn(descendants(0, Map.of()));

        bean.setPath("/mysite");
        bean.execute();

        final ArgumentCaptor<ContentQuery> captor = ArgumentCaptor.forClass(ContentQuery.class);
        verify(contentService).find(captor.capture());
        final ContentQuery query = captor.getValue();
        assertEquals(SITE_PATH, query.getParentPath());
        assertTrue(query.isRecursive(), "all descendants, not only children");
        assertEquals(0, query.getSize(), "no hits are fetched, only the total and the aggregation");

        final List<AggregationQuery> aggregations = new ArrayList<>();
        query.getAggregationQueries().forEach(aggregations::add);
        assertEquals(1, aggregations.size());
        final TermsAggregationQuery terms = assertInstanceOf(TermsAggregationQuery.class, aggregations.getFirst());
        assertEquals("type", terms.getFieldName());
        assertEquals(InventoryBean.ALL_TYPES, terms.getSize(), "every type, not only the first 10");
    }

    // Required 2: a path that does not exist.
    @Test
    void reportsAPathThatDoesNotExist() {
        when(contentService.getByPath(any())).thenThrow(
                ContentNotFoundException.create().contentPath(ContentPath.from("/nope")).build());

        bean.setPath("/nope");

        assertEquals(new Failure(FailureReason.NOT_FOUND, "No content at /nope"), bean.execute());
        verify(contentService, never()).find(any(ContentQuery.class));
    }

    @Test
    void reportsAProjectThatDoesNotExist() {
        when(contentService.getByPath(any())).thenThrow(new RepositoryNotFoundException(RepositoryId.from("com.enonic.cms.nope")));

        bean.setProject("nope");
        bean.setPath("/mysite");

        assertEquals(new Failure(FailureReason.NOT_FOUND, "No content at /mysite"), bean.execute());
    }

    // Required 3: the service is called inside a context with the requested repository and branch.
    @Test
    void callsTheServiceInTheRequestedRepositoryAndBranchAsTheCallingUser() {
        final List<Context> seen = new ArrayList<>();
        when(contentService.getByPath(SITE_PATH)).thenAnswer(invocation -> {
            seen.add(ContextAccessor.current());
            return content(SITE_PATH, ContentTypeName.site());
        });
        when(contentService.find(any(ContentQuery.class))).thenAnswer(invocation -> {
            seen.add(ContextAccessor.current());
            return descendants(0, Map.of());
        });

        final User caller = User.create().key(PrincipalKey.from("user:system:editor")).login("editor").build();
        final AuthenticationInfo callerAuth = AuthenticationInfo.create().user(caller).principals(RoleKeys.AUTHENTICATED).build();
        final Context requestContext = ContextBuilder.create().authInfo(callerAuth).build();

        bean.setProject("myproject");
        bean.setBranch("master");
        bean.setPath("/mysite");
        final Inventory inventory = requestContext.callWith(() -> {
            final Inventory result = success(bean.execute());
            assertNull(ContextAccessor.current().getRepositoryId(), "the caller's own context is left alone");
            return result;
        });

        assertEquals(2, seen.size(), "both service calls were made");
        for (final Context context : seen) {
            assertEquals(RepositoryId.from("com.enonic.cms.myproject"), context.getRepositoryId());
            assertEquals(ContentConstants.BRANCH_MASTER, context.getBranch());
            assertEquals(caller.getKey(), context.getAuthInfo().getUser().getKey(), "no switch to another user");
            assertEquals(callerAuth.getPrincipals(), context.getAuthInfo().getPrincipals(), "no extra roles");
        }
        assertEquals("myproject", inventory.project());
        assertEquals("master", inventory.branch());
    }

    @Test
    void projectAndBranchDefaultToDefaultAndDraft() {
        final List<Context> seen = new ArrayList<>();
        when(contentService.getByPath(SITE_PATH)).thenAnswer(invocation -> {
            seen.add(ContextAccessor.current());
            return content(SITE_PATH, ContentTypeName.site());
        });
        when(contentService.find(any(ContentQuery.class))).thenReturn(descendants(0, Map.of()));

        bean.setProject(" ");
        bean.setPath("/mysite");
        bean.execute();

        assertEquals(RepositoryId.from("com.enonic.cms.default"), seen.getFirst().getRepositoryId());
        assertEquals(ContentConstants.BRANCH_DRAFT, seen.getFirst().getBranch());
    }

    @Test
    void reportsInvalidInputWithoutCallingTheService() {
        assertEquals(new Failure(FailureReason.INVALID_INPUT, "Missing required parameter 'path'"), bean.execute());
        verifyNoInteractions(contentService);
    }

    @Test
    void acceptsAnEmptyContentTree() {
        when(contentService.find(any(ContentQuery.class))).thenReturn(descendants(0, Map.of()));
        bean.setPath("/");

        final Inventory inventory = success(bean.execute());

        assertEquals(0, inventory.total());
        assertEquals(Map.of(), inventory.byType());
    }

    @Test
    void doesNotMislabelUnexpectedServiceFailuresAsInvalidInput() {
        when(contentService.getByPath(SITE_PATH)).thenThrow(new IllegalArgumentException("Unexpected service failure"));
        bean.setPath("/mysite");

        assertThrows(IllegalArgumentException.class, bean::execute);
    }

    private static Inventory success(final InventoryResult result) {
        return assertInstanceOf(Success.class, result).inventory();
    }

    /**
     * Minimal BeanContext: the bean only asks for ContentService, so that is all it hands out.
     */
    private record StubBeanContext(ContentService contentService)
            implements BeanContext {
        @Override
        public <T> Supplier<T> getService(final Class<T> type) {
            if (type != ContentService.class) {
                throw new IllegalArgumentException("Service [" + type.getName() + "] not found");
            }
            return () -> type.cast(contentService);
        }

        @Override
        public <T> Supplier<T> getBinding(final Class<T> type) {
            throw new UnsupportedOperationException();
        }

        @Override
        public ApplicationKey getApplicationKey() {
            throw new UnsupportedOperationException();
        }

        @Override
        public ResourceKey getResourceKey() {
            throw new UnsupportedOperationException();
        }
    }
}
