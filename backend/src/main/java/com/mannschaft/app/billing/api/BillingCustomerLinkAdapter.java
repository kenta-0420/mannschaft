package com.mannschaft.app.billing.api;

import com.mannschaft.app.billing.BillingCustomerLinkPort;
import com.mannschaft.app.billing.EntitlementScopeKind;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/**
 * {@link BillingCustomerLinkPort} の実装（{@code billing_customers} を持つ {@code billing.api} 側に置く）。
 *
 * <p>ポートの Javadoc にある実在欠陥（F20.1 決済フロー由来の契約は {@code billing_customer_id} を
 * 持たない）を、Saga の予約時に一度だけ引き上げることで解消する。</p>
 *
 * <p>引き上げ（INSERT）は {@link BillingCustomerProvisioner} の<b>独立トランザクション</b>へ委ねる。
 * 呼び出し元の tx1 に参加したまま UNIQUE 違反を起こすと tx1 が rollback-only になり、
 * 例外を捕まえて続行しても commit 時に必ず失敗するためである（詳細は委譲先の Javadoc）。</p>
 */
@Component
@RequiredArgsConstructor
class BillingCustomerLinkAdapter implements BillingCustomerLinkPort {

    private final BillingCustomerJpaRepository billingCustomerJpaRepository;
    private final BillingCustomerProvisioner billingCustomerProvisioner;

    /**
     * {@inheritDoc}
     *
     * <p>{@code MANDATORY}: 呼び出し元（Saga の tx1）で契約行が {@code FOR UPDATE} 済みであることを
     * 前提にする。読み取りと書き戻しは tx1 に属し、INSERT だけが独立 tx で行われる。</p>
     */
    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public UUID resolveOrProvision(
            EntitlementScopeKind scopeKind, Long scopeId, Long organizationId, String pspCustomerRef) {

        // uk_bcu_scope は deleted_at を含まない（＝論理削除済みでも UNIQUE を占有する）ため、
        // 存在判定も deleted_at で絞らない。絞ると「見つからないのに INSERT できない」状態になる。
        UUID existing = findByScope(scopeKind, scopeId);
        if (existing != null) {
            return existing;
        }
        try {
            return billingCustomerProvisioner.provision(
                    scopeKind, scopeId, organizationId, pspCustomerRef);
        } catch (DataIntegrityViolationException e) {
            // 並行する同一 scope の予約が先に INSERT して commit した。UNIQUE 違反は
            // 【独立トランザクションの中】で起きたので呼び出し元の tx1 は生きている。
            //
            // ★読み直しは【新しいトランザクション】で行う。tx1 のまま読むと見えない ——
            //   InnoDB は duplicate key エラーを相手の commit 後に返すが、tx1 は
            //   REPEATABLE READ で自分が始まった時点のビューを持ち続けるため、
            //   勝者の行は commit 済みなのに tx1 からは存在しない（CI 実測で判明）。
            UUID winner = billingCustomerProvisioner.findInNewTransaction(scopeKind, scopeId);
            if (winner == null) {
                // 本当に行が無い＝uk_bcu_scope 以外の整合性違反である。事実を隠さずそのまま上げる。
                throw e;
            }
            return winner;
        }
    }

    /**
     * 呼び出し元の tx1 のビューで探す（事前確認用）。
     *
     * <p>ここで見つかるのは「tx1 の読み取りビュー確立より前に commit された行」だけである。
     * 見つからなくても他トランザクションが既に作っている可能性は否定できないため、
     * INSERT が UNIQUE で弾かれた場合の読み直しは
     * {@link BillingCustomerProvisioner#findInNewTransaction} を使う。</p>
     */
    private UUID findByScope(EntitlementScopeKind scopeKind, Long scopeId) {
        return billingCustomerJpaRepository.findByScopeKindAndScopeId(scopeKind, scopeId)
                .map(BillingCustomerEntity::getId)
                .orElse(null);
    }
}
