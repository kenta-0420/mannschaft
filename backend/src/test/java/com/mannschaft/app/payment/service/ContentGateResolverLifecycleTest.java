package com.mannschaft.app.payment.service;

import com.mannschaft.app.cms.entity.BlogPostEntity;
import com.mannschaft.app.cms.payment.BlogPostContentGateResolver;
import com.mannschaft.app.social.announcement.AnnouncementFeedEntity;
import com.mannschaft.app.social.announcement.AnnouncementFeedRepository;
import com.mannschaft.app.social.announcement.AnnouncementScopeType;
import com.mannschaft.app.social.announcement.payment.AnnouncementContentGateResolver;
import com.mannschaft.app.cms.repository.BlogPostRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.time.Clock;
import java.time.ZoneId;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ContentGateResolverLifecycleTest {

    private static Clock fixedClock() {
        return Clock.fixed(LocalDateTime.of(2026, 8, 28, 12, 0)
                .atZone(ZoneId.systemDefault()).toInstant(), ZoneId.systemDefault());
    }

    @Mock private BlogPostRepository blogPostRepository;
    @Mock private AnnouncementFeedRepository announcementFeedRepository;

    @Test
    void blogPostResolverReturnsActualScopeAndRejectsDeletedOrMissing() {
        BlogPostContentGateResolver resolver = new BlogPostContentGateResolver(blogPostRepository);
        BlogPostEntity post = BlogPostEntity.builder().id(10L).teamId(3L).organizationId(null)
                .authorId(8L).build();
        when(blogPostRepository.findById(10L)).thenReturn(Optional.of(post));
        assertThat(resolver.resolveForAccess(10L)).get().satisfies(target -> {
            assertThat(target.contentId()).isEqualTo(10L);
            assertThat(target.teamId()).isEqualTo(3L);
            assertThat(target.organizationId()).isNull();
        });

        BlogPostEntity deleted = post.toBuilder().deletedAt(LocalDateTime.now()).build();
        when(blogPostRepository.findById(11L)).thenReturn(Optional.of(deleted));
        when(blogPostRepository.findById(12L)).thenReturn(Optional.empty());
        assertThat(resolver.resolveForAccess(11L)).isEmpty();
        assertThat(resolver.resolveForAccess(12L)).isEmpty();
    }

    @Test
    void announcementResolverUsesFixedLifecycleBoundaryAndActualScope() {
        LocalDateTime now = LocalDateTime.of(2026, 8, 28, 12, 0);
        AnnouncementContentGateResolver resolver = new AnnouncementContentGateResolver(announcementFeedRepository, fixedClock());
        AnnouncementFeedEntity base = AnnouncementFeedEntity.builder().id(20L)
                .scopeType(AnnouncementScopeType.TEAM).scopeId(4L).sourceDeletedAt(null).build();
        when(announcementFeedRepository.findById(20L)).thenReturn(Optional.of(base));
        assertThat(resolver.resolveForAccess(20L)).get().satisfies(target -> {
            assertThat(target.teamId()).isEqualTo(4L);
            assertThat(target.organizationId()).isNull();
        });

        when(announcementFeedRepository.findById(21L)).thenReturn(Optional.of(base.toBuilder()
                .expiresAt(now.minusNanos(1)).build()));
        when(announcementFeedRepository.findById(22L)).thenReturn(Optional.of(base.toBuilder()
                .expiresAt(now).build()));
        when(announcementFeedRepository.findById(23L)).thenReturn(Optional.of(base.toBuilder()
                .expiresAt(now.plusDays(1)).build()));
        when(announcementFeedRepository.findById(24L)).thenReturn(Optional.of(base.toBuilder()
                .sourceDeletedAt(now.minusDays(1)).build()));
        when(announcementFeedRepository.findById(25L)).thenReturn(Optional.empty());
        assertThat(resolver.resolveForAccess(21L)).isEmpty();
        assertThat(resolver.resolveForAccess(22L)).isEmpty();
        assertThat(resolver.resolveForAccess(23L)).isPresent();
        assertThat(resolver.resolveForAccess(24L)).isEmpty();
        assertThat(resolver.resolveForAccess(25L)).isEmpty();
    }

    @Test
    void announcementOrganizationScopeIsPreserved() {
        AnnouncementContentGateResolver resolver = new AnnouncementContentGateResolver(announcementFeedRepository, fixedClock());
        AnnouncementFeedEntity feed = AnnouncementFeedEntity.builder().id(30L)
                .scopeType(AnnouncementScopeType.ORGANIZATION).scopeId(9L).build();
        when(announcementFeedRepository.findById(30L)).thenReturn(Optional.of(feed));
        assertThat(resolver.resolveForAccess(30L)).get().satisfies(target -> {
            assertThat(target.teamId()).isNull();
            assertThat(target.organizationId()).isEqualTo(9L);
        });
    }
}
