package com.mannschaft.app.common.visibility.mapping;

import com.mannschaft.app.actionmemo.enums.OrgVisibility;
import com.mannschaft.app.common.visibility.StandardVisibility;

/**
 * F02.5 アクションメモ — {@link OrgVisibility} を {@link StandardVisibility} に正規化する Mapper。
 *
 * <p>設計書: {@code docs/features/F00_content_visibility_resolver.md} §5.2 / §5.3 完全一致。
 *
 * <p>{@code organization_id} が NULL の場合は本 Mapper の対象外
 * (個人/チームスコープのみで扱われ、組織タイムラインには現れない — 機能側で判定)。
 */
public final class OrgVisibilityMapper {

    private OrgVisibilityMapper() {
        throw new AssertionError("utility class");
    }

    /**
     * 機能側 enum を {@link StandardVisibility} に変換する。
     *
     * @param v 機能側可視性 (non-null)
     * @return 正規化された {@link StandardVisibility} (non-null)
     */
    public static StandardVisibility toStandard(OrgVisibility v) {
        return switch (v) {
            // 所属者全員判定（W5・挙動保存）: F02.5 アクションメモのチームタイムライン投稿は
            // 「組織内のチームメンバーのみ」(TEAM_ONLY) を対象とするが、設計書・enum とも
            // 応援者(SUPPORTER)を区別する記述が無く、応援者除外の確証が取れない。
            // 過剰制限を避けるため直接所属者全員 = SCOPE_AFFILIATED へ正準化し挙動を保存する
            // （旧 MEMBERS_ONLY と同一判定 = isMemberOf）。
            // ※「応援者にも見せたくない内輪」とすべきか否かは要マスター裁可（W5 報告）。
            case TEAM_ONLY -> StandardVisibility.SCOPE_AFFILIATED;
            case ORG_WIDE -> StandardVisibility.ORGANIZATION_WIDE;
        };
    }
}
