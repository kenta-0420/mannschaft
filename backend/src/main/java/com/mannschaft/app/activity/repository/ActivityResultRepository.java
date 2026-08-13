package com.mannschaft.app.activity.repository;

import com.mannschaft.app.activity.ActivityScopeType;
import com.mannschaft.app.activity.ActivityStatus;
import com.mannschaft.app.activity.ActivityVisibility;
import com.mannschaft.app.activity.entity.ActivityResultEntity;
import com.mannschaft.app.activity.visibility.ActivityResultVisibilityProjection;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

/**
 * 活動記録リポジトリ。
 */
public interface ActivityResultRepository extends JpaRepository<ActivityResultEntity, Long> {

    Page<ActivityResultEntity> findByScopeTypeAndScopeIdOrderByActivityDateDescIdDesc(
            ActivityScopeType scopeType, Long scopeId, Pageable pageable);

    Page<ActivityResultEntity> findByScopeTypeAndScopeIdAndTemplateIdOrderByActivityDateDescIdDesc(
            ActivityScopeType scopeType, Long scopeId, Long templateId, Pageable pageable);

    Optional<ActivityResultEntity> findByScheduleId(Long scheduleId);

    /**
     * CMP-028 Phase B — 認証済み一覧の可視性 SQL 述語化。
     *
     * <p>旧実装（{@code ActivityResultService#listActivities}）は 1 ページ分を無条件取得後、
     * {@code ContentVisibilityChecker#filterAccessible} でメモリフィルタしており、
     * 非公開行が混ざるとページ内に歯抜けが出ていた（AC-6）。本メソッドは
     * F00 の {@code MembershipBatchQueryService#resolveVisibleLevels} が返した可視
     * {@code StandardVisibility} 集合を {@link com.mannschaft.app.activity.ActivityVisibility}
     * へ逆写像した {@code visibilities} を SQL の {@code IN} 述語に渡し、
     * PUBLISHED 行はここで絞り込む。</p>
     *
     * <p>DRAFT 行は F00 の status 軸（作成者本人 or SystemAdmin のみ可視）と同じ意味論を
     * SQL 上でも再現するため、{@code visibility} 述語とは独立に
     * {@code createdBy = :viewerUserId OR :viewerIsSystemAdmin = true} で絞る。
     * {@code viewerUserId} が {@code null}（匿名）の場合は {@code ar.createdBy = NULL} が
     * 常に偽になるため自然に DRAFT が除外される（fail-closed）。</p>
     *
     * @param scopeType           スコープ種別
     * @param scopeId             スコープ ID
     * @param visibilities        SQL の {@code IN} に渡す可視 {@link ActivityVisibility} 集合
     *                            （呼び出し元で必ず非空にすること。{@code PUBLIC} が常に含まれる）
     * @param viewerUserId        閲覧者 userId（{@code null} 可、未認証）
     * @param viewerIsSystemAdmin 閲覧者が SystemAdmin なら {@code true}
     * @param pageable            ページネーション
     * @return 可視な活動記録のページ（総件数は DB の COUNT による正確値）
     */
    @Query("SELECT ar FROM ActivityResultEntity ar "
            + "WHERE ar.scopeType = :scopeType AND ar.scopeId = :scopeId "
            + "AND ((ar.status = com.mannschaft.app.activity.ActivityStatus.PUBLISHED "
            + "        AND ar.visibility IN :visibilities) "
            + "  OR (ar.status = com.mannschaft.app.activity.ActivityStatus.DRAFT "
            + "        AND (ar.createdBy = :viewerUserId OR :viewerIsSystemAdmin = true))) "
            + "ORDER BY ar.activityDate DESC, ar.id DESC")
    Page<ActivityResultEntity> findVisibleByScopeTypeAndScopeId(
            @Param("scopeType") ActivityScopeType scopeType,
            @Param("scopeId") Long scopeId,
            @Param("visibilities") Collection<ActivityVisibility> visibilities,
            @Param("viewerUserId") Long viewerUserId,
            @Param("viewerIsSystemAdmin") boolean viewerIsSystemAdmin,
            Pageable pageable);

