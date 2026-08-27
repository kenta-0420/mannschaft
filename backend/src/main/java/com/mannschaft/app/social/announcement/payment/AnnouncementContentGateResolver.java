package com.mannschaft.app.social.announcement.payment;

import com.mannschaft.app.payment.constant.ContentGateType;
import com.mannschaft.app.payment.spi.ContentGateResolver;
import com.mannschaft.app.social.announcement.AnnouncementFeedRepository;
import com.mannschaft.app.social.announcement.AnnouncementScopeType;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * お知らせフィードの課金ゲート用スコープResolver。
 */
@Component
@RequiredArgsConstructor
public class AnnouncementContentGateResolver implements ContentGateResolver {

    private final AnnouncementFeedRepository announcementFeedRepository;

    @Override
    public String contentType() {
        return ContentGateType.ANNOUNCEMENT;
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
