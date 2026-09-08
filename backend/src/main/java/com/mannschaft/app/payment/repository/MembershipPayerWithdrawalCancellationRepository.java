package com.mannschaft.app.payment.repository;

import com.mannschaft.app.payment.entity.MembershipPayerWithdrawalCancellationEntity;
import com.mannschaft.app.payment.entity.MembershipPayerWithdrawalCancellationStatus;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * 柱③-B（CMP-260901-1538）PR-3: 払い手退会に伴う期末解約の処理状態リポジトリ。
 *
 * <p>設計書 {@code docs/architecture/billing_payer_handover_design.md} §6.1。
 * 履歴表であり {@code organization_id} を持たないため {@code AbstractTenantAwareRepository} は継承しない
 * （{@code BillingPayerHandoverRequestRepository} と同型の判断）。</p>
 */
public interface MembershipPayerWithdrawalCancellationRepository
        extends JpaRepository<MembershipPayerWithdrawalCancellationEntity, UUID> {

    /** サブスク ID で引く（1サブスクにつき1行・{@code uk_mpwc_subscription}）。 */
    Optional<MembershipPayerWithdrawalCancellationEntity> findBySubscriptionId(UUID subscriptionId);

    /**
     * サブスク ID で引き、行を {@code PESSIMISTIC_WRITE} ロックして取得する。
     *
     * <p>同一 payer の退会イベントが再送・並走した場合に、同じサブスクの処理状態を二重に進めないため。</p>
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT c FROM MembershipPayerWithdrawalCancellationEntity c WHERE c.subscriptionId = :subscriptionId")
    Optional<MembershipPayerWithdrawalCancellationEntity> findBySubscriptionIdForUpdate(
            @Param("subscriptionId") UUID subscriptionId);

    /**
     * 退会取消時の復旧対象（{@code idx_mpwc_payer_status} で引く）。
     *
     * <p>「退会処理由来で予約が成立し、まだ復旧していない」行だけを返す。
     * 本人が退会前に明示解約した契約はそもそもこの表に行を持たないため、復活対象にならない
     * （Codex 検分1巡目 P1-3 の核心）。</p>
     */
    List<MembershipPayerWithdrawalCancellationEntity> findByPayerUserIdAndStatusAndRestoredAtIsNull(
            Long payerUserId, MembershipPayerWithdrawalCancellationStatus status);

    /**
     * 再試行対象（PR-4 の夜次バッチが使う・{@code idx_mpwc_retry} で引く）。
     *
     * <p>本 PR では駆動そのものは実装しない（リリース依存として PR-4 に委ねる）。ただし
     * <b>状態と検索経路は本 PR で用意する</b>——状態が無ければ PR-4 でも拾いようがないため。</p>
     */
    List<MembershipPayerWithdrawalCancellationEntity> findByStatusInAndRestoredAtIsNullOrderByUpdatedAtAsc(
            List<MembershipPayerWithdrawalCancellationStatus> statuses);
}
