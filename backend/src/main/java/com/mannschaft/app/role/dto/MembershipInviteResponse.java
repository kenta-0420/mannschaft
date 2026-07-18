package com.mannschaft.app.role.dto;

import java.time.LocalDateTime;

/**
 * チャットからチーム/組織への承諾型招待の発行レスポンス（F04.12・201 Created）。
 *
 * <p>JSON 契約は camelCase（設計書 D-12）。</p>
 *
 * @param tokenId       発行された招待トークン ID
 * @param token         承諾/辞退 API に渡す UUID トークン文字列
 * @param targetUserId  宛先ユーザー（DM 相手）の ID
 * @param scopeType     招待先種別（{@code TEAM} / {@code ORGANIZATION}）
 * @param scopeId       招待先 ID
 * @param scopeName     招待先の表示名
 * @param status        オファー状態（発行直後は {@code PENDING}）
 * @param expiresAt     有効期限
 * @param cardMessageId DM に投稿された招待カードメッセージ ID
 */
public record MembershipInviteResponse(
        Long tokenId,
        String token,
        Long targetUserId,
        String scopeType,
        Long scopeId,
        String scopeName,
        String status,
        LocalDateTime expiresAt,
        Long cardMessageId
) {
}
