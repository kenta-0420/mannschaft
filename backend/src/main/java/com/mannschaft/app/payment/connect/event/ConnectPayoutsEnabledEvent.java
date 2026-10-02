package com.mannschaft.app.payment.connect.event;

import java.util.UUID;

/**
 * Connect 受取口座の払出可否が false→true へ遷移したことを表すイベント（Issue #2990 L3 / ORDERING_ONLY 是正）。
 *
 * <p>{@code ConnectAccountService#applyAccountUpdated}（{@code account.updated} Webhook の鏡像更新）は
 * 業務トランザクションの内側で本イベントを publish するだけに留め、HELD escrow の昇格は
 * {@code ConnectHeldEscrowPromotionListener}（{@code AFTER_COMMIT}）が行う。</p>
 *
 * <p>ID のみを運ぶ（描画済み文字列・{@code LocalDateTime} を載せない）。</p>
 *
 * @param connectAccountId 払出可能になった Connect アカウントの内部ID（{@code connect_accounts.id}）
 */
public record ConnectPayoutsEnabledEvent(UUID connectAccountId) {
}
