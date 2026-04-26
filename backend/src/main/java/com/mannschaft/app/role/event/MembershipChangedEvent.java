package com.mannschaft.app.role.event;

/**
 * F02.2.1: スコープに対するメンバーシップ（ロール割当）が変化したことを表すドメインイベント。
 *
 * <p>RoleService の {@code assignRole} / {@code changeRole} / {@code removeMember} /
 * {@code leaveScope} / {@code transferOwnership} で発火される。
 * ダッシュボード機能は本イベントを {@code @TransactionalEventListener(AFTER_COMMIT)} で受信し、
 * 当該ユーザー向けの閲覧者ロールキャッシュを無効化する。</p>
 *
 * <p>本クラスは「定義のみ」で外部 API 互換性は持たない。アプリケーション内部のみで利用される。</p>
 *
 * <p>設計書: docs/features/F02.2.1_dashboard_widget_role_visibility.md §5（キャッシュ戦略）</p>
 *
 * @param userId     対象ユーザーID（必須）
 * @param scopeType  スコープ種別。{@code TEAM} または {@code ORGANIZATION}（必須）
 * @param scopeId    スコープID（必須）
 * @param changeType 変更種別（{@link ChangeType#ASSIGNED} / {@link ChangeType#CHANGED} /
 *                   {@link ChangeType#REMOVED}）
 */
public record MembershipChangedEvent(
        Long userId,
        String scopeType,
        Long scopeId,
        ChangeType changeType
) {

    /**
     * メンバーシップ変更種別。
     */
    public enum ChangeType {
        /** 新規ロール割当（assignRole / transferOwnership で対象ユーザーが新規昇格） */
        ASSIGNED,
        /** 既存ロールの変更（changeRole / transferOwnership の現オーナーダウングレード） */
        CHANGED,
        /** ロール解除・除名・退会（removeMember / leaveScope） */
        REMOVED
    }

    public MembershipChangedEvent {
        if (userId == null) {
            throw new IllegalArgumentException("userId must not be null");
        }
        if (scopeType == null || scopeType.isBlank()) {
            throw new IllegalArgumentException("scopeType must not be blank");
        }
        if (scopeId == null) {
            throw new IllegalArgumentException("scopeId must not be null");
        }
        if (changeType == null) {
            throw new IllegalArgumentException("changeType must not be null");
        }
    }
}
