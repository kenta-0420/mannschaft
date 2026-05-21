package com.mannschaft.app.publicview.service;

import com.mannschaft.app.cms.repository.BlogPostRepository;
import com.mannschaft.app.organization.repository.OrganizationRepository;
import com.mannschaft.app.team.repository.TeamRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * F19.1 Phase 3 sitemap.xml 生成用クエリサービス。
 *
 * <p>PUBLIC 可視チーム・組織・投稿の ID + lastmod を取得する。
 * 1時間キャッシュ前提のため N+1 を気にせず一括取得してよい。</p>
 *
 * <p>設計書: docs/features/F19.1_public_pages_identity_disclosure.md §9.2</p>
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class SitemapQueryService {

    private final TeamRepository teamRepository;
    private final OrganizationRepository organizationRepository;
    private final BlogPostRepository blogPostRepository;

    /**
     * PUBLIC チームの {@link SitemapEntry} を全件取得する。
     *
     * <p>{@code visibility = PUBLIC} かつ未アーカイブ・未論理削除のチームが対象。</p>
     */
    public List<SitemapEntry> findPublicTeamEntries() {
        return teamRepository.findAllPublicTeams()
                .stream()
                .map(t -> new SitemapEntry(t.getId(), t.getUpdatedAt()))
                .toList();
    }

    /**
     * PUBLIC 組織の {@link SitemapEntry} を全件取得する。
     *
     * <p>{@code visibility = PUBLIC} かつ未アーカイブ・未論理削除の組織が対象。</p>
     */
    public List<SitemapEntry> findPublicOrganizationEntries() {
        return organizationRepository.findAllPublicOrganizations()
                .stream()
                .map(o -> new SitemapEntry(o.getId(), o.getUpdatedAt()))
                .toList();
    }

    /**
     * PUBLIC チームの投稿（blog_posts）の teamId + postId + updatedAt を全件取得する。
     *
     * <p>{@code visibility = PUBLIC} かつ {@code status = PUBLISHED} の投稿が対象。</p>
     */
    public List<SitemapPostEntry> findPublicTeamPostEntries() {
        return blogPostRepository.findAllPublicPostsByTeam()
                .stream()
                .map(bp -> new SitemapPostEntry(bp.getTeamId(), bp.getId(), bp.getUpdatedAt()))
                .toList();
    }

    /**
     * PUBLIC 組織の投稿（blog_posts）の organizationId + postId + updatedAt を全件取得する。
     *
     * <p>{@code visibility = PUBLIC} かつ {@code status = PUBLISHED} の投稿が対象。</p>
     */
    public List<SitemapPostEntry> findPublicOrganizationPostEntries() {
        return blogPostRepository.findAllPublicPostsByOrganization()
                .stream()
                .map(bp -> new SitemapPostEntry(bp.getOrganizationId(), bp.getId(), bp.getUpdatedAt()))
                .toList();
    }
}
