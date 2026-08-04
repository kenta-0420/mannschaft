package com.mannschaft.app.cms.repository;

import com.mannschaft.app.cms.PostStatus;
import com.mannschaft.app.cms.entity.BlogPostEntity;
import com.mannschaft.app.cms.visibility.BlogPostVisibilityProjection;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

/**
 * ブログ記事リポジトリ。
 */
public interface BlogPostRepository extends JpaRepository<BlogPostEntity, Long> {

    String SEARCH_BY_TEAM = "SELECT * FROM blog_posts WHERE team_id = :teamId AND MATCH(title, body) AGAINST(:keyword IN BOOLEAN MODE) AND deleted_at IS NULL";
    String SEARCH_BY_ORG = "SELECT * FROM blog_posts WHERE organization_id = :orgId AND MATCH(title, body) AGAINST(:keyword IN BOOLEAN MODE) AND deleted_at IS NULL";

    Page<BlogPostEntity> findByTeamIdAndStatusOrderByPinnedDescPublishedAtDesc(
            Long teamId, PostStatus status, Pageable pageable);

    Page<BlogPostEntity> findByOrganizationIdAndStatusOrderByPinnedDescPublishedAtDesc(
            Long organizationId, PostStatus status, Pageable pageable);

    Page<BlogPostEntity> findByUserIdAndStatusOrderByPublishedAtDesc(
            Long userId, PostStatus status, Pageable pageable);

    Page<BlogPostEntity> findByTeamIdOrderByPinnedDescCreatedAtDesc(Long teamId, Pageable pageable);

    Page<BlogPostEntity> findByOrganizationIdOrderByPinnedDescCreatedAtDesc(Long organizationId, Pageable pageable);

    Page<BlogPostEntity> findByUserIdOrderByCreatedAtDesc(Long userId, Pageable pageable);

    Optional<BlogPostEntity> findByTeamIdAndSlug(Long teamId, String slug);

    Optional<BlogPostEntity> findByOrganizationIdAndSlug(Long organizationId, String slug);

    Optional<BlogPostEntity> findByUserIdAndSlug(Long userId, String slug);

    @Query(value = SEARCH_BY_TEAM, nativeQuery = true)
    Page<BlogPostEntity> searchByTeam(@Param("teamId") Long teamId, @Param("keyword") String keyword, Pageable pageable);

    @Query(value = SEARCH_BY_ORG, nativeQuery = true)
    Page<BlogPostEntity> searchByOrganization(@Param("orgId") Long orgId, @Param("keyword") String keyword, Pageable pageable);

    /**
     * 認証ユーザー自身のブログ記事をID指定で取得する（削除済み除外）。
     *
     * <p>authorId 不一致 / 削除済み / 不在 は全て空を返す（IDOR 対策）。</p>
     *
     * @param id       ブログ記事 ID
     * @param authorId 著者（認証ユーザー）ID
     * @return 該当する BlogPostEntity（存在しない場合は空）
     */
    Optional<BlogPostEntity> findByIdAndAuthorIdAndDeletedAtIsNull(Long id, Long authorId);

    long countBySeriesId(Long seriesId);

    /**
     * RSS/Atom フィード向け: チームスコープで公開済み記事を最大20件取得する。
     * 可視性フィルタリングは {@link com.mannschaft.app.common.visibility.ContentVisibilityChecker} に委譲するため
     * Visibility 条件を含まない。
     */
    List<BlogPostEntity> findTop20ByTeamIdAndStatusOrderByPublishedAtDesc(
            Long teamId, PostStatus status);

    /**
     * RSS/Atom フィード向け: 組織スコープで公開済み記事を最大20件取得する。
     * 可視性フィルタリングは {@link com.mannschaft.app.common.visibility.ContentVisibilityChecker} に委譲するため
     * Visibility 条件を含まない。
     */
    List<BlogPostEntity> findTop20ByOrganizationIdAndStatusOrderByPublishedAtDesc(
            Long organizationId, PostStatus status);

