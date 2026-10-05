package com.mannschaft.app.recruitment.event;

/**
 * F22.1 市: 申込によって札が定員に到達（{@code OPEN→FULL}）したことを表すイベント（02_api_design §6.1）。
 *
 * <p>CMP-260930-1932: 申込の業務TX（{@code RecruitmentParticipantService#apply}）は publish するだけに留め、
 * {@code com.mannschaft.app.recruitment.service.MarketFinalizeConfirmationListener} が {@code AFTER_COMMIT} + {@code @Async} で札主へ最終認証の
 * 確認通知を送る。通知の失敗（受信者上限・受信者行の失敗など）で申込そのものが失敗しないようにするため。</p>
 *
 * <p>ID のみを渡す。リスナーは札の最新状態を読み直し、{@code FULL} のときだけ送る
 * （同一TX内で {@code FULL→OPEN} に戻ってコミットされた札には送らない）。</p>
 *
 * @param listingId FULL に到達した札ID（確認通知の {@code source_id}）
 */
public record MarketListingReachedFullEvent(Long listingId) {
}
