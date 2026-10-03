package com.mannschaft.app.team.service;

import java.util.UUID;

/**
 * チームの ACTIVE な加盟先組織1件の読み出し結果（F01.2.1 4-B。{@link TeamService#getOrganizations} の戻り値）。
 *
 * <p>応答 DTO への変換（チームグループの名前の解決・閲覧者ごとの出し分け）は、トランザクションを持たない
 * {@link TeamOrgSummaryService} が行う。組織・ロールの表を読む本クラスの入口と、組織のグループを読む側を
 * 別のトランザクションに分けるための受け渡し用の値である。</p>
 *
 * @param organizationId  組織 ID
 * @param slug            組織の slug
 * @param name            組織名
 * @param visibility      組織の公開範囲
 * @param memberCount     組織の人数（{@code user_roles} の行数。従来の {@code memberCount} と同じ）
 * @param groupsEnabled   組織のチームグループ機能が有効か
 * @param groupId         加盟行の {@code group_id}（未分類・削除済みグループを指す行の判定は呼び出し側）
 */
public record TeamOrgMembershipSummary(Long organizationId, String slug, String name, String visibility,
                                       int memberCount, boolean groupsEnabled, UUID groupId) {
}
