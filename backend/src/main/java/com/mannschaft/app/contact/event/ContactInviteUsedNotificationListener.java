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
 * 招待リンク使用の通知配送リスナー（Issue #2834 / CMP-056 第1群ロットA）。
 *
 * <p>{@code ContactInviteTokenService#acceptInvite} の業務トランザクション（利用回数インクリメント・
 * 双方向連絡先追加）が commit された後（{@code AFTER_COMMIT}）に非同期（{@code event-pool}）で発火する。
 * 単一受信者のため型確立PR #2910 の {@link ContactRequestNotificationListener} と同型。</p>
 *
 * <h2>是正前の欠陥</h2>
 * <p>是正前は {@code acceptInvite} の {@code @Transactional} 内で {@code createNotification} を直接呼び、
 * {@code try/catch} で握って継続していた。{@code createNotification} は既定の {@code REQUIRED} 伝播で
 * 業務トランザクションに参加するため、通知側の DB 例外は rollback-only を残し、catch しても
 * <b>招待受諾の永続化（利用回数・連絡先追加）ごと巻き戻っていた</b>。</p>
 *
 * <h2>意図的な挙動変更: Push/WebSocket 配信が付く</h2>
 * <p>是正前の {@code ContactInviteTokenService#sendInviteUsedNotification} は
 * {@code notificationService.createNotification} を直接呼ぶだけで dispatch しておらず、
 * {@code CONTACT_INVITE_USED} は <b>DB 保存のみ</b>（Push / WebSocket 配信なし）だった。
 * 本リスナーは {@link NotificationDeliveryRunner#sendOne}（= create + dispatch）を使うため、
 * 以後この通知は <b>Push / WebSocket でも配信される</b>。これは型（#2910 の配送リスナー金型）に
 * 寄せた結果として<b>意図的に受け入れた挙動変更</b>であり、退行ではない。
 * 招待リンクが使われたことは受信者にとって即時性のある通知であり、他の連絡先系通知
 * （{@code CONTACT_REQUEST} 等）が既に dispatch 済みであることとも整合する。
 * なお SafetyCheck / Onboarding 側は是正前から {@code notificationHelper.notify}
 * （= create + dispatch）だったため、配信面では等価であり挙動は変わらない。</p>
 *
 * <h2>D-5: 越境アクセスは Repository ではなく Service 経由</h2>
 * <p>{@code auth} ドメインへの越境は {@code UserRepository} を直接 DI せず
 * {@link UserService#getFullName}（Service 経由）を使う（{@code CrossDomainRepositoryDependencyArchTest} D-5）。
 * 是正前のサービス実装は {@code userRepository.findById} で姓名を組み立てていたが、リスナー側では
 * 同等の {@code getFullName} に置き換える（表示名の組み立て規則は変わらない）。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ContactInviteUsedNotificationListener {

    private final NotificationDeliveryRunner notificationDeliveryRunner;
    private final UserService userService;
    private final UserLocaleCache userLocaleCache;
    private final MessageSource messageSource;

    @BackgroundFeaturePolicy(mode = BackgroundFeatureMode.ALWAYS,
            reason = "連絡先・メンバー招待は棚卸し台帳で beta=コア・gate_key 未発行の常時提供機能であり、招待リンク使用通知だけを止める停止条件が存在しないため常時実行する")
    @Async("event-pool")
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onContactInviteUsedNotification(ContactInviteUsedNotificationEvent event) {
        try {
            NotificationDeliveryRequest request = buildRequest(event);
            NotificationEntity created = notificationDeliveryRunner.sendOne(request);
            if (created == null) {
                // visibility deny（例外ではない）。NotificationService 側で既に WARN 済み。
                log.warn("招待リンク使用通知が visibility deny によりスキップされました: "
                                + "recipientUserId={}, notificationType={}, sourceType={}, sourceId={}",
                        request.recipientUserId(), request.notificationType(),
                        request.sourceType(), request.sourceId());
            }
        } catch (Exception e) {
            log.error("招待リンク使用通知の配送に失敗しました: issuerId={}, tokenId={}, actorId={}",
                    event.issuerId(), event.tokenId(), event.actorId(), e);
        }
    }

    /** 通知配送要求を組み立てる（業務TX外・AFTER_COMMIT 後に実行される）。 */
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
