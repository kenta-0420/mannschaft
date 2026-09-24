package com.mannschaft.app.social.event;

/**
 * フレンドチーム成立／解除の通知発火イベント（Issue #2834 / CMP-056 第1群ロットB）。
 *
 * <p>{@code TeamFriendsService#follow} / {@code #unfollow} は業務トランザクション
 * （{@code follows} / {@code team_friends} の INSERT / DELETE）の内側で本イベントを publish するだけに
 * 留める。両チーム ADMIN の解決・チーム名の解決・ロケール解決・件名/本文の組み立ては
 * {@code TeamFriendNotificationListener}（{@code AFTER_COMMIT}）側で行う。</p>
 *
 * <p>本イベントの新設により、{@code TeamFriendsService} の通知ヘルパーに残っていた
 * 「根治には呼び出し側との伝播設計そのものの変更が必要であり、それは #2834 の範囲」という
 * Codex 検分（PR #2861 P1）の自認コメントが解消される。</p>
 *
 * @param kind         成立か解除か
 * @param teamId       操作を行った自チームID
 * @param targetTeamId 相手チームID
 * @param teamFriendId フレンド関係ID（{@code sourceId}）
 * @param actorId      操作実行者ユーザーID
 */
public record TeamFriendNotificationEvent(
        Kind kind,
        Long teamId,
        Long targetTeamId,
        Long teamFriendId,
        Long actorId) {

    /** 通知の種別。 */
    public enum Kind {
        /** 相互フォロー成立（{@code FRIEND_ESTABLISHED}）。 */
        ESTABLISHED,
        /** フレンド関係解除（{@code FRIEND_DISSOLVED}）。 */
        DISSOLVED
    }
}
