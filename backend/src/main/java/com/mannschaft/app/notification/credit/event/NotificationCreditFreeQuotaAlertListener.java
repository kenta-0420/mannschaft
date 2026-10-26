package com.mannschaft.app.notification.credit.event;

import com.mannschaft.app.common.backgroundgate.BackgroundFeatureMode;
import com.mannschaft.app.common.backgroundgate.BackgroundFeaturePolicy;
import com.mannschaft.app.common.i18n.UserLocaleCache;
import com.mannschaft.app.notification.NotificationPriority;
import com.mannschaft.app.notification.NotificationScopeType;
import com.mannschaft.app.notification.service.NotificationDeliveryRequest;
import com.mannschaft.app.notification.service.NotificationDeliveryResult;
import com.mannschaft.app.notification.service.NotificationDeliveryRunner;
import com.mannschaft.app.role.service.RoleService;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.MessageSource;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * F09.13 無料通知枠 90% 超過アラートの配送リスナー（Issue #2990 / L2 ROLLBACK_COUPLED 是正）。
 *
 * <h2>是正前の欠陥 — 何が巻き戻っていたか</h2>
 * <p>是正前は {@code NotificationCreditService#sendFreeQuotaAlertAsync} が
 * {@code @Async} 付きの {@code protected} メソッドで、同一クラスの {@code consume} から
 * <b>無修飾の自己呼び出し</b>で呼ばれていた。自己呼び出しは Spring のプロキシを経由しないため
 * {@code @Async} は失効し、<b>アラート送信は {@code consume} の業務トランザクション内で同期実行</b>
 * されていた。{@code NotificationHelper#notifyAllLocalized} が内部で呼ぶ
 * {@code createNotification} は既定の {@code REQUIRED} 伝播で同じトランザクションに参加するため、
 * 通知側の DB 例外は rollback-only を残し、メソッド内の {@code try/catch} で握っても
 * commit 時に {@code UnexpectedRollbackException} となって
 * <b>呼び出し元の業務処理ごと巻き戻っていた</b>。
 * 実害の入口は {@code DirectMailService#sendMail}（一斉メール送信）であり、
 * 「無料枠が 9,000 通に達した回の一斉メールだけが、送信記録ごと消える」という形で現れる。</p>
 *
 * <h2>是正後</h2>
 * <p>{@code consume} は {@link NotificationCreditFreeQuotaAlertEvent} を publish するだけに留め、
 * 本リスナーが {@code AFTER_COMMIT} + {@code @Async("event-pool")} で受け取り、受信者ごとに
 * {@link NotificationDeliveryRunner#sendOne}（1 件 = 1 独立トランザクション）へ委譲する。
 * 業務トランザクションは既に確定しており、通知の失敗はもう業務を巻き戻さない。</p>
 *
 * <h2>D-5: 越境アクセスは Repository ではなく Service 経由</h2>
 * <p>受信者（組織 ADMIN）の解決は {@code role} ドメインへの越境であるため、
 * {@code UserRoleRepository} を直接 DI せず {@link RoleService#getAdminUserIdsByOrganizationId}
 * を使う（{@code CrossDomainRepositoryDependencyArchTest} D-5）。</p>
 *
 * <h2>挙動の同一性</h2>
 * <p>是正前は {@code NotificationHelper#notifyAllLocalized}（= {@code createNotification} +
 * {@code dispatch} を受信者ごと）だった。{@link NotificationDeliveryRunner#sendOne} も
 * create + dispatch であるため、Push / WebSocket 配信の有無は<b>変わらない</b>。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class NotificationCreditFreeQuotaAlertListener {

    private final NotificationDeliveryRunner notificationDeliveryRunner;
    private final RoleService roleService;
    private final UserLocaleCache userLocaleCache;
    private final MessageSource messageSource;

    @BackgroundFeaturePolicy(mode = BackgroundFeatureMode.ALWAYS,
            reason = "通知クレジットの無料枠超過アラートは課金整合に直結する。止めると組織 ADMIN は無料枠を使い切ったことを知らぬまま超過分をクレジットから引かれ続け、猶予期間 72 時間の起点も気付かぬまま過ぎて送信が突然ブロックされる。alert_sent_this_month は月1回しか立たないため取りこぼしは再送されず、閉栓中に落とすと当月ぶんは永久に失われる")
    @Async("event-pool")
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onNotificationCreditFreeQuotaAlert(NotificationCreditFreeQuotaAlertEvent event) {
        Long organizationId = event.organizationId();
        if (organizationId == null) {
            return;
        }

        // 受信者リストの解決は全体で1回。失敗すると誰にも送れないため外側の try に置き、配送を中止する。
        List<Long> adminUserIds;
        try {
            adminUserIds = roleService.getAdminUserIdsByOrganizationId(organizationId);
        } catch (Exception e) {
            log.error("無料通知枠アラートの受信者（組織ADMIN）解決に失敗しました（配送中止）: organizationId={}",
                    organizationId, e);
            return;
        }
        if (adminUserIds == null || adminUserIds.isEmpty()) {
            return;
        }

        // locale の一括解決は失敗しても既定 locale で継続できるため配送は止めない。
        Map<Long, String> locales;
        try {
            locales = userLocaleCache.getLocales(adminUserIds);
        } catch (Exception e) {
            log.warn("無料通知枠アラートの locale 一括解決に失敗（既定 locale で継続）: organizationId={}, error={}",
                    organizationId, e.getMessage());
            locales = Map.of();
        }

        for (Long recipientUserId : adminUserIds) {
            try {
                // 組み立ても受信者単位で内側 try に入れる（1人ぶんの失敗が他を巻き添えにしない）。
                Locale locale = Locale.forLanguageTag(locales.getOrDefault(recipientUserId, "ja"));
                NotificationDeliveryRequest request = buildRequest(organizationId, recipientUserId, locale);
                NotificationDeliveryResult result = notificationDeliveryRunner.sendOne(request);
                if (result == NotificationDeliveryResult.VISIBILITY_DENIED) {
                    // visibility deny（例外ではない）。NotificationService 側で既に WARN 済み。
                    log.warn("無料通知枠アラートが visibility deny によりスキップされました: "
                                    + "recipientUserId={}, organizationId={}",
                            recipientUserId, organizationId);
                }
            } catch (Exception e) {
                // 非同期イベント失敗の監査記録（規約上必須）。catch は業務TX外なので rollback で消えない。
                log.error("無料通知枠アラートの配送に失敗しました（この受信者はスキップ）: "
                                + "recipientUserId={}, organizationId={}",
                        recipientUserId, organizationId, e);
            }
        }
    }

    /** 通知配送要求を組み立てる（業務TX外・AFTER_COMMIT 後に実行される）。 */
    private NotificationDeliveryRequest buildRequest(Long organizationId, Long recipientUserId, Locale locale) {
        return new NotificationDeliveryRequest(
                recipientUserId,
                "NOTIFICATION_CREDIT_ALERT",
                NotificationPriority.NORMAL,
                messageSource.getMessage(
                        "notification.credit.freeQuotaAlert.title", null,
                        "無料通知枠が残りわずかです", locale),
                messageSource.getMessage(
                        "notification.credit.freeQuotaAlert.body", null,
                        "今月の無料通知枠（10,000通）の90%を使用しました。超過分はクレジットから消費されます。", locale),
                "NOTIFICATION_CREDIT",
                organizationId,
                NotificationScopeType.ORGANIZATION,
                organizationId,
                "/organizations/" + organizationId + "/settings/notification-credits",
                null);
    }
}
