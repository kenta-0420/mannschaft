package com.mannschaft.app.common.visibility;

/**
 * {@link VisibilityDecision} が deny を返す際の理由コード。
 *
 * <p>設計書: {@code docs/features/F00_content_visibility_resolver.md} §7.2 完全一致。
 *
 * <p>{@code assertCanView} における HTTP コードへのマッピング (§7.4):
 * <ul>
 *   <li>{@link #NOT_FOUND} → {@code VISIBILITY_004} (404)
 *   <li>その他 deny → {@code VISIBILITY_001} (403)
 * </ul>
 */
public enum DenyReason {

    /** コンテンツが存在しない (削除済み・ID 不正等). */
    NOT_FOUND,

    /** スコープ (TEAM/ORGANIZATION) の所属メンバーではない. */
    NOT_A_MEMBER,

    /** 所属はしているが、必要なロール (SUPPORTER / ADMIN 等) を満たさない. */
    INSUFFICIENT_ROLE,

    /** {@code PRIVATE} などで作成者本人ではない. */
    NOT_OWNER,

    /** {@code CUSTOM_TEMPLATE} のルール評価でマッチしなかった. */
    TEMPLATE_RULE_NO_MATCH,

    /** 該当 {@link ReferenceType} に対応する Resolver が未登録 (fail-closed). */
    UNSUPPORTED_REFERENCE_TYPE,

    /** その他、上記に分類できない拒否理由. */
    UNSPECIFIED
}
