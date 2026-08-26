package com.mannschaft.app.tournament.dto;

import lombok.Builder;
import lombok.Getter;

import java.util.List;

/**
 * F08.7.1 / 02: 主催大会サマリ（ORG_TOURNAMENT_SUMMARY ウィジェット）レスポンス DTO。
 *
 * <p>組織が主催する各大会 × 各部の「首位チーム名・参加チーム数・大会 status」だけを
 * 一覧表示するための俯瞰用レスポンス。設計書 docs/features/F08.7.1_tournament_extensions/
 * 02_dashboard_widgets.md §2.1 ② に準拠。</p>
 *
 * <p>N+1 回避: 集約クエリで首位・参加数を一括取得し、本 DTO へ組み立てる。
 * 大会本体ループ内で個別クエリを撃たない。</p>
 */
@Builder(toBuilder = true)
@Getter
public class OrganizationTournamentSummaryResponse {

    /** 主催大会サマリ一覧。 */
    private final List<TournamentSummaryEntry> tournaments;

    @Builder(toBuilder = true)
    @Getter
    public static class TournamentSummaryEntry {
        private final Long tournamentId;
        private final String name;
        /** 大会ステータス（DRAFT は除外済み）。 */
        private final String status;
        private final List<DivisionSummaryEntry> divisions;
    }

    public record DivisionSummaryEntry(
            Long divisionId,
            String name,
            Integer participantCount,
            /** 首位チーム名。参加 0 件 / 順位未計算なら null。 */
            String leaderTeamName) {
    }
}
