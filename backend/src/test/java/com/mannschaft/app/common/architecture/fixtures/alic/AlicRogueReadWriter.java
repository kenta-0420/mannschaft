package com.mannschaft.app.common.architecture.fixtures.alic;

import com.mannschaft.app.social.announcement.AnnouncementFeedService;
import com.mannschaft.app.social.announcement.AnnouncementReadService;
import com.mannschaft.app.social.announcement.AnnouncementScopeType;

/**
 * fixture（CMP-261011-0035 AC-F03 負例）: 判定部品（AnnouncementReadCommandService）を経ずに
 * 既読の書き込みメソッドを直接呼ぶダミー。規則は2辺とも違反として検出しなければならない。
 * Spring Bean にはしない。
 */
public class AlicRogueReadWriter {

    private final AnnouncementFeedService feedService;
    private final AnnouncementReadService readService;

    public AlicRogueReadWriter(AnnouncementFeedService feedService, AnnouncementReadService readService) {
        this.feedService = feedService;
        this.readService = readService;
    }

    public void bypass(Long scopeId, Long feedId, Long userId) {
        feedService.markAsRead(AnnouncementScopeType.ORGANIZATION, scopeId, feedId, userId);
        readService.markAsRead(AnnouncementScopeType.ORGANIZATION, scopeId, feedId, userId);
    }
}
