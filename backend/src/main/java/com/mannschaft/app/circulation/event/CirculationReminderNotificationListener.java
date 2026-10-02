package com.mannschaft.app.circulation.event;

import com.mannschaft.app.circulation.entity.CirculationDocumentEntity;
import com.mannschaft.app.circulation.repository.CirculationDocumentRepository;
import com.mannschaft.app.common.backgroundgate.BackgroundFeatureMode;
import com.mannschaft.app.common.backgroundgate.BackgroundFeaturePolicy;
import com.mannschaft.app.common.i18n.UserLocaleCache;
import com.mannschaft.app.notification.NotificationPriority;
import com.mannschaft.app.notification.NotificationScopeType;
import com.mannschaft.app.notification.service.NotificationDeliveryRequest;
import com.mannschaft.app.notification.service.NotificationDeliveryResult;
import com.mannschaft.app.notification.service.NotificationDeliveryRunner;
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
 * 回覧手動リマインドの通知配送リスナー（Issue #2834 / CMP-056 第1群ロットB）。
 *
 * <p>{@code CirculationService#remindDocument} の業務トランザクションが commit された後
 * （{@code AFTER_COMMIT}）に非同期（{@code event-pool}）で発火する。<b>複数受信者</b>の金型として
 * ロットA の {@code OnboardingReminderNotificationListener} と同型
 * （受信者リストの解決は全体で1回・外側 try、受信者ごとに組み立て＋配送を内側 try で隔離）。</p>
 *
 * <h2>是正前の欠陥</h2>
 * <p>是正前は {@code remindDocument} の {@code @Transactional} 内で受信者をループし、
 * {@code notificationService.createNotification} の失敗を1件ずつ catch して継続していた。
 * {@code createNotification} は既定の {@code REQUIRED} 伝播で業務トランザクションに参加するため、
 * 1 受信者の DB 例外が rollback-only を残し、catch して続行した<b>他受信者の通知もコミット時に
 * まとめて消えていた</b>（是正前のコメント自身が「本処理の巻き戻りは防がない」と自認していた）。</p>
 *
 * <h2>意図的な挙動変更: Push/WebSocket 配信が付く</h2>
 * <p>是正前は {@code notificationService.createNotification} を直接呼ぶだけで dispatch しておらず、
 * {@code CIRCULATION_REMINDER} は <b>DB 保存のみ</b>（Push / WebSocket 配信なし）だった。本リスナーは
 * {@link NotificationDeliveryRunner#sendOne}（= create + dispatch）を使うため、以後この通知は
 * <b>Push / WebSocket でも配信される</b>。ロットA の {@code ContactInviteUsedNotificationListener} と
 * 同じく、型に寄せた結果として<b>意図的に受け入れた挙動変更</b>であり退行ではない
 * （督促は受信者にとって即時性のある通知であり、他の回覧系通知が既に dispatch 済みであることとも整合する）。</p>
 *
 * <h2>削除済み source を参照しないことの確認</h2>
 * <p>{@code sourceType=CIRCULATION_DOCUMENT} は {@code NotificationSourceTypeMapper} にマップ済みで
 * visibility ガードの対象になるが、督促（{@code remindDocument}）は文書行を削除も状態遷移もしないため、
 * {@code AFTER_COMMIT} の時点でも {@code ACTIVE} のまま生存している。よってコミット後発火による
 * 「静かな deny」は発生しない。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class CirculationReminderNotificationListener {

    private final NotificationDeliveryRunner notificationDeliveryRunner;
    private final CirculationDocumentRepository documentRepository;
    private final UserLocaleCache userLocaleCache;
    private final MessageSource messageSource;

    @BackgroundFeaturePolicy(mode = BackgroundFeatureMode.ALWAYS,
            reason = "回覧は棚卸し台帳で beta=コア・gate_key 未発行の常時提供機能であり、督促通知だけを止める停止条件が存在しないため常時実行する")
    @Async("event-pool")
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onCirculationReminderNotification(CirculationReminderNotificationEvent event) {
        if (event.recipientUserIds() == null || event.recipientUserIds().isEmpty()) {
            return;
        }

        // 受信者リスト共通の前処理（文書の再解決）は全体で1回。ここが失敗したら誰にも送れない。
        CirculationDocumentEntity document;
        try {
            document = documentRepository.findById(event.documentId()).orElse(null);
        } catch (Exception e) {
            log.error("回覧リマインド通知の文書解決に失敗しました: documentId={}, actorId={}",
                    event.documentId(), event.actorId(), e);
            return;
        }
        if (document == null) {
            log.error("回覧リマインド通知の文書解決に失敗しました（文書が見つかりません）: documentId={}, actorId={}",
                    event.documentId(), event.actorId());
            return;
        }

        // locale は一括解決（N+1 防止）。解決自体の失敗は既定 locale で継続する（ロットA と同様）。
        Map<Long, String> locales;
        try {
            locales = userLocaleCache.getLocales(event.recipientUserIds());
        } catch (Exception e) {
            log.warn("回覧リマインドの locale 一括解決に失敗（既定 locale で継続）: documentId={}, error={}",
                    event.documentId(), e.getMessage());
            locales = Map.of();
        }

        NotificationScopeType notifScope = scopeTypeToNotificationScope(document.getScopeType());

        int denied = 0;
        int failed = 0;
        Long firstFailedUserId = null;
        for (Long recipientUserId : event.recipientUserIds()) {
            try {
                // 組み立ても受信者単位で内側 try に入れる（1人ぶんの組み立て失敗が他を巻き添えにしない）。
                NotificationDeliveryRequest request = buildRequest(
                        recipientUserId, document, notifScope, event.actorId(),
                        Locale.forLanguageTag(locales.getOrDefault(recipientUserId, "ja")));
                NotificationDeliveryResult result = notificationDeliveryRunner.sendOne(request);
                if (result == NotificationDeliveryResult.VISIBILITY_DENIED) {
                    // visibility deny（例外ではない）。NotificationService 側で既に WARN 済み。
                    denied++;
                    log.warn("回覧リマインド通知が visibility deny によりスキップされました: "
                                    + "recipientUserId={}, notificationType={}, sourceType={}, sourceId={}",
                            request.recipientUserId(), request.notificationType(),
                            request.sourceType(), request.sourceId());
                }
            } catch (Exception e) {
                failed++;
                if (firstFailedUserId == null) {
                    firstFailedUserId = recipientUserId;
                }
                log.error("回覧リマインド通知の配送に失敗しました: recipientUserId={}, documentId={}, actorId={}",
                        recipientUserId, event.documentId(), event.actorId(), e);
            }
        }

        // 集計ログのレベルは個別ログと揃える。deny は正常系なので WARN、例外が1件でもあれば ERROR。
        if (failed > 0 || denied > 0) {
            String summary = "回覧リマインド一括配送の結果: documentId={}, total={}, failed={}, denied={}, "
                    + "firstFailedUserId={}";
            if (failed > 0) {
                log.error(summary, event.documentId(), event.recipientUserIds().size(),
                        failed, denied, firstFailedUserId);
            } else {
                log.warn(summary, event.documentId(), event.recipientUserIds().size(),
                        failed, denied, firstFailedUserId);
            }
        }
    }

    /** 通知配送要求を組み立てる（業務TX外・AFTER_COMMIT 後に実行される）。 */
    private NotificationDeliveryRequest buildRequest(Long recipientUserId, CirculationDocumentEntity document,
                                                    NotificationScopeType notifScope, Long actorId, Locale locale) {
        return new NotificationDeliveryRequest(
                recipientUserId,
                "CIRCULATION_REMINDER",
                NotificationPriority.NORMAL,
                messageSource.getMessage(
                        "notification.circulation.reminder.title", null,
                        "回覧の未確認があります", locale),
                messageSource.getMessage(
                        "notification.circulation.reminder.body",
                        new Object[]{document.getTitle()},
                        "「" + document.getTitle() + "」の押印をお願いします。", locale),
                "CIRCULATION_DOCUMENT",
                document.getId(),
                notifScope,
                document.getScopeId(),
                "/circulations/" + document.getId(),
                actorId);
    }

    /** scope_type 文字列を {@link NotificationScopeType} に変換する（是正前の分岐をそのまま移送）。 */
    private NotificationScopeType scopeTypeToNotificationScope(String scopeType) {
        if ("TEAM".equals(scopeType)) {
            return NotificationScopeType.TEAM;
        }
        if ("ORGANIZATION".equals(scopeType)) {
            return NotificationScopeType.ORGANIZATION;
        }
        return NotificationScopeType.PERSONAL;
    }
}
