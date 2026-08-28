package com.mannschaft.app.role.dto;

import java.time.OffsetDateTime;

/**
 * 承諾型招待カードの描画契約データ（F04.12・設計書 §5 inviteData）。
 *
 * <p>chat ドメインのメッセージ取得経路（{@code GET /chat/channels/{id}/messages}）が、
 * {@code message_type = 'INVITE_CARD'} のメッセージに対して role ドメインの
 * {@link com.mannschaft.app.role.service.MembershipInviteService#resolveInviteCardData(Long, Long)}
 * を呼び出して取得する値オブジェクト。</p>
 *
 * <p><strong>ドメイン境界（原則1・原則5・ArchUnit D-1）:</strong> chat ドメインは role ドメインの
 * Entity（{@link com.mannschaft.app.role.entity.InviteTokenEntity}）を直接参照してはならない。
 * 本 record は Entity を漏らさない DTO として role → chat のドメイン間受け渡しに用いる。</p>
 *
 * @param tokenId   招待トークン ID
 * @param token     承諾/辞退 API に渡す UUID 文字列
 * @param scopeType 招待先種別（{@code TEAM} / {@code ORGANIZATION}）
 * @param scopeId   招待先 ID
 * @param scopeName 招待先の表示名
 * @param status    導出済みの表示状態（{@code PENDING} / {@code JOINED} / {@code EXPIRED} / {@code REVOKED}）
 * @param isTarget  呼出ユーザーが宛先本人か（true=参加/辞退活性、false=承諾待ち）
 * @param expiresAt 有効期限
 */
public record InviteCardData(
        Long tokenId,
        String token,
        String scopeType,
        Long scopeId,
        String scopeName,
        String status,
        boolean isTarget,
        OffsetDateTime expiresAt) {
}
