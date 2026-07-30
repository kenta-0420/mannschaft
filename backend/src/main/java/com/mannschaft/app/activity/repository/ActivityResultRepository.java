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
     * ID と visibility で活動記録を取得する（スコープ不問）。
     *
     * <p>SNS シェア用の公開ページが ID 直引きで PUBLIC な記録を取得するために使用する。
     * {@code @SQLRestriction("deleted_at IS NULL")} により論理削除済は自動除外される。</p>
     *
     * @param id 活動記録 ID
     * @param visibility 公開範囲
     * @return 条件を満たす活動記録（存在しない場合は空）
     */
    Optional<ActivityResultEntity> findByIdAndVisibility(Long id, ActivityVisibility visibility);

    /**
     * ID + visibility + status で活動記録を取得する（スコープ不問・匿名公開経路の正準）。
     *
     * <p>{@link #findByIdAndVisibility(Long, ActivityVisibility)} は status 条件を持たないため、
     * {@code visibility=PUBLIC} のまま公開されていない下書き（{@code status=DRAFT}）が
     * 匿名で閲覧できてしまう欠陥があった。匿名公開経路は必ず本メソッド
     * （{@code PUBLIC} かつ {@code PUBLISHED}）を使うこと。</p>
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
