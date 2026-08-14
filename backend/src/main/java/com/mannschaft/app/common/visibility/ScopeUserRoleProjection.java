package com.mannschaft.app.common.visibility;

/**
 * 「1 スコープ × 複数ユーザー」向きのロール一括解決の射影（F03.16 §4.5.0 段1）。
 *
 * <p>既存の {@code MembershipBatchQueryService} が扱う「1 ユーザー × 複数スコープ」とは
 * <b>向きが逆</b>である。メンション通知フィルタ（§6.3）や {@code mention-candidates}（§4.4）は
 * 「単一スケジュールのスコープに対し、候補ユーザー全員のロールを知りたい」という向きであり、
 * 候補者ごとに {@code resolveEffectiveRoleName} を呼ぶと候補者数に比例して SQL が増える
 * （AC-39 が禁じる形）。</p>
 *
 * <p>ロール名は {@code roles.name}（{@code user_roles} 由来）または
 * {@code memberships.role_kind}（MEMBER / SUPPORTER）の文字列で、いずれも
 * {@link RolePriority} の語彙と一致する。強弱の比較は {@link RolePriority} で<b>メモリ上</b>
 * 行うため、優先度を引くための追加 SQL は発行しない。</p>
 */
public interface ScopeUserRoleProjection {

    /** 対象ユーザー ID。 */
    Long getUserId();

    /** ロール名（{@code roles.name} または {@code memberships.role_kind} の名前）。 */
    String getRoleName();
}