    /**
     * {@link #findVisibleByScopeTypeAndScopeId} の templateId 絞り込み版。述語は同一。
     */
    @Query("SELECT ar FROM ActivityResultEntity ar "
            + "WHERE ar.scopeType = :scopeType AND ar.scopeId = :scopeId AND ar.templateId = :templateId "
            + "AND ((ar.status = com.mannschaft.app.activity.ActivityStatus.PUBLISHED "
            + "        AND ar.visibility IN :visibilities) "
            + "  OR (ar.status = com.mannschaft.app.activity.ActivityStatus.DRAFT "
            + "        AND (ar.createdBy = :viewerUserId OR :viewerIsSystemAdmin = true))) "
            + "ORDER BY ar.activityDate DESC, ar.id DESC")
    Page<ActivityResultEntity> findVisibleByScopeTypeAndScopeIdAndTemplateId(
            @Param("scopeType") ActivityScopeType scopeType,
            @Param("scopeId") Long scopeId,
            @Param("templateId") Long templateId,
            @Param("visibilities") Collection<ActivityVisibility> visibilities,
            @Param("viewerUserId") Long viewerUserId,
            @Param("viewerIsSystemAdmin") boolean viewerIsSystemAdmin,
            Pageable pageable);

    /**
     * F06.4 匿名公開一覧の正準クエリ — スコープ配下の<b>公開済み</b>活動記録をページング取得する。
     *
     * <p>{@code visibility = PUBLIC} かつ {@code status = PUBLISHED} を <b>SQL の WHERE 句で</b>
     * 絞る。論理削除済みは {@code @SQLRestriction("deleted_at IS NULL")} が自動除外する。
     * 並び順は {@code activityDate DESC, id DESC}（ページング安定性のため id を tiebreaker）。</p>
     *
     * <h3>なぜ可視性条件を SQL に書くのか（F00 一本化方針との関係）</h3>
     * <p>「可視性判定は F00 に一本化する」という方針の実体は
     * <b>「二つ目の判定器を作るな／手書きのロール階層を書くな」</b>であって
     * 「SQL に書くな」ではない。むしろ設計書
     * {@code docs/features/F02.6_announcement_widget.md} は
     * 「検証は Repository 層の {@code @Query} レベルで WHERE 句に入れる
     * （Service 層の if 文に依存しない）」と規定している。</p>
     *
     * <p>金型は F19.1 の
     * {@link com.mannschaft.app.cms.repository.BlogPostRepository#findPublicPostsByTeamId}
     * （{@code visibility = PUBLIC AND status = PUBLISHED} を SQL で絞り、一覧経路では
     * {@code filterAccessible} を呼ばない）。{@code PublicActivityQueryService} 自身が
     * その {@code PublicPostQueryService} を「金型」と明記しており、同じ流儀に揃える。
     * {@code TournamentService#listPublicTournaments} も同様に SQL 述語を採る。</p>
     *
     * <p><b>本メソッドの述語は独自ラダーではなく F00 自身の宣言の機械的転写である</b>:
     * 匿名（{@code userId = null}）では {@code MembershipBatchQueryService#snapshotForUser} が
     * {@code UserScopeRoleSnapshot.empty()} を返してラダーが縮退し、
     * {@link com.mannschaft.app.common.visibility.StandardVisibility#PUBLIC} の Javadoc が
     * 「未認証時は PUBLIC かつ PUBLISHED のときのみ true、それ以外はすべて fail-closed」と
     * 明文で宣言している。両者の一致は契約テスト AC-32（等価性番人）が
     * {@code visibility × status × deleted} の全 8 組合せで機械的に固定している。</p>
     *
     * <p><b>ページング歯抜けの根治</b>: 旧実装は本メソッドを持たず、スコープ配下の全行から
     * 1 ページ分（= limit 件）を取得したうえで<b>取得後にメモリで</b>可視性フィルタしていた。
     * 落ちた分は補充されないため {@code limit=20} でも 20 件返らなかった（AC-30）。
     * SQL 段で絞ることで、要求件数ちょうどが返り、総件数も実公開件数と一致する（AC-31）。</p>
     *
     * @param scopeType スコープ種別（TEAM / ORGANIZATION / COMMITTEE）
     * @param scopeId   スコープ ID
     * @param pageable  ページネーション（件数上限は呼び出し元が丸める）
     * @return 公開済み活動記録のページ
     */
    @Query("SELECT ar FROM ActivityResultEntity ar "
            + "WHERE ar.scopeType = :scopeType AND ar.scopeId = :scopeId "
            + "AND ar.visibility = com.mannschaft.app.activity.ActivityVisibility.PUBLIC "
            + "AND ar.status = com.mannschaft.app.activity.ActivityStatus.PUBLISHED "
            + "ORDER BY ar.activityDate DESC, ar.id DESC")
    Page<ActivityResultEntity> findPublicByScopeTypeAndScopeId(
            @Param("scopeType") ActivityScopeType scopeType,
            @Param("scopeId") Long scopeId,
            Pageable pageable);

