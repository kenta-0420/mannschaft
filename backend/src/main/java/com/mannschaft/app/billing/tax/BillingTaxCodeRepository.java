package com.mannschaft.app.billing.tax;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * {@link BillingTaxCodeEntity} の永続化・解決クエリ。
 *
 * <p>正本: {@code .claude/campaigns/price-rev-plan-v3.md} 決定6・AC-4〜AC-16。</p>
 */
public interface BillingTaxCodeRepository extends JpaRepository<BillingTaxCodeEntity, UUID> {

    /** 論理削除済みでない一覧（ロック行 {@code __TAX_CODE_LOCK__} を含む生の全件）。 */
    @Query("SELECT t FROM BillingTaxCodeEntity t WHERE t.deletedAt IS NULL AND t.code <> '__TAX_CODE_LOCK__'")
    List<BillingTaxCodeEntity> findAllVisible();

    Optional<BillingTaxCodeEntity> findByIdAndDeletedAtIsNull(UUID id);

    Optional<BillingTaxCodeEntity> findByCodeAndValidFromAndDeletedAtIsNull(String code, Instant validFrom);

    /**
     * 同一 code の有効期間が重なる既存行を返す（半開区間 {@code [validFrom, validUntil)}）。
     * {@code validUntil=null} は無期限を意味する。
     */
    @Query("SELECT t FROM BillingTaxCodeEntity t WHERE t.code = :code AND t.deletedAt IS NULL "
            + "AND t.validFrom < COALESCE(:validUntil, t.validFrom) "
            + "AND (t.validUntil IS NULL OR t.validUntil > :validFrom) "
            + "AND (:validUntil IS NULL OR t.validFrom < :validUntil)")
    List<BillingTaxCodeEntity> findOverlapping(
            @Param("code") String code, @Param("validFrom") Instant validFrom, @Param("validUntil") Instant validUntil);

    /**
     * 指定時刻に有効な行（{@code enabled} 問わず。enabled 判定は呼び出し側が行う）を取得する。
     * 半開区間 {@code [validFrom, validUntil)}。
     */
    @Query("SELECT t FROM BillingTaxCodeEntity t WHERE t.code = :code AND t.deletedAt IS NULL "
            + "AND t.validFrom <= :at AND (t.validUntil IS NULL OR :at < t.validUntil) "
            + "ORDER BY t.validFrom DESC")
    Optional<BillingTaxCodeEntity> findEffectiveAt(@Param("code") String code, @Param("at") Instant at);

    /** 税コード専用ロック行を {@code FOR UPDATE} で取得し、create/update を直列化する（決定6改訂）。 */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT t FROM BillingTaxCodeEntity t WHERE t.code = '__TAX_CODE_LOCK__'")
    BillingTaxCodeEntity lockTaxCodeLockRowForUpdate();
}
