package com.mannschaft.app.common.visibility.mapping;

import com.mannschaft.app.common.visibility.StandardVisibility;
import com.mannschaft.app.tournament.TournamentVisibility;

/**
 * {@link com.mannschaft.app.tournament.TournamentVisibility} を {@link StandardVisibility}
 * に正規化する。
 *
 * <p>設計書: {@code docs/features/F00_content_visibility_resolver.md} §5.2 対応表完全一致。
 */
public final class TournamentVisibilityMapper {

    private TournamentVisibilityMapper() {
        throw new AssertionError("utility class");
    }

    /**
     * 機能側の {@link TournamentVisibility} を共通の {@link StandardVisibility} に写像する。
     *
     * <p>F08.7 順位UI Wave0: 6 値拡張。5 値は同名写像（恒等に近い）、大会専用軸
     * {@link TournamentVisibility#PARTICIPANTS_ONLY} のみ正準に対応値が無いため
     * {@link StandardVisibility#CUSTOM} に写像し、Resolver の {@code evaluateCustom} で
     * 「参加チーム関係者か」を個別判定する。</p>
     *
     * @param v 機能側 enum (non-null)
     * @return 対応する StandardVisibility 値
     */
    public static StandardVisibility toStandard(TournamentVisibility v) {
        return switch (v) {
            case PUBLIC -> StandardVisibility.PUBLIC;
            case SUPPORTERS_AND_ABOVE -> StandardVisibility.SUPPORTERS_AND_ABOVE;
            case MEMBERS_AND_ABOVE -> StandardVisibility.MEMBERS_AND_ABOVE;
            case ADMINS_AND_ABOVE -> StandardVisibility.ADMINS_AND_ABOVE;
            // SCOPE_AFFILIATED = 主催組織に直接所属する全員（旧 MEMBERS_ONLY 相当の正準値）。
            case SCOPE_AFFILIATED -> StandardVisibility.SCOPE_AFFILIATED;
            // PARTICIPANTS_ONLY = 参加チーム関係者のみ（大会専用軸）。正準対応値が無く CUSTOM 経由。
            case PARTICIPANTS_ONLY -> StandardVisibility.CUSTOM;
        };
    }
}
