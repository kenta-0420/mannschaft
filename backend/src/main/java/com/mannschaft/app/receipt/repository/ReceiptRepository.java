package com.mannschaft.app.receipt.repository;

import com.mannschaft.app.receipt.ReceiptScopeType;
import com.mannschaft.app.receipt.ReceiptSourceType;
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

    /**
     * 元データ（{@code source_type} + {@code source_ref}）で<b>有効な</b>領収書を検索する
     * （F08.12 §3.1 の冪等判定用）。
     *
     * <p><b>これは重複防止の主体ではない。</b>「検索して無ければ作る」は TOCTOU であり、
     * webhook の重複配送や webhook と手動補填の並行実行では 2 通作れてしまう。
     * 正しさは {@code uq_r_active_platform_source}（生成列 + UNIQUE）が保証し、
     * 本メソッドは正常系で DB 例外を出さないための<b>速度と可読性のための先読み</b>である。
     * この役割分担は {@code WebhookIdempotencyService#tryBegin} と同じ構えである。</p>
     */
    @Query("SELECT r FROM ReceiptEntity r WHERE r.scopeType = :scopeType "
            + "AND r.sourceType = :sourceType AND r.sourceRef = :sourceRef AND r.voidedAt IS NULL")
    Optional<ReceiptEntity> findActiveBySource(
            @Param("scopeType") ReceiptScopeType scopeType,
            @Param("sourceType") ReceiptSourceType sourceType,
            @Param("sourceRef") String sourceRef);

    /**
     * スコープ内の領収書を、無効化済みを除いて発行日降順で取得する（運営コンソールの既定）。
     */
    Page<ReceiptEntity> findByScopeTypeAndScopeIdAndVoidedAtIsNullOrderByIssuedAtDesc(
            ReceiptScopeType scopeType, Long scopeId, Pageable pageable);
}