    /**
     * F00 共通可視性基盤 (BlogPostVisibilityResolver) 向けバルク射影取得。
     *
     * <p>{@code @SQLRestriction("deleted_at IS NULL")} は {@link BlogPostEntity} に
     * 付与されているが、constructor expression を使う本クエリでは適用されないため
     * WHERE 句で明示的に {@code deleted_at IS NULL} を指定する。
     *
     * <p>SQL 1 本で {@link BlogPostVisibilityProjection} を生成し、N+1 を防ぐ。
     *
     * @param ids 射影対象 blog_post_id 集合（空でない）
     * @return 実存する BlogPost の Projection リスト
     */
    @Query("""
            SELECT new com.mannschaft.app.cms.visibility.BlogPostVisibilityProjection(
                bp.id,
                CASE
                    WHEN bp.teamId IS NOT NULL THEN 'TEAM'
                    WHEN bp.organizationId IS NOT NULL THEN 'ORGANIZATION'
                    ELSE NULL
                END,
                COALESCE(bp.teamId, bp.organizationId),
                bp.authorId,
                bp.visibilityTemplateId,
                bp.visibility,
                bp.status)
            FROM BlogPostEntity bp
            WHERE bp.id IN :ids AND bp.deletedAt IS NULL
            """)
    List<BlogPostVisibilityProjection> findVisibilityProjectionsByIdIn(
            @Param("ids") Collection<Long> ids);

    // ========================================================================
    // F19.1 Phase 1: 公開ページ用 ソース直 JOIN 方式での絞り込み
    //
    // §4.2 軍議追補に従い、announcement_feeds 経由ではなく blog_posts を直接
    // visibility = PUBLIC かつ status = PUBLISHED で取得する。
    // 設計書: docs/features/F19.1_public_pages_identity_disclosure.md §4.2 / §7.6
    // ========================================================================

    /**
     * F19.1 Phase 1: チームの公開ブログ記事一覧を取得する。
     *
     * <p>{@code visibility = PUBLIC} かつ {@code status = PUBLISHED} かつ未削除の
     * 記事のみ返す（{@code @SQLRestriction} で削除済みは自然に除外）。
     * 並び順は {@code publishedAt DESC, id DESC}（ページング安定性のため id を tiebreaker）。</p>
     *
     * @param teamId   対象チーム ID
     * @param pageable ページネーション
     * @return 公開記事ページ
     */
    @Query("SELECT bp FROM BlogPostEntity bp "
            + "WHERE bp.teamId = :teamId "
            + "AND bp.visibility = com.mannschaft.app.cms.Visibility.PUBLIC "
            + "AND bp.status = com.mannschaft.app.cms.PostStatus.PUBLISHED "
            + "ORDER BY bp.publishedAt DESC, bp.id DESC")
    Page<BlogPostEntity> findPublicPostsByTeamId(@Param("teamId") Long teamId, Pageable pageable);

    /**
     * F19.1 Phase 1: 組織の公開ブログ記事一覧を取得する。
     *
     * @see #findPublicPostsByTeamId(Long, Pageable)
     */
    @Query("SELECT bp FROM BlogPostEntity bp "
            + "WHERE bp.organizationId = :organizationId "
            + "AND bp.visibility = com.mannschaft.app.cms.Visibility.PUBLIC "
            + "AND bp.status = com.mannschaft.app.cms.PostStatus.PUBLISHED "
            + "ORDER BY bp.publishedAt DESC, bp.id DESC")
    Page<BlogPostEntity> findPublicPostsByOrganizationId(
            @Param("organizationId") Long organizationId, Pageable pageable);

    /**
     * F19.1 Phase 1: チームの公開ブログ記事を ID 指定で取得する。
     *
     * <p>{@code teamId} 不一致 / PRIVATE / 未公開 / 削除済 / 不在 は全て空を返す
     * （IDOR 対策で 404 隠蔽。呼び出し側 Service で {@code orElseThrow}）。</p>
     */
    @Query("SELECT bp FROM BlogPostEntity bp "
            + "WHERE bp.id = :postId "
            + "AND bp.teamId = :teamId "
            + "AND bp.visibility = com.mannschaft.app.cms.Visibility.PUBLIC "
            + "AND bp.status = com.mannschaft.app.cms.PostStatus.PUBLISHED")
    Optional<BlogPostEntity> findPublicPostByTeamIdAndId(
            @Param("teamId") Long teamId, @Param("postId") Long postId);

