package com.mannschaft.app.billing.api;

import com.mannschaft.app.billing.EntitlementScopeKind;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * F20.1 課金履歴 API（AC-48〜AC-59）の読み取り専用クエリ。
 *
 * <p>既存の {@link BillingInvoiceJpaRepository}（webhook 投影側が使う）とは別インタフェースに
 * 分けている。履歴 API の keyset ページングは webhook 投影とライフサイクルが異なるため、
 * 片方の変更が他方の意図を壊さないようにする。</p>
 *
 * <p><b>整列</b>: {@code (period_end DESC, id DESC)}。{@code period_end} が NULL の行は
 * {@code nullFlag} を第1キーにして末尾へ寄せ、NULL 群の内部でも {@code id DESC} で決定的に並べる。
 * {@code id} は UUIDv7 のため tie-break として安定に働く。</p>
 *
 * <p><b>N+1 を出さない</b>（AC-57/AC-58）: 一覧は本クエリ1本だけで、明細行・調整は引かない。
 * 明細取得は invoice / lines / adjustments の3本固定で、子の有無に依らず同じ本数を発行する。</p>
 */
public interface BillingInvoiceHistoryJpaRepository
        extends JpaRepository<BillingInvoiceEntity, UUID> {

    /**
     * scope 内の invoice を keyset で1ページ読む。
     *
     * <p>{@code hasCursor=0} のとき先頭ページ。{@code hasCursor=1} のときは
     * {@code (cursorNullFlag, cursorPeriodEnd, cursorId)} より厳密に後ろの行だけを返す。
     * {@code cursorPeriodEnd} は NULL 行のカーソルでも型の決まった番兵値を渡すため常に非 null。</p>
     */
    @Query("""
            SELECT i FROM BillingInvoiceEntity i
             WHERE i.scopeKind = :scopeKind
               AND i.scopeId = :scopeId
               AND i.deletedAt IS NULL
               AND (:hasCursor = 0
                    OR (CASE WHEN i.periodEnd IS NULL THEN 1 ELSE 0 END) > :cursorNullFlag
                    OR ((CASE WHEN i.periodEnd IS NULL THEN 1 ELSE 0 END) = :cursorNullFlag
                        AND :cursorNullFlag = 0
                        AND i.periodEnd < :cursorPeriodEnd)
                    OR ((CASE WHEN i.periodEnd IS NULL THEN 1 ELSE 0 END) = :cursorNullFlag
                        AND :cursorNullFlag = 0
                        AND i.periodEnd = :cursorPeriodEnd
                        AND i.id < :cursorId)
                    OR ((CASE WHEN i.periodEnd IS NULL THEN 1 ELSE 0 END) = :cursorNullFlag
                        AND :cursorNullFlag = 1
                        AND i.id < :cursorId))
             ORDER BY (CASE WHEN i.periodEnd IS NULL THEN 1 ELSE 0 END) ASC,
                      i.periodEnd DESC,
                      i.id DESC
            """)
    List<BillingInvoiceEntity> findPage(
            @Param("scopeKind") EntitlementScopeKind scopeKind,
            @Param("scopeId") Long scopeId,
            @Param("hasCursor") int hasCursor,
            @Param("cursorNullFlag") int cursorNullFlag,
            @Param("cursorPeriodEnd") Instant cursorPeriodEnd,
            @Param("cursorId") UUID cursorId,
            Pageable pageable);
}
