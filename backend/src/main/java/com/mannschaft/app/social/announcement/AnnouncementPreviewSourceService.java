package com.mannschaft.app.social.announcement;

import com.mannschaft.app.bulletin.ScopeType;
import com.mannschaft.app.bulletin.service.BulletinReadFacade;
import com.mannschaft.app.cms.service.BlogPostPreviewService;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.common.NameResolverService;
import com.mannschaft.app.team.service.TeamService;
import com.mannschaft.app.organization.service.OrganizationService;
import com.mannschaft.app.social.announcement.dto.AnnouncementPreviewResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Objects;
import org.springframework.web.util.UriUtils;

/** 元ドメインの読取境界を使う。本文取得前の metadata には署名処理・業務更新を含めない。 */
@Service
@RequiredArgsConstructor
public class AnnouncementPreviewSourceService {
    private final BlogPostPreviewService blogPostService;
    private final BulletinReadFacade bulletinReadFacade;
    private final NameResolverService nameResolverService;
    private final TeamService teamService;
    private final OrganizationService organizationService;

    Metadata metadata(AnnouncementFeedEntity feed, Long viewer) {
        assertProvisionedScope(feed);
        String slug = nameResolverService.resolveScopeSlug(feed.getScopeType().name(), feed.getScopeId());
        if (slug == null || slug.isBlank()) throw notFound();
        if (feed.getSourceType() == AnnouncementSourceType.BLOG_POST) {
            var source = blogPostService.getPreviewMetadata(feed.getSourceId(), viewer);
            assertScope(feed, source.scopeType(), source.scopeId());
            if (source.slug() == null || source.slug().isBlank()) throw notFound();
            return new Metadata(source.accessState(), source.slug(), slug);
        }
        var source = bulletinReadFacade.getPreviewMetadata(feed.getSourceId(), viewer);
        assertScope(feed, source.scopeType(), source.scopeId());
        return new Metadata("FULL", null, slug);
    }

    AnnouncementPreviewResponse full(AnnouncementFeedEntity feed, Long viewer, Metadata metadata) {
        assertProvisionedScope(feed);
        if (feed.getSourceType() == AnnouncementSourceType.BLOG_POST) {
            var body = blogPostService.getPreviewById(feed.getSourceId(), viewer,
                    feed.getScopeType().name(), feed.getScopeId());
            if ("LOCKED".equals(body.getAccessState())) return locked(feed);
            String slug = body.getContent().slug();
            if (slug == null || slug.isBlank()) throw notFound();
            String url = "/blog/posts/" + encode(slug) + "?"
                    + (feed.getScopeType() == AnnouncementScopeType.TEAM ? "teamId=" : "organizationId=") + feed.getScopeId();
            return new AnnouncementPreviewResponse(feed.getId(), feed.getScopeType(), feed.getScopeId(), "FULL",
                    feed.getSourceType(), feed.getSourceId(), url, body, null, List.of());
        }
        var body = bulletinReadFacade.getThread(ScopeType.valueOf(feed.getScopeType().name()),
                feed.getScopeId(), feed.getSourceId(), viewer);
        String url = (feed.getScopeType() == AnnouncementScopeType.TEAM ? "/teams/" : "/organizations/")
                + encode(metadata.scopeSlug()) + "/bulletin?threadId=" + feed.getSourceId();
        return new AnnouncementPreviewResponse(feed.getId(), feed.getScopeType(), feed.getScopeId(), "FULL",
                feed.getSourceType(), feed.getSourceId(), url, null, body,
                bulletinReadFacade.listThreadAttachments(feed.getSourceId(), viewer));
    }

    private void assertProvisionedScope(AnnouncementFeedEntity feed) {
        if ((feed.getScopeType() == AnnouncementScopeType.TEAM && teamService.isProvisioned(feed.getScopeId()))
                || (feed.getScopeType() == AnnouncementScopeType.ORGANIZATION
                    && organizationService.isProvisioned(feed.getScopeId()))) throw notFound();
    }

    private static void assertScope(AnnouncementFeedEntity feed, String type, Long id) {
        if (!feed.getScopeType().name().equals(type) || !Objects.equals(feed.getScopeId(), id)) throw notFound();
    }
    private static String encode(String value) { return UriUtils.encodePathSegment(value, StandardCharsets.UTF_8); }
    private static BusinessException notFound() { return new BusinessException(AnnouncementErrorCode.ANNOUNCE_001); }
    private static AnnouncementPreviewResponse locked(AnnouncementFeedEntity feed) {
        return AnnouncementPreviewResponse.locked(feed.getId(), feed.getScopeType(), feed.getScopeId());
    }
    public record Metadata(String accessState, String postSlug, String scopeSlug) {}
}
