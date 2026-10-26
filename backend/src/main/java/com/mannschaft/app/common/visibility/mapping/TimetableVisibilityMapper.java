package com.mannschaft.app.common.visibility.mapping;

import com.mannschaft.app.common.visibility.StandardVisibility;
import com.mannschaft.app.timetable.TimetableVisibility;

/**
 * {@link com.mannschaft.app.timetable.TimetableVisibility} を {@link StandardVisibility}
 * に正規化する。
 *
 * <p>設計書: {@code docs/features/F00_content_visibility_resolver.md} §5.2 対応表完全一致。
 */
public final class TimetableVisibilityMapper {

    private TimetableVisibilityMapper() {
        throw new AssertionError("utility class");
    }

    /**
     * 機能側の {@link TimetableVisibility} を共通の {@link StandardVisibility} に写像する。
     *
     * @param v 機能側 enum (non-null)
     * @return 対応する StandardVisibility 値
     */
    public static StandardVisibility toStandard(TimetableVisibility v) {
        return switch (v) {
            // 内輪判定（W5）: 設計書 F03.9 §DB 設計で visibility カラムに
            // 「PUBLIC: SUPPORTER も閲覧可。MEMBERS_ONLY: MEMBER 以上のみ」と明記。
            // SUPPORTER は visibility='PUBLIC' の時間割のみ閲覧可（権限表・API 認可とも一致）。
            // よって応援者除外の MEMBERS_AND_ABOVE へ締める
            // （挙動変更: SUPPORTER は MEMBERS_ONLY の時間割を閲覧できなくなる）。
            case MEMBERS_ONLY -> StandardVisibility.MEMBERS_AND_ABOVE;
            case PUBLIC -> StandardVisibility.PUBLIC;
        };
    }
}