    /**
     * F06.4 sitemap.xml 用 — <b>親スコープが公開であるものに限った</b>公開活動記録を全件取得する。
     *
     * <h3>なぜ {@link #findPublicByScopeTypeAndScopeId} を使い回さないのか</h3>
     * <p>あちらは「単一スコープ配下をページング取得する」形であり、sitemap が必要とする
     * 「全公開スコープを横断して全件」とは形が違う。公開スコープの数だけ呼べば
     * スコープ数に比例した SQL が出る。金型の {@code BlogPostRepository} も
     * {@code findPublicPostsByTeamId}（ページング）と
     * {@code findAllPublicPostsByTeam}（sitemap 用・全件）を<b>両方持っている</b>のと同じ理由で、
     * sitemap 専用の全件クエリを別に置く。可視性述語
     * （{@code visibility = PUBLIC AND status = PUBLISHED}）は
     * {@link #findPublicByScopeTypeAndScopeId} と<b>字面まで同一</b>に保つこと。</p>
     *
     * <h3>親スコープの公開性まで見る理由（sitemap 固有の要件）</h3>
     * <p>{@link #findPublicByScopeTypeAndScopeId} は記録自身の可視性しか見ない。
     * 単票 / 一覧 API では {@code PublicActivityQueryService} が親スコープの公開性を
     * 別途前置するのでそれで足りるが、<b>sitemap は URL をそのまま検索エンジンに教える</b>ため、
     * 親が非公開のまま載せると「非公開チームの存在とその配下の記録 ID」を外部に開示してしまう。
     * よって本メソッドは公開スコープ ID 集合を受け取り、SQL の段で親スコープを絞り込む。</p>
     *
     * <p>{@link com.mannschaft.app.activity.ActivityScopeType#COMMITTEE} は公開ページを
     * 持たないため、述語が TEAM / ORGANIZATION のみを列挙することで自動的に除外される
     * （fail-closed）。論理削除済みは {@code @SQLRestriction("deleted_at IS NULL")} が自動除外する。</p>
     *
     * <p>sitemap は 1 時間キャッシュ前提のため全件取得してよい
     * （{@code SitemapQueryService} クラス Javadoc）。</p>
     *
     * <p><b>「status 条件なしの finder を追加しない」規約（#2548）との関係</b>:
     * 本メソッドは ID 直引き finder ではないが、同じ趣旨に従い
     * {@code status = PUBLISHED} を述語に含めている。status を落とすと
     * <b>下書きの URL を検索エンジンに配ってしまう</b>ため、決して外さないこと。</p>
     *
     * @param publicTeamIds         公開チームの ID 集合（<b>空にしないこと</b>。空集合は JPQL の
     *                              {@code IN ()} を生成して SQL 構文エラーになるため、
     *                              呼び出し元が実在しない番兵値を入れる）
     * @param publicOrganizationIds 公開組織の ID 集合（同上）
     * @return 親スコープが公開である PUBLIC + PUBLISHED の活動記録（全件）
     */
    @Query("SELECT ar FROM ActivityResultEntity ar "
            + "WHERE ar.visibility = com.mannschaft.app.activity.ActivityVisibility.PUBLIC "
            + "AND ar.status = com.mannschaft.app.activity.ActivityStatus.PUBLISHED "
            + "AND ((ar.scopeType = com.mannschaft.app.activity.ActivityScopeType.TEAM "
            + "        AND ar.scopeId IN :publicTeamIds) "
            + "  OR (ar.scopeType = com.mannschaft.app.activity.ActivityScopeType.ORGANIZATION "
            + "        AND ar.scopeId IN :publicOrganizationIds)) "
            + "ORDER BY ar.id ASC")
    List<ActivityResultEntity> findPublicForSitemap(
            @Param("publicTeamIds") Collection<Long> publicTeamIds,
            @Param("publicOrganizationIds") Collection<Long> publicOrganizationIds);

