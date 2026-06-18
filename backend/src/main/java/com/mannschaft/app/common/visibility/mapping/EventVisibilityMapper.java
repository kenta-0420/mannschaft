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
            // 内輪判定（W5・W3 SCOPE_AFFILIATED から締め直し）: EventVisibility は
            // SUPPORTERS_AND_ABOVE（「サポーター以上に公開」）を別値として併存させており、
            // MEMBERS_ONLY（「メンバーのみ」）は応援者を含まない内輪の意図であることが確定。
            // よって応援者除外の MEMBERS_AND_ABOVE へ締める
            // （挙動変更: 直接所属の SUPPORTER は MEMBERS_ONLY イベントを閲覧できなくなる。
            //  応援者に見せたいイベントは SUPPORTERS_AND_ABOVE を選ぶ運用）。
            case MEMBERS_ONLY -> StandardVisibility.MEMBERS_AND_ABOVE;
            case SUPPORTERS_AND_ABOVE -> StandardVisibility.SUPPORTERS_AND_ABOVE;
            // 可視性ラダー統一（#1341）: FE が送る新ラダー値名。同名写像（旧 MEMBERS_ONLY と同一の可視範囲）。
            case MEMBERS_AND_ABOVE -> StandardVisibility.MEMBERS_AND_ABOVE;
            case ADMINS_AND_ABOVE -> StandardVisibility.ADMINS_AND_ABOVE;
            // SCOPE_AFFILIATED = 直接所属者全員（応援者・ゲスト含む。旧 MEMBERS_ONLY 相当の正準値）。
            case SCOPE_AFFILIATED -> StandardVisibility.SCOPE_AFFILIATED;
        };
    }
}
