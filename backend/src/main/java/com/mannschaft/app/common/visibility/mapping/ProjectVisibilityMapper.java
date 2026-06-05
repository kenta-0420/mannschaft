package com.mannschaft.app.common.visibility.mapping;

import com.mannschaft.app.common.visibility.StandardVisibility;
import com.mannschaft.app.todo.ProjectVisibility;

/**
 * {@link com.mannschaft.app.todo.ProjectVisibility} を {@link StandardVisibility}
 * に正規化する。
 *
 * <p>設計書: {@code docs/features/F00_content_visibility_resolver.md} §5.2 対応表完全一致。
 */
public final class ProjectVisibilityMapper {

    private ProjectVisibilityMapper() {
        throw new AssertionError("utility class");
    }

    /**
     * 機能側の {@link ProjectVisibility} を共通の {@link StandardVisibility} に写像する。
     *
     * @param v 機能側 enum (non-null)
     * @return 対応する StandardVisibility 値
     */
    public static StandardVisibility toStandard(ProjectVisibility v) {
        return switch (v) {
            case PRIVATE -> StandardVisibility.PRIVATE;
            // 内輪判定（W5・W3 SCOPE_AFFILIATED から締め直し）: ProjectVisibility は
            // PUBLIC に「SUPPORTOR も閲覧可」のセマンティクスを持たせており（enum コメント
            // 「PUBLIC=SUPPORTER も閲覧可」/ 設計書 F02.3 §DB 設計 visibility カラム
            // 「PUBLIC（SUPPORTER も閲覧可）」）、SUPPORTER は「公開プロジェクトの閲覧（TODO は対象外）」
            // のみと §権限表で明記。MEMBERS_ONLY は応援者を含まない内輪の意図であることが確定。
            // よって応援者除外の MEMBERS_AND_ABOVE へ締める
            // （挙動変更: 直接所属の SUPPORTER は MEMBERS_ONLY プロジェクトを閲覧できなくなる）。
            case MEMBERS_ONLY -> StandardVisibility.MEMBERS_AND_ABOVE;
            case PUBLIC -> StandardVisibility.PUBLIC;
        };
    }
}
