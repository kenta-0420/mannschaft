package com.mannschaft.app.organization.service;

import java.util.UUID;

/**
 * 組織の ACTIVE な加盟チーム1件の読み出し結果（F01.2.1 4-B。{@link OrganizationMembershipService#getTeams} の戻り値）。
 *
 * <p>応答 DTO への変換（実効グループの判定・絞り込み・閲覧者ごとの出し分け）は、トランザクションを持たない
 * {@link OrgTeamListService} が行う。チーム・ロールの表を読む本クラスの入口と、組織のグループを読む側を
 * 別のトランザクションに分けるための受け渡し用の値である。</p>
 *
 * @param slug        チームの slug
 * @param name        チーム名
 * @param visibility  チームの公開範囲
 * @param memberCount チームの人数（{@code user_roles} の行数。従来の {@code memberCount} と同じ）
 * @param groupId     加盟行の {@code group_id}（未分類・削除済みグループを指す行の判定は呼び出し側）
 */
public record OrgTeamMembershipView(String slug, String name, String visibility, int memberCount, UUID groupId) {
}
