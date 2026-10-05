package com.mannschaft.app.ranch.service;

import com.mannschaft.app.common.CursorPagedResponse;
import com.mannschaft.app.common.ranchsource.api.SourceRewardLink;
import com.mannschaft.app.common.ranchsource.api.SourceRewardLinkProvider;
import com.mannschaft.app.common.ranchsource.api.SourceRewardReference;
import com.mannschaft.app.ranch.dto.RanchRecord;
import com.mannschaft.app.ranch.reward.RanchRewardSourceType;
import com.mannschaft.app.ranch.reward.api.RanchRewardEnvelope.IdType;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

/** AC26: 現在の源閲覧判定を通過したリンクだけを本人台帳に加え、元台帳は保持する。 */
class RanchRecordSourceLinkResolverTest {
    @Test
    void missingSourceProviderKeepsRecordAndCursorWithoutLink() {
        var page = page();
        var result = new RanchRecordSourceLinkResolver(List.of()).resolve(21L, page);
        assertThat(result.getData()).isEqualTo(page.page().getData());
        assertThat(result.getMeta()).isSameAs(page.page().getMeta());
    }

    @Test
    void permissionRecheckGetsViewerAndTypedRefAndUsesOnlySourceOwnedRoute() {
        var provider = mock(SourceRewardLinkProvider.class);
        var reference = new SourceRewardReference(RanchRewardSourceType.BLOG_FIRST_PUBLISH, IdType.LONG, "37");
        when(provider.sourceType()).thenReturn(RanchRewardSourceType.BLOG_FIRST_PUBLISH);
        when(provider.resolve(21L, reference)).thenReturn(Optional.of(
                new SourceRewardLink(SourceRewardLink.Kind.BLOG, "37", "/blog/posts/source-slug")));
        var result = new RanchRecordSourceLinkResolver(List.of(provider)).resolve(21L, page());
        assertThat(result.getData().getFirst().sourceLink()).isEqualTo(
                new RanchRecord.SourceLink("BLOG", "37", "/blog/posts/source-slug"));
        verify(provider).sourceType();
        verify(provider).resolve(21L, reference);
        verifyNoMoreInteractions(provider);
    }

    @Test
    void currentPermissionDenialKeepsRewardAndReturnsNullLink() {
        var provider = mock(SourceRewardLinkProvider.class);
        when(provider.sourceType()).thenReturn(RanchRewardSourceType.BLOG_FIRST_PUBLISH);
        when(provider.resolve(21L, new SourceRewardReference(
                RanchRewardSourceType.BLOG_FIRST_PUBLISH, IdType.LONG, "37"))).thenReturn(Optional.empty());
        var original = page();
        var result = new RanchRecordSourceLinkResolver(List.of(provider)).resolve(21L, original);
        assertThat(result.getData()).isEqualTo(original.page().getData());
    }

    @Test
    void everyReadRechecksSourceAndDoesNotRetainPreviouslyAllowedLink() {
        var provider = mock(SourceRewardLinkProvider.class);
        when(provider.sourceType()).thenReturn(RanchRewardSourceType.BLOG_FIRST_PUBLISH);
        when(provider.resolve(21L, new SourceRewardReference(
                RanchRewardSourceType.BLOG_FIRST_PUBLISH, IdType.LONG, "37")))
                .thenReturn(Optional.of(new SourceRewardLink(
                        SourceRewardLink.Kind.BLOG, "37", "/blog/posts/source-slug")))
                .thenReturn(Optional.empty());
        var resolver = new RanchRecordSourceLinkResolver(List.of(provider));
        var read = page();
        assertThat(resolver.resolve(21L, read).getData().getFirst().sourceLink()).isNotNull();
        assertThat(resolver.resolve(21L, read).getData().getFirst().sourceLink()).isNull();
        assertThat(read.page().getData().getFirst().sourceLink()).isNull();
    }

    @Test
    void ambiguousProviderRegistrationNeverChoosesOnePermissionBoundary() {
        var first = mock(SourceRewardLinkProvider.class);
        var second = mock(SourceRewardLinkProvider.class);
        when(first.sourceType()).thenReturn(RanchRewardSourceType.BLOG_FIRST_PUBLISH);
        when(second.sourceType()).thenReturn(RanchRewardSourceType.BLOG_FIRST_PUBLISH);
        var original = page();
        var result = new RanchRecordSourceLinkResolver(List.of(first, second)).resolve(21L, original);
        assertThat(result.getData()).isEqualTo(original.page().getData());
        verify(first).sourceType();
        verify(second).sourceType();
        verifyNoMoreInteractions(first, second);
    }

    @Test
    void unexpectedSourceProgrammingFailureIsNotSilentlyConvertedToPermissionDenial() {
        var provider = mock(SourceRewardLinkProvider.class);
        when(provider.sourceType()).thenReturn(RanchRewardSourceType.BLOG_FIRST_PUBLISH);
        when(provider.resolve(21L, new SourceRewardReference(
                RanchRewardSourceType.BLOG_FIRST_PUBLISH, IdType.LONG, "37")))
                .thenThrow(new IllegalStateException("synthetic source programming failure"));
        assertThatThrownBy(() -> new RanchRecordSourceLinkResolver(List.of(provider)).resolve(21L, page()))
                .isInstanceOf(IllegalStateException.class);
    }

    private static RanchRecordQueryReader.ReadPage page() {
        UUID id = UUID.randomUUID();
        var record = new RanchRecord(id, "REWARD", "BLOG_FIRST_PUBLISH", "4", "0",
                Instant.parse("2026-10-05T00:00:00Z"), null);
        return new RanchRecordQueryReader.ReadPage(CursorPagedResponse.of(List.of(record),
                new CursorPagedResponse.CursorMeta("opaque-next", true, 20)), Map.of(id,
                new RanchRecordSourceRef(RanchRewardSourceType.BLOG_FIRST_PUBLISH, IdType.LONG, "37")));
    }
}
