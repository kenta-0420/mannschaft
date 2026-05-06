package com.mannschaft.app.social.announcement.adapter;

import com.mannschaft.app.social.announcement.AnnouncementContentRequest;
import com.mannschaft.app.social.announcement.AnnouncementSourceType;
import com.mannschaft.app.timeline.dto.CreatePostRequest;
import com.mannschaft.app.timeline.dto.PostResponse;
import com.mannschaft.app.timeline.service.TimelinePostService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * F02.8 タイムライン投稿チャネルアダプター。
 *
 * <p>{@link TimelinePostService} を呼び出してタイムライン投稿を作成し、
 * 作成された投稿の ID を返す。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class TimelinePostAnnouncementAdapter implements AnnouncementChannelAdapter {

    private final TimelinePostService timelinePostService;

    @Override
    public AnnouncementSourceType getSourceType() {
        return AnnouncementSourceType.TIMELINE_POST;
    }

    @Override
    public Long createContent(AnnouncementContentRequest content, String scopeType,
                              Long scopeId, String visibility, Long userId) {
        // タイムライン投稿はタイトルなし。body を content として使用する。
        String postContent = content.getBody() != null ? content.getBody() : content.getTitle();

        CreatePostRequest request = new CreatePostRequest(
                postContent,
                scopeType,       // TEAM または ORGANIZATION
                scopeId,
                null,            // postedAsType: USER（デフォルト）
                null,            // postedAsId
                null,            // parentId
                null,            // repostOfId
                null,            // scheduledAt
                null,            // poll
                null             // attachments
        );

        PostResponse response = timelinePostService.createPost(request, userId);

        log.info("タイムライン投稿作成完了 postId={}, scopeType={}, scopeId={}",
                response.getId(), scopeType, scopeId);
        return response.getId();
    }

    @Override
    public String buildContentUrl(String scopeType, Long scopeId, Long contentId) {
        String scopePath = "TEAM".equalsIgnoreCase(scopeType) ? "teams" : "organizations";
        return "/" + scopePath + "/" + scopeId + "/timeline/" + contentId;
    }
}
