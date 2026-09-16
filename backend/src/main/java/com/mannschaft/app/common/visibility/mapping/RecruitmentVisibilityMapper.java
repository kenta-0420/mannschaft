package com.mannschaft.app.common.visibility.mapping;

import com.mannschaft.app.common.visibility.StandardVisibility;
import com.mannschaft.app.recruitment.RecruitmentVisibility;

/**
 * F03.11 募集型予約 — {@link RecruitmentVisibility} を {@link StandardVisibility} に正規化する Mapper。
 *
 * <p>設計書: {@code docs/features/F00_content_visibility_resolver.md} §5.2 / §5.3 完全一致。
 *
 * <p>マスター裁可 C-2 (2026-05-04): SUPPORTERS_ONLY は GUEST 以外の全認証メンバーを包含する
 * {@link StandardVisibility#SUPPORTERS_AND_ABOVE} に正規化する。
 */
public final class RecruitmentVisibilityMapper {

    private RecruitmentVisibilityMapper() {
        throw new AssertionError("utility class");
    }

    /**
     * 機能側 enum を {@link StandardVisibility} に変換する。
     *
     * @param v 機能側可視性 (non-null)
     * @return 正規化された {@link StandardVisibility} (non-null)
     */
    public static StandardVisibility toStandard(RecruitmentVisibility v) {
        return switch (v) {
            case PUBLIC -> StandardVisibility.PUBLIC;
            // 挙動不変・名称正準化（W3）: SCOPE_AFFILIATED = isMemberOf = 旧 MEMBERS_ONLY と同一判定。
            case SCOPE_ONLY -> StandardVisibility.SCOPE_AFFILIATED;
            case SUPPORTERS_ONLY -> StandardVisibility.SUPPORTERS_AND_ABOVE;
            case CUSTOM_TEMPLATE -> StandardVisibility.CUSTOM_TEMPLATE;
            case SELECTED_SCOPES -> StandardVisibility.CUSTOM;
            // F22.1 市: フレンドチーム限定の非公開札。可視範囲は scope ロールでは決まらず、
            // recruitment_friend_targets を F01.5 サービスで都度解決する「機能独自セマンティクス」のため
            // CUSTOM に正規化し、Resolver 側の個別ハンドリング（evaluateCustom）に委ねる。
            // MEMBERS_ONLY 等にマップすると scope メンバーへ誤って公開されるため不可。
            case FRIEND_TEAMS_ONLY -> StandardVisibility.CUSTOM;
        };
    }
}
