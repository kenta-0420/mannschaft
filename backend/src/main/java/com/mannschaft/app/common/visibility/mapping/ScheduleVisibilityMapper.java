package com.mannschaft.app.common.visibility.mapping;

import com.mannschaft.app.common.visibility.StandardVisibility;
import com.mannschaft.app.schedule.ScheduleVisibility;

/**
 * {@link com.mannschaft.app.schedule.ScheduleVisibility} を {@link StandardVisibility}
 * に正規化する。
 *
 * <p>設計書: {@code docs/features/F00_content_visibility_resolver.md} §5.2 対応表完全一致。
 *
 * <p>対応関係:
 * <ul>
 *   <li>{@code MEMBERS_ONLY} → {@link StandardVisibility#MEMBERS_ONLY}</li>
 *   <li>{@code ORGANIZATION} → {@link StandardVisibility#ORGANIZATION_WIDE}</li>
 *   <li>{@code CUSTOM_TEMPLATE} → {@link StandardVisibility#CUSTOM_TEMPLATE}</li>
 * </ul>
 */
public final class ScheduleVisibilityMapper {

    private ScheduleVisibilityMapper() {
        throw new AssertionError("utility class");
    }

    /**
     * 機能側の {@link ScheduleVisibility} を共通の {@link StandardVisibility} に写像する。
     *
     * @param v 機能側 enum (non-null)
     * @return 対応する StandardVisibility 値
     */
    public static StandardVisibility toStandard(ScheduleVisibility v) {
        return switch (v) {
            case MEMBERS_ONLY -> StandardVisibility.MEMBERS_ONLY;
            case ORGANIZATION -> StandardVisibility.ORGANIZATION_WIDE;
            case CUSTOM_TEMPLATE -> StandardVisibility.CUSTOM_TEMPLATE;
        };
    }
}
