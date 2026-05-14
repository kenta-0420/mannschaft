package com.mannschaft.app.membership.event;

import com.mannschaft.app.membership.domain.ScopeType;

/**
 * メンバーシップが終了したことを表すドメインイベント（F15.3）。
 *
 * <p>{@link com.mannschaft.app.membership.service.MembershipService#leave} で
 * memberships に left_at がセットされた直後に発火される。サポータ関係終了も含む。</p>
 *
 * <p>受信側の典型例:</p>
 * <ul>
 *   <li>{@code com.mannschaft.app.scopefolder.listener.MembershipEventListener} —
 *       マイスコープフォルダから該当アイテムを物理削除（dangling 防止 / 設計書 §9.6）</li>
 * </ul>
 *
 * <p>設計書: docs/features/F15.3_scope_folder_integration.md §6.5 / §13④</p>
 *
 * @param userId    対象ユーザーID（必須）
 * @param scopeType スコープ種別（TEAM / ORGANIZATION）
 * @param scopeId   スコープID（team_id または organization_id）
 */
public record MembershipEndedEvent(
        Long userId,
        ScopeType scopeType,
        Long scopeId
) {

    public MembershipEndedEvent {
        if (userId == null) {
            throw new IllegalArgumentException("userId must not be null");
        }
        if (scopeType == null) {
            throw new IllegalArgumentException("scopeType must not be null");
        }
        if (scopeId == null) {
            throw new IllegalArgumentException("scopeId must not be null");
        }
    }
}
