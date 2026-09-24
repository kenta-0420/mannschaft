package com.mannschaft.app.recruitment.event;

/**
 * 募集の手動取下げまたは自動キャンセルが確定したことを決済ドメインへ通知するイベント。
 *
 * <p>クロスドメイン FK を作らず、募集 ID の論理参照だけで未確定の与信を取消す。</p>
 *
 * @param listingId 募集 ID
 * @param paymentEnabled 謝礼決済を利用する募集か
 */
public record RecruitmentCancelledEvent(Long listingId, boolean paymentEnabled) {
}
