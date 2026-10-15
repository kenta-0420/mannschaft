package com.mannschaft.app.recruitment.event;

import java.util.List;

/**
 * F03.11 募集取下げ（主催者キャンセル）の通知配送要求イベント（Issue #2990 / L2 ROLLBACK_COUPLED 是正）。
 *
 * <p>{@code RecruitmentListingService#cancelInternal} の業務トランザクション
 * （募集の CANCELLED 化・参加者の一括キャンセル・履歴書き込み）の内側で publish し、
 * {@link RecruitmentCancelledNotificationListener} が {@code AFTER_COMMIT} で受け取る。</p>
 *
 * <p>イベントには<b>読み直せる ID のみ</b>を載せる。募集タイトル・取下げ理由・スコープ・実行者は
 * 業務本文であるため積まず、配送リスナーが {@code listingId} から読み直して組み立てる。</p>
 *
 * <p>{@link RecruitmentCancelledEvent}（決済ドメインへの与信取消通知）とは別物である。
 * あちらは業務連鎖、こちらは付随通知であり、混ぜると再び業務が通知に巻き込まれる。</p>
 *
 * @param listingId        募集ID（本文の読み直しキー、かつ通知の {@code sourceId}）
 * @param recipientUserIds 受信者ユーザーID一覧（キャンセルされた参加者。業務TX内で確定済み）
 */
public record RecruitmentCancelledNotificationEvent(
        Long listingId,
        List<Long> recipientUserIds) {
}
