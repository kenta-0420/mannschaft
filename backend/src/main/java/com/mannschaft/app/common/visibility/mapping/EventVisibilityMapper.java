package com.mannschaft.app.common.visibility.mapping;

import com.mannschaft.app.common.visibility.StandardVisibility;
import com.mannschaft.app.event.entity.EventVisibility;

/**
 * {@link com.mannschaft.app.event.entity.EventVisibility} を {@link StandardVisibility}
 * に正規化する。
 *
 * <p>設計書: {@code docs/features/F00_content_visibility_resolver.md} §5.2 対応表完全一致。
 *
 * <p>マスター確定: SUPPORTERS_AND_ABOVE は GUEST 以外の全認証メンバーを含む
 * (memory/project_f00_phase_a_decisions.md C-2 / 2026-05-04)。
 */
public final class EventVisibilityMapper {

    private EventVisibilityMapper() {
        throw new AssertionError("utility class");
    }

    /**
     * 機能側の {@link EventVisibility} を共通の {@link StandardVisibility} に写像する。
     *
     * @param v 機能側 enum (non-null)
     * @return 対応する StandardVisibility 値
     */
    public static StandardVisibility toStandard(EventVisibility v) {
        return switch (v) {
            case PUBLIC -> StandardVisibility.PUBLIC;
            case MEMBERS_ONLY -> StandardVisibility.MEMBERS_ONLY;
            case SUPPORTERS_AND_ABOVE -> StandardVisibility.SUPPORTERS_AND_ABOVE;
        };
    }
}
