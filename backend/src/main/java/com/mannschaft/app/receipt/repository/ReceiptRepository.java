package com.mannschaft.app.receipt.repository;

import com.mannschaft.app.receipt.ReceiptScopeType;
import com.mannschaft.app.receipt.entity.ReceiptEntity;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

/**
 * 領収書リポジトリ。
 */
public interface ReceiptRepository extends JpaRepository<ReceiptEntity, Long>, JpaSpecificationExecutor<ReceiptEntity> {

    /**
     * スコープ内の領収書を発行日降順で取得する。
     */
    Page<ReceiptEntity> findByScopeTypeAndScopeIdOrderByIssuedAtDesc(
            ReceiptScopeType scopeType, Long scopeId, Pageable pageable);

    /**
     * 受領者ユーザー ID で領収書を発行日降順で取得する。
     */
    Page<ReceiptEntity> findByRecipientUserIdOrderByIssuedAtDesc(Long recipientUserId, Pageable pageable);

    /**
     * 受領者ユーザー ID とスコープで領収書を取得する。
     */
    Page<ReceiptEntity> findByRecipientUserIdAndScopeTypeAndScopeIdOrderByIssuedAtDesc(
            Long recipientUserId, ReceiptScopeType scopeType, Long scopeId, Pageable pageable);

    /**
     * 支払い実績 ID で発行済み（未無効化）の領収書を検索する（重複チェック用）。
     */
    @Query("SELECT r FROM ReceiptEntity r WHERE r.memberPaymentId = :memberPaymentId AND r.voidedAt IS NULL")
    List<ReceiptEntity> findActiveByMemberPaymentId(@Param("memberPaymentId") Long memberPaymentId);

    /**
     * ID とスコープで検索する。
     */
    Optional<ReceiptEntity> findByIdAndScopeTypeAndScopeId(Long id, ReceiptScopeType scopeType, Long scopeId);

    /**
     * 受領者ユーザー ID と領収書 ID で検索する（マイページ用）。
     */
    Optional<ReceiptEntity> findByIdAndRecipientUserId(Long id, Long recipientUserId);

    /**
     * 受領者ユーザー ID と発行年で年間サマリー用の領収書一覧を取得する。
     */
    @Query("SELECT r FROM ReceiptEntity r WHERE r.recipientUserId = :userId " +
            "AND YEAR(r.issuedAt) = :year AND r.voidedAt IS NULL")
    List<ReceiptEntity> findActiveByRecipientUserIdAndYear(
            @Param("userId") Long userId, @Param("year") int year);

    /**
     * 受領者ユーザー ID と発行年とスコープで年間サマリー用の領収書一覧を取得する。
     */
    @Query("SELECT r FROM ReceiptEntity r WHERE r.recipientUserId = :userId " +
            "AND YEAR(r.issuedAt) = :year AND r.scopeType = :scopeType AND r.scopeId = :scopeId " +
            "AND r.voidedAt IS NULL")
    List<ReceiptEntity> findActiveByRecipientUserIdAndYearAndScope(
            @Param("userId") Long userId, @Param("year") int year,
            @Param("scopeType") ReceiptScopeType scopeType, @Param("scopeId") Long scopeId);

    /**
     * スコープ内の無効化済み領収書を年間集計用に取得する。
     */
    @Query("SELECT r FROM ReceiptEntity r WHERE r.recipientUserId = :userId " +
            "AND YEAR(r.issuedAt) = :year AND r.voidedAt IS NOT NULL")
    List<ReceiptEntity> findVoidedByRecipientUserIdAndYear(
            @Param("userId") Long userId, @Param("year") int year);
}
