package com.mannschaft.app.payment.escrow;

import com.mannschaft.app.payment.connect.ScopeKind;
import com.mannschaft.app.payment.entity.StripeCustomerEntity;
import com.mannschaft.app.payment.escrow.event.ChargeAuthorizationFailedEvent;
import com.mannschaft.app.payment.repository.StripeCustomerRepository;
import com.mannschaft.app.recruitment.event.RecruitmentParticipantConfirmedEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.time.Duration;
import java.time.LocalDateTime;

/**
 * F22.1 統一決済 P2-b: 応募確定 → 謝礼の与信（authorize）を起動するリスナ（設計書 02 §5.1・§1 行#4）。
 *
 * <p>recruitment ドメインの {@link RecruitmentParticipantConfirmedEvent} を {@code AFTER_COMMIT}+{@code @Async}
 * で受け、{@link ConnectChargeService#authorize} を呼ぶ。外部 API ではなくイベント駆動の system 経路のため
 * {@code actorUserId=null}（認可済みフロー前提）でコマンドを組み立てる（IDOR 防止の認可は札主の明示 API
 * 経路でのみ働く・設計書 02 §1 行#4）。</p>
 *
 * <p><b>本波の範囲は与信（authorize）まで。</b> capture（払出）・返金は次Phase（P2-c）。</p>
 *
 * <p><b>第一陣 status 意味論の根治におけるリスナの扱い（2026-06-10・決定(a)・温存）:</b>
 * 確定設計では「成立＋与信を札主アクションで結合」（札主が成立時に同期でカード確認＝confirm）する。本リスナは
 * その前段として escrow 行と manual-capture PaymentIntent を<b>事前起票</b>する役割に整理した。
 * {@link ConnectChargeService#authorize} の根治により、本リスナ経由でも escrow は
 * {@link EscrowStatus#AUTHORIZED} ではなく {@link EscrowStatus#PENDING_CONFIRMATION}（PI 作成済・札主未 confirm）
 * で起票される。リスナは {@code @Async} ゆえ {@code clientSecret} を呼出元へ返せないが、これは設計上の問題ではない:
 * 札主への {@code clientSecret} 返却は<b>第二陣の同期 Controller</b> が担い（同一応募の冪等再取得で既存
 * PENDING_CONFIRMATION 行と PI を引き当て、{@code clientSecret} を再生成して札主に返す）、札主の confirm 完了は
 * {@code payment_intent.amount_capturable_updated} webhook が {@link EscrowStatus#AUTHORIZED} へ昇格させる。
 * リスナを廃止せず温存するのは、成立時点で escrow を物理化しておくことで第二陣 Controller・自動 capture バッチ・
 * webhook の各経路が「成立した応募には必ず escrow 行が存在する」前提で動けるためである（事前起票＝結合の起点）。
 * 受取側 onboarding 未完（{@code payouts_enabled=false}）の {@link EscrowStatus#HELD} 経路は不変（PI 未作成）。</p>
 *
 * <p><b>与信失敗の救済（設計書 02 §5.1 / PAYMENT_041・根治）:</b> 本リスナは {@code AFTER_COMMIT} 後ゆえ
 * 応募のロールバックは不可。{@link ConnectChargeService#authorize} が失敗した場合に例外を {@code @Async} 既定
 * ハンドラのログに埋もれさせると「観測も後続アクションも不能」になる（握り潰し・対処療法）。これを避けるため、
 * 失敗を try/catch で捕え ERROR ログを残したうえで {@link ChargeAuthorizationFailedEvent} を発火し、
 * 失敗が<b>観測可能・後続でアクション可能</b>な結節点を作る。通知本実装（応募者・札主への再試行案内）は将来の
 * リスナが本イベントを購読して行う。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RecruitmentChargeAuthorizationListener {

    /**
     * 第三陣-b「7日超 fallback」の閾値（日数・マスター裁可）。成立〜役務日がこの日数を超える謝礼は、カード与信が
     * 役務完了前に失効する（Stripe 仕様で manual-capture の与信は約7日で失効）ため、成立時に与信せず
     * {@link EscrowStatus#DEFERRED}（完了時即時払い予定）で起票する（設計書 02 §5.1）。
     */
    static final long ESCROW_MAX_HOLD_DAYS = 7L;

    private final ConnectChargeService connectChargeService;
    private final StripeCustomerRepository stripeCustomerRepository;
    private final ApplicationEventPublisher eventPublisher;

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

        // 第三陣-b「7日超 fallback」判定（マスター裁可・02 §5.1）: 成立（now）〜役務日（event.serviceDate）が
        // ESCROW_MAX_HOLD_DAYS を超えるなら、カード与信が役務完了前に失効するため成立時に与信せず DEFERRED で起票し、
        // 最終認証時に即時払いへフォールバックする。役務日不明（serviceDate=null・助っ人等で start_at 未設定）は
        // 安全側で従来 escrow（与信）に倒し、与信の失効ハンドリングは第三陣のライフサイクルバッチに委ねる。
        boolean deferred = isBeyondHoldWindow(event.serviceDate());
        if (deferred) {
            log.info("F22.1 第三陣-b: 成立〜役務日が {} 日超のため完了時即時払い（DEFERRED）で起票: "
                            + "listingId={}, participantId={}, serviceDate={}",
                    ESCROW_MAX_HOLD_DAYS, event.listingId(), event.participantId(), event.serviceDate());
        }

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
                null, // system 経路（イベント駆動）: actor 認可はスキップ（02 §1 行#4「外部API無し」）
                null, // subKey: source_kind（RECRUITMENT）の既定手数料パターンを引く
                deferred); // 7日超なら DEFERRED（完了時即時払い予定）・7日以内/役務日不明は従来与信

        // AFTER_COMMIT 後ゆえ応募はロールバックできない。与信失敗を握り潰さず、ERROR ログ＋失敗イベントで救済する
        // （02 §5.1 / PAYMENT_041・根治）。例外はここで処理し終え、@Async 既定ハンドラのログに埋もれさせない。
        try {
            AuthorizeChargeResult result = connectChargeService.authorize(cmd);
            log.info("F22.1 謝礼の与信完了: listingId={}, participantId={}, escrowId={}, status={}",
                    event.listingId(), event.participantId(), result.escrowId(), result.status());
        } catch (RuntimeException e) {
            log.error("F22.1 謝礼の与信失敗（救済イベント発火・応募はロールバック不可）: "
                            + "sourceId={}, participantId={}, payerUserId={}, reason={}",
                    event.listingId(), event.participantId(), event.payerUserId(), e.getMessage(), e);
            eventPublisher.publishEvent(new ChargeAuthorizationFailedEvent(
                    EscrowSourceKind.RECRUITMENT,
                    event.listingId(),
                    event.participantId(),
                    ScopeKind.USER,
                    event.payerUserId(),
                    e.getMessage()));
        }
    }

    /**
     * 成立（現在時刻）〜役務日が {@link #ESCROW_MAX_HOLD_DAYS} を超えるか判定する（第三陣-b 7日判定）。
     *
     * <p>{@code serviceDate} が {@code null}（役務日不明）の場合は {@code false}（安全側で従来与信）。過去日
     * （既に役務日を過ぎている）も {@code false}（即時に近く与信失効の懸念がないため従来与信で足りる）。</p>
     *
     * @param serviceDate 役務日（札の start_at・null 可）
     * @return 成立〜役務日が ESCROW_MAX_HOLD_DAYS 超なら true（DEFERRED 対象）
     */
    private boolean isBeyondHoldWindow(LocalDateTime serviceDate) {
        if (serviceDate == null) {
            return false;
        }
        LocalDateTime now = LocalDateTime.now();
        if (!serviceDate.isAfter(now)) {
            return false;
        }
        return Duration.between(now, serviceDate).toDays() > ESCROW_MAX_HOLD_DAYS;
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
