package com.mannschaft.app.tournament.leaguetransfer.dto;

/**
 * 昇降格候補（境界部の昇格枠/降格枠チーム）レスポンス DTO（F08.7.1 / 03 §3.3 / §6）。
 *
 * <p>テーブルを持たず、{@code transfer-candidates} API が完了済み大会の最上位/最下位ディビジョンの
 * {@code promotion_slots} / {@code relegation_slots} と {@code tournament_standings} から都度導出する。
 * {@code resolvedTargetOrganizationId} は組織階層（祖先/子孫 ASSOCIATION）から解決した送り先 org
 * （0 件で解決不能なら NULL を返し、UI 側で ADMIN へ警告する）。</p>
 *
 * @param teamId                       昇降格枠に該当するチーム ID
 * @param sourceDivisionId             移籍元（境界部）ディビジョン ID
 * @param sourceDivisionName           移籍元ディビジョン名
 * @param direction                    PROMOTION / RELEGATION
 * @param finalRank                    移籍元での最終順位（枠判定根拠）
 * @param resolvedTargetOrganizationId 解決した送り先 org（解決不能なら NULL）
 */
public record TransferCandidateResponse(
        Long teamId,
        Long sourceDivisionId,
        String sourceDivisionName,
        String direction,
        Integer finalRank,
        Long resolvedTargetOrganizationId
) {
}
