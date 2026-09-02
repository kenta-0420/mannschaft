package com.mannschaft.app.recruitment.event;

import com.mannschaft.app.common.backgroundgate.BackgroundFeatureMode;
import com.mannschaft.app.common.backgroundgate.BackgroundFeaturePolicy;
import com.mannschaft.app.common.i18n.UserLocaleCache;
import com.mannschaft.app.notification.NotificationPriority;
import com.mannschaft.app.notification.NotificationScopeType;
import com.mannschaft.app.notification.service.NotificationDeliveryRequest;
import com.mannschaft.app.notification.service.NotificationDeliveryResult;
import com.mannschaft.app.notification.service.NotificationDeliveryRunner;
import com.mannschaft.app.recruitment.entity.RecruitmentListingEntity;
import com.mannschaft.app.recruitment.repository.RecruitmentListingRepository;
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
 * F03.11 募集取下げ通知の配送リスナー（Issue #2990 / L2 ROLLBACK_COUPLED 是正）。
 *
 * <h2>是正前の欠陥 — 何が巻き戻っていたか</h2>
 * <p>是正前は {@code RecruitmentListingService#cancelInternal} が業務トランザクションの内側で
 * {@code sendCancelledNotifications} を呼び、その中の {@code NotificationHelper#notifyAllLocalized}
 * （非バルク経路 = 受信者ごとに {@code createNotification} を既定の {@code REQUIRED} 伝播で実行）が
 * <b>業務トランザクションに参加</b>していた。通知側の DB 例外は rollback-only を残すため、
 * <b>募集の CANCELLED 化・参加者の一括キャンセル・参加者履歴の書き込みまでまとめて巻き戻っていた</b>。
 * 個人札（パーソナルマーケット）の取下げ {@code PersonalMarketListingService#cancel} も
 * {@code cancelPersonalListing} 経由で同じ経路を通るため、同じ欠陥を共有していた。</p>
 *
 * <h2>是正後</h2>
 * <p>業務トランザクションは {@link RecruitmentCancelledNotificationEvent} を publish するだけに留め、
 * 本リスナーが {@code AFTER_COMMIT} + {@code @Async("event-pool")} で受け取り、受信者ごとに
 * {@link NotificationDeliveryRunner#sendOne}（1 件 = 1 独立トランザクション）へ委譲する。</p>
 *
 * <h2>停止時挙動に {@code ALWAYS} を選んだ理由</h2>
 * <p>本イベントの発火元は 2 経路ある。チーム／組織の募集取下げ（{@code FEATURE_RECRUITMENT_ENABLED}）と
 * 個人札の取下げ（{@code FEATURE_MARKET_ENABLED}）である。<b>上流が単一の gate_key で閉じない</b>ため
 * {@code DROP_WHEN_DISABLED} は選べない（{@link BackgroundFeatureMode#DROP_WHEN_DISABLED} 第三の型）。</p>
 *
 * <h2>挙動の同一性</h2>
 * <p>是正前の {@code notifyAllLocalized}（非バルク経路）は受信者ごとに
 * {@code createNotification} + {@code dispatch} を行っていた。{@link NotificationDeliveryRunner#sendOne}
 * も create + dispatch であり、可視性ガード（{@code createNotification} 内）も同じく効くため、
 * 配信面の挙動は変わらない。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RecruitmentCancelledNotificationListener {

    private final NotificationDeliveryRunner notificationDeliveryRunner;
    private final RecruitmentListingRepository listingRepository;
    private final UserLocaleCache userLocaleCache;
    private final MessageSource messageSource;

    @BackgroundFeaturePolicy(mode = BackgroundFeatureMode.ALWAYS,
            reason = "募集の取下げ通知は上流が単一 gate_key で閉じない（チーム/組織募集は FEATURE_RECRUITMENT_ENABLED、"
                    + "個人札は FEATURE_MARKET_ENABLED）ため DROP は選べない。落とすと参加者は自分の参加が "
                    + "CANCELLED にされた事実を知らされず、支払済みの謝礼やスケジュールの前提が黙って消える。"
                    + "イベントは再生されず取りこぼしは復旧不能であるため常時実行する")
    @Async("event-pool")
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onRecruitmentCancelledNotification(RecruitmentCancelledNotificationEvent event) {
        List<Long> recipients = event.recipientUserIds();
        if (event.listingId() == null || recipients == null || recipients.isEmpty()) {
            return;
        }

        // 業務本文（タイトル・理由・スコープ・実行者）の読み直し。失敗は握りつぶさず配送を中止する。
        RecruitmentListingEntity listing;
        try {
            listing = listingRepository.findById(event.listingId()).orElse(null);
        } catch (Exception e) {
            log.error("募集取下げ通知の募集読み直しに失敗しました（配送中止）: listingId={}, recipientCount={}",
                    event.listingId(), recipients.size(), e);
            return;
        }
        if (listing == null) {
            log.error("募集取下げ通知の募集が読み直し時点で存在しません（配送中止）: listingId={}, recipientCount={}",
                    event.listingId(), recipients.size());
            return;
        }

        // locale の一括解決は失敗しても既定 locale で継続できるため配送は止めない。
        Map<Long, String> locales;
        try {
            locales = userLocaleCache.getLocales(recipients);
        } catch (Exception e) {
            log.warn("募集取下げ通知の locale 一括解決に失敗（既定 locale で継続）: listingId={}, error={}",
                    event.listingId(), e.getMessage());
            locales = Map.of();
        }

        for (Long recipientUserId : recipients) {
            try {
                // 組み立ても受信者単位で内側 try に入れる（1人ぶんの失敗が他を巻き添えにしない）。
                Locale locale = Locale.forLanguageTag(locales.getOrDefault(recipientUserId, "ja"));
                NotificationDeliveryRequest request = buildRequest(listing, recipientUserId, locale);
                NotificationDeliveryResult result = notificationDeliveryRunner.sendOne(request);
                if (result == NotificationDeliveryResult.VISIBILITY_DENIED) {
                    // visibility deny（例外ではない）。NotificationService 側で既に WARN 済み。
                    log.warn("募集取下げ通知が visibility deny によりスキップされました: "
                            + "recipientUserId={}, listingId={}", recipientUserId, listing.getId());
                }
            } catch (Exception e) {
                // 非同期イベント失敗の監査記録（規約上必須）。catch は業務TX外なので rollback で消えない。
                log.error("募集取下げ通知の配送に失敗しました（この受信者はスキップ）: "
                        + "recipientUserId={}, listingId={}", recipientUserId, listing.getId(), e);
            }
        }
    }

    /** 通知配送要求を組み立てる（業務TX外・AFTER_COMMIT 後に実行される）。 */
    private NotificationDeliveryRequest buildRequest(
            RecruitmentListingEntity listing, Long recipientUserId, Locale locale) {
        NotificationScopeType scopeType = switch (listing.getScopeType()) {
            case PERSONAL -> NotificationScopeType.PERSONAL;
            case TEAM -> NotificationScopeType.TEAM;
            case ORGANIZATION -> NotificationScopeType.ORGANIZATION;
        };
        String reason = listing.getCancelledReason() != null ? listing.getCancelledReason() : "-";
        return new NotificationDeliveryRequest(
                recipientUserId,
                "RECRUITMENT_CANCELLED",
                NotificationPriority.NORMAL,
                messageSource.getMessage(
                        "notification.recruitment.cancelled.title", null,
                        "募集が取り下げられました", locale),
                messageSource.getMessage(
                        "notification.recruitment.cancelled.body",
                        new Object[]{listing.getTitle(), reason},
                        listing.getTitle() + " は主催者により取り下げられました。", locale),
                "RECRUITMENT_LISTING",
                listing.getId(),
                scopeType,
                listing.getScopeId(),
                "/market",
                listing.getCancelledBy());
    }
}