    /**
     * ID + visibility + status で活動記録を取得する（スコープ不問・匿名公開経路の正準）。
     *
     * <p><b>本メソッドが匿名公開経路の唯一の入口である。</b>
     * かつて存在した {@code findByIdAndVisibility(Long, ActivityVisibility)} は status 条件を
     * 持たなかったため、{@code visibility=PUBLIC} のまま公開されていない下書き
     * （{@code status=DRAFT}）が匿名で閲覧できてしまう欠陥があった。
     * 同じ穴を再び開けないよう当該メソッドは<b>削除済み</b>であり、
     * status 条件を伴わない ID 直引き finder を本リポジトリに追加してはならない。</p>
     *
     * <p>{@code @SQLRestriction("deleted_at IS NULL")} により論理削除済は自動除外される。</p>
     *
     * @param id 活動記録 ID
     * @param visibility 公開範囲
     * @param status ライフサイクル状態
     * @return 条件を満たす活動記録（存在しない場合は空）
     */
    Optional<ActivityResultEntity> findByIdAndVisibilityAndStatus(
            Long id, ActivityVisibility visibility, ActivityStatus status);

    long countByScopeTypeAndScopeId(ActivityScopeType scopeType, Long scopeId);

    long countByScopeTypeAndScopeIdAndTemplateId(ActivityScopeType scopeType, Long scopeId, Long templateId);

    @Query("SELECT ar FROM ActivityResultEntity ar WHERE ar.scopeType = :scopeType AND ar.scopeId = :scopeId " +
            "AND (:templateId IS NULL OR ar.templateId = :templateId) " +
            "AND (:dateFrom IS NULL OR ar.activityDate >= :dateFrom) " +
            "AND (:dateTo IS NULL OR ar.activityDate <= :dateTo)")
    List<ActivityResultEntity> findForExport(
            @Param("scopeType") ActivityScopeType scopeType,
            @Param("scopeId") Long scopeId,
            @Param("templateId") Long templateId,
            @Param("dateFrom") LocalDate dateFrom,
            @Param("dateTo") LocalDate dateTo,
            Pageable pageable);

    /**
     * F00 ContentVisibilityResolver 向けバッチ射影取得。
     *
     * <p>{@link com.mannschaft.app.activity.visibility.ActivityResultVisibilityResolver}
     * が SQL 1 本で実存確認込みのメタデータ取得を行うために用いる。
     * {@code @SQLRestriction("deleted_at IS NULL")} により論理削除済は自動除外される。</p>
     *
     * <p>{@code scopeType} は enum を文字列として返すため、JPQL では
     * {@code CAST(... AS string)} を用いて {@code "TEAM" / "ORGANIZATION" / "COMMITTEE"}
     * のいずれかにする。</p>
     *
     * @param ids 取得対象 activity_result の ID 集合
     * @return 実存する {@link ActivityResultVisibilityProjection} のリスト（論理削除分を除外）
     */
    @Query("""
            SELECT new com.mannschaft.app.activity.visibility.ActivityResultVisibilityProjection(
                ar.id,
                CASE
                    WHEN ar.scopeType = com.mannschaft.app.activity.ActivityScopeType.TEAM THEN 'TEAM'
                    WHEN ar.scopeType = com.mannschaft.app.activity.ActivityScopeType.ORGANIZATION THEN 'ORGANIZATION'
                    WHEN ar.scopeType = com.mannschaft.app.activity.ActivityScopeType.COMMITTEE THEN 'COMMITTEE'
                    ELSE NULL
                END,
                ar.scopeId,
                ar.createdBy,
                ar.visibility,
                ar.status)
            FROM ActivityResultEntity ar
            WHERE ar.id IN :ids AND ar.deletedAt IS NULL
            """)
    List<ActivityResultVisibilityProjection> findVisibilityProjectionsByIdIn(
            @Param("ids") Collection<Long> ids);
}
