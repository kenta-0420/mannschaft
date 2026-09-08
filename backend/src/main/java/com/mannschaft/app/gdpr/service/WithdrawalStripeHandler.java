package com.mannschaft.app.gdpr.service;

import com.mannschaft.app.auth.event.WithdrawalRequestedEvent;
import com.mannschaft.app.billing.BillingContractService;
import com.mannschaft.app.billing.BillingContractService.HandoverTargetContract;
import com.mannschaft.app.billing.BillingPayerHandoverService;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.common.backgroundgate.BackgroundFeatureMode;
import com.mannschaft.app.common.backgroundgate.BackgroundFeaturePolicy;
import com.mannschaft.app.payment.service.MembershipSubscriptionService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.util.List;

/**
 * 退会受付（Day 0・{@link WithdrawalRequestedEvent}）時の決済連携ハンドラ
 * （柱③-B・CMP-260901-1538 PR-3。設計書 {@code docs/architecture/billing_payer_handover_design.md} §1.3・§5・§6）。
 *
 * <h2>是正前の欠陥（設計書 §1.3）</h2>
 * <p>本ハンドラは {@code WithdrawalRequestedEvent} を購読していたが、実体は
 * {@code log.warn("Stripeサブスクキャンセル未実装")} だけの<b>スタブ</b>であった。{@code billing_contracts} も
 * {@code membership_subscriptions} も対象外だったため、<b>払い手が退会しても Stripe 側の課金は止まらず、
 * 30日後の強匿名化を経てもなお退会者個人の Customer へ課金が続く</b>（§1.4）。本 PR でこれを実装に置き換える。</p>
 *
 * <h2>本ハンドラが行う2系統</h2>
 * <ol>
 *   <li><b>{@code membership_subscriptions}（受益者単位の会費）</b>:
 *       {@link MembershipSubscriptionService#cancelAllForPayerOnWithdrawal} で
 *       {@code payer_user_id} 一致の ACTIVE/PAST_DUE を<b>期末解約</b>し、受益者へ予告通知する（AC-13）。
 *       受益者は支払い済みの期間を最後まで使えるべきなので即時解約はしない。</li>
 *   <li><b>{@code billing_contracts}（TEAM/ORG のプラン契約）</b>: 退会者が {@code payer_user_id} である
 *       TEAM/ORG 契約を検出し（{@link BillingContractService#findHandoverTargetContractsForPayer}）、
 *       他 ADMIN への<b>引継要求</b>を発行する（§5.1・§5.2）。ここで契約を解約してはならない——
 *       §5.4 の原則により、引継が非終端の間は purge 側の期末解約フォールバックを発火させない。
 *       猶予（14日）内に引継が成立しなければ {@code EXPIRED} となり、purge 側が期末解約に倒す（§5.3）。</li>
 * </ol>
 *
 * <h2>D-1 / D-5</h2>
 * <p>他ドメイン（payment / billing）へは<b>Service 経由</b>でのみ触れ、Repository も Entity も参照しない。
 * 是正前は {@code StripeCustomerRepository}／{@code TeamSubscriptionRepository} を直接 DI していた
 * （{@code CrossDomainRepositoryDependencyArchTest} D-5 の凍結済み違反）。本 PR でその依存を撤去する。
 * {@code TeamSubscriptionEntity} は「ガワだけ」の旧テーブル（{@code team_subscriptions}）であり、
 * 実際の継続課金は {@code membership_subscriptions} が担うため参照ごと廃止する。</p>
 *
 * <h2>失敗の扱い</h2>
 * <p>{@code AFTER_COMMIT} で動くため、本ハンドラの失敗は退会そのものを巻き戻さない。2系統は互いに独立で、
 * 片方の失敗でもう片方を落とさない。契約ごとの引継要求も件単位で捕捉する（1件の
 * {@code HANDOVER_NO_CANDIDATE} が他契約の引継を巻き添えにしない）。<b>いずれも ERROR/WARN として
 * 必ずログに残し、握りつぶさない</b>。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class WithdrawalStripeHandler {

    private final MembershipSubscriptionService membershipSubscriptionService;
    private final BillingContractService billingContractService;
    private final BillingPayerHandoverService billingPayerHandoverService;

    @BackgroundFeaturePolicy(mode = BackgroundFeatureMode.ALWAYS,
            reason = "止めると退会時に Stripe 側のサブスクリプションが解約されず、退会済み利用者へ課金が継続して決済側と DB の整合が壊れる")
    @Async("event-pool")
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void handleWithdrawal(WithdrawalRequestedEvent event) {
        Long userId = event.getUserId();

        cancelMembershipSubscriptions(userId);
        requestPayerHandovers(userId);
    }

    /** ①受益者単位の会費（{@code membership_subscriptions}）を期末解約する（AC-13）。 */
    private void cancelMembershipSubscriptions(Long userId) {
        try {
            List<String> scheduled = membershipSubscriptionService.cancelAllForPayerOnWithdrawal(userId);
            log.info("退会時の決済連携: 継続課金の期末解約を予約しました userId={}, 件数={}", userId, scheduled.size());
        } catch (Exception e) {
            log.error("退会時の決済連携: 継続課金の期末解約に失敗しました userId={}", userId, e);
        }
    }

    /** ②TEAM/ORG のプラン契約について他 ADMIN への引継要求を発行する（§5.1・§5.2）。 */
    private void requestPayerHandovers(Long userId) {
        List<HandoverTargetContract> targets;
        try {
            targets = billingContractService.findHandoverTargetContractsForPayer(userId);
        } catch (Exception e) {
            log.error("退会時の決済連携: 引継対象契約の検出に失敗しました userId={}", userId, e);
            return;
        }
        if (targets.isEmpty()) {
            log.info("退会時の決済連携: 引継対象の TEAM/ORG 契約なし userId={}", userId);
            return;
        }

        int requested = 0;
        int skipped = 0;
        for (HandoverTargetContract target : targets) {
            try {
                billingPayerHandoverService.requestHandover(
                        target.scopeKind(), target.scopeId(), target.contractId(), userId);
                requested++;
            } catch (BusinessException e) {
                // 引継先 ADMIN 不在・進行中の要求あり・契約が対象外（PAST_DUE/期末が過去）など、
                // 「その契約では引継が成立しない」業務的な結論。purge 側の期末解約フォールバックに委ねる（§5.3・§5.4）。
                skipped++;
                log.warn("退会時の決済連携: 引継要求を発行できませんでした（purge のフォールバックに委ねます）: "
                                + "userId={}, contractId={}, scope={}/{}, errorCode={}",
                        userId, target.contractId(), target.scopeKind(), target.scopeId(),
                        e.getErrorCode() != null ? e.getErrorCode().getCode() : null);
            } catch (Exception e) {
                skipped++;
                log.error("退会時の決済連携: 引継要求の発行に失敗しました userId={}, contractId={}",
                        userId, target.contractId(), e);
            }
        }
        log.info("退会時の決済連携: 引継要求 userId={}, 対象={}, 発行={}, 見送り={}",
                userId, targets.size(), requested, skipped);
    }
}
