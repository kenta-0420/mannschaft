package com.mannschaft.app.contact.listener;

import com.mannschaft.app.contact.event.ContactInviteUsedNotificationEvent;
import com.mannschaft.app.auth.service.UserService;
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
 * 連絡先招待リンク使用の通知配送リスナー（Issue #2834 / CMP-056 横展開）。
 *
 * <p>{@code ContactInviteTokenService#acceptInvite} の業務トランザクションが commit された後
 * （{@code AFTER_COMMIT}）に非同期（{@code event-pool}）で発火する。通知の文面組み立て
 * （アクター名解決・ロケール解決・件名/本文組み立て）も本リスナーの責務。業務行（利用回数の
 * インクリメント・連絡先追加）は AFTER_COMMIT の時点で既にコミット済みのため、ここで参照を行っても
 * 業務トランザクションを巻き込まない。実際の生成・配信は {@link NotificationDeliveryRunner#sendOne}
 * （{@code REQUIRES_NEW}）へ委譲する。</p>
 *
 * <h2>D-5: 越境アクセスは Repository ではなく Service 経由</h2>
 * <p>{@code auth} ドメインへの越境は {@code UserRepository} を直接 DI せず
 * {@link UserService#getFullName}（Service 経由）を使う（CLAUDE.md ドメイン境界の原則・
 * {@code CrossDomainRepositoryDependencyArchTest} D-5）。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ContactInviteUsedNotificationListener {

    private final NotificationDeliveryRunner notificationDeliveryRunner;
    private final UserService userService;
    private final UserLocaleCache userLocaleCache;
    private final MessageSource messageSource;

    @Async("event-pool")
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onContactInviteUsedNotification(ContactInviteUsedNotificationEvent event) {
        try {
            NotificationDeliveryRequest request = buildRequest(event);
            NotificationEntity created = notificationDeliveryRunner.sendOne(request);
            if (created == null) {
                // visibility deny（例外ではない）。NotificationService 側で既に WARN 済み。
                log.warn("招待リンク使用通知が visibility deny によりスキップされました: "
                                + "recipientUserId={}, tokenId={}",
                        request.recipientUserId(), event.tokenId());
            }
        } catch (Exception e) {
            log.error("招待リンク使用通知の配送に失敗しました: issuerId={}, tokenId={}, actorId={}",
                    event.issuerId(), event.tokenId(), event.actorId(), e);
        }
    }

    private NotificationDeliveryRequest buildRequest(ContactInviteUsedNotificationEvent event) {
        Locale locale = Locale.forLanguageTag(userLocaleCache.getLocale(event.issuerId()));
        String defaultActorName = messageSource.getMessage(
                "notification.contact.common.defaultActorName", null, "ユーザー", locale);
        String actorName = userService.getFullName(event.actorId()).orElse(defaultActorName);

        return new NotificationDeliveryRequest(
                event.issuerId(),
                "CONTACT_INVITE_USED",
                NotificationPriority.NORMAL,
                messageSource.getMessage(
                        "notification.contact.inviteUsed.title", null,
                        "招待リンクが使用されました", locale),
                messageSource.getMessage(
                        "notification.contact.inviteUsed.body",
                        new Object[]{actorName},
                        actorName + " さんが招待リンクを使用しました", locale),
                "CONTACT_INVITE_TOKEN",
                event.tokenId(),
                NotificationScopeType.PERSONAL,
                event.issuerId(),
                "/settings/contact-invite-tokens",
                event.actorId());
    }
}
