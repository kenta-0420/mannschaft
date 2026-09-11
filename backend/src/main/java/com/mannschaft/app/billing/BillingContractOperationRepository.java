package com.mannschaft.app.billing;

import com.mannschaft.app.common.repository.AbstractTenantAwareRepository;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Billing Center PR6a: 契約操作 Saga リポジトリ（{@code billing_contract_operations}）。
 *
 * <p>{@code organization_id} NULL 許容＋{@code deleted_at} 保持のため
 * {@link AbstractTenantAwareRepository} を継承する（{@link BillingContractRepository} 前例）。</p>
 *
 * <p>このフェーズでは Repo 骨格のみ（起票・遷移・reconcile Service は別部隊）。</p>
 */
public interface BillingContractOperationRepository
        extends AbstractTenantAwareRepository<BillingContractOperationEntity, UUID> {

    /** 契約×冪等キーで取得する（{@code uk_bco_idempotency}・冪等リトライの既存行解決に使用）。 */
    Optional<BillingContractOperationEntity> findByContractIdAndIdempotencyKeyAndDeletedAtIsNull(
            UUID contractId, String idempotencyKey);

    /** 契約の操作履歴をステータス絞り込みで取得する（進行中操作の存在確認に使用）。 */
    List<BillingContractOperationEntity> findByContractIdAndStatusAndDeletedAtIsNull(
            UUID contractId, BillingOperationStatus status);

    /** 主キーで取得する（deleted_at 除外）。 */
    Optional<BillingContractOperationEntity> findByIdAndDeletedAtIsNull(UUID id);

    /**
     * 停止窓の回収（D8・AC-78/79/80/81）の走査。
     *
     * <p>{@code status ∈ {CREATED, CALLING_STRIPE} ∧ deleted_at IS NULL ∧ updated_at < :staleBefore}。
     * {@code RECONCILIATION_REQUIRED} は走査対象に含めない（pointer を保持したまま reconcile が
     * 確定させる検疫であり、回収が横から触ってはならない・AC-8）。1周の件数は {@link Pageable} で
     * 必ず上限を置き、無制限に読み込まない。</p>
     *
     * @param statuses    走査対象の status 集合
     * @param staleBefore この時刻より前に更新された行だけを拾う（半開区間・しきい値ちょうどは含まない）
     * @param pageable    1周の上限と並び順（古い順）
     * @return stale な operation
     */
    List<BillingContractOperationEntity> findByStatusInAndDeletedAtIsNullAndUpdatedAtLessThan(
            Collection<BillingOperationStatus> statuses, LocalDateTime staleBefore, Pageable pageable);

    /**
     * status を<b>条件付き更新</b>で進める（AC-82 の再入・並行防止の要）。
     *
     * <p>回収は複数プロセス・複数スレッドから同じ行へ同時に到達しうる。本メソッドの
     * <b>更新件数（0 か 1）だけ</b>を「自分が勝った」の唯一の根拠とし、勝者のみが pointer 解放と
     * 反映を行う。敗者は例外を投げずに 0 件を受け取って何もしない（例外で落ちると次周で拾えなくなる）。</p>
     *
     * <p>一括更新は {@code @PreUpdate} を経由しないため {@code updated_at} を明示的に渡す。</p>
     *
     * @param operationId 対象 operation
     * @param from        期待する現在の status（CAS の比較対象）
     * @param to          遷移先 status
     * @param step        遷移先の step
     * @param errorCode   {@code error_code} 列へ記録する値（{@code null} なら据え置き）
     * @param updatedAt   更新時刻（注入 Clock 由来）
     * @return 更新件数（1 なら自分が勝者・0 なら他が先に進めた）
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("UPDATE BillingContractOperationEntity o "
            + "SET o.status = :to, o.step = :step, "
            + "o.errorCode = COALESCE(:errorCode, o.errorCode), "
            + "o.version = o.version + 1, o.updatedAt = :updatedAt "
            + "WHERE o.id = :operationId AND o.status = :from AND o.deletedAt IS NULL")
    int compareAndSetStatus(@Param("operationId") UUID operationId,
                            @Param("from") BillingOperationStatus from,
                            @Param("to") BillingOperationStatus to,
                            @Param("step") BillingOperationStep step,
                            @Param("errorCode") String errorCode,
                            @Param("updatedAt") LocalDateTime updatedAt);
}
