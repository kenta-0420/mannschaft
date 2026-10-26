package com.mannschaft.app.payment.escrow.event;

import com.mannschaft.app.payment.connect.ScopeKind;
import com.mannschaft.app.payment.escrow.EscrowSourceKind;

/**
 * F22.1 統一決済 P2-b: 謝礼の与信（authorize）に失敗したことを表すドメインイベント（設計書 02 §5.1 / PAYMENT_041）。
 *
 * <p>{@link com.mannschaft.app.payment.escrow.RecruitmentChargeAuthorizationListener} は応募確定後
 * {@code AFTER_COMMIT}+{@code @Async} で与信を起動する。AFTER_COMMIT 後ゆえ応募自体のロールバックは不可であり、
 * 与信が失敗しても例外を {@code @Async} 既定ハンドラのログに埋もれさせると「観測も後続アクションも不能」になる
 * （対処療法・握り潰し）。これを避けるため、失敗を本イベントとして発火して<b>観測可能・後続でアクション可能</b>な
 * 結節点とする（根治）。</p>
 *
 * <p>将来の通知リスナ（応募者・札主へ「与信失敗→再試行/カード更新」を通知・PAYMENT_041）が本イベントを購読する。
 * 通知本実装までは購読者は存在しないが、失敗がログに埋もれず将来の救済導線につながることを保証する。</p>
 *
 * <ul>
 *   <li>{@code sourceKind} — 出所種別（{@link EscrowSourceKind#RECRUITMENT} 等）。</li>
 *   <li>{@code sourceId} — 札 ID（escrow の source_id）。</li>
 *   <li>{@code sourceParticipantId} — 応募 ID（escrow の source_participant_id）。</li>
 *   <li>{@code payerScope} — 支払者（応募者）の主体（USER 等）。</li>
 *   <li>{@code payerScopeId} — 支払者の users.id。通知の宛先解決に使う。</li>
 *   <li>{@code reason} — 失敗事由（例外メッセージ等・ログ/通知用の人間可読テキスト）。</li>
 * </ul>
 */
public record ChargeAuthorizationFailedEvent(
        EscrowSourceKind sourceKind,
        Long sourceId,
        Long sourceParticipantId,
        ScopeKind payerScope,
        Long payerScopeId,
        String reason) {
}
