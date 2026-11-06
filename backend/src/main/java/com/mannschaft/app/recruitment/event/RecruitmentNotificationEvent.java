package com.mannschaft.app.recruitment.event;

import java.util.List;

/**
 * F03.11 / F22.1 募集・市の付随通知の配送要求イベント（Issue #2997 / CMP-260827-1152 第1陣 G2）。
 *
 * <p>{@code RecruitmentListingService#publish}（一般公開・フレンド宛公開）と
 * {@code RecruitmentListingService#confirmApplication} の業務トランザクションの内側で publish し、
 * {@link RecruitmentNotificationListener} が {@code AFTER_COMMIT} で受け取る。</p>
 *
 * <p>受信者は業務TX内で確定して載せる（{@link RecruitmentCancelledNotificationEvent} と同じ型）。
 * 受信者解決は他ドメインのリポジトリ（role / social）を読むため、配送リスナーへは持ち込まず
 * 従来どおり Service 側で行う（ドメイン境界の ArchUnit 凍結ストアを増やさない）。
 * 募集タイトル等の業務本文は積まず、リスナーが {@code listingId} から読み直して組み立てる。</p>
 *
 * @param kind             通知の種別
 * @param listingId        募集ID（本文の読み直しキー、かつ通知の {@code sourceId}）
 * @param recipientUserIds 受信者ユーザーID一覧（業務TX内で確定済み）
 * @param actorId          実行者ID（{@link Kind#CONFIRMED} のみ。公開系は募集の作成者を用いるため {@code null}）
 */
public record RecruitmentNotificationEvent(
        Kind kind,
        Long listingId,
        List<Long> recipientUserIds,
        Long actorId) {

    /** 通知の種別。 */
    public enum Kind {
        /** 募集公開（RECRUITMENT_PUBLISHED）。配信対象（MEMBERS/SUPPORTERS/FOLLOWERS）へ。 */
        PUBLISHED,
        /** フレンド宛非公開札の公開（MARKET_FRIEND_LISTING）。宛先フレンドチームの ADMIN へ。 */
        FRIEND_LISTING,
        /** 申込確定（RECRUITMENT_CONFIRMED）。応募者本人へ。 */
        CONFIRMED
    }

    public static RecruitmentNotificationEvent published(Long listingId, List<Long> recipientUserIds) {
        return new RecruitmentNotificationEvent(Kind.PUBLISHED, listingId, recipientUserIds, null);
    }

    public static RecruitmentNotificationEvent friendListing(Long listingId, List<Long> recipientUserIds) {
        return new RecruitmentNotificationEvent(Kind.FRIEND_LISTING, listingId, recipientUserIds, null);
    }

    public static RecruitmentNotificationEvent confirmed(Long listingId, Long applicantUserId, Long actorId) {
        return new RecruitmentNotificationEvent(Kind.CONFIRMED, listingId, List.of(applicantUserId), actorId);
    }
}