    /**
     * F19.1 Phase 1: 組織の公開ブログ記事を ID 指定で取得する。
     */
    @Query("SELECT bp FROM BlogPostEntity bp "
            + "WHERE bp.id = :postId "
            + "AND bp.organizationId = :organizationId "
            + "AND bp.visibility = com.mannschaft.app.cms.Visibility.PUBLIC "
            + "AND bp.status = com.mannschaft.app.cms.PostStatus.PUBLISHED")
    Optional<BlogPostEntity> findPublicPostByOrganizationIdAndId(
            @Param("organizationId") Long organizationId, @Param("postId") Long postId);

    /**
     * F19.1 Phase 3 sitemap.xml 用: <b>公開チーム</b>配下の PUBLIC + PUBLISHED 投稿を全件取得する。
     *
     * <h3>親スコープの公開性まで見る理由（sitemap 固有の要件）</h3>
     * <p>投稿自身の可視性しか見ないと、<b>非公開チーム配下の PUBLIC 投稿</b>が sitemap に載る。
     * 単票 API なら「親が非公開なら 404」で守れるが、<b>sitemap は URL をそのまま検索エンジンに
     * 教える</b>ため、載せた時点で取り返しがつかない。しかも投稿 URL は
     * {@code /public/teams/{teamId}/posts/{postId}} という形で親 ID を含むため、
     * 「非公開チームが実在すること」と「その配下の投稿 ID」を同時に開示してしまう。
     * よって本メソッドは公開チーム ID 集合を受け取り、SQL の段で親スコープを絞り込む。</p>
     *
     * <p>絞り込みを SQL に降ろすのは、スコープごとに 1 本ずつ撃つ N+1 を避けるためでもある
     * （活動記録側の金型 {@code ActivityResultRepository#findPublicForSitemap} と同じ書き味）。</p>
     *
     * <p>sitemap は1時間キャッシュ前提のため N+1 を気にせず一括取得してよい。
     * {@code @SQLRestriction} により論理削除済み投稿は自動除外される。
     * {@code status = PUBLISHED} は<b>決して外さないこと</b>（下書きの URL を配ってしまう）。</p>
     *
     * <p>設計書: docs/features/F19.1_public_pages_identity_disclosure.md §9.2</p>
     *
     * @param publicTeamIds 公開チームの ID 集合（<b>空にしないこと</b>。空集合は JPQL の
     *                      {@code IN ()} を生成して SQL 構文エラーになるため、
     *                      呼び出し元が実在しない番兵値を入れる）
     * @return 親チームが公開である PUBLIC + PUBLISHED の投稿（全件）
     */
    @Query("SELECT bp FROM BlogPostEntity bp "
            + "WHERE bp.teamId IN :publicTeamIds "
            + "AND bp.visibility = com.mannschaft.app.cms.Visibility.PUBLIC "
            + "AND bp.status = com.mannschaft.app.cms.PostStatus.PUBLISHED "
            + "ORDER BY bp.teamId ASC, bp.id ASC")
    List<BlogPostEntity> findAllPublicPostsByTeam(
            @Param("publicTeamIds") Collection<Long> publicTeamIds);

    /**
     * F19.1 Phase 3 sitemap.xml 用: <b>公開組織</b>配下の PUBLIC + PUBLISHED 投稿を全件取得する。
     *
     * <p>親スコープを絞る理由・空集合の扱いは
     * {@link #findAllPublicPostsByTeam(Collection)} と同一（チームを組織に読み替えること）。</p>
     *
     * <p>設計書: docs/features/F19.1_public_pages_identity_disclosure.md §9.2</p>
     *
     * @param publicOrganizationIds 公開組織の ID 集合（<b>空にしないこと</b>。理由は上記参照）
     * @return 親組織が公開である PUBLIC + PUBLISHED の投稿（全件）
     */
    @Query("SELECT bp FROM BlogPostEntity bp "
            + "WHERE bp.organizationId IN :publicOrganizationIds "
            + "AND bp.visibility = com.mannschaft.app.cms.Visibility.PUBLIC "
            + "AND bp.status = com.mannschaft.app.cms.PostStatus.PUBLISHED "
            + "ORDER BY bp.organizationId ASC, bp.id ASC")
    List<BlogPostEntity> findAllPublicPostsByOrganization(
            @Param("publicOrganizationIds") Collection<Long> publicOrganizationIds);

