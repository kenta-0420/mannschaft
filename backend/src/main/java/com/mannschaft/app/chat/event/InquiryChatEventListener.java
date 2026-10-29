package com.mannschaft.app.chat.event;

import com.mannschaft.app.admin.service.AdminBusinessAlertService;
import com.mannschaft.app.common.backgroundgate.BackgroundFeatureMode;
import com.mannschaft.app.common.backgroundgate.BackgroundFeaturePolicy;
import com.mannschaft.app.common.i18n.UserLocaleCache;
import com.mannschaft.app.notification.NotificationPriority;
import com.mannschaft.app.notification.NotificationScopeType;
import com.mannschaft.app.notification.entity.NotificationEntity;
import com.mannschaft.app.notification.service.NotificationDispatchService;
import com.mannschaft.app.notification.service.NotificationService;
import com.mannschaft.app.role.repository.UserRoleRepository;
import com.mannschaft.app.team.service.TeamService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.MessageSource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * F10.7 問い合わせ通知イベントリスナー。
 *
 * <p>{@link InquiryReceivedEvent} を受信し、対象チームの ADMIN / DEPUTY_ADMIN 全員へ
 * 通知を送信する。Valkey を使った重複抑制（5分以内の同一チャンネルへの連続通知は1通のみ）を実装する。</p>
 *
 * <h3>重複抑制キー設計</h3>
 * <ul>
 *   <li>{@code inquiry_notified:{channelId}} — TTL 300秒（5分）</li>
 * </ul>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class InquiryChatEventListener {

    private static final long DEDUP_TTL_SECONDS = 300L;

    private final UserRoleRepository userRoleRepository;
    private final NotificationService notificationService;
    private final StringRedisTemplate redisTemplate;
    private final AdminBusinessAlertService adminBusinessAlertService;
    private final NotificationDispatchService notificationDispatchService;
    private final MessageSource messageSource;
    private final UserLocaleCache userLocaleCache;
    /**
     * 遷移先 URL の slug 解決用（Repository は直接使わず Service 経由）。
     *
     * <p>コンストラクタ引数に足すと ArchUnit の凍結ストア（D-5 クロスドメイン Repository 依存）に
     * 記録済みのコンストラクタ署名が変わり、既存違反が「新規違反」と誤判定されるため、
     * 署名を変えないフィールド注入にしている（Repository ではなく Service なので新たな違反は生まない）。</p>
     */
    @Autowired
    private TeamService teamService;

    @BackgroundFeaturePolicy(mode = BackgroundFeatureMode.ALWAYS,
            reason = "対応する gate_key が無く停止条件を宣言できないため常時実行する。問い合わせ受信時のチャット連携。機能単位の閉栓が要るようになった時点で gate_key の発行から検討すること")
    @Async("event-pool")
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onInquiryReceived(InquiryReceivedEvent event) {
        String dedupKey = "inquiry_notified:" + event.getChannelId();
        Boolean isNew = redisTemplate.opsForValue()
                .setIfAbsent(dedupKey, "1", DEDUP_TTL_SECONDS, TimeUnit.SECONDS);
        if (!Boolean.TRUE.equals(isNew)) {
            log.debug("問い合わせ通知スキップ（重複抑制）: channelId={}", event.getChannelId());
            return; // 5分以内の重複通知をスキップ
        }

        List<Long> recipientIds = new ArrayList<>();
        recipientIds.addAll(userRoleRepository.findAdminUserIdsByTeamId(event.getTeamId()));
        recipientIds.addAll(userRoleRepository.findAllDeputyAdminUserIdsByTeamId(event.getTeamId()));
        recipientIds = recipientIds.stream().distinct().toList();

        // 遷移先の slug はループの前に 1 回だけ解決する。FE ルートは slug のみ受けるため、
        // 解決できない（不在・削除済み）ときは数値 URL を作らず通知一覧へ逃がす。
        String slug = teamService.getSlugById(event.getTeamId());
        String actionUrl = slug != null
                ? "/teams/" + slug + "/chat?channel=" + event.getChannelId()
                : "/notifications";

        // Issue #2715 CMP-055 ロットC-6: 受信者ごとに locale が異なるため、ループの外で一括解決する（N+1 防止）。
        // Codex 検分是正（PR #2873）: バルク取得自体を try で隔離し、失敗時は既定 locale ("ja") で継続する。
        Map<Long, String> locales;
        try {
            locales = userLocaleCache.getLocales(recipientIds);
        } catch (Exception e) {
            log.warn("locale 一括解決に失敗（既定 locale で継続）: channelId={}, error={}", event.getChannelId(), e.getMessage());
            locales = Map.of();
        }

        for (Long recipientId : recipientIds) {
            if (recipientId.equals(event.getActorUserId())) {
                continue;
            }
            try {
                Locale locale = Locale.forLanguageTag(locales.getOrDefault(recipientId, "ja"));
                String title = messageSource.getMessage(
                        "notification.chat.inquiryReceived.title", null,
                        "問い合わせが届きました", locale);
                String body = messageSource.getMessage(
                        "notification.chat.inquiryReceived.body",
                        new Object[]{event.getSenderDisplayName(), event.getChannelName()},
                        event.getSenderDisplayName() + "から「" + event.getChannelName() + "」に問い合わせが届きました",
                        locale);
                // sourceType=CHAT_MESSAGE の実体はメッセージ ID。従来はチャンネル ID を渡しており、
                // F00 ChatMessageVisibilityResolver が対象を解決できず全受信者で visibility deny になっていた。
                NotificationEntity created = notificationService.createNotification(
                        recipientId,
                        "INQUIRY_RECEIVED",
                        NotificationPriority.HIGH,
                        title,
                        body,
                        "CHAT_MESSAGE",
                        event.getMessageId(),
                        NotificationScopeType.TEAM,
                        event.getTeamId(),
                        actionUrl,
                        event.getActorUserId()
                );
                adminBusinessAlertService.invalidateCache(recipientId);
                // DB 作成した通知を WS/Push でリアルタイム配信する（他の通知経路と同一パターン）。
                // visibility deny 等で通知が作られなかった場合 (null) は配信しない。
                if (created != null) {
                    notificationDispatchService.dispatch(created);
                }
            } catch (Exception e) {
                log.warn("問い合わせ通知の送信に失敗しました: recipientId={}, channelId={}", recipientId, event.getChannelId(), e);
            }
        }

        log.info("問い合わせ通知送信完了: channelId={}, teamId={}, recipientCount={}", event.getChannelId(), event.getTeamId(), recipientIds.size());
    }
}
