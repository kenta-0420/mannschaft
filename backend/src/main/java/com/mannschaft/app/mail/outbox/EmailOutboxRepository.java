package com.mannschaft.app.mail.outbox;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * F09.18 メール配信 outbox 用 Repository。
 *
 * <p>本ドメインは {@code organization_id} が NULL の行 (認証メール) を含むため、
 * {@code AbstractTenantAwareRepository} は適用しない (設計書 §4.4)。</p>
 */
@Repository
public interface EmailOutboxRepository extends JpaRepository<EmailOutboxEntity, UUID> {

    /**
     * 送信対象を取得する Worker のメインクエリ。
     *
     * <p>{@code SELECT ... FOR UPDATE SKIP LOCKED} で複数 pod が同じ行を取れない設計
     * (設計書 §7.4 冪等性保証 第 2 段階)。</p>
     */
    @Query(value = """
            SELECT * FROM email_outbox
             WHERE status = 'PENDING'
               AND next_attempt_at <= NOW(3)
             ORDER BY next_attempt_at
             LIMIT :limit
             FOR UPDATE SKIP LOCKED
            """, nativeQuery = true)
    List<EmailOutboxEntity> findReadyForSending(@Param("limit") int limit);

    /** ステータス別件数を返す。監視メトリクス用。 */
    long countByStatus(String status);

    /** 最古の指定ステータス行 1 件 (queue 滞留時間メトリクス用)。 */
    Optional<EmailOutboxEntity> findFirstByStatusOrderByCreatedAtAsc(String status);

    /**
     * GDPR 削除権連携: 退会済ユーザーの行を匿名化する (設計書 §9.2)。
     * payload_json / to_address / to_address_hash / user_id を NULL 化する。
     * sent_at / status / template_kind / source_domain (集計可能メタ) は残す。
     */
    @Modifying
    @Query(value = """
            UPDATE email_outbox
               SET to_address = NULL,
                   to_address_hash = NULL,
                   payload_json = NULL,
                   user_id = NULL
             WHERE user_id = :userId
            """, nativeQuery = true)
    int anonymizeByUserId(@Param("userId") Long userId);

    /**
     * SENDING のまま放置された残骸を PENDING に戻す (設計書 §15-付記)。
     * 毎時バッチが {@code threshold = NOW() - 5min} で実行する。
     */
    @Modifying
    @Query(value = """
            UPDATE email_outbox
               SET status = 'PENDING'
             WHERE status = 'SENDING'
               AND updated_at < :threshold
            """, nativeQuery = true)
    int recoverStuckSending(@Param("threshold") LocalDateTime threshold);

    /**
     * 保持期間バッチ: payload_json を NULL 化する (設計書 §9.1、30 日経過後)。
     * to_address / to_address_hash は別バッチ (13 ヶ月) で処理する。
     */
    @Modifying
    @Query(value = """
            UPDATE email_outbox
               SET payload_json = NULL
             WHERE created_at < :threshold
               AND payload_json IS NOT NULL
            """, nativeQuery = true)
    int purgePayloadBefore(@Param("threshold") LocalDateTime threshold);

    /**
     * SYSTEM_ADMIN 向け一覧取得。フィルターは NULL で全件対象 (設計書 §6.2)。
     *
     * <p>{@code :status} / {@code :sourceDomain} 等はすべて nullable で、
     * NULL を渡した場合は条件を無視する。</p>
     */
    @Query(value = """
            SELECT * FROM email_outbox
             WHERE (:status IS NULL OR status = :status)
               AND (:sourceDomain IS NULL OR source_domain = :sourceDomain)
               AND (:fromDate IS NULL OR created_at >= :fromDate)
               AND (:toDate IS NULL OR created_at <= :toDate)
             ORDER BY created_at DESC
            """,
            countQuery = """
            SELECT COUNT(*) FROM email_outbox
             WHERE (:status IS NULL OR status = :status)
               AND (:sourceDomain IS NULL OR source_domain = :sourceDomain)
               AND (:fromDate IS NULL OR created_at >= :fromDate)
               AND (:toDate IS NULL OR created_at <= :toDate)
            """,
            nativeQuery = true)
    Page<EmailOutboxEntity> findByFilters(
            @Param("status") String status,
            @Param("sourceDomain") String sourceDomain,
            @Param("fromDate") LocalDateTime fromDate,
            @Param("toDate") LocalDateTime toDate,
            Pageable pageable);

    /** 直近 N 時間以内に作成された行のうち、指定ステータスの件数。監視メトリクス用。 */
    @Query(value = """
            SELECT COUNT(*) FROM email_outbox
             WHERE status = :status
               AND created_at >= :since
            """, nativeQuery = true)
    long countByStatusSince(@Param("status") String status, @Param("since") LocalDateTime since);
}
