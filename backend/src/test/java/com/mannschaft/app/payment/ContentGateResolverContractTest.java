package com.mannschaft.app.payment;

import com.mannschaft.app.cms.payment.BlogPostContentGateResolver;
import com.mannschaft.app.cms.repository.BlogPostRepository;
import com.mannschaft.app.filesharing.payment.SharedFileContentGateResolver;
import com.mannschaft.app.filesharing.repository.SharedFileRepository;
import com.mannschaft.app.payment.constant.ContentGateType;
import com.mannschaft.app.payment.service.ContentGateResolverRegistry;
import com.mannschaft.app.payment.spi.ContentGateResolver;
import com.mannschaft.app.schedule.payment.ScheduleContentGateResolver;
import com.mannschaft.app.schedule.repository.ScheduleRepository;
import com.mannschaft.app.social.announcement.AnnouncementFeedRepository;
import com.mannschaft.app.social.announcement.AnnouncementScopeType;
import com.mannschaft.app.social.announcement.payment.AnnouncementContentGateResolver;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;

/**
 * 課金ゲートResolverの登録・スコープ委譲契約を検証する。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("ContentGateResolver SPI 契約")
class ContentGateResolverContractTest {

    @Mock private ContentGateResolver post;
    @Mock private ContentGateResolver file;
    @Mock private ContentGateResolver announcement;
    @Mock private ContentGateResolver schedule;
    @Mock private BlogPostRepository blogPosts;
    @Mock private SharedFileRepository files;
    @Mock private AnnouncementFeedRepository announcements;
    @Mock private ScheduleRepository schedules;

    @Test
    @DisplayName("Registry は contentType ごとに dispatch する")
    void registryDispatchesByContentType() {
        given(post.contentType()).willReturn(ContentGateType.POST);
        given(file.contentType()).willReturn(ContentGateType.FILE);
        given(announcement.contentType()).willReturn(ContentGateType.ANNOUNCEMENT);
        given(schedule.contentType()).willReturn(ContentGateType.SCHEDULE);
        given(file.existsInScope(7L, 2L, null)).willReturn(true);

        boolean result = new ContentGateResolverRegistry(List.of(post, file, announcement, schedule))
                .existsInScope(ContentGateType.FILE, 7L, 2L, null);

        assertThat(result).isTrue();
        verify(file).existsInScope(7L, 2L, null);
    }

    @Test
    @DisplayName("Registry は必須4種の不足を fail-fast する")
    void registryRejectsMissingRequiredResolver() {
        given(post.contentType()).willReturn(ContentGateType.POST);
        given(file.contentType()).willReturn(ContentGateType.FILE);
        given(announcement.contentType()).willReturn(ContentGateType.ANNOUNCEMENT);

        assertThatThrownBy(() -> new ContentGateResolverRegistry(List.of(post, file, announcement)))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("Registry は contentType 重複を fail-fast する")
    void registryRejectsDuplicateContentType() {
        given(post.contentType()).willReturn(ContentGateType.POST);
        given(file.contentType()).willReturn(ContentGateType.POST);

        assertThatThrownBy(() -> new ContentGateResolverRegistry(List.of(post, file, announcement, schedule)))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("POST Resolver は team/org scope を Repository に委譲し、双方nullは拒否する")
    void postResolverDelegatesScopes() {
        BlogPostContentGateResolver resolver = new BlogPostContentGateResolver(blogPosts);
        given(blogPosts.existsByIdAndTeamId(1L, 2L)).willReturn(true);
        given(blogPosts.existsByIdAndOrganizationId(1L, 3L)).willReturn(true);

        assertThat(resolver.existsInScope(1L, 2L, null)).isTrue();
        assertThat(resolver.existsInScope(1L, null, 3L)).isTrue();
        assertThat(resolver.existsInScope(1L, null, null)).isFalse();
    }

    @Test
    @DisplayName("FILE Resolver は team/org scope を Repository に委譲し、双方nullは拒否する")
    void fileResolverDelegatesScopes() {
        SharedFileContentGateResolver resolver = new SharedFileContentGateResolver(files);
        given(files.existsInTeamScope(1L, 2L)).willReturn(true);
        given(files.existsInOrganizationScope(1L, 3L)).willReturn(true);

        assertThat(resolver.existsInScope(1L, 2L, null)).isTrue();
        assertThat(resolver.existsInScope(1L, null, 3L)).isTrue();
        assertThat(resolver.existsInScope(1L, null, null)).isFalse();
    }

    @Test
    @DisplayName("ANNOUNCEMENT Resolver は scope type/id を Repository に委譲する")
    void announcementResolverDelegatesScopes() {
        AnnouncementContentGateResolver resolver = new AnnouncementContentGateResolver(announcements);
        given(announcements.existsByIdAndScopeTypeAndScopeId(1L, AnnouncementScopeType.TEAM, 2L))
                .willReturn(true);
        given(announcements.existsByIdAndScopeTypeAndScopeId(1L, AnnouncementScopeType.ORGANIZATION, 3L))
                .willReturn(true);

        assertThat(resolver.existsInScope(1L, 2L, null)).isTrue();
        assertThat(resolver.existsInScope(1L, null, 3L)).isTrue();
        assertThat(resolver.existsInScope(1L, null, null)).isFalse();
    }

    @Test
    @DisplayName("SCHEDULE Resolver は team/org scope を Repository に委譲し、双方nullは拒否する")
    void scheduleResolverDelegatesScopes() {
        ScheduleContentGateResolver resolver = new ScheduleContentGateResolver(schedules);
        given(schedules.existsByIdAndTeamId(1L, 2L)).willReturn(true);
        given(schedules.existsByIdAndOrganizationId(1L, 3L)).willReturn(true);

        assertThat(resolver.existsInScope(1L, 2L, null)).isTrue();
        assertThat(resolver.existsInScope(1L, null, 3L)).isTrue();
        assertThat(resolver.existsInScope(1L, null, null)).isFalse();
    }
}
