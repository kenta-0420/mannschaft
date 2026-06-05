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
     * @param v 機能側 enum (non-null)
     * @return 対応する StandardVisibility 値
     */
    public static StandardVisibility toStandard(TournamentVisibility v) {
        return switch (v) {
            case PUBLIC -> StandardVisibility.PUBLIC;
            // 内輪判定（W5）: 設計書 F08.7 §権限と役割で SUPPORTER は
            // 「公開設定の大会の順位表・結果閲覧のみ」と明記され、MEMBERS_ONLY 大会は
            // 応援者に見せない内輪。よって応援者除外の MEMBERS_AND_ABOVE へ締める
            // （挙動変更: SUPPORTER は MEMBERS_ONLY の大会を閲覧できなくなる）。
            case MEMBERS_ONLY -> StandardVisibility.MEMBERS_AND_ABOVE;
        };
    }
}