    // ========================================================================
    // F19.1 Phase 4: 公開検索用 lastPostDate 一括取得（N+1 防止）
    // ========================================================================

    /**
     * F19.1 Phase 4 公開チーム検索用: チーム ID 集合に対する最新投稿日時を一括取得する。
     *
     * <p>N+1 を防ぐために、チームのページ取得後に 1 本のクエリで全 lastPostDate を取得する。
     * 戻り値は {@code Object[]{teamId, maxCreatedAt}} の List。呼び出し側でマップに変換する。</p>
     *
     * <p>削除済み投稿は {@code @SQLRestriction} により自動除外される。</p>
     *
     * @param teamIds 対象チーム ID 集合（空の場合は空リストを返す）
     * @return チームごとの最新投稿日時（{@code [teamId, maxCreatedAt]} の形式）
     */
    @Query("""
            SELECT bp.teamId, MAX(bp.createdAt)
            FROM BlogPostEntity bp
            WHERE bp.teamId IN :teamIds
              AND bp.status = com.mannschaft.app.cms.PostStatus.PUBLISHED
            GROUP BY bp.teamId
            """)
    List<Object[]> findMaxCreatedAtByTeamIdIn(@Param("teamIds") Collection<Long> teamIds);

    // ========================================================================
    // F19.1 Phase 6: 公開ユーザープロフィール — 著者単位の公開投稿一覧
    // 設計書: docs/features/F19.1_public_pages_identity_disclosure.md §6.6
    // ========================================================================

    /**
     * F19.1 Phase 6: 著者 ID に紐づく公開ブログ記事一覧を取得する。
     *
     * <p>条件:</p>
     * <ul>
     *   <li>{@code authorId} が一致する</li>
     *   <li>{@code visibility = PUBLIC} かつ {@code status = PUBLISHED}</li>
     *   <li>{@code public_visible = true}（F19.1 Phase 2 で追加されたフラグ）</li>
     *   <li>未削除（{@code @SQLRestriction} により自動適用）</li>
     * </ul>
     *
     * <p>チーム/組織スコープを問わず authorId で横断検索する。
     * TODO: publicview → cms のクロスドメイン参照。将来はイベント駆動化候補。</p>
     *
     * @param authorId 著者ユーザー ID
     * @param pageable ページネーション
     * @return 公開投稿ページ（作成日時 DESC）
     */
    @Query("SELECT bp FROM BlogPostEntity bp "
            + "WHERE bp.authorId = :authorId "
            + "AND bp.visibility = com.mannschaft.app.cms.Visibility.PUBLIC "
            + "AND bp.status = com.mannschaft.app.cms.PostStatus.PUBLISHED "
            + "AND bp.publicVisible = true "
            + "ORDER BY bp.createdAt DESC, bp.id DESC")
    Page<BlogPostEntity> findPublicPostsByAuthorId(@Param("authorId") Long authorId, Pageable pageable);

    /**
     * F19.1 Phase 4 公開組織検索用: 組織 ID 集合に対する最新投稿日時を一括取得する。
     *
     * <p>N+1 を防ぐために、組織のページ取得後に 1 本のクエリで全 lastPostDate を取得する。
     * 戻り値は {@code Object[]{organizationId, maxCreatedAt}} の List。呼び出し側でマップに変換する。</p>
     *
     * <p>削除済み投稿は {@code @SQLRestriction} により自動除外される。</p>
     *
     * @param organizationIds 対象組織 ID 集合（空の場合は空リストを返す）
     * @return 組織ごとの最新投稿日時（{@code [organizationId, maxCreatedAt]} の形式）
     */
    @Query("""
            SELECT bp.organizationId, MAX(bp.createdAt)
            FROM BlogPostEntity bp
            WHERE bp.organizationId IN :organizationIds
              AND bp.status = com.mannschaft.app.cms.PostStatus.PUBLISHED
            GROUP BY bp.organizationId
            """)
    List<Object[]> findMaxCreatedAtByOrganizationIdIn(@Param("organizationIds") Collection<Long> organizationIds);
}
