package com.mannschaft.app.common.visibility.mapping;

import com.mannschaft.app.common.visibility.StandardVisibility;
import com.mannschaft.app.tournament.TournamentVisibility;

import java.util.EnumSet;
import java.util.Set;

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

    /**
     * CMP-028 Phase C: {@link #toStandard(TournamentVisibility)} の<strong>逆写像</strong>。
     * {@code MembershipBatchQueryService#resolveVisibleLevels} が返した「行に依存せず判定できる
     * 可視 {@link StandardVisibility} ラダー集合」を、SQL の {@code visibility IN (...)} に渡せる
     * 機能固有 enum 集合へ変換する。
     *
     * <p><strong>単射性の確認</strong>: {@code PUBLIC/SUPPORTERS_AND_ABOVE/MEMBERS_AND_ABOVE/
     * ADMINS_AND_ABOVE/SCOPE_AFFILIATED} の 5 値は {@link #toStandard} で 5 対 5 の一対一写像
     * であり単射（injective）である。よって逆写像は曖昧さなく安全に定義できる。</p>
     *
     * <p><strong>{@code PARTICIPANTS_ONLY} は含まれない</strong>: {@code StandardVisibility.CUSTOM}
     * は行依存（参加チーム関係者か）の判定のため {@code resolveVisibleLevels} のラダー集合には
     * 現れない。呼び出し側は {@code TournamentParticipantRepository} の
     * {@code EXISTS} 述語を別途 OR で組み合わせること
     * （{@code TournamentVisibilityResolver#evaluateCustom} と同一の判定を SQL へ翻訳）。</p>
     *
     * @param standardLevels {@code resolveVisibleLevels} が返した可視 {@link StandardVisibility} 集合
     * @return 対応する {@link TournamentVisibility} 集合（該当なしは空集合）
     */
    public static Set<TournamentVisibility> toFunctional(Set<StandardVisibility> standardLevels) {
        if (standardLevels == null || standardLevels.isEmpty()) {
            return Set.of();
        }
        EnumSet<TournamentVisibility> result = EnumSet.noneOf(TournamentVisibility.class);
        if (standardLevels.contains(StandardVisibility.PUBLIC)) {
            result.add(TournamentVisibility.PUBLIC);
        }
        if (standardLevels.contains(StandardVisibility.SUPPORTERS_AND_ABOVE)) {
            result.add(TournamentVisibility.SUPPORTERS_AND_ABOVE);
        }
        if (standardLevels.contains(StandardVisibility.MEMBERS_AND_ABOVE)) {
            result.add(TournamentVisibility.MEMBERS_AND_ABOVE);
        }
        if (standardLevels.contains(StandardVisibility.ADMINS_AND_ABOVE)) {
            result.add(TournamentVisibility.ADMINS_AND_ABOVE);
        }
        if (standardLevels.contains(StandardVisibility.SCOPE_AFFILIATED)) {
            result.add(TournamentVisibility.SCOPE_AFFILIATED);
        }
        return Set.copyOf(result);
    }
}
