package com.mannschaft.app.recruitment.event;

import com.mannschaft.app.common.backgroundgate.BackgroundFeatureMode;
import com.mannschaft.app.common.backgroundgate.BackgroundFeaturePolicy;
import com.mannschaft.app.common.i18n.UserLocaleCache;
import com.mannschaft.app.notification.NotificationPriority;
import com.mannschaft.app.notification.NotificationScopeType;
import com.mannschaft.app.notification.service.NotificationDeliveryRequest;
import com.mannschaft.app.notification.service.NotificationDeliveryResult;
import com.mannschaft.app.notification.service.NotificationDeliveryRunner;
import com.mannschaft.app.recruitment.RecruitmentScopeType;
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
 * 募集の公開・申込確定・フレンド宛公開の通知配送リスナー（Issue #2997 / CMP-260827-1152 第1陣 G2）。
 *
 * <h2>是正前の欠陥</h2>
 * <p>{@code RecruitmentListingService#publish} / {@code #confirmApplication} は業務トランザクションの内側で
 * {@code NotificationHelper#notifyAllLocalized} / {@code #notify}（受信者ごとの {@code createNotification} を
 * 既定の {@code REQUIRED} 伝播で実行）を同期で呼んでいた。そのため (1) 業務TXがロールバックする場合でも
 * 通知が先に作られ、(2) 通知側の DB 例外が rollback-only を残して募集の公開・参加者の確定まで
 * 巻き戻しうる。フレンド宛の {@code MarketFriendTargetService#distributeFriendListing} も同様。</p>
 *
 * <h2>是正後</h2>
 * <p>業務TXは受信者を確定して {@link RecruitmentNotificationEvent} を publish するだけに留め、本リスナーが
 * {@code AFTER_COMMIT} + {@code @Async("event-pool")} で受け取り、受信者ごとに
 * {@link NotificationDeliveryRunner#sendOne}（1 件 = 1 独立トランザクション）へ委譲する。
 * 1 受信者の失敗は他の受信者・業務データへ波及しない。</p>
 *
 * <h2>挙動の同一性</h2>
 * <p>受信者集合（配信対象 MEMBERS/SUPPORTERS/FOLLOWERS、フレンドチームの ADMIN、応募者本人）の解決規則と
 * 文面は是正前と同一。是正前の {@code notifyAllLocalized} は先に可視性で絞り込んだうえで受信者ごとに
 * {@code createNotification} + {@code dispatch} を行っていた。{@code sendOne} も create + dispatch で、
 * 可視性ガードは {@code createNotification} 内で同様に効く（deny は例外でなく
 * {@code VISIBILITY_DENIED} で返る）ため配送面の意味論は変わらない。</p>
 *
 * <h2>停止時挙動に {@code ALWAYS} を選んだ理由</h2>
 * <p>発火元は、チーム／組織の募集（{@code FEATURE_RECRUITMENT_ENABLED}）とフレンド宛の札
 * （{@code FEATURE_MARKET_ENABLED}）の 2 系統あり、上流が単一の gate_key で閉じない。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RecruitmentNotificationListener {

    private final NotificationDeliveryRunner notificationDeliveryRunner;
    private final RecruitmentListingRepository listingRepository;
    private final UserLocaleCache userLocaleCache;
    private final MessageSource messageSource;

    @BackgroundFeaturePolicy(mode = BackgroundFeatureMode.ALWAYS,
            reason = "募集の公開・申込確定・フレンド宛公開の通知は上流が単一 gate_key で閉じない"
                    + "（チーム/組織募集は FEATURE_RECRUITMENT_ENABLED、フレンド宛の札は FEATURE_MARKET_ENABLED）ため "
                    + "DROP は選べない。落とすと対象者は公開・確定の事実を知らされず、イベントは再生されず"
                    + "取りこぼしは復旧不能であるため常時実行する")
    @Async("event-pool")
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onRecruitmentNotification(RecruitmentNotificationEvent event) {
        List<Long> recipients = event.recipientUserIds();
        if (event.listingId() == null || recipients == null || recipients.isEmpty()) {
            return;
        }

        // 業務本文（タイトル・スコープ・作成者）の読み直し。失敗は握りつぶさず配送を中止して ERROR に残す。
        RecruitmentListingEntity listing;
        try {
            listing = listingRepository.findById(event.listingId()).orElse(null);
        } catch (Exception e) {
            log.error("募集通知の募集読み直しに失敗しました（配送中止）: kind={}, listingId={}, recipientCount={}",
                    event.kind(), event.listingId(), recipients.size(), e);
            return;
        }
        if (listing == null) {
            log.error("募集通知の募集が読み直し時点で存在しません（配送中止）: kind={}, listingId={}, recipientCount={}",
                    event.kind(), event.listingId(), recipients.size());
            return;
        }

        // locale の一括解決は失敗しても既定 locale で継続できるため配送は止めない。
        Map<Long, String> locales;
        try {
            locales = userLocaleCache.getLocales(recipients);
        } catch (Exception e) {
            log.warn("募集通知の locale 一括解決に失敗（既定 locale で継続）: kind={}, listingId={}, error={}",
                    event.kind(), event.listingId(), e.getMessage());
            locales = Map.of();
        }

        for (Long recipientUserId : recipients) {
            try {
                // 組み立ても受信者単位の try に入れる（1 人ぶんの失敗が他を巻き添えにしない）。
                Locale locale = Locale.forLanguageTag(locales.getOrDefault(recipientUserId, "ja"));
                NotificationDeliveryResult result = notificationDeliveryRunner.sendOne(
                        buildRequest(event, listing, recipientUserId, locale));
                if (result == NotificationDeliveryResult.VISIBILITY_DENIED) {
                    // visibility deny（例外ではない）。NotificationService 側で既に WARN 済み。
                    log.warn("募集通知が visibility deny によりスキップされました: kind={}, recipientUserId={}, listingId={}",
                            event.kind(), recipientUserId, listing.getId());
                }
            } catch (Exception e) {
                // 非同期イベント失敗の監査記録（規約上必須）。catch は業務TX外なので rollback で消えない。
                log.error("募集通知の配送に失敗しました（この受信者はスキップ）: kind={}, recipientUserId={}, listingId={}",
                        event.kind(), recipientUserId, listing.getId(), e);
            }
        }
    }

    /** 通知配送要求を組み立てる（業務TX外・AFTER_COMMIT 後に実行される）。文面・宛先 URL は是正前と同一。 */
    private NotificationDeliveryRequest buildRequest(
            RecruitmentNotificationEvent event, RecruitmentListingEntity listing,
            Long recipientUserId, Locale locale) {
        String title = listing.getTitle();
        return switch (event.kind()) {
            case PUBLISHED -> new NotificationDeliveryRequest(
                    recipientUserId,
                    "RECRUITMENT_PUBLISHED",
                    NotificationPriority.NORMAL,
                    messageSource.getMessage(
                            "notification.recruitment.published.title", new Object[]{title},
                            "新着募集: " + title, locale),
                    messageSource.getMessage(
                            "notification.recruitment.published.body", new Object[]{title},
                            title + " の募集が公開されました。", locale),
                    "RECRUITMENT_LISTING",
                    listing.getId(),
                    toScopeType(listing),
                    listing.getScopeId(),
                    "/recruitment-listings/" + listing.getId(),
                    listing.getCreatedBy());
            case CONFIRMED -> new NotificationDeliveryRequest(
                    recipientUserId,
                    "RECRUITMENT_CONFIRMED",
                    NotificationPriority.NORMAL,
                    messageSource.getMessage(
                            "notification.recruitment.confirmed.title", null, "参加が確定しました", locale),
                    messageSource.getMessage(
                            "notification.recruitment.confirmed.body", new Object[]{title},
                            title + " の参加が確定しました。", locale),
                    "RECRUITMENT_LISTING",
                    listing.getId(),
                    toScopeType(listing),
                    listing.getScopeId(),
                    "/recruitment-listings/" + listing.getId(),
                    event.actorId());
            case FRIEND_LISTING -> new NotificationDeliveryRequest(
                    recipientUserId,
                    "MARKET_FRIEND_LISTING",
                    NotificationPriority.NORMAL,
                    messageSource.getMessage(
                            "notification.recruitment.friendListing.title", new Object[]{title},
                            "フレンドチームから届いた札: " + title, locale),
                    messageSource.getMessage(
                            "notification.recruitment.friendListing.body", new Object[]{title},
                            title + " の募集が届きました。", locale),
                    "MARKET_FRIEND_LISTING",
                    listing.getId(),
                    NotificationScopeType.TEAM,
                    listing.getScopeId(),
                    "/market/listings/" + listing.getId(),
                    listing.getCreatedBy());
        };
    }

    private static NotificationScopeType toScopeType(RecruitmentListingEntity listing) {
        return listing.getScopeType() == RecruitmentScopeType.TEAM
                ? NotificationScopeType.TEAM : NotificationScopeType.ORGANIZATION;
    }
}
