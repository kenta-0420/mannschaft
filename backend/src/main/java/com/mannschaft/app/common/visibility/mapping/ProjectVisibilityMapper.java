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
            // 挙動不変・名称正準化（W3）: SCOPE_AFFILIATED = isMemberOf = 旧 MEMBERS_ONLY と同一判定。
            case MEMBERS_ONLY -> StandardVisibility.SCOPE_AFFILIATED;
            case PUBLIC -> StandardVisibility.PUBLIC;
        };
    }
}
