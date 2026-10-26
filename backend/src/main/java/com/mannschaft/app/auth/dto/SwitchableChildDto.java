package com.mannschaft.app.auth.dto;

/**
 * F08.9 P3a 切替可能な子（後見切替が許可される段階の子）。
 *
 * <p>{@code GET /api/v1/me/guardianship/switchable-children} の {@code children[]} 要素。
 * camelCase 1:1（02_api_design §2.1）。</p>
 *
 * @param childUserId  子（受益者）のユーザーID
 * @param displayName  子の表示名（UI 表示用）
 * @param stageKey     年齢段階の i18n ラベルキー（日本＝{@code elementary}）
 * @param switchAllowed 常に {@code true}（切替可能な子のみがこのリストに入る）
 */
public record SwitchableChildDto(
        Long childUserId,
        String displayName,
        String stageKey,
        boolean switchAllowed) {
}
