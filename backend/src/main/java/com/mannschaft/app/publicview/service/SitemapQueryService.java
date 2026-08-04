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
     * <h3>載せてよいものの定義（4 条件すべてを満たすものだけ）</h3>
     * <ol>
     *   <li>{@code visibility = PUBLIC}</li>
     *   <li>{@code status = PUBLISHED}（{@code DRAFT} は載せない）</li>
     *   <li>論理削除されていない（{@code @SQLRestriction("deleted_at IS NULL")} が自動除外）</li>
     *   <li><b>親スコープ（チーム）も公開である</b></li>
     * </ol>
     *
     * <p><b>なぜ親を絞るのか</b>: 投稿の公開 URL は
     * {@code /public/teams/{teamId}/posts/{postId}} という形で<b>親 ID を含む</b>。
     * 単票 API は親が非公開なら 404 に倒すので実害は無いが、<b>sitemap は URL そのものを
     * 検索エンジンへ能動的に送る</b>ため、親が非公開のまま載せると
     * 「非公開チームが実在すること」と「その配下の投稿 ID」を同時に開示してしまう。
     * したがってここで必ず親を絞る（<b>親を絞るという方針</b>は活動記録側
     * {@link #findPublicActivityEntries()} と同じ。ただし<b>空集合の回避方式だけは異なり</b>、
     * あちらは番兵値・こちらは早期 return である。理由は
     * {@code BlogPostRepository#findAllPublicPostsByTeam} の Javadoc 参照）。</p>
     *
     * <p>公開チームの ID 集合は<b>本クラスの既存メソッド</b> {@link #findPublicTeamEntries()}
     * の結果から作る。理由は {@link #findPublicActivityEntries()} の Javadoc に詳しい
     * （番人 D-1 / D-5 の凍結ストアは越境依存を<b>出現回数まで</b>記録しており、
     * {@code teamRepository} を直接呼ぶと新規違反として CI が落ちる）。
     * ID 集合を 1 回渡して 1 本の SQL で引くため、公開チーム数に比例した N+1 にはならない。</p>
     *
     * <p>公開チームが 0 件のときは SQL を撃たずに空リストを返す（JPQL の {@code IN ()} 回避）。</p>
     */
    public List<SitemapPostEntry> findPublicTeamPostEntries() {
        Set<Long> publicTeamIds = findPublicTeamEntries()
                .stream()
                .map(SitemapEntry::id)
                .collect(Collectors.toSet());
        if (publicTeamIds.isEmpty()) {
            // 公開チームが 1 つも無い＝載せてよい投稿も存在しない。SQL を撃つ必要すらない。
            return List.of();
        }
        return blogPostRepository.findAllPublicPostsByTeam(publicTeamIds)
                .stream()
                .map(bp -> new SitemapPostEntry(bp.getTeamId(), bp.getId(), bp.getUpdatedAt()))
                .toList();
    }

    /**
     * PUBLIC 組織の投稿（blog_posts）の organizationId + postId + updatedAt を全件取得する。
     *
     * <p>載せてよいものの定義・<b>なぜ親を絞るのか</b>・空集合の扱いは
     * {@link #findPublicTeamPostEntries()} と同一（チームを組織に読み替えること）。
     * 公開組織の ID 集合は本クラスの {@link #findPublicOrganizationEntries()} から作る。</p>
     */
    public List<SitemapPostEntry> findPublicOrganizationPostEntries() {
        Set<Long> publicOrganizationIds = findPublicOrganizationEntries()
                .stream()
                .map(SitemapEntry::id)
                .collect(Collectors.toSet());
        if (publicOrganizationIds.isEmpty()) {
            // 公開組織が 1 つも無い＝載せてよい投稿も存在しない。
            return List.of();
        }
        return blogPostRepository.findAllPublicPostsByOrganization(publicOrganizationIds)
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
     * <p>公開チーム / 組織の ID 集合は、<b>本クラスの既存メソッド</b>
     * {@link #findPublicTeamEntries()} / {@link #findPublicOrganizationEntries()} の結果から作る
     * （いずれも {@code visibility = PUBLIC} かつ未アーカイブ・未論理削除を返す）。
     * スコープごとに SQL を撃つのではなく<b>ID 集合を 1 回渡して 1 本の SQL で引く</b>ため、
     * 公開スコープ数に比例した N+1 にはならない。</p>
     *
     * <p><b>なぜ {@code teamRepository.findAllPublicTeams()} を直接呼ばないのか</b>:
     * そちらを呼ぶと {@code TeamEntity} / {@code OrganizationEntity} と各 Repository への
     * <b>依存回数が増える</b>。番人 D-1 / D-5 の凍結ストアは違反を<b>出現回数まで含めて</b>
     * 記録しているため、既存の凍結数（entity 各 2 / repository 各 3）を超えた瞬間に
     * 「新規違反」として fail する。既存メソッド経由なら呼び出しは {@code this} に閉じ、
     * 越境依存を 1 つも増やさずに同じ ID 集合が得られる。</p>
     *
     * <p>{@code COMMITTEE} スコープの記録は公開ページを持たないため、
     * TEAM / ORGANIZATION の ID 集合のどちらにも入らず自動的に除外される（fail-closed）。</p>
     */
    public List<SitemapEntry> findPublicActivityEntries() {
        Set<Long> publicTeamIds = findPublicTeamEntries()
                .stream()
                .map(SitemapEntry::id)
                .collect(Collectors.toSet());
        Set<Long> publicOrganizationIds = findPublicOrganizationEntries()
                .stream()
                .map(SitemapEntry::id)
                .collect(Collectors.toSet());

        return activityResultService
                .findPublicActivitiesForSitemap(publicTeamIds, publicOrganizationIds)
                .stream()
                .map(row -> new SitemapEntry(row.activityId(), row.lastMod()))
                .toList();
    }
}
