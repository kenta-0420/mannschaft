package com.mannschaft.app.auth.dto;

/**
 * F08.9 P3a 切替が封印された子（自立段階に到達し切替できない子）。
 *
 * <p>{@code GET /api/v1/me/guardianship/switchable-children} の {@code blockedChildren[]} 要素。
 * camelCase 1:1（02_api_design §2.1）。「ある日突然封印された」事故を UI 側で説明できるよう
 * {@code reason} を添える。</p>
 *
 * @param childUserId  子（受益者）のユーザーID
 * @param displayName  子の表示名（UI 表示用）
 * @param stageKey     年齢段階の i18n ラベルキー（日本＝{@code junior_high}）
 * @param switchAllowed 常に {@code false}（封印された子のみがこのリストに入る）
 * @param reason       封印理由の i18n ラベルキー（例: {@code AGE_LOCKED}）
 */
public record BlockedChildDto(
        Long childUserId,
        String displayName,
        String stageKey,
        boolean switchAllowed,
        String reason) {
}
