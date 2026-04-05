package com.mannschaft.app.membership.repository;

import com.mannschaft.app.membership.CardStatus;
import com.mannschaft.app.membership.ScopeType;
import com.mannschaft.app.membership.entity.MemberCardEntity;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

/**
 * 会員証リポジトリ。
 */
public interface MemberCardRepository extends JpaRepository<MemberCardEntity, Long> {

    /**
     * ユーザーの全会員証を取得する（論理削除除外）。
     */
    List<MemberCardEntity> findByUserIdAndDeletedAtIsNullOrderByIssuedAtAsc(Long userId);

    /**
     * カードコードで会員証を検索する（論理削除除外）。
     */
    Optional<MemberCardEntity> findByCardCodeAndDeletedAtIsNull(String cardCode);

    /**
     * IDで会員証を検索する（論理削除除外）。
     */
    Optional<MemberCardEntity> findByIdAndDeletedAtIsNull(Long id);

    /**
     * スコープ別の会員証一覧を取得する（論理削除除外）。
     */
    @Query("SELECT mc FROM MemberCardEntity mc WHERE mc.scopeType = :scopeType AND mc.scopeId = :scopeId " +
            "AND mc.status = :status AND mc.deletedAt IS NULL ORDER BY mc.cardNumber ASC")
    List<MemberCardEntity> findByScopeAndStatus(
            @Param("scopeType") ScopeType scopeType,
            @Param("scopeId") Long scopeId,
            @Param("status") CardStatus status);

    /**
     * スコープ別会員証一覧を名前・番号で検索する（論理削除除外）。
     */
    @Query("SELECT mc FROM MemberCardEntity mc WHERE mc.scopeType = :scopeType AND mc.scopeId = :scopeId " +
            "AND mc.status = :status AND mc.deletedAt IS NULL " +
            "AND (mc.displayName LIKE %:q% OR mc.cardNumber LIKE %:q%) ORDER BY mc.cardNumber ASC")
    List<MemberCardEntity> findByScopeAndStatusWithSearch(
            @Param("scopeType") ScopeType scopeType,
            @Param("scopeId") Long scopeId,
            @Param("status") CardStatus status,
            @Param("q") String q);

    /**
     * ユーザーのスコープ別会員証を取得する（論理削除除外）。
     */
    Optional<MemberCardEntity> findByUserIdAndScopeTypeAndScopeIdAndDeletedAtIsNull(
            Long userId, ScopeType scopeType, Long scopeId);

    /**
     * スコープ内の最大会員番号の数値部分を取得する（FOR UPDATE でレースコンディション防止）。
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query(value = "SELECT MAX(CAST(SUBSTRING_INDEX(mc.card_number, '-', -1) AS UNSIGNED)) " +
            "FROM member_cards mc WHERE mc.scope_type = :scopeType AND mc.scope_id = :scopeId",
            nativeQuery = true)
    Optional<Long> findMaxCardNumberInScope(
            @Param("scopeType") String scopeType,
            @Param("scopeId") Long scopeId);

    /**
     * PLATFORMスコープの最大会員番号を取得する（scope_id IS NULL）。
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query(value = "SELECT MAX(CAST(SUBSTRING_INDEX(mc.card_number, '-', -1) AS UNSIGNED)) " +
            "FROM member_cards mc WHERE mc.scope_type = 'PLATFORM' AND mc.scope_id IS NULL",
            nativeQuery = true)
    Optional<Long> findMaxCardNumberInPlatformScope();

    /**
     * チェックイン回数と最終チェックイン日時をアトミックに更新する。
     */
    @Modifying
    @Query("UPDATE MemberCardEntity mc SET mc.checkinCount = mc.checkinCount + 1, " +
            "mc.lastCheckinAt = CURRENT_TIMESTAMP WHERE mc.id = :cardId")
    void incrementCheckinCount(@Param("cardId") Long cardId);

    /**
     * スコープ内のステータス別件数を取得する。
     */
    @Query("SELECT mc.status, COUNT(mc) FROM MemberCardEntity mc " +
            "WHERE mc.scopeType = :scopeType AND mc.scopeId = :scopeId AND mc.deletedAt IS NULL " +
            "GROUP BY mc.status")
    List<Object[]> countByScopeGroupByStatus(
            @Param("scopeType") ScopeType scopeType,
            @Param("scopeId") Long scopeId);
}
