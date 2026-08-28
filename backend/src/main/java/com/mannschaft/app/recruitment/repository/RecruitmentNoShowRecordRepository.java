package com.mannschaft.app.recruitment.repository;

import com.mannschaft.app.recruitment.entity.RecruitmentNoShowRecordEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * F03.11 Phase 5b: 無断キャンセル記録リポジトリ。
 */
public interface RecruitmentNoShowRecordRepository extends JpaRepository<RecruitmentNoShowRecordEntity, Long> {

    List<RecruitmentNoShowRecordEntity> findByUserId(Long userId);

    Optional<RecruitmentNoShowRecordEntity> findByParticipantId(Long participantId);

    /** 確定済みNO_SHOWのうち指定期間内のユーザー件数（ペナルティ閾値判定用）。 */
    @Query("""
            SELECT COUNT(r) FROM RecruitmentNoShowRecordEntity r
            WHERE r.userId = :userId
              AND r.confirmed = true
              AND (r.disputeResolution <> 'REVOKED' OR r.disputeResolution IS NULL)
              AND r.recordedAt >= :since
            """)
    long countConfirmedNoShows(@Param("userId") Long userId, @Param("since") LocalDateTime since);

    /** 仮マーク（confirmed=FALSE）かつ指定時間を経過したレコード（確定バッチ用）。 */
    @Query("""
            SELECT r FROM RecruitmentNoShowRecordEntity r
            WHERE r.confirmed = false
              AND r.recordedAt <= :before
            """)
    List<RecruitmentNoShowRecordEntity> findUnconfirmedBefore(@Param("before") LocalDateTime before);

    /**
     * 指定募集枠に紐づく<b>未解決の異議申立</b>（{@code disputed = TRUE} かつ
     * {@code dispute_resolution IS NULL}）を取得する（#2497 募集枠論理削除時の自動取下げ用）。
     *
     * <p><b>意図的に {@code RecruitmentListingEntity} を JOIN しない。</b>
     * 兄弟の {@link #findByScopeTypeAndScopeId} / {@link #findByIdAndScopeTypeAndScopeId} は
     * スコープ境界を得るために募集枠を JOIN しており、募集枠側の
     * {@code @SQLRestriction("deleted_at IS NULL")} が効く。そのため<b>募集枠を論理削除した後は
     * 配下の NO_SHOW 記録を一切引けなくなる</b>（これが #2497 の根本原因＝異議が永久に未解決のまま
     * ペナルティに算入され続ける）。本クエリは論理削除の直後に呼ばれるため、募集枠に依存しない
     * {@code listing_id} 直引きにする必要がある。呼出元（{@code RecruitmentListingService#archive}）が
     * 募集枠エンティティの取得時に既にスコープ権限を検証済みであり、帰属は {@code listingId} が担保する。</p>
     *
     * @param listingId 募集枠 ID
     * @return 未解決の異議申立を持つ NO_SHOW 記録（0 件なら空リスト）
     */
    List<RecruitmentNoShowRecordEntity> findByListingIdAndDisputedTrueAndDisputeResolutionIsNull(Long listingId);

    /** スコープ内の NO_SHOW 記録一覧（管理者用）。 */
    @Query("""
            SELECT r FROM RecruitmentNoShowRecordEntity r
            JOIN RecruitmentListingEntity l ON l.id = r.listingId
            WHERE l.scopeType = :scopeType
              AND l.scopeId = :scopeId
            ORDER BY r.recordedAt DESC
            """)
    List<RecruitmentNoShowRecordEntity> findByScopeTypeAndScopeId(
            @Param("scopeType") com.mannschaft.app.recruitment.RecruitmentScopeType scopeType,
            @Param("scopeId") Long scopeId);

    /**
     * スコープ帰属を検証しながら NO_SHOW 記録を 1 件取得する（管理操作用）。
     *
     * <p>NO_SHOW 記録は自身にスコープ列を持たず、紐づく募集枠（{@code recruitment_listings}）の
     * {@code scope_type}/{@code scope_id} が唯一のテナント境界である。よって管理者向けの
     * 単票操作では、パス由来の親スコープに対する権限確認（{@code checkAdminOrAbove}）だけでなく
     * 「その記録が本当に当該スコープに属するか」をこのクエリで併せて検証する必要がある。
     * {@code findById} での直引きは、自スコープの管理者が他スコープの記録 ID を URL に差し込む
     * テナント越境（BOLA）を許してしまう。</p>
     *
     * <p>兄弟の {@link #findByScopeTypeAndScopeId} と同じ JOIN 条件を単票向けに絞ったもの。
     * 不在・越境のいずれも {@link java.util.Optional#empty()} に畳み込まれるため、
     * 呼出元が同一の {@code NO_SHOW_RECORD_NOT_FOUND} を投げることで ID の実在も秘匿できる。</p>
     */
    @Query("""
            SELECT r FROM RecruitmentNoShowRecordEntity r
            JOIN RecruitmentListingEntity l ON l.id = r.listingId
            WHERE r.id = :recordId
              AND l.scopeType = :scopeType
              AND l.scopeId = :scopeId
            """)
    Optional<RecruitmentNoShowRecordEntity> findByIdAndScopeTypeAndScopeId(
            @Param("recordId") Long recordId,
            @Param("scopeType") com.mannschaft.app.recruitment.RecruitmentScopeType scopeType,
            @Param("scopeId") Long scopeId);
}
