package com.mannschaft.app.payment.escrow;

import com.mannschaft.app.payment.connect.ScopeKind;
import com.mannschaft.app.payment.entity.StripeCustomerEntity;
import com.mannschaft.app.payment.repository.StripeCustomerRepository;
import com.mannschaft.app.recruitment.event.RecruitmentParticipantConfirmedEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * F22.1 統一決済 P2-b: 応募確定 → 謝礼の与信（authorize）を起動するリスナ（設計書 02 §5.1・§1 行#4）。
 *
 * <p>recruitment ドメインの {@link RecruitmentParticipantConfirmedEvent} を {@code AFTER_COMMIT}+{@code @Async}
 * で受け、{@link ConnectChargeService#authorize} を呼ぶ。外部 API ではなくイベント駆動の system 経路のため
 * {@code actorUserId=null}（認可済みフロー前提）でコマンドを組み立てる（IDOR 防止の認可は札主の明示 API
 * 経路でのみ働く・設計書 02 §1 行#4）。</p>
 *
 * <p><b>本波の範囲は与信（authorize）まで。</b> capture（払出）・返金は次Phase（P2-c）。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RecruitmentChargeAuthorizationListener {

    private final ConnectChargeService connectChargeService;
    private final StripeCustomerRepository stripeCustomerRepository;

    /**
     * 応募確定イベントを受けて謝礼の与信を開始する。
     *
     * @param event 応募確定イベント
     */
    @Async("event-pool")
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onParticipantConfirmed(RecruitmentParticipantConfirmedEvent event) {
        ScopeKind payeeKind = parsePayeeKind(event.payeeKind());
        if (payeeKind == null) {
            log.warn("F22.1 与信スキップ: payeeKind 不正/未設定: listingId={}, payeeKind={}",
                    event.listingId(), event.payeeKind());
            return;
        }

        Long payeeScopeId = switch (payeeKind) {
            case USER -> event.payeeUserId();
            case TEAM, ORG -> event.listingScopeId();
        };
        if (payeeScopeId == null) {
            log.warn("F22.1 与信スキップ: payeeScopeId が解決できません: listingId={}, payeeKind={}",
                    event.listingId(), payeeKind);
            return;
        }

        // テナント列（organization_id）は ORG 受領時のみ確定できる（TEAM の所属 org はここでは未解決）。
        Long organizationId = payeeKind == ScopeKind.ORG ? payeeScopeId : null;

        // 支払者（応募者）の Stripe Customer を解決（無ければ HELD 経路では PI を作らないため null 許容）。
        String payerStripeCustomerId = stripeCustomerRepository.findByUserId(event.payerUserId())
                .map(StripeCustomerEntity::getStripeCustomerId)
                .orElse(null);

        AuthorizeChargeCommand cmd = new AuthorizeChargeCommand(
                EscrowSourceKind.RECRUITMENT,
                event.listingId(),
                event.participantId(),
                ScopeKind.USER,
                event.payerUserId(),
                payerStripeCustomerId,
                payeeKind,
                payeeScopeId,
                event.faceAmount(),
                "JPY",
                organizationId,
                null); // system 経路（イベント駆動）: actor 認可はスキップ（02 §1 行#4「外部API無し」）

        AuthorizeChargeResult result = connectChargeService.authorize(cmd);
        log.info("F22.1 謝礼の与信完了: listingId={}, participantId={}, escrowId={}, status={}",
                event.listingId(), event.participantId(), result.escrowId(), result.status());
    }

    private ScopeKind parsePayeeKind(String payeeKind) {
        if (payeeKind == null) {
            return null;
        }
        try {
            return ScopeKind.valueOf(payeeKind);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
