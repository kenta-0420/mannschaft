package com.mannschaft.app.contact.event;

import com.mannschaft.app.auth.service.UserService;
import com.mannschaft.app.common.backgroundgate.BackgroundFeatureMode;
import com.mannschaft.app.common.backgroundgate.BackgroundFeaturePolicy;
import com.mannschaft.app.common.i18n.UserLocaleCache;
import com.mannschaft.app.notification.NotificationPriority;
import com.mannschaft.app.notification.NotificationScopeType;
import com.mannschaft.app.notification.entity.NotificationEntity;
import com.mannschaft.app.notification.service.NotificationDeliveryRequest;
import com.mannschaft.app.notification.service.NotificationDeliveryRunner;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.MessageSource;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.util.Locale;

/**
 * 連絡先申請の通知配送リスナー（Issue #2834 / CMP-056 型確立PR）。
 *
 * <p>{@code ContactRequestService} の業務トランザクションが commit された後（{@code AFTER_COMMIT}）に
 * 非同期（{@code event-pool}）で発火する。<b>通知の文面組み立て（アクター名解決・ロケール解決・
 * 件名/本文組み立て）も本リスナーの責務</b>（Codex 検分 [P2] 是正・{@link ContactRequestNotificationEvent}
 * javadoc 参照）。業務行は AFTER_COMMIT の時点で既にコミット済みのため、ここで {@code UserService}
 * 経由の参照を行っても業務トランザクションを巻き込まない。実際の生成・配信は
 * {@link NotificationDeliveryRunner#sendOne}（{@code REQUIRES_NEW}）へ委譲する。</p>
 *
 * <h2>D-5: 越境アクセスは Repository ではなく Service 経由</h2>
 * <p>{@code auth} ドメインへの越境は {@code UserRepository} を直接 DI せず
 * {@link UserService#getFullName}（Service 経由）を使う（CLAUDE.md ドメイン境界の原則・
 * {@code CrossDomainRepositoryDependencyArchTest} D-5）。</p>
 *
 * <h2>境界: 例外・deny のログはこの非TXリスナーで書く</h2>
 * <p>組み立て（{@code UserService} 参照 / {@code getLocale} / {@code getMessage}）も配送
 * （{@code sendOne}）も
 * {@code try/catch} で囲むのは本リスナー（トランザクション境界の<b>外</b>）であり、Runner 内部
 * （{@code REQUIRES_NEW} トランザクションの<b>内側</b>）で catch しない。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ContactRequestNotificationListener {

    private final NotificationDeliveryRunner notificationDeliveryRunner;
    /** Issue #2834 / CMP-056 検分対応（D-5）: 越境アクセスは Repository 直接ではなく Service 経由。 */
    private final UserService userService;
    private final UserLocaleCache userLocaleCache;
    private final MessageSource messageSource;

    @BackgroundFeaturePolicy(mode = BackgroundFeatureMode.ALWAYS,
            reason = "対応する gate_key が無く停止条件を宣言できないため常時実行する。コンタクト申請の通知。機能単位の閉栓が要るようになった時点で gate_key の発行から検討すること")
    @Async("event-pool")
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onContactRequestNotification(ContactRequestNotificationEvent event) {
        try {
            NotificationDeliveryRequest request = buildRequest(event);
            NotificationEntity created = notificationDeliveryRunner.sendOne(request);
            if (created == null) {
                // visibility deny（例外ではない）。NotificationService 側で既に WARN 済み。
                log.warn("連絡先申請通知が visibility deny によりスキップされました: "
                                + "recipientUserId={}, notificationType={}, sourceType={}, sourceId={}",
                        request.recipientUserId(), request.notificationType(),
                        request.sourceType(), request.sourceId());
            }
        } catch (Exception e) {
            log.error("連絡先申請通知の配送に失敗しました: kind={}, targetId={}, requestId={}, actorId={}",
                    event.kind(), event.targetId(), event.requestId(), event.actorId(), e);
        }
    }

    /**
     * 通知配送要求を組み立てる（Codex検分[P2]是正: 業務TX外・AFTER_COMMIT後に実行される）。
     *
     * <p>{@code userService.getFullName} が空（並行削除等）を返した場合は、元実装と同じくデフォルト表示名
     * にフォールバックする（フォールバック自体は維持）。</p>
     */
    private NotificationDeliveryRequest buildRequest(ContactRequestNotificationEvent event) {
        Locale locale = Locale.forLanguageTag(userLocaleCache.getLocale(event.targetId()));
        String defaultName = messageSource.getMessage(
                "notification.contact.common.defaultActorName", null, "ユーザー", locale);
        String actorName = userService.getFullName(event.actorId()).orElse(defaultName);

        return switch (event.kind()) {
            case REQUEST_RECEIVED -> new NotificationDeliveryRequest(
                    event.targetId(),
                    "CONTACT_REQUEST_RECEIVED",
                    NotificationPriority.NORMAL,
                    messageSource.getMessage(
                            "notification.contact.requestReceived.title", null, "連絡先申請", locale),
                    messageSource.getMessage(
                            "notification.contact.requestReceived.body",
                            new Object[]{actorName},
                            actorName + " さんから連絡先申請が届きました", locale),
                    "CONTACT_REQUEST",
                    event.requestId(),
                    NotificationScopeType.PERSONAL,
                    event.targetId(),
                    "/settings/contact-requests",
                    event.actorId());
            case REQUEST_ACCEPTED -> new NotificationDeliveryRequest(
                    event.targetId(),
                    "CONTACT_REQUEST_ACCEPTED",
                    NotificationPriority.NORMAL,
                    messageSource.getMessage(
                            "notification.contact.requestAccepted.title", null,
                            "連絡先申請が承認されました", locale),
                    messageSource.getMessage(
                            "notification.contact.requestAccepted.body",
                            new Object[]{actorName},
                            actorName + " さんが連絡先申請を承認しました", locale),
                    "CONTACT_REQUEST",
                    event.requestId(),
                    NotificationScopeType.PERSONAL,
                    event.targetId(),
                    "/chat",
                    event.actorId());
        };
    }
}
