package com.mannschaft.app.social.announcement.adapter;

import com.mannschaft.app.cms.dto.BlogPostResponse;
import com.mannschaft.app.cms.dto.CreateBlogPostRequest;
import com.mannschaft.app.cms.service.BlogPostService;
import com.mannschaft.app.social.announcement.AnnouncementContentRequest;
import com.mannschaft.app.social.announcement.AnnouncementSourceType;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * F02.8 ブログ記事チャネルアダプター。
 *
 * <p>{@link BlogPostService} を呼び出してブログ記事を作成し、
 * 作成された記事の ID を返す。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class BlogPostAnnouncementAdapter implements AnnouncementChannelAdapter {

    private final BlogPostService blogPostService;

    @Override
    public AnnouncementSourceType getSourceType() {
        return AnnouncementSourceType.BLOG_POST;
    }

    @Override
    public Long createContent(AnnouncementContentRequest content, String scopeType,
                              Long scopeId, String visibility, Long userId) {
        // CreateBlogPostRequest は String 型（Long文字列 or UUID文字列）を受け入れる後方互換形式
        String teamId = "TEAM".equalsIgnoreCase(scopeType) ? scopeId.toString() : null;
        String organizationId = "ORGANIZATION".equalsIgnoreCase(scopeType) ? scopeId.toString() : null;

        CreateBlogPostRequest request = new CreateBlogPostRequest(
                teamId,
                organizationId,
                null,            // socialProfileId
                content.getTitle(),
                null,            // slug（自動生成）
                content.getBody(),
                null,            // excerpt
                null,            // coverImageUrl
                null,            // postType（デフォルト BLOG）
                visibility,      // コンテンツ visibility を引き継ぐ
                null,            // priority（告知ウィザード側で設定）
                null,            // tagIds
                null,            // publishedAt（即時公開）
                null,            // archiveAt
                false,           // crossPostToTimeline
                null,            // seriesId
                null             // seriesOrder
        );

        BlogPostResponse response = blogPostService.createPost(userId, request);

        log.info("ブログ記事作成完了 postId={}, scopeType={}, scopeId={}",
                response.getId(), scopeType, scopeId);
        return response.getId();
    }

    @Override
    public String buildContentUrl(String scopeType, Long scopeId, Long contentId) {
        String scopePath = "TEAM".equalsIgnoreCase(scopeType) ? "teams" : "organizations";
        return "/" + scopePath + "/" + scopeId + "/blog/" + contentId;
    }
}
