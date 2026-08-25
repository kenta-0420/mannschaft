package com.mannschaft.app.payment.event;

import com.mannschaft.app.common.i18n.UserLocaleCache;
import com.mannschaft.app.notification.NotificationPriority;
import com.mannschaft.app.notification.NotificationScopeType;
import com.mannschaft.app.notification.entity.NotificationEntity;
import com.mannschaft.app.notification.service.NotificationDeliveryRequest;
import com.mannschaft.app.notification.service.NotificationDeliveryRunner;
import com.mannschaft.app.role.service.RoleService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.MessageSource;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 立替金の精算確定通知の配送リスナー（Issue #2834 / CMP-056 第1群ロットB）。
 *
 * <p>{@code TeamPaymentAdvanceService#confirmSettlement} の業務トランザクション（SETTLED 化）が
 * commit された後（{@code AFTER_COMMIT}）に非同期（{@code event-pool}）で発火する。<b>複数受信者</b>
 * （協会 ADMIN 全員）の金型としてロットA の {@code OnboardingReminderNotificationListener} と同型。</p>
 *
 * <h2>是正前の欠陥</h2>
 * <p>是正前は {@code confirmSettlement} の {@code @Transactional} 内で
 * {@code notificationHelper.notifyAll} を呼び、{@code BusinessException} だけを catch していた。
 * だが {@code notifyAll} は受信者の可視性フィルタ（DB 参照）と {@code createNotification}
 * （既定の {@code REQUIRED} 伝播）を業務トランザクションの中で実行するため、DB 例外は
 * rollback-only を立て、<b>精算確定（SETTLED 化）と監査ログごと巻き戻っていた</b>。
 * 「精算確定そのものは確定済み」という是正前コメントの前提が成立していなかった。</p>
 *
 * <h2>意図的な挙動変更: locale の解決元</h2>
 * <p>是正前は {@code LocaleContextHolder.getLocale()}（＝<b>操作者のリクエストスレッドの locale</b>）
 * で全受信者ぶんの文面を組み立てていた。本リスナーは業務TX外・別スレッド（{@code event-pool}）で
 * 動くため {@code LocaleContextHolder} は使えず、また使うべきでもない。他のロットと同様
 * {@link UserLocaleCache#getLocales} で<b>受信者ごとの locale</b>をバルク解決する。
 * 受信者本人の言語で届くようになるため、これは是正であり退行ではない。</p>
 *
 * <h2>配信面の等価性</h2>
 * <p>是正前の {@code notificationHelper.notifyAll} も create + dispatch であり、
 * {@link NotificationDeliveryRunner#sendOne} への置換で Push/WebSocket の有無は変わらない。</p>
 *
 * <h2>D-5: 越境アクセスは Repository ではなく Service 経由</h2>
 * <p>協会 ADMIN の解決は {@code role} ドメインの {@code UserRoleRepository} を直接 DI せず
 * {@link RoleService#getAdminUserIdsByOrganizationId} を使う
 * （{@code CrossDomainRepositoryDependencyArchTest} D-5）。</p>
 *
 * <h2>削除済み source を参照しないことの確認</h2>
 * <p>{@code sourceType=PAYMENT_ADVANCE} は {@code NotificationSourceTypeMapper} に未登録であり
 * fail-soft で visibility ガードの対象外（{@code sourceId} も是正前から {@code null}）。
 * 精算確定は立替行を削除しないため、いずれにせよコミット後の「静かな deny」は起きない。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PaymentAdvanceSettledNotificationListener {

    /** 精算確認通知の通知種別（是正前の {@code TeamPaymentAdvanceService} 定数と同値）。 */
    private static final String SETTLEMENT_NOTIFICATION_TYPE = "PAYMENT_ADVANCE_SETTLED";

    /** 精算確認通知の sourceType（F00 visibility マッパー非対応＝fail-soft で素通り）。 */
    private static final String SETTLEMENT_NOTIFICATION_SOURCE_TYPE = "PAYMENT_ADVANCE";

    private final NotificationDeliveryRunner notificationDeliveryRunner;
    private final RoleService roleService;
    private final UserLocaleCache userLocaleCache;
    private final MessageSource messageSource;

    @Async("event-pool")
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onPaymentAdvanceSettledNotification(PaymentAdvanceSettledNotificationEvent event) {
        if (event.organizationId() == null) {
            return;
        }

        // 受信者リストの解決は全体で1回。ここが失敗したら誰にも送れない。
        List<Long> orgAdmins;
        try {
            orgAdmins = roleService.getAdminUserIdsByOrganizationId(event.organizationId());
        } catch (Exception e) {
            log.error("精算確認通知の受信者（協会 ADMIN）解決に失敗しました: advanceId={}, orgId={}",
                    event.advanceId(), event.organizationId(), e);
            return;
        }
        if (orgAdmins == null || orgAdmins.isEmpty()) {
            log.debug("精算確認通知: 協会 ADMIN 不在のためスキップ orgId={}", event.organizationId());
            return;
        }

        // locale は一括解決（N+1 防止）。解決自体の失敗は既定 locale で継続する。
        Map<Long, String> locales;
        try {
            locales = userLocaleCache.getLocales(orgAdmins);
        } catch (Exception e) {
            log.warn("精算確認通知の locale 一括解決に失敗（既定 locale で継続）: advanceId={}, error={}",
                    event.advanceId(), e.getMessage());
            locales = Map.of();
        }

        int denied = 0;
        int failed = 0;
        Long firstFailedUserId = null;
        for (Long adminUserId : orgAdmins) {
            try {
                // 組み立ても受信者単位で内側 try に入れる。
                NotificationDeliveryRequest request = buildRequest(
                        adminUserId, event,
                        Locale.forLanguageTag(locales.getOrDefault(adminUserId, "ja")));
                NotificationEntity created = notificationDeliveryRunner.sendOne(request);
                if (created == null) {
                    // visibility deny（例外ではない）。NotificationService 側で既に WARN 済み。
                    denied++;
                    log.warn("精算確認通知が visibility deny によりスキップされました: "
                                    + "recipientUserId={}, notificationType={}, sourceType={}, sourceId={}",
                            request.recipientUserId(), request.notificationType(),
                            request.sourceType(), request.sourceId());
                }
            } catch (Exception e) {
                failed++;
                if (firstFailedUserId == null) {
                    firstFailedUserId = adminUserId;
                }
                log.error("精算確認通知の配送に失敗しました: recipientUserId={}, advanceId={}, orgId={}",
                        adminUserId, event.advanceId(), event.organizationId(), e);
            }
        }

        // 集計ログのレベルは個別ログと揃える。deny は正常系なので WARN、例外が1件でもあれば ERROR。
        if (failed > 0 || denied > 0) {
            String summary = "精算確認通知一括配送の結果: advanceId={}, orgId={}, total={}, failed={}, denied={}, "
                    + "firstFailedUserId={}";
            if (failed > 0) {
                log.error(summary, event.advanceId(), event.organizationId(), orgAdmins.size(),
                        failed, denied, firstFailedUserId);
            } else {
                log.warn(summary, event.advanceId(), event.organizationId(), orgAdmins.size(),
                        failed, denied, firstFailedUserId);
            }
        }
    }

    /** 通知配送要求を組み立てる（業務TX外・AFTER_COMMIT 後に実行される）。 */
    private NotificationDeliveryRequest buildRequest(Long recipientUserId,
                                                    PaymentAdvanceSettledNotificationEvent event, Locale locale) {
        return new NotificationDeliveryRequest(
                recipientUserId,
                SETTLEMENT_NOTIFICATION_TYPE,
                NotificationPriority.NORMAL,
                messageSource.getMessage(
                        "notification.payment_advance.settled.title", null,
                        "立替金の精算が確認されました", locale),
                messageSource.getMessage(
                        "notification.payment_advance.settled.body",
                        new Object[]{event.advancedAmount(), event.currency()},
                        event.advancedAmount() + " " + event.currency() + " の立替金が精算済みになりました。",
                        locale),
                SETTLEMENT_NOTIFICATION_SOURCE_TYPE,
                null,
                NotificationScopeType.ORGANIZATION,
                event.organizationId(),
                "/organizations/" + event.organizationId() + "/payment-requests",
                event.actorUserId());
    }
}
