package com.mannschaft.app.publicview.service;

import com.mannschaft.app.activity.service.ActivityResultService;
import com.mannschaft.app.cms.repository.BlogPostRepository;
import com.mannschaft.app.organization.repository.OrganizationRepository;
import com.mannschaft.app.team.repository.TeamRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * F19.1 Phase 3 sitemap.xml 生成用クエリサービス。
 *
 * <p>PUBLIC 可視チーム・組織・投稿・活動記録（F06.4）の ID + lastmod を取得する。
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
     * F06.4 公開活動記録の取得元。
     *
     * <p>他の 3 つと違い Repository ではなく <b>Service</b> を握っているのは、activity は
     * 別ドメインであり、その Repository を直接引くと番人 D-5
     * （{@code CrossDomainRepositoryDependencyArchTest}）の新規違反になるため。
     * CLAUDE.md「ドメイン間のデータ取得は Service のメソッド呼び出し経由で行う」に従う。</p>
     */
    private final ActivityResultService activityResultService;

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

    /**
     * F06.4 公開活動記録の {@link SitemapEntry} を全件取得する（公開 URL: {@code /activity/{id}}）。
     *
     * <h3>載せてよいものの定義（4 条件すべてを満たすものだけ）</h3>
     * <ol>
     *   <li>{@code visibility = PUBLIC}</li>
     *   <li>{@code status = PUBLISHED}（{@code DRAFT} は載せない）</li>
     *   <li>論理削除されていない（{@code @SQLRestriction("deleted_at IS NULL")} が自動除外）</li>
     *   <li><b>親スコープ（チーム / 組織）も公開である</b></li>
     * </ol>
     *
     * <p>4 番目が sitemap 固有の勘所である。単票 / 一覧 API では
     * {@code PublicActivityQueryService} が親スコープを検証したうえで
     * 非公開なら 404 に倒すので実害は無いが、<b>sitemap は URL そのものを検索エンジンへ
     * 能動的に送る</b>ため、親が非公開のまま載せると「非公開チームが存在すること」と
     * 「その配下の記録 ID」を外部に開示してしまう。したがってここで必ず親を絞る。</p>
     *
     * <p>公開チーム / 組織の ID 集合はこのクラスが既に持っている
     * {@code findAllPublicTeams} / {@code findAllPublicOrganizations}（いずれも
     * {@code visibility = PUBLIC} かつ未アーカイブ・未論理削除）から作る。
     * スコープごとに SQL を撃つのではなく<b>ID 集合を 1 回渡して 1 本の SQL で引く</b>ため、
     * 公開スコープ数に比例した N+1 にはならない。</p>
     *
     * <p>{@code COMMITTEE} スコープの記録は公開ページを持たないため、
     * TEAM / ORGANIZATION の ID 集合のどちらにも入らず自動的に除外される（fail-closed）。</p>
     */
    public List<SitemapEntry> findPublicActivityEntries() {
        Set<Long> publicTeamIds = teamRepository.findAllPublicTeams()
                .stream()
                .map(t -> t.getId())
                .collect(Collectors.toSet());
        Set<Long> publicOrganizationIds = organizationRepository.findAllPublicOrganizations()
                .stream()
                .map(o -> o.getId())
                .collect(Collectors.toSet());

        return activityResultService
                .findPublicActivitiesForSitemap(publicTeamIds, publicOrganizationIds)
                .stream()
                .map(row -> new SitemapEntry(row.activityId(), row.lastMod()))
                .toList();
    }
}
