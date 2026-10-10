package com.mannschaft.app.common.visibility;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

class ContentVisibilityIsolatedReadTest {
    private ContentVisibilityChecker checker(ContentVisibilityResolver<?> resolver) {
        return new ContentVisibilityChecker(List.of(resolver),
                new VisibilityMetrics(new SimpleMeterRegistry()));
    }

    @Test
    void independentEntriesRequireNewPrimaryTransaction() throws Exception {
        for (String name : List.of("canViewIsolated", "canViewUuidIsolated")) {
            Class<?> idType = name.equals("canViewIsolated") ? Long.class : UUID.class;
            Transactional tx = ContentVisibilityChecker.class
                    .getMethod(name, ReferenceType.class, idType, Long.class)
                    .getAnnotation(Transactional.class);
            assertThat(tx).isNotNull();
            assertThat(tx.propagation()).isEqualTo(Propagation.REQUIRES_NEW);
            assertThat(tx.readOnly()).isFalse();
        }
    }

    @Test
    void longEntryKeepsFreshResolverDecisionAndPropagatesFaults() {
        ContentVisibilityResolver<?> resolver = mock(ContentVisibilityResolver.class);
        when(resolver.referenceType()).thenReturn(ReferenceType.BLOG_POST);
        when(resolver.canView(7L, 9L)).thenReturn(true, false)
                .thenThrow(new IllegalStateException("programming fault"));
        ContentVisibilityChecker checker = checker(resolver);
        assertThat(checker.canViewIsolated(ReferenceType.BLOG_POST, 7L, 9L)).isTrue();
        assertThat(checker.canViewIsolated(ReferenceType.BLOG_POST, 7L, 9L)).isFalse();
        assertThatThrownBy(() -> checker.canViewIsolated(ReferenceType.BLOG_POST, 7L, 9L))
                .isInstanceOf(IllegalStateException.class);
        verify(resolver, times(3)).canView(7L, 9L);
        assertThat(checker.canViewIsolated(ReferenceType.SCHEDULE, 7L, 9L)).isFalse();
    }

    @Test
    void uuidEntryKeepsTypedResolverAndUnsupportedDenial() {
        UUID id = UUID.fromString("019a0000-0000-7000-8000-000000000001");
        ContentVisibilityResolver<?> resolver = mock(ContentVisibilityResolver.class);
        when(resolver.referenceType()).thenReturn(ReferenceType.REFLECTION_ENTRY);
        when(resolver.canViewUuid(id, 9L)).thenReturn(true, false);
        ContentVisibilityChecker checker = checker(resolver);
        assertThat(checker.canViewUuidIsolated(ReferenceType.REFLECTION_ENTRY, id, 9L)).isTrue();
        assertThat(checker.canViewUuidIsolated(ReferenceType.REFLECTION_ENTRY, id, 9L)).isFalse();
        verify(resolver, times(2)).canViewUuid(id, 9L);
        verify(resolver, never()).canView(any(), any());
        assertThat(checker.canViewUuidIsolated(ReferenceType.BLOG_POST, id, 9L)).isFalse();
    }
}
