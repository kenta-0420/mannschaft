package com.mannschaft.app.social.announcement.adapter;

import com.mannschaft.app.bulletin.ScopeType;
import com.mannschaft.app.bulletin.dto.CreateThreadRequest;
import com.mannschaft.app.bulletin.dto.ThreadResponse;
import com.mannschaft.app.bulletin.service.BulletinThreadService;
import com.mannschaft.app.social.announcement.AnnouncementContentRequest;
import com.mannschaft.app.social.announcement.AnnouncementSourceType;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * F02.8 掲示板スレッドチャネルアダプター。
 *
 * <p>{@link BulletinThreadService} を呼び出してスレッドを作成し、
 * 作成されたスレッドの ID を返す。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class BulletinThreadAnnouncementAdapter implements AnnouncementChannelAdapter {

    private final BulletinThreadService bulletinThreadService;

    @Override
    public AnnouncementSourceType getSourceType() {
        return AnnouncementSourceType.BULLETIN_THREAD;
    }

    @Override
    public Long createContent(AnnouncementContentRequest content, String scopeType,
                              Long scopeId, String visibility, Long userId) {
        ScopeType bulletinScopeType = ScopeType.valueOf(scopeType);

        CreateThreadRequest request = new CreateThreadRequest(
                content.getCategoryId(),
                content.getTitle(),
                content.getBody(),
                null,   // priority は告知ウィザード側で設定済み
                null,   // readTrackingMode デフォルト
                AnnouncementSourceType.BULLETIN_THREAD.name(),
                null
        );

        ThreadResponse response = bulletinThreadService.createThread(
                bulletinScopeType, scopeId, userId, request);

        log.info("掲示板スレッド作成完了 threadId={}, scopeType={}, scopeId={}",
                response.getId(), scopeType, scopeId);
        return response.getId();
    }

    @Override
    public String buildContentUrl(String scopeType, Long scopeId, Long contentId) {
        String scopePath = "TEAM".equalsIgnoreCase(scopeType) ? "teams" : "organizations";
        return "/" + scopePath + "/" + scopeId + "/bulletin/threads/" + contentId;
    }
}
