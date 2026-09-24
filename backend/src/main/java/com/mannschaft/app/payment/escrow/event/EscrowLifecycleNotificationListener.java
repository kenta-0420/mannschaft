package com.mannschaft.app.payment.escrow.event;

import com.mannschaft.app.common.backgroundgate.BackgroundFeatureMode;
import com.mannschaft.app.common.backgroundgate.BackgroundFeaturePolicy;
import com.mannschaft.app.common.i18n.UserLocaleCache;
import com.mannschaft.app.notification.NotificationPriority;
import com.mannschaft.app.notification.NotificationScopeType;
import com.mannschaft.app.notification.service.NotificationDeliveryRequest;
import com.mannschaft.app.notification.service.NotificationDeliveryResult;
import com.mannschaft.app.notification.service.NotificationDeliveryRunner;
import com.mannschaft.app.payment.connect.ConnectAccountEntity;
import com.mannschaft.app.payment.connect.ConnectAccountRepository;
import com.mannschaft.app.payment.connect.ScopeKind;
import com.mannschaft.app.payment.escrow.EscrowTransactionEntity;
import com.mannschaft.app.payment.escrow.EscrowTransactionRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.MessageSource;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

/**
 * escrow ライフサイクル通知の配送リスナー（Issue #2990 L7）。
 *
 * <p>{@code EscrowLifecycleService} の業務トランザクション（Stripe 呼び出し ＋ {@code escrow_transactions} の
 * 状態遷移）が commit された<b>後</b>に、非同期（{@code event-pool}）で通知を配送する。</p>
 *
 * <h2>是正前の欠陥: 通知1件の失敗で「Stripe は動いたのに DB は巻き戻る」</h2>
 * <p>是正前は {@code EscrowLifecycleService}（{@code REQUIRES_NEW}）の内側から
 * {@code EscrowNotificationService}（{@code @Transactional}・既定の {@code REQUIRED} で参加）を直接呼び、
 * その先で {@code NotificationService#createNotification} が {@code notifications} へ INSERT していた。
 * INSERT が失敗すると例外は業務トランザクションへそのまま伝播し、次が巻き戻る:</p>
 * <ul>
 *   <li><b>取消経路</b>: Stripe 側では {@code PaymentIntent.cancel} が<b>既に成功している</b>のに、
 *       {@code escrow_transactions.status} は PENDING_CONFIRMATION / HELD / AUTHORIZED のまま戻り、
 *       {@code cancelled_at} も消える。以後バッチは取消済みの与信を毎回取消対象として拾い続け、
 *       DB は「まだ与信が生きている」と主張し続ける（Stripe と台帳の乖離）。</li>
 *   <li><b>HELD 昇格経路</b>: Stripe 側で manual-capture の PaymentIntent が<b>作成済み</b>なのに、
 *       {@code stripe_payment_intent_id} と PENDING_CONFIRMATION 遷移が巻き戻り、
 *       作成した PI の ID を台帳が失う（＝孤児 PI）。札主は confirm 画面へ辿り着けない。</li>
 * </ul>
 *
 * <h2>Issue #2990 L3（PR #3073）との関係</h2>
 * <p>L3 は {@code ConnectAccountService} から {@code promoteHeldEscrow} を呼ぶ<b>位置</b>を
 * {@link com.mannschaft.app.payment.connect.event.ConnectHeldEscrowPromotionListener}
 * （{@code AFTER_COMMIT}）へ移し、{@code payouts_enabled} の読み直しが新値を見るようにした。
 * しかし通知呼び出しそのものは {@code promoteHeldEscrow} の {@code REQUIRES_NEW} の<b>内側</b>に
 * 残ったままであり、上記の巻き戻りは L3 のあとも生きていた。本リスナーがそこを断つ。</p>
 *
 * <h2>受信者の解決（ドメイン境界・是正前の方針を維持）</h2>
 * <p>札主は常に {@code payer_scope_kind=USER} のため確実に通知できる。応じ手（payee）は
 * {@code payee_kind=USER} のときだけ {@code connect_accounts.scope_id} を宛先にできる。
 * TEAM/ORG のメンバー宛配信は escrow ドメインから行うと境界を越えるため、是正前と同じく
 * 情報ログを残してスキップする（握りつぶさない）。</p>
 *
 * <h2>locale の解決を LocaleContextHolder から受信者ごとへ改めた理由</h2>
 * <p>是正前は {@code LocaleContextHolder.getLocale()} で文面を解決していたが、発火元はバッチと
 * Webhook スレッドであり、そこにリクエスト locale は存在しない（＝実質つねに既定 locale）。
 * 本リスナーでは受信者ごとに {@link UserLocaleCache} で解決する。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class EscrowLifecycleNotificationListener {

    /** 通知 sourceType（是正前の {@code EscrowNotificationService} と同値）。 */
    static final String SOURCE_TYPE_ESCROW = "ESCROW";

    /** 通知種別: 謝礼の取消（札主・応じ手向け）。 */
    static final String TYPE_ESCROW_CANCELLED = "ESCROW_CANCELLED";

    /** 通知種別: HELD 昇格による札主への決済確認依頼。 */
    static final String TYPE_ESCROW_PAYMENT_REQUIRED = "ESCROW_PAYMENT_REQUIRED";

    /** 札主の決済確認画面（HELD 昇格後の confirm 導線・03 §1）。 */
    static final String PAYMENT_CONFIRM_PATH = "/payment/escrow/%s/confirm";

    /** 通知タイトル i18n キー（取消）。 */
    static final String MSG_CANCELLED_TITLE = "notification.escrow.cancelled.title";

    /** 通知本文 i18n キー（札主未 confirm 放置による取消）。 */
    static final String MSG_CANCELLED_PENDING_BODY = "notification.escrow.cancelled.pending.body";

    /** 通知本文 i18n キー（受取口座未登録＝HELD 失効による取消）。 */
    static final String MSG_CANCELLED_HELD_BODY = "notification.escrow.cancelled.held.body";

    /** 通知本文 i18n キー（与信失効による取消）。 */
    static final String MSG_CANCELLED_AUTHORIZED_BODY = "notification.escrow.cancelled.authorized.body";

    /** 通知タイトル i18n キー（HELD 昇格＝決済確認依頼）。 */
    static final String MSG_PAYMENT_REQUIRED_TITLE = "notification.escrow.payment_required.title";

    /** 通知本文 i18n キー（HELD 昇格＝決済確認依頼）。 */
    static final String MSG_PAYMENT_REQUIRED_BODY = "notification.escrow.payment_required.body";

    private final EscrowTransactionRepository escrowTransactionRepository;
    private final ConnectAccountRepository connectAccountRepository;
    private final NotificationDeliveryRunner notificationDeliveryRunner;
    private final UserLocaleCache userLocaleCache;
    private final MessageSource messageSource;

    /** 謝礼取消の通知（札主 ＋ payee=USER の応じ手）。 */
    @BackgroundFeaturePolicy(mode = BackgroundFeatureMode.ALWAYS,
            reason = "与信取消は Stripe 側で既に確定した金銭事象であり、札主に請求が発生しないことを伝える"
                    + "通知を止めると身に覚えのない与信枠の増減として現れるため常時実行する")
    @Async("event-pool")
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onEscrowCancelled(EscrowCancelledEvent event) {
        EscrowTransactionEntity escrow = reload(event.escrowId(), "取消");
        if (escrow == null) {
            return;
        }

        // 受信者の解決は全体で1回（外側）。ここで失敗したら誰にも送れないため配送を中止する。
        List<Long> recipients;
        try {
            recipients = resolveCancelRecipients(escrow);
        } catch (Exception e) {
            log.error("escrow 取消通知の受信者解決に失敗しました（配送中止）: escrowId={}", event.escrowId(), e);
            return;
        }

        String bodyKey = switch (event.reason()) {
            case PENDING_CONFIRMATION_EXPIRED -> MSG_CANCELLED_PENDING_BODY;
            case HELD_EXPIRED -> MSG_CANCELLED_HELD_BODY;
            // 募集取下げ由来の取消は是正前と同じ本文（AUTHORIZED 失効の文面）を使う。
            case AUTHORIZATION_EXPIRED, RECRUITMENT_CANCELLED -> MSG_CANCELLED_AUTHORIZED_BODY;
        };

        for (Long recipientUserId : recipients) {
            try {
                Locale locale = localeOf(recipientUserId);
                NotificationDeliveryRequest request = new NotificationDeliveryRequest(
                        recipientUserId,
                        TYPE_ESCROW_CANCELLED,
                        NotificationPriority.HIGH,
                        messageSource.getMessage(MSG_CANCELLED_TITLE, null, MSG_CANCELLED_TITLE, locale),
                        messageSource.getMessage(bodyKey, null, bodyKey, locale),
                        SOURCE_TYPE_ESCROW,
                        null, // sourceId は escrow の UUID（Long でないため null・是正前と同じ）
                        NotificationScopeType.PERSONAL,
                        recipientUserId,
                        null,
                        null);
                if (notificationDeliveryRunner.sendOne(request) == NotificationDeliveryResult.VISIBILITY_DENIED) {
                    log.warn("escrow 取消通知が visibility deny によりスキップされました: escrowId={}, recipientUserId={}",
                            event.escrowId(), recipientUserId);
                }
            } catch (Exception e) {
                log.error("escrow 取消通知の配送に失敗しました（他の受信者は継続）: escrowId={}, recipientUserId={}",
                        event.escrowId(), recipientUserId, e);
            }
        }
    }

    /** HELD 昇格に伴う札主への決済確認依頼。 */
    @BackgroundFeaturePolicy(mode = BackgroundFeatureMode.ALWAYS,
            reason = "HELD 昇格後の謝礼は札主の confirm が無ければ与信が失効して取消されるため、"
                    + "この依頼通知を止めると謝礼そのものが成立しなくなる")
    @Async("event-pool")
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onEscrowPaymentRequired(EscrowPaymentRequiredEvent event) {
        EscrowTransactionEntity escrow = reload(event.escrowId(), "決済確認依頼");
        if (escrow == null) {
            return;
        }
        Long payerUserId = escrow.getPayerScopeId();
        if (payerUserId == null) {
            log.error("escrow 決済確認依頼通知: 札主が解決できません（配送中止）: escrowId={}", event.escrowId());
            return;
        }
        try {
            Locale locale = localeOf(payerUserId);
            NotificationDeliveryRequest request = new NotificationDeliveryRequest(
                    payerUserId,
                    TYPE_ESCROW_PAYMENT_REQUIRED,
                    NotificationPriority.HIGH,
                    messageSource.getMessage(
                            MSG_PAYMENT_REQUIRED_TITLE, null, MSG_PAYMENT_REQUIRED_TITLE, locale),
                    messageSource.getMessage(
                            MSG_PAYMENT_REQUIRED_BODY, null, MSG_PAYMENT_REQUIRED_BODY, locale),
                    SOURCE_TYPE_ESCROW,
                    null,
                    NotificationScopeType.PERSONAL,
                    payerUserId,
                    String.format(PAYMENT_CONFIRM_PATH, escrow.getId()),
                    null);
            if (notificationDeliveryRunner.sendOne(request) == NotificationDeliveryResult.VISIBILITY_DENIED) {
                log.warn("escrow 決済確認依頼通知が visibility deny によりスキップされました: escrowId={}, payerUserId={}",
                        event.escrowId(), payerUserId);
            }
        } catch (Exception e) {
            log.error("escrow 決済確認依頼通知の配送に失敗しました: escrowId={}, payerUserId={}",
                    event.escrowId(), payerUserId, e);
        }
    }

    /**
     * escrow を読み直す。文面に要る値（受信者・状態）は業務データのためイベントに載せない。
     * 読み直しの失敗・不在は握りつぶさず ERROR ログを残して配送を中止する。
     */
    private EscrowTransactionEntity reload(UUID escrowId, String label) {
        try {
            Optional<EscrowTransactionEntity> found = escrowTransactionRepository.findById(escrowId);
            if (found.isEmpty()) {
                log.error("escrow {}通知: 対象 escrow を読み直せません（配送中止）: escrowId={}", label, escrowId);
                return null;
            }
            return found.get();
        } catch (Exception e) {
            log.error("escrow {}通知: escrow の読み直しに失敗しました（配送中止）: escrowId={}", label, escrowId, e);
            return null;
        }
    }

    /** 札主（常に USER）と、payee=USER のときだけ応じ手を宛先に含める。 */
    private List<Long> resolveCancelRecipients(EscrowTransactionEntity escrow) {
        List<Long> recipients = new ArrayList<>();
        if (escrow.getPayerScopeId() != null) {
            recipients.add(escrow.getPayerScopeId());
        }
        Long payeeUserId = resolvePayeeUserId(escrow);
        if (payeeUserId == null) {
            log.info("escrow 取消通知: payee が TEAM/ORG のため応じ手への直接通知はスキップ（札主には通知する）: escrowId={}",
                    escrow.getId());
        } else if (!recipients.contains(payeeUserId)) {
            recipients.add(payeeUserId);
        }
        return recipients;
    }

    /** payee が USER の場合の宛先 userId（{@code connect_accounts.scope_id}）。TEAM/ORG は null。 */
    private Long resolvePayeeUserId(EscrowTransactionEntity escrow) {
        if (escrow.getPayeeKind() != ScopeKind.USER) {
            return null;
        }
        return connectAccountRepository.findById(escrow.getPayeeConnectAccountId())
                .map(ConnectAccountEntity::getScopeId)
                .orElse(null);
    }

    /** 受信者の locale。解決に失敗しても既定（ja）で配送を続ける（文面のためだけの値）。 */
    private Locale localeOf(Long userId) {
        try {
            return Locale.forLanguageTag(userLocaleCache.getLocale(userId));
        } catch (Exception e) {
            log.warn("escrow 通知の locale 解決に失敗（既定 ja で継続）: userId={}, error={}", userId, e.getMessage());
            return Locale.forLanguageTag("ja");
        }
    }
}
