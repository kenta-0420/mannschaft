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
 *   <li>{@code MEMBERS_ONLY} → {@link StandardVisibility#SCOPE_AFFILIATED}（挙動保存。応援者可否は別軸 min_view_role が司る）</li>
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
            // 所属者全員判定（W5・挙動保存）: schedule の応援者包含/除外は本 visibility 軸ではなく
            // 別軸の min_view_role（ANYONE/SUPPORTER+/MEMBER+/ADMIN_ONLY・F03.1 §DB 設計）が司る。
            // ScheduleVisibility.MEMBERS_ONLY は「スコープへの直接所属で評価する」という所属軸を
            // 意味するに過ぎず（設計書 §min_view_role の評価スコープ）、ここで応援者を機械的に
            // 除外すると min_view_role='SUPPORTER+' のスケジュールを過剰制限してしまう。
            // よって直接所属者全員 = SCOPE_AFFILIATED へ正準化し挙動を保存する
            // （旧 MEMBERS_ONLY と同一判定 = isMemberOf）。
            case MEMBERS_ONLY -> StandardVisibility.SCOPE_AFFILIATED;
            case ORGANIZATION -> StandardVisibility.ORGANIZATION_WIDE;
            case CUSTOM_TEMPLATE -> StandardVisibility.CUSTOM_TEMPLATE;
        };
    }
}
