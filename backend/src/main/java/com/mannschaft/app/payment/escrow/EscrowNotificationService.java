package com.mannschaft.app.payment.escrow;

import com.mannschaft.app.notification.NotificationPriority;
import com.mannschaft.app.notification.NotificationScopeType;
import com.mannschaft.app.notification.service.NotificationService;
import com.mannschaft.app.payment.connect.ConnectAccountEntity;
import com.mannschaft.app.payment.connect.ConnectAccountRepository;
import com.mannschaft.app.payment.connect.ScopeKind;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

/**
 * F22.1 謝礼決済 第三陣: escrow ライフサイクル通知の集約サービス。
 *
 * <p>未確認放置の自動取消・HELD 昇格に伴う札主（支払者）／応じ手（受取者）への通知を、既存の
 * 汎用通知基盤（{@link NotificationService#createNotification}）に委譲して送る。新規通知基盤は作らない
 * （CLAUDE.md i18n／既存サービス流用方針）。文面は {@code messages*.properties}（{@code notification.escrow.*}）の
 * MessageSource キーで管理し、UI 文字列を直書きしない。</p>
 *
 * <h3>受信者の解決（ドメイン境界）</h3>
 * <p>escrow ドメインは team/org のメンバー構成を知らない。札主は常に {@code payer_scope_kind=USER}
 * （{@code payer_scope_id}＝userId）であり確実に通知できる。応じ手（payee）は USER/TEAM/ORG いずれもありうるが、
 * payee が USER の場合のみ {@code connect_accounts.scope_id}＝userId を宛先にできる。TEAM/ORG の payee は
 * メンバー宛配信を escrow ドメインから行うと境界を越えるため、本陣では payee=USER のみ直接通知し、TEAM/ORG は
 * 通知をスキップする（情報ログを残し握りつぶさない）。札主通知は常に行うため、取消/昇格の事実は札主側で必ず可視化される。</p>
 *
 * <p>設計書: docs/features/F22.1_market/payment/02_api_design.md §5.2</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class EscrowNotificationService {

    /** 通知 sourceType（{@code NotificationEntity.sourceType}）。 */
    static final String SOURCE_TYPE_ESCROW = "ESCROW";

    /** 通知種別: 未確認放置による謝礼取消（札主・応じ手向け）。 */
    static final String TYPE_ESCROW_CANCELLED = "ESCROW_CANCELLED";

    /** 通知種別: HELD 昇格による札主への決済確認依頼。 */
    static final String TYPE_ESCROW_PAYMENT_REQUIRED = "ESCROW_PAYMENT_REQUIRED";

    /** 札主の決済確認画面（HELD 昇格後の confirm 導線・03 §1）。{@code %d}＝escrow ID は UUID のため %s。 */
    static final String PAYMENT_CONFIRM_PATH = "/payment/escrow/%s/confirm";

    private final NotificationService notificationService;
    private final ConnectAccountRepository connectAccountRepository;

    /**
     * 未確認放置（PENDING_CONFIRMATION 期限超過 / HELD・AUTHORIZED 失効）で謝礼が取り消されたことを
     * 札主と（payee=USER の場合）応じ手へ通知する（設計書 02 §5.2）。
     *
     * <p>title/body は呼び出し側（{@link EscrowLifecycleService}）が i18n 解決済みで渡す。取消理由
     * （onboarding 未完了 等）は症状を隠さず本文へ含める（CLAUDE.md 根治原則）。</p>
     *
     * @param escrow 取消済み escrow（CANCELLED 確定後）
     * @param title  通知タイトル（i18n 解決済み）
     * @param body   通知本文（i18n 解決済み・取消理由を含む）
     */
    @Transactional
    public void notifyCancelled(EscrowTransactionEntity escrow, String title, String body) {
        // 札主（常に USER）へ通知。
        notifyUser(escrow.getPayerScopeId(), TYPE_ESCROW_CANCELLED, NotificationPriority.HIGH,
                title, body, escrow);

        // 応じ手（payee）が USER の場合のみ直接通知（TEAM/ORG はメンバー配信が境界越えのため本陣ではスキップ）。
        Long payeeUserId = resolvePayeeUserId(escrow);
        if (payeeUserId != null && !payeeUserId.equals(escrow.getPayerScopeId())) {
            notifyUser(payeeUserId, TYPE_ESCROW_CANCELLED, NotificationPriority.HIGH, title, body, escrow);
        } else if (payeeUserId == null) {
            log.info("escrow 取消通知: payee が TEAM/ORG のため応じ手への直接通知はスキップ（札主には通知済み）: escrowId={}",
                    escrow.getId());
        }
    }

    /**
     * HELD 昇格（受取口座登録完了で PI を作成し PENDING_CONFIRMATION 化）に伴い、札主へ決済確認を依頼する
     * （設計書 02 §5.2）。actionUrl は札主の決済確認画面（03 §1）。
     *
     * @param escrow 昇格後 escrow（PENDING_CONFIRMATION・PI 作成済）
     * @param title  通知タイトル（i18n 解決済み）
     * @param body   通知本文（i18n 解決済み）
     */
    @Transactional
    public void notifyPaymentRequired(EscrowTransactionEntity escrow, String title, String body) {
        String actionUrl = String.format(PAYMENT_CONFIRM_PATH, escrow.getId());
        notificationService.createNotification(
                escrow.getPayerScopeId(),
                TYPE_ESCROW_PAYMENT_REQUIRED,
                NotificationPriority.HIGH,
                title,
                body,
                SOURCE_TYPE_ESCROW,
                null, // sourceId は escrow の UUID（Long でないため null・sourceType+actionUrl で特定可能）
                NotificationScopeType.PERSONAL,
                escrow.getPayerScopeId(),
                actionUrl,
                null,
                escrow.getOrganizationId());
    }

    private void notifyUser(Long userId, String type, NotificationPriority priority,
                            String title, String body, EscrowTransactionEntity escrow) {
        if (userId == null) {
            return;
        }
        notificationService.createNotification(
                userId,
                type,
                priority,
                title,
                body,
                SOURCE_TYPE_ESCROW,
                null,
                NotificationScopeType.PERSONAL,
                userId,
                null,
                null,
                escrow.getOrganizationId());
    }

    /**
     * payee が USER の場合の宛先 userId（{@code connect_accounts.scope_id}）を解決する。TEAM/ORG は null。
     */
    private Long resolvePayeeUserId(EscrowTransactionEntity escrow) {
        if (escrow.getPayeeKind() != ScopeKind.USER) {
            return null;
        }
        Optional<ConnectAccountEntity> payee =
                connectAccountRepository.findById(escrow.getPayeeConnectAccountId());
        return payee.map(ConnectAccountEntity::getScopeId).orElse(null);
    }
}
