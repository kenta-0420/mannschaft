package com.mannschaft.app.role.dto;

/**
 * オーナー委譲オファーの承諾（＝実行）レスポンス（F01.2・承諾型・200 OK）。
 *
 * <p>承諾で「対象→ADMIN 昇格」「発行者→MEMBER 降格」を実行した結果を返す。
 * JSON 契約は camelCase。</p>
 *
 * @param newAdmin      新 ADMIN（＝承諾した指名相手）
 * @param previousAdmin 降格した旧 ADMIN（＝発行者。降格先は MEMBER）
 */
public record TransferOwnershipAcceptResponse(
        MemberBrief newAdmin,
        MemberBrief previousAdmin
) {

    /**
     * 委譲後のメンバー概要（ロール付き）。
     *
     * @param userId      ユーザー ID
     * @param displayName 表示名
     * @param role        委譲後のロール（{@code ADMIN} / {@code MEMBER}）
     */
    public record MemberBrief(
            Long userId,
            String displayName,
            String role
    ) {
    }
}
