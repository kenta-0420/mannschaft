package com.mannschaft.app.recruitment.event;

import com.mannschaft.app.recruitment.RecruitmentScopeType;

import java.util.List;

/**
 * CMP-260930-1932: 自動キャンセル（最小定員未達）の確定後に、キャンセルされた参加者へ確認通知を
 * 配送するためのイベント（AFTER_COMMIT で購読する。業務TX内では publish のみ）。
 *
 * <p>試練（red）段階の骨格。publish 側（{@code RecruitmentAutoCancelBatch}）と購読側
 * （{@code RecruitmentAutoCancelledNotificationListener}）の実装は出陣で行う。</p>
 *
 * @param listingId         自動キャンセルした募集ID（通知の source_id）
 * @param sourceScopeType   募集のスコープ種別（PERSONAL は通知側で PLATFORM に写像する）
 * @param sourceScopeId     募集のスコープID
 * @param recipientUserIds  キャンセルされた参加者のユーザーID
 */
public record RecruitmentAutoCancelledNotificationEvent(
        Long listingId,
        RecruitmentScopeType sourceScopeType,
        Long sourceScopeId,
        List<Long> recipientUserIds) {
}
