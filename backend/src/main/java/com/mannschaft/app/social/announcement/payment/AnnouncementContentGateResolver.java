package com.mannschaft.app.social.announcement.payment;

import com.mannschaft.app.payment.constant.ContentGateType;
import com.mannschaft.app.payment.spi.ContentGateResolver;
import com.mannschaft.app.payment.spi.ContentGateTarget;
import com.mannschaft.app.social.announcement.AnnouncementFeedRepository;
import com.mannschaft.app.social.announcement.AnnouncementScopeType;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.Optional;

/**
 * お知らせフィードの課金ゲート用スコープResolver。
 */
@Component
public class AnnouncementContentGateResolver implements ContentGateResolver {

    private final AnnouncementFeedRepository announcementFeedRepository;
    private final Clock wallClock;

    public AnnouncementContentGateResolver(AnnouncementFeedRepository announcementFeedRepository,
                                           @Qualifier("wallClock") Clock wallClock) {
        this.announcementFeedRepository = announcementFeedRepository;
        this.wallClock = wallClock;
    }
    @Override
    public String contentType() {
        return ContentGateType.ANNOUNCEMENT;
    }

    @Override
    public Optional<ContentGateTarget> resolveForAccess(Long contentId) {
        return announcementFeedRepository.findById(contentId)
                .filter(feed -> feed.getSourceDeletedAt() == null)
                .filter(feed -> feed.getExpiresAt() == null
                        || feed.getExpiresAt().isAfter(LocalDateTime.now(wallClock)))
                .map(feed -> new ContentGateTarget(
                        feed.getId(),
                        feed.getScopeType() == AnnouncementScopeType.TEAM ? feed.getScopeId() : null,
                        feed.getScopeType() == AnnouncementScopeType.ORGANIZATION ? feed.getScopeId() : null));
    }

    @Override
    public boolean existsInScope(Long contentId, Long teamId, Long organizationId) {
        if (teamId != null) {
            return announcementFeedRepository.existsByIdAndScopeTypeAndScopeId(
                    contentId, AnnouncementScopeType.TEAM, teamId);
        }
        return organizationId != null
                && announcementFeedRepository.existsByIdAndScopeTypeAndScopeId(
                        contentId, AnnouncementScopeType.ORGANIZATION, organizationId);
    }
}
